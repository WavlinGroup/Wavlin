//! Shazam signature generation and linear resampling, ported from
//! `integration/identify/ShazamSignatureGenerator.kt` and `AudioResampler.kt`.
//!
//! Pure functions with no dependencies. The arithmetic follows the Kotlin code operation by
//! operation (same FFT twiddle recurrence, same comparison order, same float-to-int casts), so the
//! output is meant to be byte-identical. Every function returns `None` for input it cannot handle,
//! and Kotlin then runs its original code, which also reproduces the original error behaviour.

mod crc32;
mod fft;
mod resample;
mod signature;

pub use resample::{resample_linear, resample_linear_le_bytes};
pub use signature::{signature_from_i16, signature_from_le_bytes};

/// Sample rate the signature algorithm requires. Matches `VibraSignature.REQUIRED_SAMPLE_RATE`.
pub const REQUIRED_SAMPLE_RATE: i32 = 16_000;

/// Upper bound on resampler input and output sample counts (16 Mi samples, 32 MiB of PCM). Real
/// recordings are a few hundred thousand samples; the bound only keeps a bad input from exhausting
/// memory on a 32-bit device.
pub(crate) const MAX_SAMPLES: usize = 1 << 24;

/// Upper bound for signature input: ten minutes at 16 kHz. Recordings are about ten seconds; this
/// keeps a pathological input from running for minutes inside one JNI call.
pub(crate) const MAX_SIGNATURE_SAMPLES: usize = REQUIRED_SAMPLE_RATE as usize * 600;

/// Little-endian bytes to samples. A trailing odd byte is dropped.
pub(crate) fn le_bytes_to_i16(bytes: &[u8]) -> Vec<i16> {
    bytes.chunks_exact(2).map(|pair| i16::from_le_bytes([pair[0], pair[1]])).collect()
}

pub(crate) fn i16_to_le_bytes(samples: &[i16]) -> Vec<u8> {
    let mut out = Vec::with_capacity(samples.len() * 2);
    for s in samples {
        out.extend_from_slice(&s.to_le_bytes());
    }
    out
}

#[cfg(test)]
mod tests;
