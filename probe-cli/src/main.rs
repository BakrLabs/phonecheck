use probe_core::{cpu, ram, storage};
use serde::Serialize;

fn show<T: Serialize>(value: &T) {
    println!("{}", serde_json::to_string_pretty(value).unwrap_or_default());
}

fn arg_u64(args: &[String], at: usize, default: u64) -> u64 {
    args.get(at).and_then(|a| a.parse().ok()).unwrap_or(default)
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("cpu") => {
            show(&cpu::info());
            show(&cpu::bench());
        }
        Some("ram") => {
            show(&ram::info());
            let marker = std::env::temp_dir().join("probe_ram.marker");
            show(&ram::stress(arg_u64(&args, 2, 512), &marker.to_string_lossy()));
        }
        Some("storage") => {
            let dir = args.get(2).map(String::as_str).unwrap_or("probe_tmp");
            show(&storage::run(dir, arg_u64(&args, 3, 512)));
        }
        _ => println!("usage: probe cpu | probe ram [mb] | probe storage [dir] [mb, 0 = all free space]"),
    }
}
