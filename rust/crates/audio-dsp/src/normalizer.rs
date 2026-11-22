//! Gain stage of `VolumeNormalizationAudioProcessor.kt`.
//!
//! Kotlin reads and writes PCM with an explicit little-endian order, so this module does too.
//! Samples are scaled in `f64` (`f32` for float PCM), clamped to the sample range and truncated
//! toward zero, exactly as the Kotlin `coerceIn(...).toInt()` chain does.

/// PCM layouts handled by the Kotlin processor (`C.ENCODING_PCM_*`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SampleFormat {
    S16,
    S24,
    S32,
    F32,
}

impl SampleFormat {
    /// Codes used by `NativeAudio.kt`: 0 = 16-bit, 1 = 24-bit, 2 = 32-bit, 3 = float. These are
    /// this bridge's own codes, not Media3 `C.ENCODING_*` values.
    pub fn from_code(code: i32) -> Option<Self> {
        match code {
            0 => Some(Self::S16),
            1 => Some(Self::S24),
            2 => Some(Self::S32),
            3 => Some(Self::F32),
            _ => None,
        }
    }

    pub fn bytes_per_sample(self) -> usize {
        match self {
            Self::S16 => 2,
            Self::S24 => 3,
            Self::S32 | Self::F32 => 4,
        }
    }
}

/// `10.0.pow(gainMb / 2000.0)` from `setTargetGain`.
pub fn gain_mb_to_linear(gain_mb: i32) -> f64 {
    10f64.powf(f64::from(gain_mb) / 2000.0)
}

/// Processes whole samples of `input` into `output` and returns the bytes written. A trailing
/// partial sample is not consumed. With `linear_gain == None` the samples are copied unchanged.
pub fn process(input: &[u8], output: &mut [u8], format: SampleFormat, linear_gain: Option<f64>) -> usize {
    let bps = format.bytes_per_sample();
    let samples = input.len().min(output.len()) / bps;
    let src = input.chunks_exact(bps).take(samples);
    let dst = output.chunks_exact_mut(bps);
    let Some(gain) = linear_gain else {
        for (s, d) in src.zip(dst) {
            d.copy_from_slice(s);
        }
        return samples * bps;
    };
    match format {
        SampleFormat::S16 => {
            for (s, d) in src.zip(dst) {
                let v = f64::from(i16::from_le_bytes([s[0], s[1]]));
                let p = (v * gain).clamp(-32768.0, 32767.0) as i32 as i16;
                d.copy_from_slice(&p.to_le_bytes());
            }
        }
        SampleFormat::S24 => {
            for (s, d) in src.zip(dst) {
                let v = (i32::from(s[2] as i8) << 16) | (i32::from(s[1]) << 8) | i32::from(s[0]);
                let p = (f64::from(v) * gain).clamp(-8_388_608.0, 8_388_607.0) as i32;
                d.copy_from_slice(&[(p & 0xFF) as u8, ((p >> 8) & 0xFF) as u8, ((p >> 16) & 0xFF) as u8]);
            }
        }
        SampleFormat::S32 => {
            for (s, d) in src.zip(dst) {
                let v = f64::from(i32::from_le_bytes([s[0], s[1], s[2], s[3]]));
                let p = (v * gain).clamp(-2_147_483_648.0, 2_147_483_647.0) as i32;
                d.copy_from_slice(&p.to_le_bytes());
            }
        }
        SampleFormat::F32 => {
            let g = gain as f32;
            for (s, d) in src.zip(dst) {
                let v = f32::from_le_bytes([s[0], s[1], s[2], s[3]]) * g;
                // Same comparisons as Kotlin `coerceIn`: NaN passes through unchanged.
                let p = if v < -1.0 {
                    -1.0
                } else if v > 1.0 {
                    1.0
                } else {
                    v
                };
                d.copy_from_slice(&p.to_le_bytes());
            }
        }
    }
    samples * bps
}

#[cfg(test)]
mod tests {
    use super::*;

    fn s16(v: &[i16]) -> Vec<u8> {
        v.iter().flat_map(|x| x.to_le_bytes()).collect()
    }
    fn s32(v: &[i32]) -> Vec<u8> {
        v.iter().flat_map(|x| x.to_le_bytes()).collect()
    }
    fn f32b(v: &[f32]) -> Vec<u8> {
        v.iter().flat_map(|x| x.to_le_bytes()).collect()
    }
    fn s24(v: &[i32]) -> Vec<u8> {
        v.iter().flat_map(|x| [(x & 0xFF) as u8, ((x >> 8) & 0xFF) as u8, ((x >> 16) & 0xFF) as u8]).collect()
    }
    fn run(input: &[u8], fmt: SampleFormat, gain: Option<f64>) -> Vec<u8> {
        let mut out = vec![0u8; input.len()];
        let n = process(input, &mut out, fmt, gain);
        out.truncate(n);
        out
    }

    #[test]
    fn gain_formula_matches_kotlin() {
        assert_eq!(gain_mb_to_linear(0), 1.0);
        assert!((gain_mb_to_linear(2000) - 10.0).abs() < 1e-12);
        assert!((gain_mb_to_linear(-2000) - 0.1).abs() < 1e-12);
        assert!((gain_mb_to_linear(600) - 10f64.powf(0.3)).abs() < 1e-12);
    }

    #[test]
    fn format_codes() {
        assert_eq!(SampleFormat::from_code(0), Some(SampleFormat::S16));
        assert_eq!(SampleFormat::from_code(3), Some(SampleFormat::F32));
        assert_eq!(SampleFormat::from_code(4), None);
        assert_eq!(SampleFormat::from_code(-1), None);
    }

    #[test]
    fn passthrough_is_byte_exact_for_every_format() {
        let a = s16(&[0, 1, -1, 32767, -32768]);
        assert_eq!(run(&a, SampleFormat::S16, None), a);
        let b = s24(&[0, 1, -1, 8_388_607, -8_388_608]);
        assert_eq!(run(&b, SampleFormat::S24, None), b);
        let c = s32(&[0, 1, -1, i32::MAX, i32::MIN]);
        assert_eq!(run(&c, SampleFormat::S32, None), c);
        let d = f32b(&[0.0, 0.5, -0.5, 1.5, -2.0]);
        assert_eq!(run(&d, SampleFormat::F32, None), d);
    }

    #[test]
    fn s16_scales_truncates_toward_zero_and_clamps() {
        let out = run(&s16(&[100, -3, 3, 20000, -20000]), SampleFormat::S16, Some(0.5));
        assert_eq!(out, s16(&[50, -1, 1, 10000, -10000]));
        let out = run(&s16(&[20000, -20000, 32767, -32768]), SampleFormat::S16, Some(2.0));
        assert_eq!(out, s16(&[32767, -32768, 32767, -32768]));
    }

    #[test]
    fn s24_sign_extends_scales_and_clamps() {
        let out = run(&s24(&[-1, -1000, 1000, 6_000_000]), SampleFormat::S24, Some(0.5));
        assert_eq!(out, s24(&[0, -500, 500, 3_000_000]));
        let out = run(&s24(&[6_000_000, -6_000_000]), SampleFormat::S24, Some(2.0));
        assert_eq!(out, s24(&[8_388_607, -8_388_608]));
    }

    #[test]
    fn s32_scales_and_clamps() {
        let out = run(&s32(&[1_000_000_000, -1_000_000_000, 7]), SampleFormat::S32, Some(4.0));
        assert_eq!(out, s32(&[i32::MAX, i32::MIN, 28]));
    }

    #[test]
    fn float_scales_clamps_and_keeps_nan() {
        let out = run(&f32b(&[0.25, -0.25, 0.9, -0.9]), SampleFormat::F32, Some(2.0));
        assert_eq!(out, f32b(&[0.5, -0.5, 1.0, -1.0]));
        let nan = run(&f32b(&[f32::NAN]), SampleFormat::F32, Some(2.0));
        assert!(f32::from_le_bytes([nan[0], nan[1], nan[2], nan[3]]).is_nan());
    }

    #[test]
    fn partial_trailing_sample_is_not_consumed() {
        let mut input = s16(&[10, 20]);
        input.push(0x7F);
        let mut out = vec![0u8; input.len()];
        assert_eq!(process(&input, &mut out, SampleFormat::S16, Some(1.0)), 4);
        let mut input24 = s24(&[1, 2]);
        input24.extend_from_slice(&[9, 9]);
        let mut out24 = vec![0u8; input24.len()];
        assert_eq!(process(&input24, &mut out24, SampleFormat::S24, None), 6);
    }

    #[test]
    fn short_output_limits_samples_written() {
        let input = s16(&[1, 2, 3, 4]);
        let mut out = vec![0u8; 5];
        assert_eq!(process(&input, &mut out, SampleFormat::S16, None), 4);
    }
}
