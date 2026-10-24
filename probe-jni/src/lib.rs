use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;
use probe_core::{cpu, progress, ram, storage};
use serde::Serialize;

fn json_out<T: Serialize>(env: &mut JNIEnv, value: &T) -> jstring {
    let text = serde_json::to_string(value).unwrap_or_default();
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn rust_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|v| v.into()).unwrap_or_default()
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_phoneprobe_Native_cpuInfo<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
) -> jstring {
    json_out(&mut env, &cpu::info())
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_phoneprobe_Native_cpuBench<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
) -> jstring {
    json_out(&mut env, &cpu::bench())
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_phoneprobe_Native_ramInfo<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
) -> jstring {
    json_out(&mut env, &ram::info())
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_phoneprobe_Native_ramTest<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    target_mb: jlong,
    marker: JString<'a>,
) -> jstring {
    let marker = rust_string(&mut env, &marker);
    json_out(&mut env, &ram::stress(target_mb.max(0) as u64, &marker))
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_phoneprobe_Native_storageTest<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    dir: JString<'a>,
    limit_mb: jlong,
) -> jstring {
    let dir = rust_string(&mut env, &dir);
    json_out(&mut env, &storage::run(&dir, limit_mb.max(0) as u64))
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_phoneprobe_Native_progress<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
) -> jstring {
    let (phase, done, total) = progress::snapshot();
    env.new_string(format!("{phase} {done} {total}"))
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_phoneprobe_Native_cancel<'a>(
    _env: JNIEnv<'a>,
    _class: JClass<'a>,
) {
    progress::cancel();
}

#[no_mangle]
pub extern "system" fn Java_dev_bakrlabs_phoneprobe_Native_reset<'a>(
    _env: JNIEnv<'a>,
    _class: JClass<'a>,
) {
    progress::reset();
}
