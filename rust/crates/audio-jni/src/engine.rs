//! Thread-safe engines behind the JNI handles.
//!
//! Kotlin writes parameters on one thread and ExoPlayer feeds audio on another, so each engine
//! guards its DSP state with a mutex. Audio bytes are decoded with `from_ne_bytes`: the Kotlin
//! side uses native-order buffers, and decoding by bytes avoids unaligned reads.

use audio_dsp::biquad::FilterType;
use audio_dsp::eq::{EqBand, EqProfile, Equalizer};
use audio_dsp::reverb::Reverb;
use std::sync::{Mutex, MutexGuard};

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[derive(Default)]
struct Scratch {
    f32_in: Vec<f32>,
    f32_out: Vec<f32>,
    i16_in: Vec<i16>,
    i16_out: Vec<i16>,
}

pub struct ReverbEngine {
    state: Mutex<(Reverb, Scratch)>,
}

impl Default for ReverbEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl ReverbEngine {
    pub fn new() -> Self {
        Self { state: Mutex::new((Reverb::new(), Scratch::default())) }
    }

    pub fn set_parameters(&self, room: f32, damping: f32, wet_level: f32) {
        lock(&self.state).0.set_parameters(room, damping, wet_level);
    }

    pub fn set_enabled(&self, enabled: bool) {
        lock(&self.state).0.set_enabled(enabled);
    }

    pub fn configure(&self, sample_rate: i32, channel_count: i32) -> bool {
        let Ok(channels) = usize::try_from(channel_count) else { return false };
        lock(&self.state).0.configure(sample_rate, channels).is_ok()
    }

    pub fn clear_state(&self) {
        lock(&self.state).0.clear_state();
    }

    /// Returns the number of bytes written to `output`.
    pub fn process(&self, input: &[u8], output: &mut [u8], is_float: bool) -> usize {
        let mut guard = lock(&self.state);
        let (reverb, scratch) = &mut *guard;
        if is_float {
            let n = input.len().min(output.len()) / 4;
            scratch.f32_in.clear();
            scratch.f32_in.extend(input.chunks_exact(4).take(n).map(|c| f32::from_ne_bytes([c[0], c[1], c[2], c[3]])));
            scratch.f32_out.clear();
            scratch.f32_out.resize(n, 0.0);
            let written = reverb.process_f32(&scratch.f32_in, &mut scratch.f32_out);
            for (dst, v) in output.chunks_exact_mut(4).zip(&scratch.f32_out[..written]) {
                dst.copy_from_slice(&v.to_ne_bytes());
            }
            written * 4
        } else {
            let n = input.len().min(output.len()) / 2;
            scratch.i16_in.clear();
            scratch.i16_in.extend(input.chunks_exact(2).take(n).map(|c| i16::from_ne_bytes([c[0], c[1]])));
            scratch.i16_out.clear();
            scratch.i16_out.resize(n, 0);
            let written = reverb.process_i16(&scratch.i16_in, &mut scratch.i16_out);
            for (dst, v) in output.chunks_exact_mut(2).zip(&scratch.i16_out[..written]) {
                dst.copy_from_slice(&v.to_ne_bytes());
            }
            written * 2
        }
    }
}

/// Maps the Kotlin `FilterType.ordinal` (PK, LSC, HSC, LPQ, HPQ). Unknown ordinals use the
/// peaking formula, like the `else` branch in `BiquadFilter.kt`.
pub fn filter_type_from_ordinal(ordinal: i32) -> FilterType {
    match ordinal {
        1 => FilterType::Lsc,
        2 => FilterType::Hsc,
        3 => FilterType::Lpq,
        4 => FilterType::Hpq,
        _ => FilterType::Pk,
    }
}

#[derive(Default)]
struct EqState {
    eq: Option<Equalizer>,
    in_buf: Vec<i16>,
    out_buf: Vec<i16>,
}

#[derive(Default)]
pub struct EqEngine {
    state: Mutex<EqState>,
}

impl EqEngine {
    pub fn new() -> Self {
        Self::default()
    }

    /// Builds the filter chain for the stream format. Returns the number of surviving filters,
    /// or `None` when the format is invalid (the previous state is kept).
    pub fn apply_profile(&self, sample_rate: i32, channel_count: i32, profile: &EqProfile) -> Option<usize> {
        let channels = usize::try_from(channel_count).ok()?;
        let mut eq = Equalizer::new(sample_rate, channels).ok()?;
        eq.apply_profile(profile);
        let count = eq.filter_count();
        lock(&self.state).eq = Some(eq);
        Some(count)
    }

    pub fn disable(&self) {
        lock(&self.state).eq = None;
    }

    pub fn reset_state(&self) {
        if let Some(eq) = lock(&self.state).eq.as_mut() {
            eq.reset_state();
        }
    }

    /// Returns the number of bytes written to `output`. Without a profile the input is copied.
    pub fn process(&self, input: &[u8], output: &mut [u8]) -> usize {
        let mut guard = lock(&self.state);
        let state = &mut *guard;
        let Some(eq) = state.eq.as_mut() else {
            let n = input.len().min(output.len());
            output[..n].copy_from_slice(&input[..n]);
            return n;
        };
        let n = input.len().min(output.len()) / 2;
        state.in_buf.clear();
        state.in_buf.extend(input.chunks_exact(2).take(n).map(|c| i16::from_ne_bytes([c[0], c[1]])));
        state.out_buf.clear();
        state.out_buf.resize(n, 0);
        let written = eq.process_i16(&state.in_buf, &mut state.out_buf);
        for (dst, v) in output.chunks_exact_mut(2).zip(&state.out_buf[..written]) {
            dst.copy_from_slice(&v.to_ne_bytes());
        }
        written * 2
    }
}

/// Builds an [`EqProfile`] from the flat arrays passed over JNI. `params` holds
/// `frequency, gain, q` per band.
pub fn profile_from_arrays(preamp_db: f64, params: &[f64], types: &[i32], enabled: &[bool]) -> Option<EqProfile> {
    let n = types.len();
    if params.len() != n * 3 || enabled.len() != n {
        return None;
    }
    let bands = (0..n)
        .map(|i| EqBand {
            frequency: params[i * 3],
            gain: params[i * 3 + 1],
            q: params[i * 3 + 2],
            filter_type: filter_type_from_ordinal(types[i]),
            enabled: enabled[i],
        })
        .collect();
    Some(EqProfile { preamp_db, bands })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn f32_bytes(v: &[f32]) -> Vec<u8> {
        v.iter().flat_map(|x| x.to_ne_bytes()).collect()
    }

    fn i16_bytes(v: &[i16]) -> Vec<u8> {
        v.iter().flat_map(|x| x.to_ne_bytes()).collect()
    }

    #[test]
    fn reverb_bytes_match_direct_dsp_float() {
        let samples: Vec<f32> = (0..2000).map(|i| (i as f32 * 0.02).sin() * 0.6).collect();
        let engine = ReverbEngine::new();
        engine.set_parameters(0.6, 0.4, 0.3);
        engine.set_enabled(true);
        let input = f32_bytes(&samples);
        let mut out = vec![0u8; input.len()];
        assert_eq!(engine.process(&input, &mut out, true), input.len());

        let mut direct = Reverb::new();
        direct.set_parameters(0.6, 0.4, 0.3);
        direct.set_enabled(true);
        let mut expected = vec![0.0f32; samples.len()];
        direct.process_f32(&samples, &mut expected);
        assert_eq!(out, f32_bytes(&expected));
    }

    #[test]
    fn reverb_bytes_match_direct_dsp_s16() {
        let samples: Vec<i16> = (0..2000).map(|i| ((i as f32 * 0.02).sin() * 9000.0) as i16).collect();
        let engine = ReverbEngine::new();
        engine.set_enabled(true);
        let input = i16_bytes(&samples);
        let mut out = vec![0u8; input.len()];
        assert_eq!(engine.process(&input, &mut out, false), input.len());

        let mut direct = Reverb::new();
        direct.set_enabled(true);
        let mut expected = vec![0i16; samples.len()];
        direct.process_i16(&samples, &mut expected);
        assert_eq!(out, i16_bytes(&expected));
    }

    #[test]
    fn reverb_engine_is_passthrough_when_off_and_rejects_bad_config() {
        let engine = ReverbEngine::new();
        let input = f32_bytes(&[0.1, -0.2, 0.3, -0.4]);
        let mut out = vec![0u8; input.len()];
        engine.process(&input, &mut out, true);
        assert_eq!(input, out);
        assert!(!engine.configure(0, 2));
        assert!(!engine.configure(44100, -1));
        assert!(engine.configure(48000, 2));
    }

    #[test]
    fn eq_engine_matches_direct_dsp_and_passes_through_without_profile() {
        let samples: Vec<i16> = (0..1000).map(|i| ((i as f32 * 0.05).sin() * 8000.0) as i16).collect();
        let input = i16_bytes(&samples);
        let engine = EqEngine::new();
        let mut out = vec![0u8; input.len()];
        engine.process(&input, &mut out);
        assert_eq!(input, out);

        let profile = profile_from_arrays(-3.0, &[1000.0, 6.0, 1.41, 100.0, 4.0, 0.7], &[0, 1], &[true, true]).unwrap();
        assert_eq!(engine.apply_profile(44100, 2, &profile), Some(2));
        engine.process(&input, &mut out);

        let mut direct = Equalizer::new(44100, 2).unwrap();
        direct.apply_profile(&profile);
        let mut expected = vec![0i16; samples.len()];
        direct.process_i16(&samples, &mut expected);
        assert_eq!(out, i16_bytes(&expected));

        engine.disable();
        engine.process(&input, &mut out);
        assert_eq!(input, out);
    }

    #[test]
    fn profile_arrays_with_mismatched_lengths_are_rejected() {
        assert!(profile_from_arrays(0.0, &[1.0, 2.0], &[0], &[true]).is_none());
        assert!(profile_from_arrays(0.0, &[1.0, 2.0, 3.0], &[0], &[true, false]).is_none());
    }

    #[test]
    fn unknown_filter_ordinal_uses_peaking() {
        assert_eq!(filter_type_from_ordinal(99), FilterType::Pk);
        assert_eq!(filter_type_from_ordinal(2), FilterType::Hsc);
    }

    #[test]
    fn invalid_eq_format_keeps_previous_state() {
        let engine = EqEngine::new();
        let profile = profile_from_arrays(0.0, &[1000.0, 3.0, 1.41], &[0], &[true]).unwrap();
        assert_eq!(engine.apply_profile(0, 2, &profile), None);
        assert_eq!(engine.apply_profile(44100, 0, &profile), None);
    }
}
