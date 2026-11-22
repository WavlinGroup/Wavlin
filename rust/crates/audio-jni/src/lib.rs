//! JNI bridge for `com.wavlin.music.sound.nativeaudio.NativeAudio`.
//!
//! `engine` holds the thread-safe handles and the byte <-> sample conversion, with no JNI types,
//! so it is testable on the host. `ffi` contains only the thin `extern "system"` shims.
//! `lyrics_ffi` does the same for `com.wavlin.music.integration.lyrics.NativeLyrics`, and
//! `cipher_ffi` for `com.wavlin.music.stream.cipher.NativeCipher`.

pub mod engine;
mod ffi;
mod cipher_ffi;
mod lyrics_ffi;
