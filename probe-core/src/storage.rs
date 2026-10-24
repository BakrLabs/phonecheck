use std::fs::{self, File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::os::unix::io::AsRawFd;
use std::path::{Path, PathBuf};
use std::time::Instant;

use serde::Serialize;

use crate::{progress, util};

const BLOCK: usize = 4 * 1024 * 1024;
const PER_FILE: u64 = 64;
const RESERVE: u64 = 256 * 1024 * 1024;
const CHECK_EVERY: u64 = 8;

#[derive(Serialize, Default)]
pub struct StorageReport {
    pub total_bytes: u64,
    pub free_bytes: u64,
    pub written_bytes: u64,
    pub bad_blocks: u64,
    pub fake_detected: bool,
    pub est_real_bytes: u64,
    pub complete: bool,
    pub cancelled: bool,
    pub write_mbps: f64,
    pub read_mbps: f64,
    pub error: String,
}

struct Cleanup(PathBuf);

impl Drop for Cleanup {
    fn drop(&mut self) {
        if let Ok(entries) = fs::read_dir(&self.0) {
            for e in entries.flatten() {
                if e.file_name().to_string_lossy().starts_with("probe") {
                    let _ = fs::remove_file(e.path());
                }
            }
        }
    }
}

fn block_path(dir: &Path, j: u64) -> PathBuf {
    dir.join(format!("probe{:05}.bin", j / PER_FILE))
}

fn drop_cache(f: &File) {
    unsafe {
        libc::posix_fadvise(f.as_raw_fd(), 0, 0, libc::POSIX_FADV_DONTNEED);
    }
}

fn block_ok(dir: &Path, j: u64, got: &mut [u8], want: &mut [u8]) -> bool {
    util::fill(want, j);
    let Ok(mut f) = File::open(block_path(dir, j)) else {
        return false;
    };
    if f.seek(SeekFrom::Start((j % PER_FILE) * BLOCK as u64)).is_err() {
        return false;
    }
    f.read_exact(got).is_ok() && got == want
}

fn rate(bytes: u64, secs: f64) -> f64 {
    if secs > 0.0 {
        bytes as f64 / 1_048_576.0 / secs
    } else {
        0.0
    }
}

pub fn run(dir: &str, limit_mb: u64) -> StorageReport {
    let dir = PathBuf::from(dir);
    let mut rep = StorageReport::default();
    if let Err(e) = fs::create_dir_all(&dir) {
        rep.error = e.to_string();
        return rep;
    }
    let _cleanup = Cleanup(dir.clone());

    let (total, free) = util::disk_space(&dir);
    rep.total_bytes = total;
    rep.free_bytes = free;

    let usable = free.saturating_sub(RESERVE);
    let wanted = limit_mb * 1024 * 1024;
    let full_run = limit_mb == 0 || wanted >= usable;
    let cap = if full_run { usable } else { wanted };
    let blocks = cap / BLOCK as u64;
    if blocks == 0 {
        rep.error = "Not enough free space to run the test".into();
        return rep;
    }

    let mut buf = vec![0u8; BLOCK];
    let mut got = vec![0u8; BLOCK];
    let mut want = vec![0u8; BLOCK];
    let mut writer: Option<File> = None;
    let mut written = 0u64;
    let clock = Instant::now();

    for j in 0..blocks {
        if progress::cancelled() {
            rep.cancelled = true;
            break;
        }
        if j % PER_FILE == 0 {
            if let Some(prev) = writer.take() {
                let _ = prev.sync_data();
                drop_cache(&prev);
            }
            match OpenOptions::new()
                .create(true)
                .write(true)
                .truncate(true)
                .open(block_path(&dir, j))
            {
                Ok(f) => writer = Some(f),
                Err(e) => {
                    rep.error = e.to_string();
                    break;
                }
            }
        }
        util::fill(&mut buf, j);
        let Some(f) = writer.as_mut() else { break };
        if f.write_all(&buf).is_err() {
            break;
        }
        written += 1;
        progress::set(1, written, blocks);

        if written % CHECK_EVERY == 0 {
            let _ = f.sync_data();
            drop_cache(f);
            let first = block_ok(&dir, 0, &mut got, &mut want);
            let latest = block_ok(&dir, j, &mut got, &mut want);
            if !first || !latest {
                rep.fake_detected = true;
                rep.est_real_bytes = written.saturating_sub(CHECK_EVERY) * BLOCK as u64;
                break;
            }
        }
    }
    if let Some(f) = writer.take() {
        let _ = f.sync_data();
        drop_cache(&f);
    }
    rep.written_bytes = written * BLOCK as u64;
    rep.write_mbps = rate(rep.written_bytes, clock.elapsed().as_secs_f64());
    rep.complete = full_run && written == blocks && !rep.cancelled && !rep.fake_detected;

    if rep.fake_detected || rep.cancelled || !rep.error.is_empty() || written == 0 {
        return rep;
    }

    let clock = Instant::now();
    let mut checked = 0u64;
    for j in 0..written {
        if progress::cancelled() {
            rep.cancelled = true;
            rep.complete = false;
            break;
        }
        if !block_ok(&dir, j, &mut got, &mut want) {
            rep.bad_blocks += 1;
        }
        checked += 1;
        progress::set(2, checked, written);
    }
    rep.read_mbps = rate(checked * BLOCK as u64, clock.elapsed().as_secs_f64());
    if rep.bad_blocks > 0 {
        rep.complete = false;
        rep.est_real_bytes = (written - rep.bad_blocks) * BLOCK as u64;
    }
    rep
}
