//! JNI shim for `com.wavlin.music.stream.cipher.NativePlayerConfig`.
//!
//! Returns `String[]?` in the wire format documented on `player_config_core::to_wire`. `null` means
//! the native call failed and Kotlin must run its own parser. Panics are caught and pending Java
//! exceptions cleared by `guarded`.

use crate::lyrics_ffi::{guarded, read_string, string_array};
use jni::objects::{JClass, JString};
use jni::sys::jobjectArray;
use jni::JNIEnv;
use std::ptr::null_mut;

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_stream_cipher_NativePlayerConfig_parseNative<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    json: JString<'l>,
) -> jobjectArray {
    guarded(&mut env, null_mut(), |env| {
        let Some(text) = read_string(env, &json) else { return Ok(None) };
        let wire = player_config_core::to_wire(&player_config_core::parse(&text));
        let refs: Vec<Option<&str>> = wire.iter().map(|s| Some(s.as_str())).collect();
        Ok(Some(string_array(env, &refs)?.into_raw()))
    })
}
