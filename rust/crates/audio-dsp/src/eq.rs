//! Parametric EQ chain. Port of the DSP in `CustomEqualizerAudioProcessor.kt`.
//!
//! The ExoPlayer shell (pending profile before `configure`, rejecting non-16-bit input,
//! buffer reuse) stays in Kotlin. This type owns the filters, preamp and the 16-bit PCM path.

use crate::biquad::{BiquadFilter, FilterType};
use crate::ConfigError;

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct EqBand {
    pub frequency: f64,
    pub gain: f64,
    pub q: f64,
    pub filter_type: FilterType,
    pub enabled: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct EqProfile {
    pub preamp_db: f64,
    pub bands: Vec<EqBand>,
}

pub struct Equalizer {
    sample_rate: i32,
    channel_count: usize,
    enabled: bool,
    filters: Vec<BiquadFilter>,
    preamp_gain: f64,
}

impl Equalizer {
    pub fn new(sample_rate: i32, channel_count: usize) -> Result<Self, ConfigError> {
        if sample_rate <= 0 {
            return Err(ConfigError::InvalidSampleRate(sample_rate));
        }
        if channel_count == 0 {
            return Err(ConfigError::InvalidChannelCount(channel_count));
        }
        Ok(Self {
            sample_rate,
            channel_count,
            enabled: false,
            filters: Vec::new(),
            preamp_gain: 1.0,
        })
    }

    /// Mirrors `applyProfile` once configured: preamp dB -> linear, keep only enabled bands
    /// below Nyquist, fresh filter state, equalizer on.
    pub fn apply_profile(&mut self, profile: &EqProfile) {
        self.preamp_gain = 10.0_f64.powf(profile.preamp_db / 20.0);
        let nyquist = f64::from(self.sample_rate) / 2.0;
        self.filters = profile
            .bands
            .iter()
            .filter(|b| b.enabled && b.frequency < nyquist)
            .map(|b| BiquadFilter::new(self.sample_rate, b.frequency, b.gain, b.q, b.filter_type))
            .collect();
        self.enabled = true;
        self.reset_state();
    }

    pub fn disable(&mut self) {
        self.enabled = false;
        self.filters.clear();
        self.preamp_gain = 1.0;
    }

    pub fn is_enabled(&self) -> bool {
        self.enabled
    }

    pub fn filter_count(&self) -> usize {
        self.filters.len()
    }

    pub fn preamp_gain(&self) -> f64 {
        self.preamp_gain
    }

    pub fn reset_state(&mut self) {
        for f in &mut self.filters {
            f.reset();
        }
    }

    /// Processes interleaved signed 16-bit PCM. Returns the number of samples written.
    ///
    /// Only whole frames are processed, like the Kotlin loop. With the equalizer off, or no
    /// surviving filters, the input is copied unchanged. Channel counts above 2 are copied
    /// unchanged (the Kotlin `configure` rejects them, so this is only a safe fallback).
    pub fn process_i16(&mut self, input: &[i16], output: &mut [i16]) -> usize {
        let len = input.len().min(output.len());
        if !self.enabled || self.filters.is_empty() {
            output[..len].copy_from_slice(&input[..len]);
            return len;
        }

        let ch = self.channel_count;
        let frames = len / ch;
        let written = frames * ch;
        match ch {
            1 => {
                for (src, dst) in input[..written].iter().zip(output[..written].iter_mut()) {
                    let mut processed = f64::from(*src) / 32768.0;
                    for f in &mut self.filters {
                        processed = f.process_sample(processed);
                    }
                    processed *= self.preamp_gain;
                    *dst = to_i16(processed);
                }
            }
            2 => {
                for (src, dst) in input[..written]
                    .chunks_exact(2)
                    .zip(output[..written].chunks_exact_mut(2))
                {
                    let mut left = f64::from(src[0]) / 32768.0;
                    let mut right = f64::from(src[1]) / 32768.0;
                    for f in &mut self.filters {
                        let (l, r) = f.process_stereo(left, right);
                        left = l;
                        right = r;
                    }
                    left *= self.preamp_gain;
                    right *= self.preamp_gain;
                    dst[0] = to_i16(left);
                    dst[1] = to_i16(right);
                }
            }
            _ => output[..written].copy_from_slice(&input[..written]),
        }
        written
    }
}

/// `(v * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort()`. NaN becomes 0 in both.
fn to_i16(v: f64) -> i16 {
    let scaled = v * 32768.0;
    let clamped = if scaled < -32768.0 {
        -32768.0
    } else if scaled > 32767.0 {
        32767.0
    } else {
        scaled
    };
    (clamped as i32) as i16
}

#[cfg(test)]
mod tests {
    use super::*;

    fn band(freq: f64, gain: f64, t: FilterType, enabled: bool) -> EqBand {
        EqBand { frequency: freq, gain, q: 1.41, filter_type: t, enabled }
    }

    #[test]
    fn rejects_invalid_configuration() {
        assert!(matches!(Equalizer::new(0, 2), Err(ConfigError::InvalidSampleRate(0))));
        assert!(matches!(Equalizer::new(44100, 0), Err(ConfigError::InvalidChannelCount(0))));
    }

    #[test]
    fn disabled_equalizer_copies_input() {
        let mut eq = Equalizer::new(44100, 2).unwrap();
        let input: Vec<i16> = (0..64).map(|i| (i * 311 - 9000) as i16).collect();
        let mut out = vec![0i16; 64];
        assert_eq!(eq.process_i16(&input, &mut out), 64);
        assert_eq!(input, out);
    }

    #[test]
    fn bands_that_are_disabled_or_at_or_above_nyquist_are_dropped() {
        let mut eq = Equalizer::new(44100, 2).unwrap();
        eq.apply_profile(&EqProfile {
            preamp_db: -3.0,
            bands: vec![
                band(100.0, 2.0, FilterType::Pk, true),
                band(200.0, 2.0, FilterType::Pk, false),
                band(22050.0, 2.0, FilterType::Pk, true),
                band(30000.0, 2.0, FilterType::Hsc, true),
            ],
        });
        assert_eq!(eq.filter_count(), 1);
        assert!(eq.is_enabled());
        assert!((eq.preamp_gain() - 10.0_f64.powf(-3.0 / 20.0)).abs() < 1e-15);
    }

    #[test]
    fn profile_with_no_surviving_bands_passes_audio_through() {
        let mut eq = Equalizer::new(44100, 2).unwrap();
        eq.apply_profile(&EqProfile { preamp_db: -6.0, bands: vec![band(30000.0, 3.0, FilterType::Pk, true)] });
        let input = [1000i16, -1000, 2000, -2000];
        let mut out = [0i16; 4];
        eq.process_i16(&input, &mut out);
        assert_eq!(input, out);
    }

    #[test]
    fn preamp_only_scales_with_truncation_toward_zero() {
        // A 0 dB peaking band is an identity filter, so only the preamp acts.
        let mut eq = Equalizer::new(44100, 1).unwrap();
        eq.apply_profile(&EqProfile { preamp_db: -6.0, bands: vec![band(1000.0, 0.0, FilterType::Pk, true)] });
        let input = [10000i16, -10000, 3];
        let mut out = [0i16; 3];
        eq.process_i16(&input, &mut out);
        let g = 10.0_f64.powf(-6.0 / 20.0);
        for (i, o) in input.iter().zip(out.iter()) {
            let expected = ((f64::from(*i) / 32768.0 * g) * 32768.0) as i32;
            assert!((i32::from(*o) - expected).abs() <= 1, "{i} -> {o}, expected {expected}");
        }
    }

    #[test]
    fn output_saturates_instead_of_wrapping() {
        let mut eq = Equalizer::new(44100, 1).unwrap();
        eq.apply_profile(&EqProfile { preamp_db: 12.0, bands: vec![band(1000.0, 0.0, FilterType::Pk, true)] });
        let input = [i16::MAX, i16::MIN];
        let mut out = [0i16; 2];
        eq.process_i16(&input, &mut out);
        assert_eq!(out, [i16::MAX, i16::MIN]);
    }

    #[test]
    fn partial_trailing_frame_is_not_written() {
        let mut eq = Equalizer::new(44100, 2).unwrap();
        eq.apply_profile(&EqProfile { preamp_db: 0.0, bands: vec![band(1000.0, 3.0, FilterType::Pk, true)] });
        let input = [100i16, 200, 300];
        let mut out = [7i16; 3];
        assert_eq!(eq.process_i16(&input, &mut out), 2);
        assert_eq!(out[2], 7);
    }

    #[test]
    fn disable_restores_passthrough_and_unity_preamp() {
        let mut eq = Equalizer::new(44100, 2).unwrap();
        eq.apply_profile(&EqProfile { preamp_db: -6.0, bands: vec![band(1000.0, 6.0, FilterType::Pk, true)] });
        eq.disable();
        assert!(!eq.is_enabled());
        assert_eq!(eq.filter_count(), 0);
        assert_eq!(eq.preamp_gain(), 1.0);
    }
}
