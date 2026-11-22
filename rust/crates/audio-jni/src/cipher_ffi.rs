//! JNI shims for `com.wavlin.music.stream.cipher.NativeCipher`.
//!
//! Every function returns `String[]?`: `null` means the native call failed and Kotlin must run its
//! own regex, an empty array means the pattern set found nothing, and a non-empty array holds the
//! match. Panics are caught and pending Java exceptions cleared by `guarded`.

use crate::lyrics_ffi::{guarded, read_string, string_array};
use jni::objects::{JClass, JString};
use jni::sys::jobjectArray;
use jni::JNIEnv;
use std::ptr::null_mut;

fn reply<'l>(env: &mut JNIEnv<'l>, items: &[Option<String>]) -> jni::errors::Result<jobjectArray> {
    let refs: Vec<Option<&str>> = items.iter().map(|s| s.as_deref()).collect();
    Ok(string_array(env, &refs)?.into_raw())
}

fn scan<'l>(
    env: &mut JNIEnv<'l>,
    text: &JString<'l>,
    f: impl FnOnce(&str) -> Vec<Option<String>>,
) -> jobjectArray {
    guarded(env, null_mut(), |env| {
        let Some(js) = read_string(env, text) else { return Ok(None) };
        let items = f(&js);
        Ok(Some(reply(env, &items)?))
    })
}

fn opt_int(v: Option<i32>) -> Option<String> {
    v.map(|n| n.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_stream_cipher_NativeCipher_hasQArrayNative<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    js: JString<'l>,
) -> jobjectArray {
    scan(&mut env, &js, |s| vec![Some(if cipher_core::has_q_array(s) { "1" } else { "0" }.to_owned())])
}

/// `[]` = no embedded hash; `[hash]` = found.
#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_stream_cipher_NativeCipher_findPlayerHashNative<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    js: JString<'l>,
) -> jobjectArray {
    scan(&mut env, &js, |s| cipher_core::find_player_hash(s).map(Some).into_iter().collect())
}

/// `[]` = not found; `[value]` = found. `anchored` selects the `signatureTimestamp` literal
/// pattern, otherwise the loose `sts` pattern.
#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_stream_cipher_NativeCipher_findSignatureTimestampNative<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    js: JString<'l>,
    anchored: jni::sys::jboolean,
) -> jobjectArray {
    scan(&mut env, &js, |s| {
        let found = if anchored != 0 { cipher_core::find_anchored_sts(s) } else { cipher_core::find_loose_sts(s) };
        found.map(|n| Some(n.to_string())).into_iter().collect()
    })
}

/// `[]` = not found; `[name, constantArg-or-null]` = found.
#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_stream_cipher_NativeCipher_findSigFunctionNative<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    js: JString<'l>,
) -> jobjectArray {
    scan(&mut env, &js, |s| match cipher_core::find_sig_function(s) {
        Some(m) => vec![Some(m.name), opt_int(m.constant_arg)],
        None => Vec::new(),
    })
}

/// `[]` = not found; `[name, arrayIndex-or-null]` = found.
#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_stream_cipher_NativeCipher_findNFunctionNative<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    js: JString<'l>,
) -> jobjectArray {
    scan(&mut env, &js, |s| match cipher_core::find_n_function(s) {
        Some(m) => vec![Some(m.name), opt_int(m.array_index)],
        None => Vec::new(),
    })
}
