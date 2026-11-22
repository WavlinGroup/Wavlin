//! Wavlin audio DSP core, ported from the Kotlin ExoPlayer processors.
//!
//! Source of truth for the numbers in this crate:
//! - `sound/processors/ReverbAudioProcessor.kt` -> [`reverb`]
//! - `sound/equalizer/engine/BiquadFilter.kt` and
//!   `sound/equalizer/engine/CustomEqualizerAudioProcessor.kt` -> [`biquad`], [`eq`]
//! - `sound/processors/VolumeNormalizationAudioProcessor.kt` -> [`normalizer`]
//! - `sound/processors/SilenceDetectorAudioProcessor.kt` -> [`silence`]
//! - `sound/processors/DolbyAtmosAudioProcessor.kt` -> [`atmos`]
//!
//! All constants are carried over unchanged from the Kotlin code. The ExoPlayer
//! `AudioProcessor` shells (buffer handling, configure/flush lifecycle) are not in this crate.

pub mod atmos;
pub mod biquad;
pub mod eq;
pub mod normalizer;
pub mod reverb;
pub mod silence;

/// Rejected stream configuration. Returned instead of silently falling back to defaults.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConfigError {
    InvalidSampleRate(i32),
    InvalidChannelCount(usize),
}

impl std::fmt::Display for ConfigError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidSampleRate(v) => write!(f, "invalid sample rate: {v}"),
            Self::InvalidChannelCount(v) => write!(f, "invalid channel count: {v}"),
        }
    }
}

impl std::error::Error for ConfigError {}
