//! JNI shims for `com.wavlin.music.sound.nativeaudio.NativeAudio`.
//!
//! Handles are `Box` pointers owned by the Kotlin side: created once, destroyed once. Every
//! shim catches panics so nothing unwinds across the FFI boundary; failures return a sentinel
//! (0, false or -1) and Kotlin falls back to its own path.

use crate::engine::{profile_from_arrays, EqEngine, NormalizerEngine, ReverbEngine, SilenceEngine};
use jni::objects::{JBooleanArray, JByteBuffer, JClass, JDoubleArray, JIntArray};
use jni::sys::{jboolean, jdouble, jfloat, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

fn guard<T>(default: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(default)
}

/// # Safety
/// `handle` must be 0 or a live pointer previously returned by the matching `create`.
unsafe fn engine_ref<'a, T>(handle: jlong) -> Option<&'a T> {
    (handle as *const T).as_ref()
}

fn create<T>(value: T) -> jlong {
    Box::into_raw(Box::new(value)) as jlong
}

/// # Safety
/// `handle` must be 0 or a pointer returned by `create::<T>` that has not been destroyed yet.
unsafe fn destroy<T>(handle: jlong) {
    if handle != 0 {
        drop(Box::from_raw(handle as *mut T));
    }
}

/// Resolves `[offset, offset + length)` inside a direct buffer. `None` when the buffer is not
/// direct or the range is out of bounds.
fn direct_range(env: &JNIEnv, buf: &JByteBuffer, offset: jint, length: jint) -> Option<(*mut u8, usize)> {
    let (offset, length) = (usize::try_from(offset).ok()?, usize::try_from(length).ok()?);
    let ptr = env.get_direct_buffer_address(buf).ok()?;
    let capacity = env.get_direct_buffer_capacity(buf).ok()?;
    if ptr.is_null() || offset.checked_add(length)? > capacity {
        return None;
    }
    // SAFETY: offset + length <= capacity, so the result stays inside the buffer.
    Some((unsafe { ptr.add(offset) }, length))
}

fn ranges_overlap(a: (*mut u8, usize), b: (*mut u8, usize)) -> bool {
    let (a0, b0) = (a.0 as usize, b.0 as usize);
    a0 < b0 + b.1 && b0 < a0 + a.1
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_reverbCreate(_env: JNIEnv, _class: JClass) -> jlong {
    guard(0, || create(ReverbEngine::new()))
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_reverbDestroy(_env: JNIEnv, _class: JClass, handle: jlong) {
    // SAFETY: Kotlin destroys each handle exactly once, after the last use.
    guard((), || unsafe { destroy::<ReverbEngine>(handle) })
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_reverbSetParameters(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    room: jfloat,
    damping: jfloat,
    wet_level: jfloat,
) {
    guard((), || {
        // SAFETY: see `engine_ref`.
        if let Some(e) = unsafe { engine_ref::<ReverbEngine>(handle) } {
            e.set_parameters(room, damping, wet_level);
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_reverbSetEnabled(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    enabled: jboolean,
) {
    guard((), || {
        // SAFETY: see `engine_ref`.
        if let Some(e) = unsafe { engine_ref::<ReverbEngine>(handle) } {
            e.set_enabled(enabled != JNI_FALSE);
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_reverbConfigure(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    sample_rate: jint,
    channel_count: jint,
) -> jboolean {
    let ok = guard(false, || {
        // SAFETY: see `engine_ref`.
        unsafe { engine_ref::<ReverbEngine>(handle) }.map_or(false, |e| e.configure(sample_rate, channel_count))
    });
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_reverbClearState(_env: JNIEnv, _class: JClass, handle: jlong) {
    guard((), || {
        // SAFETY: see `engine_ref`.
        if let Some(e) = unsafe { engine_ref::<ReverbEngine>(handle) } {
            e.clear_state();
        }
    })
}

/// Returns the bytes written to `output`, or -1 on any failure (Kotlin then passes input through).
#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_reverbProcess(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    input: JByteBuffer,
    input_offset: jint,
    length: jint,
    output: JByteBuffer,
    is_float: jboolean,
) -> jint {
    guard(-1, || {
        // SAFETY: see `engine_ref`.
        let Some(engine) = (unsafe { engine_ref::<ReverbEngine>(handle) }) else { return -1 };
        let Some(inp) = direct_range(&env, &input, input_offset, length) else { return -1 };
        let Some(out) = direct_range(&env, &output, 0, length) else { return -1 };
        if ranges_overlap(inp, out) {
            return -1;
        }
        // SAFETY: both ranges were bounds-checked against their buffers and do not overlap.
        let (src, dst) = unsafe { (std::slice::from_raw_parts(inp.0, inp.1), std::slice::from_raw_parts_mut(out.0, out.1)) };
        jint::try_from(engine.process(src, dst, is_float != JNI_FALSE)).unwrap_or(-1)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_eqCreate(_env: JNIEnv, _class: JClass) -> jlong {
    guard(0, || create(EqEngine::new()))
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_eqDestroy(_env: JNIEnv, _class: JClass, handle: jlong) {
    // SAFETY: Kotlin destroys each handle exactly once, after the last use.
    guard((), || unsafe { destroy::<EqEngine>(handle) })
}

/// Returns the number of surviving filters, or -1 on failure (previous state is kept).
/// `params` is `frequency, gain, q` per band; `types` holds `FilterType.ordinal`.
#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_eqApplyProfile(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    sample_rate: jint,
    channel_count: jint,
    preamp_db: jdouble,
    params: JDoubleArray,
    types: JIntArray,
    enabled: JBooleanArray,
) -> jint {
    guard(-1, || {
        // SAFETY: see `engine_ref`.
        let Some(engine) = (unsafe { engine_ref::<EqEngine>(handle) }) else { return -1 };
        let (Ok(p_len), Ok(t_len), Ok(e_len)) = (
            env.get_array_length(&params),
            env.get_array_length(&types),
            env.get_array_length(&enabled),
        ) else {
            return -1;
        };
        let (Ok(p_len), Ok(t_len), Ok(e_len)) = (usize::try_from(p_len), usize::try_from(t_len), usize::try_from(e_len)) else {
            return -1;
        };
        let mut p = vec![0.0f64; p_len];
        let mut t = vec![0i32; t_len];
        let mut e = vec![0u8; e_len];
        if env.get_double_array_region(&params, 0, &mut p).is_err()
            || env.get_int_array_region(&types, 0, &mut t).is_err()
            || env.get_boolean_array_region(&enabled, 0, &mut e).is_err()
        {
            return -1;
        }
        let flags: Vec<bool> = e.iter().map(|&b| b != JNI_FALSE).collect();
        let Some(profile) = profile_from_arrays(preamp_db, &p, &t, &flags) else { return -1 };
        engine
            .apply_profile(sample_rate, channel_count, &profile)
            .and_then(|n| jint::try_from(n).ok())
            .unwrap_or(-1)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_eqDisable(_env: JNIEnv, _class: JClass, handle: jlong) {
    guard((), || {
        // SAFETY: see `engine_ref`.
        if let Some(e) = unsafe { engine_ref::<EqEngine>(handle) } {
            e.disable();
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_eqResetState(_env: JNIEnv, _class: JClass, handle: jlong) {
    guard((), || {
        // SAFETY: see `engine_ref`.
        if let Some(e) = unsafe { engine_ref::<EqEngine>(handle) } {
            e.reset_state();
        }
    })
}

/// Returns the bytes written to `output`, or -1 on any failure.
#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_eqProcess(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    input: JByteBuffer,
    input_offset: jint,
    length: jint,
    output: JByteBuffer,
) -> jint {
    guard(-1, || {
        // SAFETY: see `engine_ref`.
        let Some(engine) = (unsafe { engine_ref::<EqEngine>(handle) }) else { return -1 };
        let Some(inp) = direct_range(&env, &input, input_offset, length) else { return -1 };
        let Some(out) = direct_range(&env, &output, 0, length) else { return -1 };
        if ranges_overlap(inp, out) {
            return -1;
        }
        // SAFETY: both ranges were bounds-checked against their buffers and do not overlap.
        let (src, dst) = unsafe { (std::slice::from_raw_parts(inp.0, inp.1), std::slice::from_raw_parts_mut(out.0, out.1)) };
        jint::try_from(engine.process(src, dst)).unwrap_or(-1)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_normalizerCreate(_env: JNIEnv, _class: JClass) -> jlong {
    guard(0, || create(NormalizerEngine::new()))
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_normalizerDestroy(_env: JNIEnv, _class: JClass, handle: jlong) {
    // SAFETY: Kotlin destroys each handle exactly once, after the last use.
    guard((), || unsafe { destroy::<NormalizerEngine>(handle) })
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_normalizerSetEnabled(_env: JNIEnv, _class: JClass, handle: jlong, enabled: jboolean) {
    guard((), || {
        // SAFETY: see `engine_ref`.
        if let Some(e) = unsafe { engine_ref::<NormalizerEngine>(handle) } {
            e.set_enabled(enabled != JNI_FALSE);
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_normalizerSetTargetGain(_env: JNIEnv, _class: JClass, handle: jlong, gain_mb: jint) {
    guard((), || {
        // SAFETY: see `engine_ref`.
        if let Some(e) = unsafe { engine_ref::<NormalizerEngine>(handle) } {
            e.set_target_gain(gain_mb);
        }
    })
}

/// `format_code`: 0 = 16-bit, 1 = 24-bit, 2 = 32-bit, 3 = float. `length` must be a whole number
/// of samples. Returns the bytes written to `output`, or -1 on any failure.
#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_normalizerProcess(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    input: JByteBuffer,
    input_offset: jint,
    length: jint,
    output: JByteBuffer,
    format_code: jint,
) -> jint {
    guard(-1, || {
        // SAFETY: see `engine_ref`.
        let Some(engine) = (unsafe { engine_ref::<NormalizerEngine>(handle) }) else { return -1 };
        let Some(inp) = direct_range(&env, &input, input_offset, length) else { return -1 };
        let Some(out) = direct_range(&env, &output, 0, length) else { return -1 };
        if ranges_overlap(inp, out) {
            return -1;
        }
        // SAFETY: both ranges were bounds-checked against their buffers and do not overlap.
        let (src, dst) = unsafe { (std::slice::from_raw_parts(inp.0, inp.1), std::slice::from_raw_parts_mut(out.0, out.1)) };
        engine.process(src, dst, format_code).and_then(|n| jint::try_from(n).ok()).unwrap_or(-1)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_silenceCreate(
    _env: JNIEnv,
    _class: JClass,
    min_silence_duration_us: jlong,
    silence_threshold: jint,
) -> jlong {
    guard(0, || create(SilenceEngine::new(min_silence_duration_us, silence_threshold)))
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_silenceDestroy(_env: JNIEnv, _class: JClass, handle: jlong) {
    // SAFETY: Kotlin destroys each handle exactly once, after the last use.
    guard((), || unsafe { destroy::<SilenceEngine>(handle) })
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_silenceConfigure(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    sample_rate: jint,
    channel_count: jint,
) -> jboolean {
    let ok = guard(false, || {
        // SAFETY: see `engine_ref`.
        unsafe { engine_ref::<SilenceEngine>(handle) }.map_or(false, |e| e.configure(sample_rate, channel_count))
    });
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_silenceClearState(_env: JNIEnv, _class: JClass, handle: jlong) {
    guard((), || {
        // SAFETY: see `engine_ref`.
        if let Some(e) = unsafe { engine_ref::<SilenceEngine>(handle) } {
            e.clear_state();
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_silenceIsSilent(_env: JNIEnv, _class: JClass, handle: jlong) -> jboolean {
    let silent = guard(false, || {
        // SAFETY: see `engine_ref`.
        unsafe { engine_ref::<SilenceEngine>(handle) }.map_or(false, |e| e.is_silent())
    });
    if silent { JNI_TRUE } else { JNI_FALSE }
}

/// Analyzes `[input_offset, input_offset + length)` without modifying it. Returns the number of
/// long-silence notifications for Kotlin to deliver, or -1 on failure (nothing was analyzed).
#[no_mangle]
pub extern "system" fn Java_com_wavlin_music_sound_nativeaudio_NativeAudio_silenceAnalyze(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    input: JByteBuffer,
    input_offset: jint,
    length: jint,
    instant_mode: jboolean,
) -> jint {
    guard(-1, || {
        // SAFETY: see `engine_ref`.
        let Some(engine) = (unsafe { engine_ref::<SilenceEngine>(handle) }) else { return -1 };
        let Some(inp) = direct_range(&env, &input, input_offset, length) else { return -1 };
        // SAFETY: the range was bounds-checked against the buffer; it is only read.
        let src = unsafe { std::slice::from_raw_parts(inp.0, inp.1) };
        jint::try_from(engine.analyze(src, instant_mode != JNI_FALSE)).unwrap_or(-1)
    })
}
