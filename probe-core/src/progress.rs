use std::sync::atomic::{AtomicBool, AtomicU64, Ordering::Relaxed};

static PHASE: AtomicU64 = AtomicU64::new(0);
static DONE: AtomicU64 = AtomicU64::new(0);
static TOTAL: AtomicU64 = AtomicU64::new(0);
static CANCEL: AtomicBool = AtomicBool::new(false);

pub fn set(phase: u64, done: u64, total: u64) {
    PHASE.store(phase, Relaxed);
    DONE.store(done, Relaxed);
    TOTAL.store(total, Relaxed);
}

pub fn snapshot() -> (u64, u64, u64) {
    (PHASE.load(Relaxed), DONE.load(Relaxed), TOTAL.load(Relaxed))
}

pub fn cancel() {
    CANCEL.store(true, Relaxed);
}

pub fn reset() {
    CANCEL.store(false, Relaxed);
    set(0, 0, 0);
}

pub fn cancelled() -> bool {
    CANCEL.load(Relaxed)
}
