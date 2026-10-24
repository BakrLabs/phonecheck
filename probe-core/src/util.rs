use std::ffi::CString;
use std::fs;
use std::os::unix::ffi::OsStrExt;
use std::path::Path;

pub fn fill(buf: &mut [u8], index: u64) {
    let mut s = index.wrapping_add(1).wrapping_mul(0x9E37_79B9_7F4A_7C15);
    for word in buf.chunks_exact_mut(8) {
        s ^= s << 13;
        s ^= s >> 7;
        s ^= s << 17;
        word.copy_from_slice(&s.to_le_bytes());
    }
}

pub fn disk_space(path: &Path) -> (u64, u64) {
    let Ok(c) = CString::new(path.as_os_str().as_bytes()) else {
        return (0, 0);
    };
    let mut st: libc::statvfs = unsafe { std::mem::zeroed() };
    if unsafe { libc::statvfs(c.as_ptr(), &mut st) } != 0 {
        return (0, 0);
    }
    let unit = st.f_frsize as u64;
    (st.f_blocks as u64 * unit, st.f_bavail as u64 * unit)
}

pub fn meminfo(key: &str) -> u64 {
    let Ok(text) = fs::read_to_string("/proc/meminfo") else {
        return 0;
    };
    text.lines()
        .find(|l| l.starts_with(key))
        .and_then(|l| l.split_whitespace().nth(1))
        .and_then(|v| v.parse::<u64>().ok())
        .map(|kb| kb * 1024)
        .unwrap_or(0)
}
