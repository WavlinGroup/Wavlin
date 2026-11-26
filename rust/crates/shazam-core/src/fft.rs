//! Real-input FFT magnitudes, a port of `computeRfft` from `ShazamSignatureGenerator.kt`.
//!
//! It is the same iterative radix-2 decimation-in-time transform with the same twiddle recurrence,
//! so the results match the Kotlin code. The recurrence is only evaluated once per stage here
//! instead of once per butterfly group; each group restarts it from (1, 0), so the values are the
//! same bits.

use std::f64::consts::PI;

pub(crate) const FFT_SIZE: usize = 2048;
pub(crate) const FFT_OUTPUT_SIZE: usize = FFT_SIZE / 2 + 1;

pub(crate) struct Rfft {
    swaps: Vec<(u16, u16)>,
    stages: Vec<Stage>,
    re: Vec<f64>,
    im: Vec<f64>,
}

struct Stage {
    half_len: usize,
    twiddles: Vec<(f64, f64)>,
}

impl Rfft {
    pub(crate) fn new() -> Self {
        let n = FFT_SIZE;

        let mut swaps = Vec::new();
        let mut j = 0usize;
        for i in 1..n {
            let mut bit = n >> 1;
            while j & bit != 0 {
                j ^= bit;
                bit >>= 1;
            }
            j ^= bit;
            if i < j {
                swaps.push((i as u16, j as u16));
            }
        }

        let mut stages = Vec::new();
        let mut len = 2usize;
        while len <= n {
            let half_len = len >> 1;
            let ang = -PI / half_len as f64;
            let w_base_re = ang.cos();
            let w_base_im = ang.sin();
            let mut w_re = 1.0f64;
            let mut w_im = 0.0f64;
            let mut twiddles = Vec::with_capacity(half_len);
            for _ in 0..half_len {
                twiddles.push((w_re, w_im));
                let new_w_re = w_re * w_base_re - w_im * w_base_im;
                w_im = w_re * w_base_im + w_im * w_base_re;
                w_re = new_w_re;
            }
            stages.push(Stage { half_len, twiddles });
            len <<= 1;
        }

        Rfft { swaps, stages, re: vec![0.0; n], im: vec![0.0; n] }
    }

    /// Writes `max((re^2 + im^2) / 2^17, 1e-10)` for bins `0..=1024` into `out`.
    pub(crate) fn magnitudes(&mut self, windowed: &[f64; FFT_SIZE], out: &mut [f64]) {
        debug_assert_eq!(out.len(), FFT_OUTPUT_SIZE);
        let n = FFT_SIZE;
        self.re.copy_from_slice(windowed);
        self.im.fill(0.0);

        for &(a, b) in &self.swaps {
            self.re.swap(a as usize, b as usize);
            self.im.swap(a as usize, b as usize);
        }

        for stage in &self.stages {
            let half_len = stage.half_len;
            let len = half_len << 1;
            let mut i = 0;
            while i < n {
                for (k, &(w_re, w_im)) in stage.twiddles.iter().enumerate() {
                    let u = i + k;
                    let v = u + half_len;
                    let even_re = self.re[u];
                    let even_im = self.im[u];
                    let odd_re = self.re[v] * w_re - self.im[v] * w_im;
                    let odd_im = self.re[v] * w_im + self.im[v] * w_re;
                    self.re[u] = even_re + odd_re;
                    self.im[u] = even_im + odd_im;
                    self.re[v] = even_re - odd_re;
                    self.im[v] = even_im - odd_im;
                }
                i += len;
            }
        }

        let scale_factor = 1.0 / 131072.0;
        let min_val = 1e-10;
        for idx in 0..FFT_OUTPUT_SIZE {
            let r = self.re[idx];
            let img = self.im[idx];
            let mag = (r * r + img * img) * scale_factor;
            out[idx] = if mag < min_val { min_val } else { mag };
        }
    }
}
