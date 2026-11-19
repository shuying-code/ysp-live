use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring};
use jni::JNIEnv;

use ysp_core::{program_url, ChannelCatalog, PlayUrl};

fn read_string(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Option<String> {
    env.get_string(value).ok().map(Into::into)
}

fn new_string(env: &mut JNIEnv<'_>, value: &str) -> jstring {
    env.new_string(value)
        .map(|string| string.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_ysp_live_NativeCore_nativeVersion(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    new_string(&mut env, env!("CARGO_PKG_VERSION"))
}

#[no_mangle]
pub extern "system" fn Java_com_ysp_live_NativeCore_nativeChannelCount(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    json: JString<'_>,
) -> jni::sys::jint {
    let Some(json) = read_string(&mut env, &json) else {
        return 0;
    };
    ChannelCatalog::from_json(&json)
        .map(|catalog| catalog.channels.len() as jni::sys::jint)
        .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_ysp_live_NativeCore_nativeValidatePlayUrl(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    url: JString<'_>,
) -> jboolean {
    read_string(&mut env, &url)
        .and_then(|url| PlayUrl::parse(&url).ok())
        .is_some() as jboolean
}

#[no_mangle]
pub extern "system" fn Java_com_ysp_live_NativeCore_nativeEpgUrl(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    pid: JString<'_>,
    date: JString<'_>,
) -> jstring {
    let Some(pid) = read_string(&mut env, &pid) else {
        return std::ptr::null_mut();
    };
    let Some(date) = read_string(&mut env, &date) else {
        return std::ptr::null_mut();
    };
    match program_url(&pid, &date) {
        Ok(url) => new_string(&mut env, &url),
        Err(_) => std::ptr::null_mut(),
    }
}
