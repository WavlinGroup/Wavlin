//! Freeverb reverb. Port of the DSP in `ReverbAudioProcessor.kt`.
//!
//! Constants and arithmetic order are carried over unchanged from the Kotlin code, in `f32`
//! like the original. The ExoPlayer shell (`isActive`, buffers, flush/reset lifecycle) stays in
//! Kotlin. As in Kotlin, [`Reverb::clear_state`] clears audio state only and keeps settings.

use crate::ConfigError;

/// Comb-filter delay lengths calibrated for 44 100 Hz.
pub const COMB_DELAYS: [i32; 8] = [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617];
pub const ALLPASS_DELAYS: [i32; 4] = [556, 441, 341, 225];
/// Stereo width in samples.
pub const SPREAD: i32 = 23;
/// Input gain before the comb bank.
pub const GAIN: f32 = 0.015;
/// Damping 0..1 maps to 0..0.4.
pub const DAMP_SCALE: f32 = 0.4;
/// Wet 0..1 maps to 0..3.
pub const WET_SCALE: f32 = 3.0;

const REFERENCE_SAMPLE_RATE: i32 = 44100;

struct Comb {
    buf: Vec<f32>,
    idx: usize,
    store: f32,
    feedback: f32,
    damp1: f32,
    damp2: f32,
}

impl Comb {
    fn new(size: i32) -> Self {
        Self {
            buf: vec![0.0; size.max(1) as usize],
            idx: 0,
            store: 0.0,
            feedback: 0.84,
            damp1: 0.2,
            damp2: 0.8,
        }
    }

    fn process(&mut self, input: f32) -> f32 {
        let out = self.buf[self.idx];
        self.store = out * self.damp2 + self.store * self.damp1;
        self.buf[self.idx] = input + self.store * self.feedback;
        self.idx += 1;
        if self.idx >= self.buf.len() {
            self.idx = 0;
        }
        out
    }

    fn reset(&mut self) {
        self.buf.fill(0.0);
        self.store = 0.0;
        self.idx = 0;
    }
}

struct Allpass {
    buf: Vec<f32>,
    idx: usize,
}

impl Allpass {
    fn new(size: i32) -> Self {
        Self { buf: vec![0.0; size.max(1) as usize], idx: 0 }
    }

    fn process(&mut self, input: f32) -> f32 {
        let out = self.buf[self.idx];
        self.buf[self.idx] = input + out * 0.5;
        self.idx += 1;
        if self.idx >= self.buf.len() {
            self.idx = 0;
        }
        out - input
    }

    fn reset(&mut self) {
        self.buf.fill(0.0);
        self.idx = 0;
    }
}

pub struct Reverb {
    room_feedback: f32,
    damp1: f32,
    damp2: f32,
    wet: f32,
    active: bool,
    comb_l: Vec<Comb>,
    comb_r: Vec<Comb>,
    ap_l: Vec<Allpass>,
    ap_r: Vec<Allpass>,
    sample_rate: i32,
    channel_count: usize,
}

impl Default for Reverb {
    fn default() -> Self {
        Self::new()
    }
}

impl Reverb {
    /// Kotlin initial state: 44 100 Hz, stereo, off, feedback 0.84, damp 0.2/0.8, wet 1.0.
    pub fn new() -> Self {
        Self {
            room_feedback: 0.84,
            damp1: 0.2,
            damp2: 0.8,
            wet: 1.0,
            active: false,
            comb_l: COMB_DELAYS.iter().map(|&d| Comb::new(d)).collect(),
            comb_r: COMB_DELAYS.iter().map(|&d| Comb::new(d + SPREAD)).collect(),
            ap_l: ALLPASS_DELAYS.iter().map(|&d| Allpass::new(d)).collect(),
            ap_r: ALLPASS_DELAYS.iter().map(|&d| Allpass::new(d + SPREAD)).collect(),
            sample_rate: REFERENCE_SAMPLE_RATE,
            channel_count: 2,
        }
    }

    /// `room`, `damping` and `wet_level` are 0..1 and clamped like the Kotlin `coerceIn`.
    pub fn set_parameters(&mut self, room: f32, damping: f32, wet_level: f32) {
        self.room_feedback = coerce(room, 0.0, 1.0) * 0.28 + 0.70;
        self.damp1 = coerce(damping, 0.0, 1.0) * DAMP_SCALE;
        self.damp2 = 1.0 - self.damp1;
        self.wet = coerce(wet_level, 0.0, 1.0) * WET_SCALE;
        self.push_params_to_combs();
    }

    pub fn set_enabled(&mut self, enabled: bool) {
        self.active = enabled;
    }

    pub fn is_enabled(&self) -> bool {
        self.active
    }

    /// Mirrors `configure`: the channel count always updates; delay lines are rebuilt (and
    /// cleared) only when the sample rate differs from the current one.
    pub fn configure(&mut self, sample_rate: i32, channel_count: usize) -> Result<(), ConfigError> {
        if sample_rate <= 0 {
            return Err(ConfigError::InvalidSampleRate(sample_rate));
        }
        if channel_count == 0 {
            return Err(ConfigError::InvalidChannelCount(channel_count));
        }
        self.channel_count = channel_count;
        if sample_rate != self.sample_rate {
            self.sample_rate = sample_rate;
            self.rebuild_filters();
        }
        Ok(())
    }

    /// Clears delay lines only. Settings are kept (Kotlin `flush`/`reset` contract).
    pub fn clear_state(&mut self) {
        for c in self.comb_l.iter_mut().chain(self.comb_r.iter_mut()) {
            c.reset();
        }
        for a in self.ap_l.iter_mut().chain(self.ap_r.iter_mut()) {
            a.reset();
        }
    }

    /// Delay-line lengths `(comb_l, comb_r, allpass_l, allpass_r)` for inspection and tests.
    pub fn delay_lengths(&self) -> (Vec<usize>, Vec<usize>, Vec<usize>, Vec<usize>) {
        (
            self.comb_l.iter().map(|c| c.buf.len()).collect(),
            self.comb_r.iter().map(|c| c.buf.len()).collect(),
            self.ap_l.iter().map(|a| a.buf.len()).collect(),
            self.ap_r.iter().map(|a| a.buf.len()).collect(),
        )
    }

    /// Interleaved 32-bit float PCM. Returns the number of samples written.
    pub fn process_f32(&mut self, input: &[f32], output: &mut [f32]) -> usize {
        let len = input.len().min(output.len());
        if !self.active {
            output[..len].copy_from_slice(&input[..len]);
            return len;
        }
        let ch = self.channel_count;
        let written = len / ch * ch;

        if ch >= 2 {
            for (src, dst) in input[..written].chunks_exact(ch).zip(output[..written].chunks_exact_mut(ch)) {
                let (in_l, in_r) = (src[0], src[1]);
                let (rev_l, rev_r) = self.reverb_stereo(in_l, in_r);
                dst[0] = coerce(in_l + rev_l * self.wet, -1.0, 1.0);
                dst[1] = coerce(in_r + rev_r * self.wet, -1.0, 1.0);
                dst[2..].copy_from_slice(&src[2..]);
            }
        } else {
            for (src, dst) in input[..written].iter().zip(output[..written].iter_mut()) {
                let rev = self.reverb_mono(*src);
                *dst = coerce(*src + rev * self.wet, -1.0, 1.0);
            }
        }
        written
    }

    /// Interleaved signed 16-bit PCM. Returns the number of samples written.
    pub fn process_i16(&mut self, input: &[i16], output: &mut [i16]) -> usize {
        let len = input.len().min(output.len());
        if !self.active {
            output[..len].copy_from_slice(&input[..len]);
            return len;
        }
        let ch = self.channel_count;
        let written = len / ch * ch;

        if ch >= 2 {
            for (src, dst) in input[..written].chunks_exact(ch).zip(output[..written].chunks_exact_mut(ch)) {
                let in_l = f32::from(src[0]) / 32768.0;
                let in_r = f32::from(src[1]) / 32768.0;
                let (rev_l, rev_r) = self.reverb_stereo(in_l, in_r);
                dst[0] = to_i16(in_l + rev_l * self.wet);
                dst[1] = to_i16(in_r + rev_r * self.wet);
                dst[2..].copy_from_slice(&src[2..]);
            }
        } else {
            for (src, dst) in input[..written].iter().zip(output[..written].iter_mut()) {
                let in_s = f32::from(*src) / 32768.0;
                let rev = self.reverb_mono(in_s);
                *dst = to_i16(in_s + rev * self.wet);
            }
        }
        written
    }

    fn reverb_stereo(&mut self, in_l: f32, in_r: f32) -> (f32, f32) {
        let mut rev_l = 0.0_f32;
        let mut rev_r = 0.0_f32;
        for c in &mut self.comb_l {
            rev_l += c.process(in_l * GAIN);
        }
        for c in &mut self.comb_r {
            rev_r += c.process(in_r * GAIN);
        }
        for a in &mut self.ap_l {
            rev_l = a.process(rev_l);
        }
        for a in &mut self.ap_r {
            rev_r = a.process(rev_r);
        }
        (rev_l, rev_r)
    }

    fn reverb_mono(&mut self, input: f32) -> f32 {
        let mut rev = 0.0_f32;
        for c in &mut self.comb_l {
            rev += c.process(input * GAIN);
        }
        for a in &mut self.ap_l {
            rev = a.process(rev);
        }
        rev
    }

    fn push_params_to_combs(&mut self) {
        let (feedback, damp1, damp2) = (self.room_feedback, self.damp1, self.damp2);
        for c in self.comb_l.iter_mut().chain(self.comb_r.iter_mut()) {
            c.feedback = feedback;
            c.damp1 = damp1;
            c.damp2 = damp2;
        }
    }

    fn rebuild_filters(&mut self) {
        let scale = f64::from(self.sample_rate) / f64::from(REFERENCE_SAMPLE_RATE);
        let scaled = |d: i32, min: i32| -> i32 { ((f64::from(d) * scale) as i32).max(min) };
        self.comb_l = COMB_DELAYS.iter().map(|&d| Comb::new(scaled(d, 8))).collect();
        self.comb_r = COMB_DELAYS.iter().map(|&d| Comb::new(scaled(d + SPREAD, 8))).collect();
        self.ap_l = ALLPASS_DELAYS.iter().map(|&d| Allpass::new(scaled(d, 4))).collect();
        self.ap_r = ALLPASS_DELAYS.iter().map(|&d| Allpass::new(scaled(d + SPREAD, 4))).collect();
        self.push_params_to_combs();
    }
}

/// Kotlin `coerceIn` on floats: comparisons only, so NaN passes through unchanged.
fn coerce(v: f32, lo: f32, hi: f32) -> f32 {
    if v < lo {
        lo
    } else if v > hi {
        hi
    } else {
        v
    }
}

/// `(v * 32768f).coerceIn(-32768f, 32767f).toInt().toShort()`.
fn to_i16(v: f32) -> i16 {
    (coerce(v * 32768.0, -32768.0, 32767.0) as i32) as i16
}

#[cfg(test)]
mod tests {
    use super::*;

    fn impulse_stereo(frames: usize) -> Vec<f32> {
        let mut v = vec![0.0f32; frames * 2];
        v[0] = 0.5;
        v[1] = 0.5;
        v
    }

    #[test]
    fn disabled_reverb_is_bit_exact_passthrough() {
        let mut r = Reverb::new();
        let input: Vec<f32> = (0..200).map(|i| (i as f32 * 0.07).sin()).collect();
        let mut out = vec![0.0f32; 200];
        assert_eq!(r.process_f32(&input, &mut out), 200);
        assert_eq!(input, out);

        let pcm: Vec<i16> = (0..200).map(|i| (i * 97 - 9000) as i16).collect();
        let mut pcm_out = vec![0i16; 200];
        r.process_i16(&pcm, &mut pcm_out);
        assert_eq!(pcm, pcm_out);
    }

    #[test]
    fn zero_wet_returns_the_dry_signal() {
        let mut r = Reverb::new();
        r.set_parameters(0.5, 0.5, 0.0);
        r.set_enabled(true);
        let input: Vec<f32> = (0..400).map(|i| (i as f32 * 0.05).sin() * 0.8).collect();
        let mut out = vec![0.0f32; 400];
        r.process_f32(&input, &mut out);
        assert_eq!(input, out);
    }

    #[test]
    fn impulse_tail_starts_at_the_shortest_comb_delay_per_channel() {
        let mut r = Reverb::new();
        r.set_enabled(true);
        let frames = 1300;
        let input = impulse_stereo(frames);
        let mut out = vec![0.0f32; frames * 2];
        r.process_f32(&input, &mut out);

        for f in 1..1116 {
            assert_eq!(out[f * 2], 0.0, "left frame {f}");
        }
        assert_ne!(out[1116 * 2], 0.0);
        for f in 1..(1116 + SPREAD as usize) {
            assert_eq!(out[f * 2 + 1], 0.0, "right frame {f}");
        }
        assert_ne!(out[(1116 + SPREAD as usize) * 2 + 1], 0.0);
    }

    #[test]
    fn parameter_mapping_matches_kotlin_scaling() {
        let mut r = Reverb::new();
        r.set_parameters(1.0, 1.0, 1.0);
        assert!((r.room_feedback - 0.98).abs() < 1e-6);
        assert!((r.damp1 - 0.4).abs() < 1e-7);
        assert!((r.damp2 - 0.6).abs() < 1e-7);
        assert_eq!(r.wet, 3.0);
        r.set_parameters(-5.0, 9.0, 2.0);
        assert!((r.room_feedback - 0.70).abs() < 1e-7);
        assert!((r.damp1 - 0.4).abs() < 1e-7);
        assert_eq!(r.wet, 3.0);
    }

    #[test]
    fn delay_lengths_at_44100_and_scaled_rates() {
        let mut r = Reverb::new();
        let (cl, cr, al, ar) = r.delay_lengths();
        assert_eq!(cl, [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617]);
        assert_eq!(cr, [1139, 1211, 1300, 1379, 1445, 1514, 1580, 1640]);
        assert_eq!(al, [556, 441, 341, 225]);
        assert_eq!(ar, [579, 464, 364, 248]);

        r.configure(48000, 2).unwrap();
        let (cl, _, al, _) = r.delay_lengths();
        // 1116 * 48000 / 44100 = 1214.69.. truncated; 225 * 48000 / 44100 = 244.9.. truncated
        assert_eq!(cl[0], 1214);
        assert_eq!(al[3], 244);
    }

    #[test]
    fn configure_same_rate_keeps_delay_state_and_new_rate_clears_it() {
        let mut r = Reverb::new();
        r.set_enabled(true);
        let input = impulse_stereo(1200);
        let mut out = vec![0.0f32; 2400];
        r.process_f32(&input, &mut out);

        r.configure(44100, 2).unwrap();
        let silence = vec![0.0f32; 2 * 20];
        let mut tail = vec![0.0f32; 2 * 20];
        r.process_f32(&silence, &mut tail);
        assert!(tail.iter().any(|v| *v != 0.0), "state must survive a same-rate configure");

        r.configure(48000, 2).unwrap();
        let mut tail2 = vec![0.0f32; 2 * 20];
        r.process_f32(&silence, &mut tail2);
        assert!(tail2.iter().all(|v| *v == 0.0), "rebuild must clear the delay lines");
    }

    #[test]
    fn clear_state_keeps_settings() {
        let mut r = Reverb::new();
        r.set_parameters(0.3, 0.6, 0.2);
        r.set_enabled(true);
        r.clear_state();
        assert!(r.is_enabled());
        assert!((r.room_feedback - (0.3 * 0.28 + 0.70)).abs() < 1e-7);
    }

    #[test]
    fn extra_channels_pass_through_untouched() {
        let mut r = Reverb::new();
        r.configure(44100, 6).unwrap();
        r.set_enabled(true);
        let mut input = vec![0.0f32; 6 * 8];
        for (i, v) in input.iter_mut().enumerate() {
            *v = (i as f32) * 0.001;
        }
        let mut out = vec![0.0f32; input.len()];
        r.process_f32(&input, &mut out);
        for f in 0..8 {
            assert_eq!(&out[f * 6 + 2..f * 6 + 6], &input[f * 6 + 2..f * 6 + 6]);
        }
    }

    #[test]
    fn mono_path_uses_left_bank_only() {
        let mut mono = Reverb::new();
        mono.configure(44100, 1).unwrap();
        mono.set_enabled(true);
        let mut input = vec![0.0f32; 1200];
        input[0] = 0.5;
        let mut out = vec![0.0f32; 1200];
        mono.process_f32(&input, &mut out);
        assert_eq!(out[0], 0.5);
        assert_ne!(out[1116], 0.0);
    }

    #[test]
    fn long_loud_input_stays_finite_and_in_range() {
        let mut r = Reverb::new();
        r.set_parameters(1.0, 0.0, 1.0);
        r.set_enabled(true);
        let n = 44100 * 4 * 2;
        let input: Vec<f32> = (0..n).map(|i| if (i / 2 / 100) % 2 == 0 { 0.9 } else { -0.9 }).collect();
        let mut out = vec![0.0f32; n];
        r.process_f32(&input, &mut out);
        assert!(out.iter().all(|v| v.is_finite() && (-1.0..=1.0).contains(v)));
    }

    #[test]
    fn s16_path_matches_float_path_within_quantisation() {
        let mut a = Reverb::new();
        let mut b = Reverb::new();
        a.set_enabled(true);
        b.set_enabled(true);
        let pcm: Vec<i16> = (0..4000).map(|i| ((i as f32 * 0.03).sin() * 12000.0) as i16).collect();
        let floats: Vec<f32> = pcm.iter().map(|&s| f32::from(s) / 32768.0).collect();
        let mut out_pcm = vec![0i16; pcm.len()];
        let mut out_f = vec![0.0f32; pcm.len()];
        a.process_i16(&pcm, &mut out_pcm);
        b.process_f32(&floats, &mut out_f);
        for (p, f) in out_pcm.iter().zip(out_f.iter()) {
            assert!((f32::from(*p) / 32768.0 - f).abs() < 2.0 / 32768.0);
        }
    }

    #[test]
    fn configure_rejects_bad_input() {
        let mut r = Reverb::new();
        assert_eq!(r.configure(0, 2), Err(ConfigError::InvalidSampleRate(0)));
        assert_eq!(r.configure(44100, 0), Err(ConfigError::InvalidChannelCount(0)));
    }
}
