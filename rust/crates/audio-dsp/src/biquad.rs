//! Biquad filter, RBJ Audio EQ Cookbook. Port of `BiquadFilter.kt`.
//!
//! Peaking (PK), low shelf (LSC) and high shelf (HSC) are implemented. `Lpq` and `Hpq` exist in
//! the Kotlin `FilterType` enum but `BiquadFilter.kt` routes them to the peaking formula
//! (its `else` branch); that behaviour is kept.

use std::f64::consts::PI;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FilterType {
    Pk,
    Lsc,
    Hsc,
    Lpq,
    Hpq,
}

/// Kotlin default for `q` in `BiquadFilter` and `ParametricEQBand`.
pub const DEFAULT_Q: f64 = 1.41;

#[derive(Debug, Clone)]
pub struct BiquadFilter {
    b0: f64,
    b1: f64,
    b2: f64,
    a1: f64,
    a2: f64,
    x1_l: f64,
    x2_l: f64,
    y1_l: f64,
    y2_l: f64,
    x1_r: f64,
    x2_r: f64,
    y1_r: f64,
    y2_r: f64,
}

impl BiquadFilter {
    pub fn new(sample_rate: i32, frequency: f64, gain: f64, q: f64, filter_type: FilterType) -> Self {
        let sr = f64::from(sample_rate);
        let (b0, b1, b2, a1, a2) = match filter_type {
            FilterType::Lsc => low_shelf(sr, frequency, gain),
            FilterType::Hsc => high_shelf(sr, frequency, gain),
            FilterType::Pk | FilterType::Lpq | FilterType::Hpq => peaking(sr, frequency, gain, q),
        };
        Self {
            b0,
            b1,
            b2,
            a1,
            a2,
            x1_l: 0.0,
            x2_l: 0.0,
            y1_l: 0.0,
            y2_l: 0.0,
            x1_r: 0.0,
            x2_r: 0.0,
            y1_r: 0.0,
            y2_r: 0.0,
        }
    }

    /// Normalised coefficients `(b0, b1, b2, a1, a2)`, with `a0 == 1`.
    pub fn coefficients(&self) -> (f64, f64, f64, f64, f64) {
        (self.b0, self.b1, self.b2, self.a1, self.a2)
    }

    /// Mono path. Uses the left-channel state, like the Kotlin code.
    pub fn process_sample(&mut self, input: f64) -> f64 {
        let output = self.b0 * input + self.b1 * self.x1_l + self.b2 * self.x2_l
            - self.a1 * self.y1_l
            - self.a2 * self.y2_l;
        self.x2_l = self.x1_l;
        self.x1_l = input;
        self.y2_l = self.y1_l;
        self.y1_l = output;
        output
    }

    pub fn process_stereo(&mut self, input_left: f64, input_right: f64) -> (f64, f64) {
        let out_l = self.b0 * input_left + self.b1 * self.x1_l + self.b2 * self.x2_l
            - self.a1 * self.y1_l
            - self.a2 * self.y2_l;
        self.x2_l = self.x1_l;
        self.x1_l = input_left;
        self.y2_l = self.y1_l;
        self.y1_l = out_l;

        let out_r = self.b0 * input_right + self.b1 * self.x1_r + self.b2 * self.x2_r
            - self.a1 * self.y1_r
            - self.a2 * self.y2_r;
        self.x2_r = self.x1_r;
        self.x1_r = input_right;
        self.y2_r = self.y1_r;
        self.y1_r = out_r;

        (out_l, out_r)
    }

    pub fn reset(&mut self) {
        self.x1_l = 0.0;
        self.x2_l = 0.0;
        self.y1_l = 0.0;
        self.y2_l = 0.0;
        self.x1_r = 0.0;
        self.x2_r = 0.0;
        self.y1_r = 0.0;
        self.y2_r = 0.0;
    }
}

fn peaking(sample_rate: f64, frequency: f64, gain: f64, q: f64) -> (f64, f64, f64, f64, f64) {
    let a = 10.0_f64.powf(gain / 40.0);
    let omega = 2.0 * PI * frequency / sample_rate;
    let sin_omega = omega.sin();
    let cos_omega = omega.cos();
    let alpha = sin_omega / (2.0 * q);

    let b0 = 1.0 + alpha * a;
    let b1 = -2.0 * cos_omega;
    let b2 = 1.0 - alpha * a;
    let a0 = 1.0 + alpha / a;
    let a1 = -2.0 * cos_omega;
    let a2 = 1.0 - alpha / a;

    (b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
}

/// Shelf slope parameter. Fixed at 1.0 in `BiquadFilter.kt`.
const SHELF_SLOPE: f64 = 1.0;

fn low_shelf(sample_rate: f64, frequency: f64, gain: f64) -> (f64, f64, f64, f64, f64) {
    let a = 10.0_f64.powf(gain / 20.0).sqrt();
    let omega = 2.0 * PI * frequency / sample_rate;
    let sin_omega = omega.sin();
    let cos_omega = omega.cos();
    let s = SHELF_SLOPE;
    let alpha = sin_omega / 2.0 * ((a + 1.0 / a) * (1.0 / s - 1.0) + 2.0).sqrt();
    let sqrt_a = a.sqrt();

    let a_plus_one = a + 1.0;
    let a_minus_one = a - 1.0;
    let two_sqrt_a_alpha = 2.0 * sqrt_a * alpha;

    let b0 = a * (a_plus_one - a_minus_one * cos_omega + two_sqrt_a_alpha);
    let b1 = 2.0 * a * (a_minus_one - a_plus_one * cos_omega);
    let b2 = a * (a_plus_one - a_minus_one * cos_omega - two_sqrt_a_alpha);
    let a0 = a_plus_one + a_minus_one * cos_omega + two_sqrt_a_alpha;
    let a1 = -2.0 * (a_minus_one + a_plus_one * cos_omega);
    let a2 = a_plus_one + a_minus_one * cos_omega - two_sqrt_a_alpha;

    (b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
}

fn high_shelf(sample_rate: f64, frequency: f64, gain: f64) -> (f64, f64, f64, f64, f64) {
    let a = 10.0_f64.powf(gain / 20.0).sqrt();
    let omega = 2.0 * PI * frequency / sample_rate;
    let sin_omega = omega.sin();
    let cos_omega = omega.cos();
    let s = SHELF_SLOPE;
    let alpha = sin_omega / 2.0 * ((a + 1.0 / a) * (1.0 / s - 1.0) + 2.0).sqrt();
    let sqrt_a = a.sqrt();

    let a_plus_one = a + 1.0;
    let a_minus_one = a - 1.0;
    let two_sqrt_a_alpha = 2.0 * sqrt_a * alpha;

    let b0 = a * (a_plus_one + a_minus_one * cos_omega + two_sqrt_a_alpha);
    let b1 = -2.0 * a * (a_minus_one + a_plus_one * cos_omega);
    let b2 = a * (a_plus_one + a_minus_one * cos_omega - two_sqrt_a_alpha);
    let a0 = a_plus_one - a_minus_one * cos_omega + two_sqrt_a_alpha;
    let a1 = 2.0 * (a_minus_one - a_plus_one * cos_omega);
    let a2 = a_plus_one - a_minus_one * cos_omega - two_sqrt_a_alpha;

    (b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Magnitude of H(e^jw) in dB from normalised coefficients.
    fn magnitude_db(f: &BiquadFilter, sample_rate: f64, hz: f64) -> f64 {
        let (b0, b1, b2, a1, a2) = f.coefficients();
        let w = 2.0 * PI * hz / sample_rate;
        let (c1, s1, c2, s2) = (w.cos(), w.sin(), (2.0 * w).cos(), (2.0 * w).sin());
        let num_re = b0 + b1 * c1 + b2 * c2;
        let num_im = -(b1 * s1 + b2 * s2);
        let den_re = 1.0 + a1 * c1 + a2 * c2;
        let den_im = -(a1 * s1 + a2 * s2);
        let num = (num_re * num_re + num_im * num_im).sqrt();
        let den = (den_re * den_re + den_im * den_im).sqrt();
        20.0 * (num / den).log10()
    }

    #[test]
    fn peaking_hits_requested_gain_at_center() {
        for gain in [-12.0, -3.5, 0.0, 4.2, 9.0] {
            let f = BiquadFilter::new(44100, 1000.0, gain, 1.41, FilterType::Pk);
            let db = magnitude_db(&f, 44100.0, 1000.0);
            assert!((db - gain).abs() < 1e-9, "gain {gain}: got {db}");
        }
    }

    #[test]
    fn peaking_zero_gain_is_identity() {
        let mut f = BiquadFilter::new(48000, 250.0, 0.0, 0.7, FilterType::Pk);
        for i in 0..64 {
            let x = (f64::from(i) * 0.37).sin();
            assert!((f.process_sample(x) - x).abs() < 1e-12);
        }
    }

    #[test]
    fn low_shelf_gain_at_dc_and_flat_at_nyquist() {
        let f = BiquadFilter::new(44100, 105.0, 8.8, 0.7, FilterType::Lsc);
        assert!((magnitude_db(&f, 44100.0, 0.001) - 8.8).abs() < 1e-3);
        assert!(magnitude_db(&f, 44100.0, 22000.0).abs() < 1e-3);
    }

    #[test]
    fn high_shelf_gain_at_nyquist_and_flat_at_dc() {
        let f = BiquadFilter::new(44100, 8000.0, -5.0, 0.7, FilterType::Hsc);
        assert!(magnitude_db(&f, 44100.0, 0.001).abs() < 1e-3);
        assert!((magnitude_db(&f, 44100.0, 22000.0) + 5.0).abs() < 0.05);
    }

    #[test]
    fn lpq_and_hpq_use_the_peaking_formula_like_kotlin() {
        let pk = BiquadFilter::new(44100, 500.0, 3.0, 1.0, FilterType::Pk).coefficients();
        assert_eq!(pk, BiquadFilter::new(44100, 500.0, 3.0, 1.0, FilterType::Lpq).coefficients());
        assert_eq!(pk, BiquadFilter::new(44100, 500.0, 3.0, 1.0, FilterType::Hpq).coefficients());
    }

    #[test]
    fn stereo_channels_are_independent_and_mono_matches_left() {
        let mut stereo = BiquadFilter::new(44100, 1000.0, 6.0, 1.41, FilterType::Pk);
        let mut mono = stereo.clone();
        for i in 0..128 {
            let l = (f64::from(i) * 0.11).sin();
            let r = (f64::from(i) * 0.23).cos();
            let (ol, _) = stereo.process_stereo(l, r);
            assert_eq!(ol, mono.process_sample(l));
        }
    }

    #[test]
    fn reset_clears_history() {
        let mut f = BiquadFilter::new(44100, 1000.0, 6.0, 1.41, FilterType::Pk);
        let first = f.process_sample(1.0);
        f.process_sample(0.5);
        f.reset();
        assert_eq!(f.process_sample(1.0), first);
    }
}
