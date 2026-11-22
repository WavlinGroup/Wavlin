//! JNI shims for `com.wavlin.music.integration.lyrics.NativeLyrics`.
//!
//! Failures return `null`; Kotlin then runs its own parser. Panics are caught and any pending Java
//! exception is cleared before returning, so a failure here never surfaces as an exception.

use jni::objects::{JClass, JDoubleArray, JIntArray, JLongArray, JObject, JObjectArray, JString};
use jni::sys::{jobjectArray, jstring};
use jni::JNIEnv;
use lyrics_core::Packed;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr::null_mut;

type JniResult<T> = jni::errors::Result<T>;

fn read_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(String::from)
}

fn len_i32(len: usize) -> JniResult<i32> {
    i32::try_from(len).map_err(|_| jni::errors::Error::JavaException)
}

fn string_array<'l>(env: &mut JNIEnv<'l>, items: &[Option<&str>]) -> JniResult<JObjectArray<'l>> {
    let array = env.new_object_array(len_i32(items.len())?, "java/lang/String", JObject::null())?;
    for (i, item) in items.iter().enumerate() {
        if let Some(text) = item {
            let s = env.new_string(text)?;
            env.set_object_array_element(&array, len_i32(i)?, &s)?;
            env.delete_local_ref(s)?;
        }
    }
    Ok(array)
}

fn boolean_array<'l>(env: &mut JNIEnv<'l>, items: &[bool]) -> JniResult<jni::objects::JBooleanArray<'l>> {
    let array = env.new_boolean_array(len_i32(items.len())?)?;
    let raw: Vec<u8> = items.iter().map(|&b| u8::from(b)).collect();
    env.set_boolean_array_region(&array, 0, &raw)?;
    Ok(array)
}

fn long_array<'l>(env: &mut JNIEnv<'l>, items: &[i64]) -> JniResult<JLongArray<'l>> {
    let array = env.new_long_array(len_i32(items.len())?)?;
    env.set_long_array_region(&array, 0, items)?;
    Ok(array)
}

fn int_array<'l>(env: &mut JNIEnv<'l>, items: &[i32]) -> JniResult<JIntArray<'l>> {
    let array = env.new_int_array(len_i32(items.len())?)?;
    env.set_int_array_region(&array, 0, items)?;
    Ok(array)
}

fn double_array<'l>(env: &mut JNIEnv<'l>, items: &[f64]) -> JniResult<JDoubleArray<'l>> {
    let array = env.new_double_array(len_i32(items.len())?)?;
    env.set_double_array_region(&array, 0, items)?;
    Ok(array)
}

/// Layout of the `Object[]` returned to Kotlin, in order:
/// 0 `LongArray` times (ms), 1 `Array<String>` texts, 2 `Array<String?>` agents,
/// 3 `BooleanArray` isBackground, 4 `IntArray` wordCounts (-1 = no word timings),
/// 5 `Array<String>` wordTexts, 6 `DoubleArray` wordStarts (s), 7 `DoubleArray` wordEnds (s),
/// 8 `BooleanArray` wordTrailingSpaces.
fn build_parse_result<'l>(env: &mut JNIEnv<'l>, p: &Packed) -> JniResult<JObjectArray<'l>> {
    let out = env.new_object_array(9, "java/lang/Object", JObject::null())?;

    let times = long_array(env, &p.times)?;
    env.set_object_array_element(&out, 0, &times)?;

    let texts: Vec<Option<&str>> = p.texts.iter().map(|s| Some(s.as_str())).collect();
    let texts = string_array(env, &texts)?;
    env.set_object_array_element(&out, 1, &texts)?;

    let agents: Vec<Option<&str>> = p.agents.iter().map(|s| s.as_deref()).collect();
    let agents = string_array(env, &agents)?;
    env.set_object_array_element(&out, 2, &agents)?;

    let background = boolean_array(env, &p.is_background)?;
    env.set_object_array_element(&out, 3, &background)?;

    let counts = int_array(env, &p.word_counts)?;
    env.set_object_array_element(&out, 4, &counts)?;

    let word_texts: Vec<Option<&str>> = p.word_texts.iter().map(|s| Some(s.as_str())).collect();
    let word_texts = string_array(env, &word_texts)?;
    env.set_object_array_element(&out, 5, &word_texts)?;

    let starts = double_array(env, &p.word_starts)?;
    env.set_object_array_element(&out, 6, &starts)?;

    let ends = double_array(env, &p.word_ends)?;
    env.set_object_array_element(&out, 7, &ends)?;

    let trailing = boolean_array(env, &p.word_trailing_spaces)?;
    env.set_object_array_element(&out, 8, &trailing)?;

    Ok(out)
}

fn guarded<T>(env: &mut JNIEnv, default: T, f: impl FnOnce(&mut JNIEnv) -> JniResult<Option<T>>) -> T {
    match catch_unwind(AssertUnwindSafe(|| f(env))) {
        Ok(Ok(Some(value))) => value,
        Ok(Err(_)) | Err(_) => {
            // A failed JNI call can leave an exception pending (for example OutOfMemoryError).
            let _ = env.exception_clear();
            default
        }
        Ok(Ok(None)) => default,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_integration_lyrics_NativeLyrics_parseNative<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    text: JString<'l>,
) -> jobjectArray {
    guarded(&mut env, null_mut(), |env| {
        let Some(input) = read_string(env, &text) else { return Ok(None) };
        let entries = lyrics_core::parse_lyrics(&input);
        let Some(packed) = lyrics_core::pack(&entries) else { return Ok(None) };
        Ok(Some(build_parse_result(env, &packed)?.into_raw()))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_integration_lyrics_NativeLyrics_filterCreditLinesNative<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    text: JString<'l>,
) -> jstring {
    guarded(&mut env, null_mut(), |env| {
        let Some(input) = read_string(env, &text) else { return Ok(None) };
        let filtered = lyrics_core::filter_credit_lines(&input);
        Ok(Some(env.new_string(filtered)?.into_raw()))
    })
}
