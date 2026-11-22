//! JNI bridge for `com.wavlin.music.sound.nativeaudio.NativeAudio`.
//!
//! `engine` holds the thread-safe handles and the byte <-> sample conversion, with no JNI types,
//! so it is testable on the host. `ffi` contains only the thin `extern "system"` shims.

pub mod engine;
mod ffi;
