//! JNI shims for `com.wavlin.music.playback.NativeQueue`.
//!
//! Both functions return `int[]?`. `null` means the native call failed or the input was not usable,
//! and Kotlin then runs its own shuffle code.

use crate::lyrics_ffi::{guarded, len_i32};
use jni::objects::{JClass, JIntArray};
use jni::sys::{jboolean, jint, jintArray, jlong};
use jni::JNIEnv;
use std::ptr::null_mut;

fn int_array<'l>(env: &mut JNIEnv<'l>, values: &[i32]) -> jni::errors::Result<jintArray> {
    let array = env.new_int_array(len_i32(values.len())?)?;
    env.set_int_array_region(&array, 0, values)?;
    Ok(array.into_raw())
}

fn read_ints(env: &mut JNIEnv, array: &JIntArray) -> jni::errors::Result<Vec<i32>> {
    let len = env.get_array_length(array)?;
    let mut out = vec![0i32; usize::try_from(len).map_err(|_| jni::errors::Error::JavaException)?];
    env.get_int_array_region(array, 0, &mut out)?;
    Ok(out)
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_playback_NativeQueue_shuffleOrderNative<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    current: jint,
    total: jint,
    playlist_first: jboolean,
    original_size: jint,
    seed: jlong,
) -> jintArray {
    guarded(&mut env, null_mut(), |env| {
        // The seed is only entropy, so the signed-to-unsigned reinterpretation is intended.
        let Some(order) = queue_core::shuffle_order(current, total, playlist_first != 0, original_size, seed as u64) else {
            return Ok(None);
        };
        Ok(Some(int_array(env, &order)?))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_playback_NativeQueue_playNextOrderNative<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    prev: JIntArray<'l>,
    after: JIntArray<'l>,
    current: jint,
    insert_index: jint,
    item_count: jint,
    size: jint,
) -> jintArray {
    guarded(&mut env, null_mut(), |env| {
        let prev = read_ints(env, &prev)?;
        let after = read_ints(env, &after)?;
        let Some(order) = queue_core::play_next_order(&prev, &after, current, insert_index, item_count, size) else {
            return Ok(None);
        };
        Ok(Some(int_array(env, &order)?))
    })
}
