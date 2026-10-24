use std::fs;
use std::hint::black_box;
use std::time::Instant;

use serde::Serialize;

use crate::progress;

#[derive(Serialize, Default)]
pub struct CpuInfo {
    pub hardware: String,
    pub present: u32,
    pub online: u32,
    pub core_types: Vec<String>,
    pub max_khz: Vec<u64>,
}

#[derive(Serialize, Default)]
pub struct CpuBench {
    pub threads: u32,
    pub single_mops: f64,
    pub multi_mops: f64,
}

fn count_list(text: &str) -> u32 {
    text.trim()
        .split(',')
        .filter(|p| !p.is_empty())
        .map(|part| match part.split_once('-') {
            Some((a, b)) => match (a.parse::<u32>(), b.parse::<u32>()) {
                (Ok(a), Ok(b)) if b >= a => b - a + 1,
                _ => 0,
            },
            None => 1,
        })
        .sum()
}

fn core_name(implementer: &str, part: &str) -> String {
    if implementer != "0x41" {
        return format!("Unknown ({implementer} {part})");
    }
    let name = match part {
        "0xc07" => "Cortex-A7",
        "0xc09" => "Cortex-A9",
        "0xc0f" => "Cortex-A15",
        "0xd03" => "Cortex-A53",
        "0xd04" => "Cortex-A35",
        "0xd05" => "Cortex-A55",
        "0xd07" => "Cortex-A57",
        "0xd08" => "Cortex-A72",
        "0xd09" => "Cortex-A73",
        "0xd0a" => "Cortex-A75",
        "0xd0b" => "Cortex-A76",
        "0xd0d" => "Cortex-A77",
        "0xd41" => "Cortex-A78",
        "0xd44" => "Cortex-X1",
        "0xd46" => "Cortex-A510",
        "0xd47" => "Cortex-A710",
        "0xd48" => "Cortex-X2",
        "0xd4d" => "Cortex-A715",
        "0xd4e" => "Cortex-X3",
        _ => return format!("ARM {part}"),
    };
    name.to_string()
}

pub fn info() -> CpuInfo {
    let mut out = CpuInfo::default();
    let read = |p: &str| fs::read_to_string(p).unwrap_or_default();
    out.present = count_list(&read("/sys/devices/system/cpu/present"));
    out.online = count_list(&read("/sys/devices/system/cpu/online"));

    let mut implementer = String::new();
    for line in read("/proc/cpuinfo").lines() {
        let Some((key, val)) = line.split_once(':') else {
            continue;
        };
        let (key, val) = (key.trim(), val.trim());
        match key {
            "Hardware" => out.hardware = val.to_string(),
            "CPU implementer" => implementer = val.to_string(),
            "CPU part" => out.core_types.push(core_name(&implementer, val)),
            _ => {}
        }
    }

    for i in 0..out.present {
        let path = format!("/sys/devices/system/cpu/cpu{i}/cpufreq/cpuinfo_max_freq");
        if let Some(khz) = fs::read_to_string(path).ok().and_then(|t| t.trim().parse().ok()) {
            out.max_khz.push(khz);
        }
    }
    out
}

fn spin(iters: u64) -> u64 {
    let mut x: u64 = 0x2545_F491_4F6C_DD1D;
    for i in 0..iters {
        x = x.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(i);
        x ^= x >> 29;
        x = x.rotate_left(17);
    }
    x
}

pub fn bench() -> CpuBench {
    const ITERS: u64 = 120_000_000;
    let threads = std::thread::available_parallelism()
        .map(|n| n.get() as u32)
        .unwrap_or(1);

    progress::set(5, 0, 0);
    let clock = Instant::now();
    black_box(spin(black_box(ITERS)));
    let single = ITERS as f64 / clock.elapsed().as_secs_f64() / 1e6;

    progress::set(6, 0, 0);
    let clock = Instant::now();
    let handles: Vec<_> = (0..threads)
        .map(|_| std::thread::spawn(|| black_box(spin(black_box(ITERS)))))
        .collect();
    for h in handles {
        let _ = h.join();
    }
    let multi = ITERS as f64 * threads as f64 / clock.elapsed().as_secs_f64() / 1e6;

    CpuBench {
        threads,
        single_mops: single,
        multi_mops: multi,
    }
}
