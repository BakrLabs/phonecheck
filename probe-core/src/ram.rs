use std::fs;
use std::time::Instant;

use serde::Serialize;

use crate::{progress, util};

const CHUNK: usize = 16 * 1024 * 1024;
const FLOOR: u64 = 400 * 1024 * 1024;
const TAG: u64 = 1000;

#[derive(Serialize, Default)]
pub struct RamInfo {
    pub meminfo_total: u64,
    pub available: u64,
    pub syscall_total: u64,
    pub swap_total: u64,
}

#[derive(Serialize, Default)]
pub struct RamReport {
    pub target_bytes: u64,
    pub touched_bytes: u64,
    pub stopped_low: bool,
    pub cancelled: bool,
    pub bad_chunks: u64,
    pub secs: f64,
}

pub fn info() -> RamInfo {
    let mut si: libc::sysinfo = unsafe { std::mem::zeroed() };
    let syscall_total = if unsafe { libc::sysinfo(&mut si) } == 0 {
        si.totalram as u64 * si.mem_unit as u64
    } else {
        0
    };
    RamInfo {
        meminfo_total: util::meminfo("MemTotal:"),
        available: util::meminfo("MemAvailable:"),
        syscall_total,
        swap_total: util::meminfo("SwapTotal:"),
    }
}

pub fn stress(target_mb: u64, marker: &str) -> RamReport {
    let want_chunks = target_mb * 1024 * 1024 / CHUNK as u64;
    let mut rep = RamReport {
        target_bytes: want_chunks * CHUNK as u64,
        ..Default::default()
    };
    let mut held: Vec<Vec<u8>> = Vec::new();
    let clock = Instant::now();

    while (held.len() as u64) < want_chunks {
        if progress::cancelled() {
            rep.cancelled = true;
            break;
        }
        if util::meminfo("MemAvailable:") < FLOOR + CHUNK as u64 {
            rep.stopped_low = true;
            break;
        }
        let next = held.len() as u64 + 1;
        let _ = fs::write(marker, (next * CHUNK as u64).to_string());
        let mut chunk: Vec<u8> = Vec::new();
        if chunk.try_reserve_exact(CHUNK).is_err() {
            rep.stopped_low = true;
            break;
        }
        chunk.resize(CHUNK, 0);
        util::fill(&mut chunk, TAG + held.len() as u64);
        held.push(chunk);
        progress::set(3, held.len() as u64, want_chunks);
    }
    rep.touched_bytes = held.len() as u64 * CHUNK as u64;

    let mut want = vec![0u8; CHUNK];
    for (i, chunk) in held.iter().enumerate() {
        util::fill(&mut want, TAG + i as u64);
        if *chunk != want {
            rep.bad_chunks += 1;
        }
        progress::set(4, i as u64 + 1, held.len() as u64);
    }
    drop(held);
    let _ = fs::remove_file(marker);
    rep.secs = clock.elapsed().as_secs_f64();
    rep
}
