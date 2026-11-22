//! Port of `DolbyAtmosAudioProcessor.kt`: the software "Dolby Atmos" chain for 2-channel PCM.
//!
//! Every constant is carried over from the Kotlin companion object, and the arithmetic keeps the
//! Kotlin `Float`/`Double` split (`f32`/`f64`) and evaluation order. The ExoPlayer shell (buffers,
//! lifecycle, status text) stays in Kotlin. Settings are written through `&mut self`, so the JNI
//! engine serializes them with the audio thread behind a mutex.

use crate::ConfigError;
use std::f64::consts::PI;

// ── Dialogue / presence (mid signal) ─────────────────────────────────────
const DIALOGUE_GAIN_DB: [f32; 4] = [0.0, 0.2, 0.4, 0.6];
const BASE_PRESENCE_DB: f64 = 1.0;
const DIALOGUE_FREQ_HZ: f64 = 2500.0;
const DIALOGUE_Q: f64 = 0.7;

// ── Stereo ───────────────────────────────────────────────────────────────
const WIDEN_GAIN: [f32; 4] = [0.0, 0.7, 1.4, 2.2];
const DECOR_GAIN: [f32; 4] = [0.0, 0.12, 0.25, 0.4];
const BASE_WIDEN: f32 = 0.5;
const BASE_DECOR: f32 = 0.1;
const SIDE_HP_HZ: f64 = 250.0;

// ── Upmix (stereo -> virtual 5.1 -> back to 2 channels) ──────────────────
const SURR_DELAY_L_S: f64 = 0.011;
const SURR_DELAY_R_S: f64 = 0.017;
const SURR_LP_HZ: f64 = 3500.0;
const SURR_CROSS_LP_HZ: f64 = 1800.0;
const SURR_GAIN: f32 = 1.0;
const SURR_CROSS: f32 = 0.5;
const SURR_MAX_S: f64 = 0.03;

// ── Diffuse background cloud ─────────────────────────────────────────────
const DIFF_COMB_L_MS: [f64; 4] = [17.1, 21.7, 25.3, 29.9];
const DIFF_COMB_R_MS: [f64; 4] = [18.3, 22.9, 26.5, 31.3];
const DIFF_AP_MS_1: f64 = 5.0;
const DIFF_AP_MS_2: f64 = 1.7;
const DIFF_FEEDBACK: f32 = 0.5;
const DIFF_DAMP: f32 = 0.4;
const DIFF_LFO_L_HZ: [f64; 4] = [0.11, 0.17, 0.23, 0.29];
const DIFF_LFO_R_HZ: [f64; 4] = [0.13, 0.19, 0.27, 0.31];
const DIFF_LFO_DEPTH_S: f64 = 0.0005;
const DIFF_IN_SIDE: f32 = 0.5;
const DIFF_IN_MID: f32 = 0.15;
const DIFF_GAIN: f32 = 1.5;
const DIFF_MID_PART: f32 = 0.3;

const MID_SHELF_HZ: f64 = 4000.0;
const MID_SHELF_DB: f64 = -2.5;

// ── Vocal / background balance ───────────────────────────────────────────
const BAL_WINDOW_DB: f32 = 1.0;
const BAL_CENTER_DB: f32 = 4.0;
const BAL_STRENGTH: f32 = 1.0;
const BAL_MAX_DB: f32 = 16.0;
const BAL_TAU_S: f64 = 0.12;
const BAL_UPDATE_FRAMES: i32 = 64;

// ── Vocal spread ─────────────────────────────────────────────────────────
const VOX_SPREAD: f32 = 0.45;
const VOX_LP_HZ: f64 = 4500.0;
const VOX_MAX_S: f64 = 0.008;
const VOX1_BASE_S: f64 = 0.0012;
const VOX1_DEPTH_S: f64 = 0.0006;
const VOX1_RATE_HZ: f64 = 0.37;
const VOX2_BASE_S: f64 = 0.0024;
const VOX2_DEPTH_S: f64 = 0.0008;
const VOX2_RATE_HZ: f64 = 0.61;

const SIDE_WIDEN_LP_HZ: f64 = 1500.0;
const DECOR_DELAY_S: f64 = 0.015;
const DECOR_HP_HZ: f64 = 300.0;

// ── Immersive sound ──────────────────────────────────────────────────────
const IMM_TAPS_L_S: [f64; 3] = [0.0073, 0.0191, 0.0310];
const IMM_TAPS_R_S: [f64; 3] = [0.0117, 0.0233, 0.0379];
const IMM_TAP_GAIN: [f32; 3] = [0.28, 0.18, 0.12];
const IMM_SIGN_L: [f32; 3] = [1.0, -1.0, 1.0];
const IMM_SIGN_R: [f32; 3] = [-1.0, 1.0, -1.0];
const IMM_BASE: f32 = 1.0;
const IMM_PER_LEVEL: f32 = 0.2;
const IMM_MODE_MULT: f32 = 2.2;
const IMM_GAIN_CAP: f32 = 3.2;
const IMM_MODE_WIDEN: f32 = 0.5;
const IMM_MODE_DECOR: f32 = 0.1;
const IMM_LP_HZ: f64 = 5500.0;
const IMM_MAX_S: f64 = 0.045;

const SIDE_SHELF_HZ: f64 = 2800.0;
const SIDE_SHELF_DB: f64 = -4.5;

// ── Graphic equalizer ────────────────────────────────────────────────────
pub const GEQ_BANDS: usize = 10;
const GEQ_FREQS: [f64; GEQ_BANDS] = [31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0];
const GEQ_Q: f64 = 1.414;
const GEQ_DB_PER_100_PERCENT: f32 = 10.0;
const DEFAULT_GEQ_PERCENT: [f32; GEQ_BANDS] = [44.0, 62.0, 27.0, -34.0, -26.0, -43.0, -18.0, -34.0, -52.0, -69.0];

// ── Speaker profile ──────────────────────────────────────────────────────
const SPEAKER_OPT_HZ: f64 = 2000.0;
const SPEAKER_OPT_DB: f64 = 2.5;
const SPEAKER_OPT_Q: f64 = 0.8;
const SPEAKER_BASS_HZ: f64 = 230.0;
const SPEAKER_BASS_DB: f64 = 3.0;
const SPEAKER_HPF_HZ: f64 = 180.0;
const SPEAKER_EXTRA_GAIN_DB: f64 = 4.5;
const SPK_LIM_RELEASE_S: f64 = 0.12;
const SPK_BALANCE_TAU_S: f64 = 0.4;
const SPK_BALANCE_MIN: f32 = 0.6;
const SPK_BALANCE_MAX: f32 = 1.7;

const GEQ_HEADROOM_DB: f64 = -2.0;

// ── 3-band split ─────────────────────────────────────────────────────────
const XOVER_LOW_HZ: f64 = 250.0;
const XOVER_HIGH_HZ: f64 = 2500.0;
const BUTTERWORTH_Q: f64 = 0.7071;

// ── Volume leveler ───────────────────────────────────────────────────────
const LEVEL_TARGET: [f32; 3] = [0.30, 0.25, 0.09];
const LEVEL_GAMMA: f32 = 0.7;
const LEVEL_G_MIN: f32 = 0.75;
const LEVEL_G_MIN_SPEAKER: f32 = 1.0;
const LEVEL_G_MAX: f32 = 2.8;
const AGC_ATTACK_S: f64 = 4.0;
const AGC_RELEASE_S: f64 = 8.0;
const AGC_UPDATE_FRAMES: i32 = 32;
const AGC_WARMUP_S: f64 = 1.5;
const AGC_WARM_ATTACK_S: f64 = 0.02;
const AGC_WARM_RELEASE_S: f64 = 0.6;
const AGC_FLOOR: f32 = 0.003;
const BAND_ATTACK_S: [f64; 3] = [0.008, 0.005, 0.002];
const BAND_RELEASE_S: [f64; 3] = [0.8, 0.6, 0.4];

// ── Audio regulator ──────────────────────────────────────────────────────
const REG_LIMIT: [f32; 3] = [0.50, 0.45, 0.20];
const REG_RATIO: f32 = 3.0;
const REG_VOLUME_TIGHTEN: f32 = 0.1;

// ── Intelligent equalizer ────────────────────────────────────────────────
const TARGET_LOW_DB: f32 = 4.0;
const TARGET_HIGH_DB: f32 = -11.0;
const EQ_STRENGTH: f32 = 0.4;
const EQ_MAX_DB: f32 = 4.0;
const EQ_POWER_TAU_S: f64 = 1.5;
const EQ_SMOOTH_TAU_S: f64 = 0.5;
const EQ_UPDATE_FRAMES: i32 = 2048;

// ── Volume maximizer ─────────────────────────────────────────────────────
const MASTER_GAIN_DB: f64 = 8.5;
const LIM_CEIL: f32 = 0.97;
const LIM_LOOKAHEAD_S: f64 = 0.0015;
const LIM_RELEASE_S: f64 = 0.3;
const CLIP_KNEE: f32 = 0.97;

const MIX_SMOOTHING: f32 = 0.0015;

// ── Kotlin-compatible helpers ────────────────────────────────────────────

/// Kotlin `coerceIn`: NaN passes through unchanged.
fn coerce(x: f32, lo: f32, hi: f32) -> f32 {
    if x < lo {
        lo
    } else if x > hi {
        hi
    } else {
        x
    }
}

/// Kotlin `max(Float, Float)`: NaN if either side is NaN.
fn fmax(a: f32, b: f32) -> f32 {
    if a.is_nan() || b.is_nan() {
        f32::NAN
    } else {
        a.max(b)
    }
}

fn exp_coef(rate_hz_or_tau: f64) -> f32 {
    (1.0 - rate_hz_or_tau.exp()) as f32
}

// ── Filter design (RBJ cookbook), pure ───────────────────────────────────

/// Normalized biquad coefficients (`a0` divided out).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Coeffs {
    pub b0: f32,
    pub b1: f32,
    pub b2: f32,
    pub a1: f32,
    pub a2: f32,
}

impl Coeffs {
    pub const IDENTITY: Coeffs = Coeffs { b0: 1.0, b1: 0.0, b2: 0.0, a1: 0.0, a2: 0.0 };
}

pub fn design_lowpass(hz: f64, sample_rate: i32) -> Coeffs {
    let w0 = 2.0 * PI * hz / f64::from(sample_rate);
    let cos_w = w0.cos();
    let alpha = w0.sin() / (2.0 * BUTTERWORTH_Q);
    let a0 = 1.0 + alpha;
    let nb0 = ((1.0 - cos_w) / 2.0 / a0) as f32;
    let nb1 = ((1.0 - cos_w) / a0) as f32;
    let na1 = ((-2.0 * cos_w) / a0) as f32;
    let na2 = ((1.0 - alpha) / a0) as f32;
    Coeffs { b0: nb0, b1: nb1, b2: nb0, a1: na1, a2: na2 }
}

pub fn design_highpass(hz: f64, sample_rate: i32) -> Coeffs {
    let w0 = 2.0 * PI * hz / f64::from(sample_rate);
    let cos_w = w0.cos();
    let alpha = w0.sin() / (2.0 * BUTTERWORTH_Q);
    let a0 = 1.0 + alpha;
    let nb0 = ((1.0 + cos_w) / 2.0 / a0) as f32;
    let nb1 = (-(1.0 + cos_w) / a0) as f32;
    let na1 = ((-2.0 * cos_w) / a0) as f32;
    let na2 = ((1.0 - alpha) / a0) as f32;
    Coeffs { b0: nb0, b1: nb1, b2: nb0, a1: na1, a2: na2 }
}

pub fn design_peaking(hz: f64, gain_db: f64, q: f64, sample_rate: i32) -> Coeffs {
    let a = 10f64.powf(gain_db / 40.0);
    let w0 = 2.0 * PI * hz / f64::from(sample_rate);
    let alpha = w0.sin() / (2.0 * q);
    let cos_w = w0.cos();
    let a0 = 1.0 + alpha / a;
    let nb0 = ((1.0 + alpha * a) / a0) as f32;
    let nb1 = ((-2.0 * cos_w) / a0) as f32;
    let nb2 = ((1.0 - alpha * a) / a0) as f32;
    let na2 = ((1.0 - alpha / a) / a0) as f32;
    Coeffs { b0: nb0, b1: nb1, b2: nb2, a1: nb1, a2: na2 }
}

pub fn design_high_shelf(hz: f64, gain_db: f64, sample_rate: i32) -> Coeffs {
    let a = 10f64.powf(gain_db / 40.0);
    let w0 = 2.0 * PI * hz / f64::from(sample_rate);
    let cos_w = w0.cos();
    let alpha = w0.sin() / 2.0 * 2f64.sqrt();
    let t = 2.0 * a.sqrt() * alpha;
    let a0 = (a + 1.0) - (a - 1.0) * cos_w + t;
    let nb0 = (a * ((a + 1.0) + (a - 1.0) * cos_w + t) / a0) as f32;
    let nb1 = (-2.0 * a * ((a - 1.0) + (a + 1.0) * cos_w) / a0) as f32;
    let nb2 = (a * ((a + 1.0) + (a - 1.0) * cos_w - t) / a0) as f32;
    let na1 = (2.0 * ((a - 1.0) - (a + 1.0) * cos_w) / a0) as f32;
    let na2 = (((a + 1.0) - (a - 1.0) * cos_w - t) / a0) as f32;
    Coeffs { b0: nb0, b1: nb1, b2: nb2, a1: na1, a2: na2 }
}

fn onepole_hp(hz: f64, sample_rate: i32) -> f32 {
    let rc = 1.0 / (2.0 * PI * hz);
    (rc / (rc + 1.0 / f64::from(sample_rate))) as f32
}

// ── Building blocks ──────────────────────────────────────────────────────

#[derive(Debug, Clone)]
struct Biquad {
    c: Coeffs,
    z1: f32,
    z2: f32,
}

impl Biquad {
    fn new() -> Self {
        Self { c: Coeffs::IDENTITY, z1: 0.0, z2: 0.0 }
    }

    fn set(&mut self, c: Coeffs) {
        self.c = c;
    }

    fn process(&mut self, x: f32) -> f32 {
        let y = self.c.b0 * x + self.z1;
        self.z1 = self.c.b1 * x - self.c.a1 * y + self.z2;
        self.z2 = self.c.b2 * x - self.c.a2 * y;
        y
    }

    fn reset(&mut self) {
        self.z1 = 0.0;
        self.z2 = 0.0;
    }
}

/// Damped feedback comb filter whose delay length wobbles slowly.
#[derive(Debug, Clone)]
struct Comb {
    delay: i32,
    depth: f32,
    buf: Vec<f32>,
    w: usize,
    store: f32,
    phase: f64,
    inc: f64,
}

impl Comb {
    fn new(delay: i32, depth: f32, rate_hz: f64, start_phase: f64, sr: f64) -> Self {
        let len = (delay + depth as i32 + 4).max(1) as usize;
        Self { delay, depth, buf: vec![0.0; len], w: 0, store: 0.0, phase: start_phase, inc: 2.0 * PI * rate_hz / sr }
    }

    fn process(&mut self, x: f32, feedback: f32, damp: f32) -> f32 {
        let len = self.buf.len();
        let d = self.delay as f32 + self.depth * (self.phase.sin() as f32);
        self.phase += self.inc;
        if self.phase > 2.0 * PI {
            self.phase -= 2.0 * PI;
        }
        let mut pos = self.w as f32 - d;
        if pos < 0.0 {
            pos += len as f32;
        }
        // Float rounding can make a tiny negative pos land exactly on buf.size.
        if pos >= len as f32 {
            pos -= len as f32;
        }
        let i0 = (pos as usize).min(len - 1);
        let frac = pos - i0 as f32;
        let i1 = if i0 + 1 >= len { 0 } else { i0 + 1 };
        let out = self.buf[i0] + (self.buf[i1] - self.buf[i0]) * frac;
        self.store = out * (1.0 - damp) + self.store * damp;
        self.buf[self.w] = x + self.store * feedback;
        self.w += 1;
        if self.w >= len {
            self.w = 0;
        }
        out
    }

    fn reset(&mut self) {
        self.buf.fill(0.0);
        self.store = 0.0;
    }
}

#[derive(Debug, Clone)]
struct Allpass {
    buf: Vec<f32>,
    idx: usize,
}

impl Allpass {
    fn new(size: i32) -> Self {
        Self { buf: vec![0.0; size.max(1) as usize], idx: 0 }
    }

    fn process(&mut self, x: f32) -> f32 {
        let b = self.buf[self.idx];
        let out = b - x;
        self.buf[self.idx] = x + b * 0.5;
        self.idx += 1;
        if self.idx >= self.buf.len() {
            self.idx = 0;
        }
        out
    }

    fn reset(&mut self) {
        self.buf.fill(0.0);
        self.idx = 0;
    }
}

fn seconds_to_len(seconds: f64, sr: f64, min: usize) -> usize {
    ((seconds * sr) as usize).max(min)
}

/// Per-buffer values computed once at the top of a processed buffer.
struct BufferParams {
    widen: f32,
    decor: f32,
    target: f32,
}

// ── The processor ────────────────────────────────────────────────────────

pub struct DolbyAtmos {
    // settings
    enabled: bool,
    dialogue_level: i32,
    stereo_level: i32,
    system_volume: f32,
    immersive_mode: bool,
    speaker_mode: bool,

    // format
    configured: bool,
    sample_rate: i32,
    channels: usize,

    // mid peaking
    applied_dialogue: i32,
    b0: f32,
    b1: f32,
    b2: f32,
    a1: f32,
    a2: f32,
    z1: f32,
    z2: f32,
    out_gain: f32,

    // stereo
    hp_coef: f32,
    hp_prev_in: f32,
    hp_prev_out: f32,
    s_band: f32,
    surr_buf: Vec<f32>,
    surr_idx: usize,
    surr_del_l: usize,
    surr_del_r: usize,
    surr_lp: f32,
    surr_lp_coef: f32,
    surr_cross_l: f32,
    surr_cross_r: f32,
    surr_cross_coef: f32,
    bal_env_m: f32,
    bal_env_s: f32,
    bal_coef: f32,
    bal_gain_m: f32,
    bal_gain_s: f32,
    bal_target_m: f32,
    bal_target_s: f32,
    bal_counter: i32,
    vox_buf: Vec<f32>,
    vox_idx: usize,
    vox_lp: f32,
    vox_lp_coef: f32,
    vox1_phase: f64,
    vox2_phase: f64,
    vox1_inc: f64,
    vox2_inc: f64,
    s_lp_coef: f32,
    dec_buf: Vec<f32>,
    dec_idx: usize,
    dec_hp_coef: f32,
    dec_prev_in: f32,
    dec_prev_out: f32,
    side_shelf: Biquad,
    mid_shelf: Biquad,
    combs_l: Vec<Comb>,
    combs_r: Vec<Comb>,
    ap_l1: Allpass,
    ap_l2: Allpass,
    ap_r1: Allpass,
    ap_r2: Allpass,

    // immersive
    imm_buf: Vec<f32>,
    imm_idx: usize,
    imm_lp: f32,
    imm_lp_coef: f32,
    imm_tap_l: [usize; 3],
    imm_tap_r: [usize; 3],
    imm_gain: f32,

    // graphic EQ and speaker profile
    geq_l: Vec<Biquad>,
    geq_r: Vec<Biquad>,
    geq_gains_db: [f32; GEQ_BANDS],
    geq_version: i32,
    applied_geq_version: i32,
    applied_speaker: bool,
    hpf_l: Biquad,
    hpf_r: Biquad,
    spk_opt_l: Biquad,
    spk_opt_r: Biquad,
    spk_bass_l: Biquad,
    spk_bass_r: Biquad,

    // multiband
    lp1_l: Biquad,
    lp1_r: Biquad,
    lp2_l: Biquad,
    lp2_r: Biquad,
    band_env: [f32; 3],
    band_atk: [f32; 3],
    band_rel: [f32; 3],
    band_pow: [f32; 3],
    band_agc: [f32; 3],
    agc_gain: [f32; 3],
    agc_atk: f32,
    agc_rel: f32,
    agc_counter: i32,
    agc_warm: i32,
    agc_warm_frames: i32,
    agc_warm_atk: f32,
    agc_warm_rel: f32,
    pow_coef: f32,
    eq_gain: [f32; 3],
    eq_target: [f32; 3],
    eq_smooth: f32,
    eq_counter: i32,
    reg_scale: f32,
    master_gain: f32,
    speaker_extra: f32,

    // look-ahead limiter
    la_buf_l: Vec<f32>,
    la_buf_r: Vec<f32>,
    la_idx: usize,
    lim_gain: f32,
    lim_atk: f32,
    lim_rel: f32,
    lim_rel_spk: f32,
    spk_env_l: f32,
    spk_env_r: f32,
    spk_coef: f32,
    spk_gain_l: f32,
    spk_gain_r: f32,
    spk_tgt_l: f32,
    spk_tgt_r: f32,
    spk_counter: i32,

    mix: f32,
}

impl Default for DolbyAtmos {
    fn default() -> Self {
        Self::new()
    }
}

impl DolbyAtmos {
    pub fn new() -> Self {
        let mut geq_gains_db = [0.0f32; GEQ_BANDS];
        for (g, p) in geq_gains_db.iter_mut().zip(DEFAULT_GEQ_PERCENT) {
            *g = p * GEQ_DB_PER_100_PERCENT / 100.0;
        }
        Self {
            enabled: false,
            dialogue_level: 3,
            stereo_level: 0,
            system_volume: 0.5,
            immersive_mode: false,
            speaker_mode: false,
            configured: false,
            sample_rate: 44100,
            channels: 2,
            applied_dialogue: -1,
            b0: 1.0,
            b1: 0.0,
            b2: 0.0,
            a1: 0.0,
            a2: 0.0,
            z1: 0.0,
            z2: 0.0,
            out_gain: 1.0,
            hp_coef: 0.0,
            hp_prev_in: 0.0,
            hp_prev_out: 0.0,
            s_band: 0.0,
            surr_buf: vec![0.0; 1],
            surr_idx: 0,
            surr_del_l: 1,
            surr_del_r: 1,
            surr_lp: 0.0,
            surr_lp_coef: 0.3,
            surr_cross_l: 0.0,
            surr_cross_r: 0.0,
            surr_cross_coef: 0.2,
            bal_env_m: 0.0,
            bal_env_s: 0.0,
            bal_coef: 0.0001,
            bal_gain_m: 1.0,
            bal_gain_s: 1.0,
            bal_target_m: 1.0,
            bal_target_s: 1.0,
            bal_counter: 0,
            vox_buf: vec![0.0; 1],
            vox_idx: 0,
            vox_lp: 0.0,
            vox_lp_coef: 0.4,
            vox1_phase: 0.0,
            vox2_phase: 1.7,
            vox1_inc: 0.0,
            vox2_inc: 0.0,
            s_lp_coef: 0.2,
            dec_buf: vec![0.0; 1],
            dec_idx: 0,
            dec_hp_coef: 0.0,
            dec_prev_in: 0.0,
            dec_prev_out: 0.0,
            side_shelf: Biquad::new(),
            mid_shelf: Biquad::new(),
            combs_l: Vec::new(),
            combs_r: Vec::new(),
            ap_l1: Allpass::new(1),
            ap_l2: Allpass::new(1),
            ap_r1: Allpass::new(1),
            ap_r2: Allpass::new(1),
            imm_buf: vec![0.0; 1],
            imm_idx: 0,
            imm_lp: 0.0,
            imm_lp_coef: 0.5,
            imm_tap_l: [1; 3],
            imm_tap_r: [1; 3],
            imm_gain: IMM_BASE,
            geq_l: vec![Biquad::new(); GEQ_BANDS],
            geq_r: vec![Biquad::new(); GEQ_BANDS],
            geq_gains_db,
            geq_version: 0,
            applied_geq_version: -1,
            applied_speaker: false,
            hpf_l: Biquad::new(),
            hpf_r: Biquad::new(),
            spk_opt_l: Biquad::new(),
            spk_opt_r: Biquad::new(),
            spk_bass_l: Biquad::new(),
            spk_bass_r: Biquad::new(),
            lp1_l: Biquad::new(),
            lp1_r: Biquad::new(),
            lp2_l: Biquad::new(),
            lp2_r: Biquad::new(),
            band_env: [0.0; 3],
            band_atk: [0.01; 3],
            band_rel: [0.0005; 3],
            band_pow: [0.0; 3],
            band_agc: [0.0; 3],
            agc_gain: [1.0; 3],
            agc_atk: 0.002,
            agc_rel: 0.00003,
            agc_counter: 0,
            agc_warm: 0,
            agc_warm_frames: 0,
            agc_warm_atk: 0.002,
            agc_warm_rel: 0.00003,
            pow_coef: 0.00002,
            eq_gain: [1.0; 3],
            eq_target: [1.0; 3],
            eq_smooth: 0.00004,
            eq_counter: 0,
            reg_scale: 1.0,
            master_gain: 10f64.powf(MASTER_GAIN_DB / 20.0) as f32,
            speaker_extra: 10f64.powf(SPEAKER_EXTRA_GAIN_DB / 20.0) as f32,
            la_buf_l: vec![0.0; 1],
            la_buf_r: vec![0.0; 1],
            la_idx: 0,
            lim_gain: 1.0,
            lim_atk: 0.05,
            lim_rel: 0.0003,
            lim_rel_spk: 0.0003,
            spk_env_l: 0.0,
            spk_env_r: 0.0,
            spk_coef: 0.0001,
            spk_gain_l: 1.0,
            spk_gain_r: 1.0,
            spk_tgt_l: 1.0,
            spk_tgt_r: 1.0,
            spk_counter: 0,
            mix: 0.0,
        }
    }

    // ── Settings ─────────────────────────────────────────────────────────

    pub fn set_parameters(&mut self, enabled: bool, dialogue_level: i32, stereo_level: i32, volume: f32, immersive: bool, speaker: bool) {
        self.speaker_mode = speaker;
        self.immersive_mode = immersive;
        self.dialogue_level = dialogue_level.clamp(0, 3);
        self.stereo_level = stereo_level.clamp(0, 3);
        self.system_volume = coerce(volume, 0.0, 1.0);
        self.enabled = enabled;
    }

    /// Replaces the 10-band curve (dB, 31 Hz to 16 kHz). Returns false and keeps the old curve
    /// when the slice does not hold exactly [`GEQ_BANDS`] values.
    pub fn set_graphic_eq(&mut self, gains_db: &[f32]) -> bool {
        let Ok(gains) = <[f32; GEQ_BANDS]>::try_from(gains_db) else { return false };
        self.geq_gains_db = gains;
        self.geq_version = self.geq_version.wrapping_add(1);
        true
    }

    /// The speaker profile that was actually applied to the last processed buffer.
    pub fn applied_speaker(&self) -> bool {
        self.applied_speaker
    }

    /// True when the next buffer is copied unchanged: unconfigured, not 2 channels, or off with
    /// the dry/wet mix fully faded out.
    pub fn will_bypass(&self) -> bool {
        !self.configured || self.channels != 2 || (!self.enabled && self.mix == 0.0)
    }

    // ── Format ───────────────────────────────────────────────────────────

    /// Rebuilds every filter and delay line for the stream format and clears the audio state.
    /// An invalid rate leaves the processor unconfigured (it then passes audio through).
    pub fn configure(&mut self, sample_rate: i32, channels: usize) -> Result<(), ConfigError> {
        self.configured = false;
        if sample_rate <= 0 {
            return Err(ConfigError::InvalidSampleRate(sample_rate));
        }
        if channels == 0 {
            return Err(ConfigError::InvalidChannelCount(channels));
        }
        self.sample_rate = sample_rate;
        self.channels = channels;
        let sr = f64::from(sample_rate);
        self.applied_dialogue = -1;

        self.hp_coef = onepole_hp(SIDE_HP_HZ, sample_rate);
        self.s_lp_coef = exp_coef(-2.0 * PI * SIDE_WIDEN_LP_HZ / sr);
        self.surr_buf = vec![0.0; seconds_to_len(SURR_MAX_S, sr, 64)];
        self.surr_idx = 0;
        let surr_max = self.surr_buf.len() - 1;
        self.surr_del_l = ((SURR_DELAY_L_S * sr) as usize).clamp(1, surr_max);
        self.surr_del_r = ((SURR_DELAY_R_S * sr) as usize).clamp(1, surr_max);
        self.surr_lp_coef = exp_coef(-2.0 * PI * SURR_LP_HZ / sr);
        self.surr_cross_coef = exp_coef(-2.0 * PI * SURR_CROSS_LP_HZ / sr);
        self.bal_coef = exp_coef(-1.0 / (BAL_TAU_S * sr));
        self.vox_buf = vec![0.0; seconds_to_len(VOX_MAX_S, sr, 64)];
        self.vox_idx = 0;
        self.vox_lp_coef = exp_coef(-2.0 * PI * VOX_LP_HZ / sr);
        self.vox1_inc = 2.0 * PI * VOX1_RATE_HZ / sr;
        self.vox2_inc = 2.0 * PI * VOX2_RATE_HZ / sr;
        self.dec_hp_coef = onepole_hp(DECOR_HP_HZ, sample_rate);
        self.dec_buf = vec![0.0; seconds_to_len(DECOR_DELAY_S, sr, 8)];
        self.dec_idx = 0;

        self.imm_buf = vec![0.0; seconds_to_len(IMM_MAX_S, sr, 64)];
        self.imm_idx = 0;
        self.imm_lp_coef = exp_coef(-2.0 * PI * IMM_LP_HZ / sr);
        let imm_max = self.imm_buf.len() - 1;
        for i in 0..3 {
            self.imm_tap_l[i] = ((IMM_TAPS_L_S[i] * sr) as usize).clamp(1, imm_max);
            self.imm_tap_r[i] = ((IMM_TAPS_R_S[i] * sr) as usize).clamp(1, imm_max);
        }

        self.side_shelf.set(design_high_shelf(SIDE_SHELF_HZ, SIDE_SHELF_DB, sample_rate));
        self.mid_shelf.set(design_high_shelf(MID_SHELF_HZ, MID_SHELF_DB, sample_rate));
        let depth = (DIFF_LFO_DEPTH_S * sr) as f32;
        self.combs_l = (0..DIFF_COMB_L_MS.len())
            .map(|i| Comb::new((((DIFF_COMB_L_MS[i] / 1000.0) * sr) as i32).max(8), depth, DIFF_LFO_L_HZ[i], i as f64 * 1.3, sr))
            .collect();
        self.combs_r = (0..DIFF_COMB_R_MS.len())
            .map(|i| Comb::new((((DIFF_COMB_R_MS[i] / 1000.0) * sr) as i32).max(8), depth, DIFF_LFO_R_HZ[i], 0.7 + i as f64 * 1.1, sr))
            .collect();
        self.ap_l1 = Allpass::new((((DIFF_AP_MS_1 / 1000.0) * sr) as i32).max(4));
        self.ap_l2 = Allpass::new((((DIFF_AP_MS_2 / 1000.0) * sr) as i32).max(4));
        self.ap_r1 = Allpass::new((((DIFF_AP_MS_1 * 1.13 / 1000.0) * sr) as i32).max(4));
        self.ap_r2 = Allpass::new((((DIFF_AP_MS_2 * 1.19 / 1000.0) * sr) as i32).max(4));
        self.applied_geq_version = -1;
        let hp = design_highpass(SPEAKER_HPF_HZ, sample_rate);
        self.hpf_l.set(hp);
        self.hpf_r.set(hp);
        let opt = design_peaking(SPEAKER_OPT_HZ, SPEAKER_OPT_DB, SPEAKER_OPT_Q, sample_rate);
        self.spk_opt_l.set(opt);
        self.spk_opt_r.set(opt);
        let bass = design_peaking(SPEAKER_BASS_HZ, SPEAKER_BASS_DB, 0.8, sample_rate);
        self.spk_bass_l.set(bass);
        self.spk_bass_r.set(bass);
        let low = design_lowpass(XOVER_LOW_HZ, sample_rate);
        self.lp1_l.set(low);
        self.lp1_r.set(low);
        let high = design_lowpass(XOVER_HIGH_HZ, sample_rate);
        self.lp2_l.set(high);
        self.lp2_r.set(high);

        for i in 0..3 {
            self.band_atk[i] = exp_coef(-1.0 / (BAND_ATTACK_S[i] * sr));
            self.band_rel[i] = exp_coef(-1.0 / (BAND_RELEASE_S[i] * sr));
        }
        self.agc_atk = exp_coef(-1.0 / (AGC_ATTACK_S * sr));
        self.agc_rel = exp_coef(-1.0 / (AGC_RELEASE_S * sr));
        self.agc_warm_atk = exp_coef(-1.0 / (AGC_WARM_ATTACK_S * sr));
        self.agc_warm_rel = exp_coef(-1.0 / (AGC_WARM_RELEASE_S * sr));
        self.agc_warm_frames = (AGC_WARMUP_S * sr) as i32;
        self.pow_coef = exp_coef(-1.0 / (EQ_POWER_TAU_S * sr));
        self.eq_smooth = exp_coef(-1.0 / (EQ_SMOOTH_TAU_S * sr));

        let la_len = seconds_to_len(LIM_LOOKAHEAD_S, sr, 8);
        self.la_buf_l = vec![0.0; la_len];
        self.la_buf_r = vec![0.0; la_len];
        self.la_idx = 0;
        self.lim_atk = exp_coef(-5.0 / la_len as f64);
        self.lim_rel = exp_coef(-1.0 / (LIM_RELEASE_S * sr));
        self.lim_rel_spk = exp_coef(-1.0 / (SPK_LIM_RELEASE_S * sr));
        self.spk_coef = exp_coef(-1.0 / (SPK_BALANCE_TAU_S * sr));

        self.configured = true;
        self.clear_state();
        Ok(())
    }

    /// Clears audio state only (filters, envelopes, delay lines); settings and the dry/wet mix
    /// position are kept, like the Kotlin `clearState`.
    pub fn clear_state(&mut self) {
        self.z1 = 0.0;
        self.z2 = 0.0;
        self.hp_prev_in = 0.0;
        self.hp_prev_out = 0.0;
        self.s_band = 0.0;
        self.vox_buf.fill(0.0);
        self.vox_idx = 0;
        self.vox_lp = 0.0;
        self.bal_env_m = 0.0;
        self.bal_env_s = 0.0;
        self.bal_gain_m = 1.0;
        self.bal_gain_s = 1.0;
        self.bal_target_m = 1.0;
        self.bal_target_s = 1.0;
        self.bal_counter = 0;
        self.dec_buf.fill(0.0);
        self.dec_idx = 0;
        self.dec_prev_in = 0.0;
        self.dec_prev_out = 0.0;
        self.imm_buf.fill(0.0);
        self.imm_idx = 0;
        self.imm_lp = 0.0;
        self.side_shelf.reset();
        self.mid_shelf.reset();
        self.surr_buf.fill(0.0);
        self.surr_idx = 0;
        self.surr_lp = 0.0;
        self.surr_cross_l = 0.0;
        self.surr_cross_r = 0.0;
        self.combs_l.iter_mut().for_each(Comb::reset);
        self.combs_r.iter_mut().for_each(Comb::reset);
        self.ap_l1.reset();
        self.ap_l2.reset();
        self.ap_r1.reset();
        self.ap_r2.reset();
        for b in [&mut self.hpf_l, &mut self.hpf_r, &mut self.spk_opt_l, &mut self.spk_opt_r, &mut self.spk_bass_l, &mut self.spk_bass_r] {
            b.reset();
        }
        self.geq_l.iter_mut().for_each(Biquad::reset);
        self.geq_r.iter_mut().for_each(Biquad::reset);
        for b in [&mut self.lp1_l, &mut self.lp1_r, &mut self.lp2_l, &mut self.lp2_r] {
            b.reset();
        }
        for i in 0..3 {
            self.band_env[i] = 0.0;
            self.band_agc[i] = 0.0;
            self.agc_gain[i] = 1.0;
            self.band_pow[i] = 0.0;
            self.eq_gain[i] = 1.0;
            self.eq_target[i] = 1.0;
        }
        self.eq_counter = 0;
        self.agc_counter = 0;
        self.agc_warm = self.agc_warm_frames;
        self.la_buf_l.fill(0.0);
        self.la_buf_r.fill(0.0);
        self.la_idx = 0;
        self.lim_gain = 1.0;
        self.spk_env_l = 0.0;
        self.spk_env_r = 0.0;
        self.spk_gain_l = 1.0;
        self.spk_gain_r = 1.0;
        self.spk_tgt_l = 1.0;
        self.spk_tgt_r = 1.0;
        self.spk_counter = 0;
    }

    // ── Processing ───────────────────────────────────────────────────────

    /// Interleaved stereo float. Returns the samples written. Bypassed buffers are copied.
    pub fn process_f32(&mut self, input: &[f32], output: &mut [f32]) -> usize {
        let n = input.len().min(output.len());
        let Some(p) = self.begin_buffer() else {
            output[..n].copy_from_slice(&input[..n]);
            return n;
        };
        let frames = n / 2;
        for f in 0..frames {
            let (ol, or) = self.process_frame(input[f * 2], input[f * 2 + 1], p.widen, p.decor, p.target);
            output[f * 2] = ol;
            output[f * 2 + 1] = or;
        }
        frames * 2
    }

    /// Interleaved stereo 16-bit. Returns the samples written. Bypassed buffers are copied.
    pub fn process_i16(&mut self, input: &[i16], output: &mut [i16]) -> usize {
        let n = input.len().min(output.len());
        let Some(p) = self.begin_buffer() else {
            output[..n].copy_from_slice(&input[..n]);
            return n;
        };
        let frames = n / 2;
        for f in 0..frames {
            let l = f32::from(input[f * 2]) / 32768.0;
            let r = f32::from(input[f * 2 + 1]) / 32768.0;
            let (ol, or) = self.process_frame(l, r, p.widen, p.decor, p.target);
            output[f * 2] = coerce(ol * 32768.0, -32768.0, 32767.0) as i32 as i16;
            output[f * 2 + 1] = coerce(or * 32768.0, -32768.0, 32767.0) as i32 as i16;
        }
        frames * 2
    }

    fn begin_buffer(&mut self) -> Option<BufferParams> {
        if !self.configured || self.channels != 2 {
            return None;
        }
        let target = if self.enabled { 1.0f32 } else { 0.0 };
        if target == 0.0 && self.mix == 0.0 {
            return None;
        }
        let d_level = self.dialogue_level;
        let imm = self.immersive_mode;
        let stereo = self.stereo_level as usize;
        let widen = BASE_WIDEN + WIDEN_GAIN[stereo] + if imm { IMM_MODE_WIDEN } else { 0.0 };
        let decor = BASE_DECOR + DECOR_GAIN[stereo] + if imm { IMM_MODE_DECOR } else { 0.0 };
        self.reg_scale = 1.0 - REG_VOLUME_TIGHTEN * self.system_volume;
        self.imm_gain = ((IMM_BASE + IMM_PER_LEVEL * self.stereo_level as f32) * if imm { IMM_MODE_MULT } else { 1.0 }).min(IMM_GAIN_CAP);
        if d_level != self.applied_dialogue {
            self.update_dialogue_filter(d_level);
        }
        let spk = self.speaker_mode;
        if self.geq_version != self.applied_geq_version || spk != self.applied_speaker {
            self.update_graphic_eq();
        }
        Some(BufferParams { widen, decor, target })
    }

    fn update_graphic_eq(&mut self) {
        self.applied_geq_version = self.geq_version;
        self.applied_speaker = self.speaker_mode;
        for i in 0..GEQ_BANDS {
            let c = design_peaking(GEQ_FREQS[i], f64::from(self.geq_gains_db[i]), GEQ_Q, self.sample_rate);
            self.geq_l[i].set(c);
            self.geq_r[i].set(c);
        }
    }

    /// Mid-signal presence peaking filter; the output gain leaves headroom for the boost.
    fn update_dialogue_filter(&mut self, level: i32) {
        self.applied_dialogue = level;
        let gain_db = BASE_PRESENCE_DB + f64::from(DIALOGUE_GAIN_DB[level as usize]);
        let a = 10f64.powf(gain_db / 40.0);
        let w0 = 2.0 * PI * DIALOGUE_FREQ_HZ / f64::from(self.sample_rate);
        let alpha = w0.sin() / (2.0 * DIALOGUE_Q);
        let cos_w = w0.cos();
        let a0 = 1.0 + alpha / a;
        self.b0 = ((1.0 + alpha * a) / a0) as f32;
        self.b1 = ((-2.0 * cos_w) / a0) as f32;
        self.b2 = ((1.0 - alpha * a) / a0) as f32;
        self.a1 = ((-2.0 * cos_w) / a0) as f32;
        self.a2 = ((1.0 - alpha / a) / a0) as f32;
        self.out_gain = 10f64.powf((GEQ_HEADROOM_DB - gain_db * 0.5) / 20.0) as f32;
    }

    /// Linear-interpolated read of the vocal delay line `delay_s` seconds behind the newest sample.
    fn vox_tap(&self, delay_s: f64) -> f32 {
        let len = self.vox_buf.len();
        let d = (delay_s * f64::from(self.sample_rate)) as f32;
        let mut pos = self.vox_idx as f32 - d;
        if pos < 0.0 {
            pos += len as f32;
        }
        if pos >= len as f32 {
            pos -= len as f32;
        }
        let i0 = (pos as usize).min(len - 1);
        let frac = pos - i0 as f32;
        let i1 = if i0 + 1 >= len { 0 } else { i0 + 1 };
        self.vox_buf[i0] + (self.vox_buf[i1] - self.vox_buf[i0]) * frac
    }

    fn process_frame(&mut self, l: f32, r: f32, widen: f32, decor: f32, target: f32) -> (f32, f32) {
        self.mix += (target - self.mix) * MIX_SMOOTHING;
        if target == 0.0 && self.mix < 0.0005 {
            self.mix = 0.0;
        }

        let mut m = (l + r) * 0.5;
        let mut s = (l - r) * 0.5;

        // Dialogue / presence: peaking filter on the mid signal (transposed direct form II).
        let my = self.b0 * m + self.z1;
        self.z1 = self.b1 * m - self.a1 * my + self.z2;
        self.z2 = self.b2 * m - self.a2 * my;
        m = self.mid_shelf.process(my);

        // Stereo: widen side above the high-pass corner, add pseudo-stereo.
        let s_hp = self.hp_coef * (self.hp_prev_out + s - self.hp_prev_in);
        self.hp_prev_in = s;
        self.hp_prev_out = s_hp;
        self.s_band += (s_hp - self.s_band) * self.s_lp_coef;
        s += widen * self.s_band;

        let m_hp = self.dec_hp_coef * (self.dec_prev_out + m - self.dec_prev_in);
        self.dec_prev_in = m;
        self.dec_prev_out = m_hp;
        let delayed = self.dec_buf[self.dec_idx];
        self.dec_buf[self.dec_idx] = m_hp;
        self.dec_idx += 1;
        if self.dec_idx >= self.dec_buf.len() {
            self.dec_idx = 0;
        }
        s += decor * delayed;

        // Immersive sound: darkened, delayed copies of the mid signal (early reflections).
        self.imm_lp += (m_hp - self.imm_lp) * self.imm_lp_coef;
        self.imm_buf[self.imm_idx] = self.imm_lp;
        let imm_len = self.imm_buf.len();
        let mut ref_l = 0.0f32;
        let mut ref_r = 0.0f32;
        for i in 0..3 {
            let il = (self.imm_idx + imm_len - self.imm_tap_l[i]) % imm_len;
            let ir = (self.imm_idx + imm_len - self.imm_tap_r[i]) % imm_len;
            ref_l += self.imm_buf[il] * IMM_TAP_GAIN[i] * IMM_SIGN_L[i];
            ref_r += self.imm_buf[ir] * IMM_TAP_GAIN[i] * IMM_SIGN_R[i];
        }
        self.imm_idx += 1;
        if self.imm_idx >= imm_len {
            self.imm_idx = 0;
        }
        ref_l *= self.imm_gain;
        ref_r *= self.imm_gain;

        let ref_gain = 1.0 / self.out_gain;
        m += (ref_l + ref_r) * 0.5 * ref_gain;
        s += (ref_l - ref_r) * 0.5 * ref_gain;

        // Diffuse background cloud (before the side darkening).
        let d_in = s_hp * DIFF_IN_SIDE + self.vox_lp * DIFF_IN_MID;
        let mut wet_l = 0.0f32;
        let mut wet_r = 0.0f32;
        for c in self.combs_l.iter_mut() {
            wet_l += c.process(d_in, DIFF_FEEDBACK, DIFF_DAMP);
        }
        for c in self.combs_r.iter_mut() {
            wet_r += c.process(d_in, DIFF_FEEDBACK, DIFF_DAMP);
        }
        wet_l = self.ap_l2.process(self.ap_l1.process(wet_l * 0.25));
        wet_r = self.ap_r2.process(self.ap_r1.process(wet_r * 0.25));
        s += (wet_l - wet_r) * 0.5 * DIFF_GAIN;
        m += (wet_l + wet_r) * 0.5 * DIFF_GAIN * DIFF_MID_PART;

        s = self.side_shelf.process(s);

        // Vocal spread: update the vocal-band low-pass first, then balance, then the micro-delays.
        self.vox_lp += (m_hp - self.vox_lp) * self.vox_lp_coef;

        // Vocal / background balance.
        self.bal_env_m += (self.vox_lp.abs() - self.bal_env_m) * self.bal_coef;
        self.bal_env_s += (s_hp.abs() - self.bal_env_s) * self.bal_coef;
        self.bal_counter += 1;
        if self.bal_counter >= BAL_UPDATE_FRAMES {
            self.bal_counter = 0;
            if self.bal_env_m > 0.0005 || self.bal_env_s > 0.0005 {
                let ratio_db = 20.0 * ((self.bal_env_m + 1e-5) / (self.bal_env_s + 1e-5)).log10() - BAL_CENTER_DB;
                let over = if ratio_db > BAL_WINDOW_DB {
                    ratio_db - BAL_WINDOW_DB
                } else if ratio_db < -BAL_WINDOW_DB {
                    ratio_db + BAL_WINDOW_DB
                } else {
                    0.0
                };
                let corr = coerce(-over * BAL_STRENGTH, -BAL_MAX_DB, BAL_MAX_DB);
                self.bal_target_m = 10f64.powf(f64::from(corr) / 40.0) as f32;
                self.bal_target_s = 10f64.powf(f64::from(-corr) / 40.0) as f32;
            }
        }
        self.bal_gain_m += (self.bal_target_m - self.bal_gain_m) * 0.004;
        self.bal_gain_s += (self.bal_target_s - self.bal_gain_s) * 0.004;
        m += (self.bal_gain_m - 1.0) * self.vox_lp;
        s += (self.bal_gain_s - 1.0) * s_hp;
        self.vox_buf[self.vox_idx] = self.vox_lp;
        let tap1 = self.vox_tap(VOX1_BASE_S + VOX1_DEPTH_S * self.vox1_phase.sin());
        let tap2 = self.vox_tap(VOX2_BASE_S + VOX2_DEPTH_S * self.vox2_phase.sin());
        s += VOX_SPREAD * (tap1 - tap2);
        self.vox1_phase += self.vox1_inc;
        if self.vox1_phase > 2.0 * PI {
            self.vox1_phase -= 2.0 * PI;
        }
        self.vox2_phase += self.vox2_inc;
        if self.vox2_phase > 2.0 * PI {
            self.vox2_phase -= 2.0 * PI;
        }
        self.vox_idx += 1;
        if self.vox_idx >= self.vox_buf.len() {
            self.vox_idx = 0;
        }

        let mut tl = (m + s) * self.out_gain;
        let mut tr = (m - s) * self.out_gain;

        // Upmix: ambience -> virtual surround channels, rendered back with a shaded crossfeed.
        self.surr_lp += (s_hp - self.surr_lp) * self.surr_lp_coef;
        self.surr_buf[self.surr_idx] = self.surr_lp;
        let surr_len = self.surr_buf.len();
        let pl = (self.surr_idx + surr_len - self.surr_del_l) % surr_len;
        let pr = (self.surr_idx + surr_len - self.surr_del_r) % surr_len;
        let ls = self.surr_buf[pl];
        let rs = -self.surr_buf[pr];
        self.surr_idx += 1;
        if self.surr_idx >= surr_len {
            self.surr_idx = 0;
        }
        self.surr_cross_l += (ls - self.surr_cross_l) * self.surr_cross_coef;
        self.surr_cross_r += (rs - self.surr_cross_r) * self.surr_cross_coef;
        tl += (ls + SURR_CROSS * self.surr_cross_r) * SURR_GAIN;
        tr += (rs + SURR_CROSS * self.surr_cross_l) * SURR_GAIN;
        if self.applied_speaker {
            tl = self.spk_bass_l.process(self.spk_opt_l.process(self.hpf_l.process(tl)));
            tr = self.spk_bass_r.process(self.spk_opt_r.process(self.hpf_r.process(tr)));
        }
        for i in 0..GEQ_BANDS {
            tl = self.geq_l[i].process(tl);
            tr = self.geq_r[i].process(tr);
        }

        let (mut band_l, mut band_r) = self.process_bands(tl, tr);

        // Volume maximizer: master gain, look-ahead limiter, soft clip.
        if self.applied_speaker {
            self.spk_env_l += (band_l.abs() - self.spk_env_l) * self.spk_coef;
            self.spk_env_r += (band_r.abs() - self.spk_env_r) * self.spk_coef;
            self.spk_counter += 1;
            if self.spk_counter >= 64 {
                self.spk_counter = 0;
                let mean = (self.spk_env_l + self.spk_env_r) * 0.5;
                if mean > 0.001 {
                    self.spk_tgt_l = coerce(mean / fmax(self.spk_env_l, 1e-4), SPK_BALANCE_MIN, SPK_BALANCE_MAX);
                    self.spk_tgt_r = coerce(mean / fmax(self.spk_env_r, 1e-4), SPK_BALANCE_MIN, SPK_BALANCE_MAX);
                }
            }
            self.spk_gain_l += (self.spk_tgt_l - self.spk_gain_l) * 0.002;
            self.spk_gain_r += (self.spk_tgt_r - self.spk_gain_r) * 0.002;
            band_l *= self.spk_gain_l;
            band_r *= self.spk_gain_r;
        }
        let mg = if self.applied_speaker { self.master_gain * self.speaker_extra } else { self.master_gain };
        let xl = band_l * mg;
        let xr = band_r * mg;
        let pk = fmax(xl.abs(), xr.abs());
        let tg = if pk > LIM_CEIL { LIM_CEIL / pk } else { 1.0 };
        let rel = if self.applied_speaker { self.lim_rel_spk } else { self.lim_rel };
        self.lim_gain += (tg - self.lim_gain) * if tg < self.lim_gain { self.lim_atk } else { rel };
        let dl = self.la_buf_l[self.la_idx];
        let dr = self.la_buf_r[self.la_idx];
        self.la_buf_l[self.la_idx] = xl;
        self.la_buf_r[self.la_idx] = xr;
        self.la_idx += 1;
        if self.la_idx >= self.la_buf_l.len() {
            self.la_idx = 0;
        }
        let wl = soft_clip(dl * self.lim_gain);
        let wr = soft_clip(dr * self.lim_gain);

        let out_l = coerce(l + (wl - l) * self.mix, -1.0, 1.0);
        let out_r = coerce(r + (wr - r) * self.mix, -1.0, 1.0);
        (out_l, out_r)
    }

    /// Splits into low/mid/high (exact-reconstruction subtraction crossover), applies per-band
    /// gain, recombines.
    fn process_bands(&mut self, xl: f32, xr: f32) -> (f32, f32) {
        let low_l = self.lp1_l.process(xl);
        let rest_l = xl - low_l;
        let mid_l = self.lp2_l.process(rest_l);
        let high_l = rest_l - mid_l;

        let low_r = self.lp1_r.process(xr);
        let rest_r = xr - low_r;
        let mid_r = self.lp2_r.process(rest_r);
        let high_r = rest_r - mid_r;

        let warm = self.agc_warm > 0;
        if warm {
            self.agc_warm -= 1;
        }
        let atk_c = if warm { self.agc_warm_atk } else { self.agc_atk };
        let rel_c = if warm { self.agc_warm_rel } else { self.agc_rel };
        let peaks = [
            fmax(low_l.abs(), low_r.abs()),
            fmax(mid_l.abs(), mid_r.abs()),
            fmax(high_l.abs(), high_r.abs()),
        ];
        for b in 0..3 {
            let pk = peaks[b];
            self.band_env[b] += (pk - self.band_env[b]) * if pk > self.band_env[b] { self.band_atk[b] } else { self.band_rel[b] };
            self.band_agc[b] += (pk - self.band_agc[b]) * if pk > self.band_agc[b] { atk_c } else { rel_c };
        }

        self.agc_counter += 1;
        if self.agc_counter >= AGC_UPDATE_FRAMES {
            self.agc_counter = 0;
            let g_min = if self.applied_speaker { LEVEL_G_MIN_SPEAKER } else { LEVEL_G_MIN };
            for b in 0..3 {
                self.agc_gain[b] = coerce((LEVEL_TARGET[b] / fmax(self.band_agc[b], AGC_FLOOR)).powf(LEVEL_GAMMA), g_min, LEVEL_G_MAX);
            }
        }

        self.band_pow[0] += ((low_l * low_l + low_r * low_r) * 0.5 - self.band_pow[0]) * self.pow_coef;
        self.band_pow[1] += ((mid_l * mid_l + mid_r * mid_r) * 0.5 - self.band_pow[1]) * self.pow_coef;
        self.band_pow[2] += ((high_l * high_l + high_r * high_r) * 0.5 - self.band_pow[2]) * self.pow_coef;

        self.eq_counter += 1;
        if self.eq_counter >= EQ_UPDATE_FRAMES {
            self.eq_counter = 0;
            self.update_eq_targets();
        }
        for b in 0..3 {
            self.eq_gain[b] += (self.eq_target[b] - self.eq_gain[b]) * self.eq_smooth;
        }

        let g0 = self.band_gain(0);
        let g1 = self.band_gain(1);
        let g2 = self.band_gain(2);

        (low_l * g0 + mid_l * g1 + high_l * g2, low_r * g0 + mid_r * g1 + high_r * g2)
    }

    fn band_gain(&self, b: usize) -> f32 {
        let e = self.band_env[b];
        let mut g = self.agc_gain[b];
        let lvl = e * g;
        let lim = REG_LIMIT[b] * self.reg_scale;
        if lvl > lim {
            g *= (lim + (lvl - lim) / REG_RATIO) / lvl;
        }
        g * self.eq_gain[b]
    }

    /// Moves low/high toward the target tone relative to mid; leaves balanced songs alone.
    fn update_eq_targets(&mut self) {
        let p_mid = self.band_pow[1];
        if p_mid < 1e-8 {
            return;
        }
        let rel_low = 10.0 * ((self.band_pow[0] + 1e-12) / p_mid).log10();
        let rel_high = 10.0 * ((self.band_pow[2] + 1e-12) / p_mid).log10();
        let db_low = coerce((TARGET_LOW_DB - rel_low) * EQ_STRENGTH, -EQ_MAX_DB, EQ_MAX_DB);
        let db_high = coerce((TARGET_HIGH_DB - rel_high) * EQ_STRENGTH, -EQ_MAX_DB, EQ_MAX_DB);
        self.eq_target[0] = 10f64.powf(f64::from(db_low) / 20.0) as f32;
        self.eq_target[1] = 1.0;
        self.eq_target[2] = 10f64.powf(f64::from(db_high) / 20.0) as f32;
    }
}

fn soft_clip(x: f32) -> f32 {
    let a = x.abs();
    if a <= CLIP_KNEE {
        return x;
    }
    let y = CLIP_KNEE + (1.0 - CLIP_KNEE) * ((a - CLIP_KNEE) / (1.0 - CLIP_KNEE)).tanh();
    if x < 0.0 {
        -y
    } else {
        y
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn gain_at(c: Coeffs, w: f64) -> f64 {
        // |H(e^jw)| for H = (b0 + b1 z^-1 + b2 z^-2) / (1 + a1 z^-1 + a2 z^-2)
        let (b0, b1, b2, a1, a2) = (f64::from(c.b0), f64::from(c.b1), f64::from(c.b2), f64::from(c.a1), f64::from(c.a2));
        let (c1, s1, c2, s2) = (w.cos(), w.sin(), (2.0 * w).cos(), (2.0 * w).sin());
        let num = ((b0 + b1 * c1 + b2 * c2).powi(2) + (b1 * s1 + b2 * s2).powi(2)).sqrt();
        let den = ((1.0 + a1 * c1 + a2 * c2).powi(2) + (a1 * s1 + a2 * s2).powi(2)).sqrt();
        num / den
    }

    #[test]
    fn lowpass_and_highpass_have_the_expected_dc_and_nyquist_gain() {
        let lp = design_lowpass(250.0, 44100);
        assert!((gain_at(lp, 0.0) - 1.0).abs() < 1e-4);
        assert!(gain_at(lp, PI) < 1e-4);
        let hp = design_highpass(180.0, 48000);
        assert!(gain_at(hp, 0.0) < 1e-4);
        assert!((gain_at(hp, PI) - 1.0).abs() < 1e-4);
    }

    #[test]
    fn peaking_hits_its_gain_at_the_centre_and_is_flat_at_zero_db() {
        let c = design_peaking(1000.0, 6.0, 1.414, 44100);
        let w = 2.0 * PI * 1000.0 / 44100.0;
        assert!((20.0 * gain_at(c, w).log10() - 6.0).abs() < 0.01);
        let flat = design_peaking(1000.0, 0.0, 1.414, 44100);
        assert!((flat.b0 - 1.0).abs() < 1e-6 && (flat.b1 - flat.a1).abs() < 1e-6 && (flat.b2 - flat.a2).abs() < 1e-6);
    }

    #[test]
    fn high_shelf_is_unity_at_dc_and_reaches_its_gain_at_nyquist() {
        let c = design_high_shelf(SIDE_SHELF_HZ, SIDE_SHELF_DB, 44100);
        assert!((gain_at(c, 0.0) - 1.0).abs() < 1e-3);
        assert!((20.0 * gain_at(c, PI).log10() - SIDE_SHELF_DB).abs() < 0.05);
    }

    fn music(frames: usize, rate: f32) -> Vec<f32> {
        let mut seed = 12345u32;
        let mut out = Vec::with_capacity(frames * 2);
        for i in 0..frames {
            let t = i as f32 / rate;
            seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
            let noise = ((seed >> 9) as f32 / 8_388_608.0 - 1.0) * 0.02;
            let bass = (2.0 * std::f32::consts::PI * 80.0 * t).sin() * 0.25;
            let voice = (2.0 * std::f32::consts::PI * 880.0 * t).sin() * 0.2;
            let air = (2.0 * std::f32::consts::PI * 6000.0 * t).sin() * 0.05;
            out.push(bass + voice + noise);
            out.push(bass * 0.8 - voice * 0.6 + air - noise);
        }
        out
    }

    fn ready(rate: i32) -> DolbyAtmos {
        let mut a = DolbyAtmos::new();
        a.configure(rate, 2).unwrap();
        a.set_parameters(true, 3, 2, 0.5, false, false);
        a
    }

    #[test]
    fn off_is_a_bit_exact_copy() {
        let mut a = DolbyAtmos::new();
        a.configure(44100, 2).unwrap();
        assert!(a.will_bypass());
        let input = music(500, 44100.0);
        let mut out = vec![0.0; input.len()];
        assert_eq!(a.process_f32(&input, &mut out), input.len());
        assert_eq!(out, input);
    }

    #[test]
    fn unconfigured_or_non_stereo_is_passed_through() {
        let mut a = DolbyAtmos::new();
        a.set_parameters(true, 3, 0, 0.5, false, false);
        assert!(a.will_bypass());
        assert_eq!(a.configure(0, 2), Err(ConfigError::InvalidSampleRate(0)));
        assert!(a.will_bypass());
        assert!(a.configure(44100, 6).is_ok());
        assert!(a.will_bypass());
        assert_eq!(a.configure(44100, 0), Err(ConfigError::InvalidChannelCount(0)));
    }

    #[test]
    fn enabled_output_is_finite_bounded_and_audibly_different() {
        for rate in [44100, 48000] {
            let mut a = ready(rate);
            let input = music(rate as usize * 2, rate as f32);
            let mut out = vec![0.0; input.len()];
            a.process_f32(&input, &mut out);
            assert!(out.iter().all(|v| v.is_finite() && v.abs() <= 1.0));
            let tail = input.len() / 2;
            let diff: f32 = input[tail..].iter().zip(&out[tail..]).map(|(x, y)| (x - y).abs()).sum::<f32>() / (input.len() - tail) as f32;
            assert!(diff > 0.01, "rate {rate}: mean difference {diff}");
        }
    }

    #[test]
    fn loud_full_scale_input_stays_in_range() {
        let mut a = ready(44100);
        let input: Vec<f32> = (0..88200).map(|i| if (i / 2 / 50) % 2 == 0 { 1.0 } else { -1.0 }).collect();
        let mut out = vec![0.0; input.len()];
        a.process_f32(&input, &mut out);
        assert!(out.iter().all(|v| v.is_finite() && v.abs() <= 1.0));
    }

    #[test]
    fn dry_wet_mix_fades_in_from_the_dry_signal() {
        let mut a = ready(44100);
        let input = music(2000, 44100.0);
        let mut out = vec![0.0; input.len()];
        a.process_f32(&input, &mut out);
        let early: f32 = input[..40].iter().zip(&out[..40]).map(|(x, y)| (x - y).abs()).sum();
        let late: f32 = input[3000..3040].iter().zip(&out[3000..3040]).map(|(x, y)| (x - y).abs()).sum();
        assert!(early < late, "early {early} late {late}");
        assert!(!a.will_bypass());
    }

    #[test]
    fn disabling_fades_out_then_bypasses() {
        let mut a = ready(44100);
        let input = music(4000, 44100.0);
        let mut out = vec![0.0; input.len()];
        a.process_f32(&input, &mut out);
        a.set_parameters(false, 3, 2, 0.5, false, false);
        assert!(!a.will_bypass());
        for _ in 0..40 {
            a.process_f32(&input, &mut out);
        }
        assert!(a.will_bypass());
        a.process_f32(&input, &mut out);
        assert_eq!(out, input);
    }

    #[test]
    fn splitting_the_input_gives_identical_output() {
        let input = music(20000, 48000.0);
        let mut whole = ready(48000);
        let mut parts = ready(48000);
        let mut a = vec![0.0; input.len()];
        whole.process_f32(&input, &mut a);
        let mut b = vec![0.0; input.len()];
        for (src, dst) in input.chunks(2000).zip(b.chunks_mut(2000)) {
            parts.process_f32(src, dst);
        }
        assert_eq!(a, b);
    }

    #[test]
    fn s16_path_matches_float_path_within_quantisation() {
        let input = music(20000, 44100.0);
        let pcm: Vec<i16> = input.iter().map(|v| (v * 32768.0).clamp(-32768.0, 32767.0) as i16).collect();
        let back: Vec<f32> = pcm.iter().map(|&v| f32::from(v) / 32768.0).collect();
        let mut fa = ready(44100);
        let mut ia = ready(44100);
        let mut fo = vec![0.0; back.len()];
        let mut io = vec![0i16; pcm.len()];
        fa.process_f32(&back, &mut fo);
        ia.process_i16(&pcm, &mut io);
        let worst = fo.iter().zip(&io).map(|(f, &i)| (f - f32::from(i) / 32768.0).abs()).fold(0.0f32, f32::max);
        assert!(worst < 2.0 / 32768.0, "worst {worst}");
    }

    #[test]
    fn same_input_is_deterministic_and_state_carries_between_buffers() {
        let input = music(10000, 44100.0);
        let mut a = ready(44100);
        let mut b = ready(44100);
        let (mut oa, mut ob) = (vec![0.0; input.len()], vec![0.0; input.len()]);
        a.process_f32(&input, &mut oa);
        b.process_f32(&input, &mut ob);
        assert_eq!(oa, ob);
        let mut second = vec![0.0; input.len()];
        a.process_f32(&input, &mut second);
        assert_ne!(oa, second);
    }

    #[test]
    fn speaker_profile_is_applied_on_the_next_buffer_and_changes_the_sound() {
        let input = music(8000, 44100.0);
        let mut earphones = ready(44100);
        let mut speaker = ready(44100);
        speaker.set_parameters(true, 3, 2, 0.5, false, true);
        assert!(!speaker.applied_speaker());
        let (mut e, mut s) = (vec![0.0; input.len()], vec![0.0; input.len()]);
        earphones.process_f32(&input, &mut e);
        speaker.process_f32(&input, &mut s);
        assert!(speaker.applied_speaker() && !earphones.applied_speaker());
        assert_ne!(e, s);
    }

    #[test]
    fn settings_are_clamped_and_graphic_eq_length_is_checked() {
        let mut a = DolbyAtmos::new();
        a.set_parameters(true, 99, -5, 7.0, false, false);
        assert_eq!((a.dialogue_level, a.stereo_level, a.system_volume), (3, 0, 1.0));
        assert!(!a.set_graphic_eq(&[0.0; 9]));
        assert!(a.set_graphic_eq(&[0.0; 10]));
        assert!(a.geq_version > 0);
    }

    #[test]
    fn default_graphic_eq_curve_matches_kotlin_percent_scaling() {
        let a = DolbyAtmos::new();
        assert!((a.geq_gains_db[0] - 4.4).abs() < 1e-6);
        assert!((a.geq_gains_db[9] + 6.9).abs() < 1e-6);
    }

    #[test]
    fn a_flat_graphic_eq_sounds_different_from_the_default_curve() {
        let input = music(10000, 44100.0);
        let mut d = ready(44100);
        let mut f = ready(44100);
        assert!(f.set_graphic_eq(&[0.0; 10]));
        let (mut od, mut of) = (vec![0.0; input.len()], vec![0.0; input.len()]);
        d.process_f32(&input, &mut od);
        f.process_f32(&input, &mut of);
        assert_ne!(od, of);
    }
}
