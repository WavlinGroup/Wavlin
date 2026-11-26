//! JNI shims for `com.wavlin.music.integration.identify.NativeShazam`.
//!
//! Both functions take and return `byte[]`. `null` means the native call failed or the input was
//! not usable, and Kotlin then runs its own signature generator or resampler.

use crate::lyrics_ffi::guarded;
use jni::objects::{JByteArray, JClass};
use jni::sys::{jbyteArray, jint};
use jni::JNIEnv;
use std::ptr::null_mut;

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_integration_identify_NativeShazam_signatureNative<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    pcm: JByteArray<'l>,
) -> jbyteArray {
    guarded(&mut env, null_mut(), |env| {
        let bytes = env.convert_byte_array(&pcm)?;
        let Some(signature) = shazam_core::signature_from_le_bytes(&bytes) else {
            return Ok(None);
        };
        Ok(Some(env.byte_array_from_slice(&signature)?.into_raw()))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_integration_identify_NativeShazam_resampleNative<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    pcm: JByteArray<'l>,
    input_rate: jint,
    output_rate: jint,
) -> jbyteArray {
    guarded(&mut env, null_mut(), |env| {
        let bytes = env.convert_byte_array(&pcm)?;
        let Some(resampled) = shazam_core::resample_linear_le_bytes(&bytes, input_rate, output_rate) else {
            return Ok(None);
        };
        Ok(Some(env.byte_array_from_slice(&resampled)?.into_raw()))
    })
}
