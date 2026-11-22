//! Silence tracking of `SilenceDetectorAudioProcessor.kt` (16-bit PCM, little-endian).
//!
//! A frame is silent when the peak of its channels is below the threshold. After
//! `min_silence_duration_us` of consecutive silent frames the segment counts as a long silence
//! and one notification is reported per segment. The callback itself stays in Kotlin.

use crate::ConfigError;

#[derive(Debug, Clone)]
pub struct SilenceDetector {
    min_silence_duration_us: i64,
    silence_threshold: i32,
    sample_rate: i64,
    channels: usize,
    consecutive_silent_frames: i64,
    in_silence: bool,
    notified_this_silence: bool,
}

impl SilenceDetector {
    /// Kotlin defaults are `2_000_000` microseconds and a threshold of `256`.
    pub fn new(min_silence_duration_us: i64, silence_threshold: i32) -> Self {
        Self {
            min_silence_duration_us,
            silence_threshold,
            sample_rate: 0,
            channels: 0,
            consecutive_silent_frames: 0,
            in_silence: false,
            notified_this_silence: false,
        }
    }

    /// An invalid format leaves the detector unconfigured (it then reports nothing).
    pub fn configure(&mut self, sample_rate: i32, channels: usize) -> Result<(), ConfigError> {
        self.sample_rate = 0;
        self.channels = 0;
        if sample_rate <= 0 {
            return Err(ConfigError::InvalidSampleRate(sample_rate));
        }
        if channels == 0 {
            return Err(ConfigError::InvalidChannelCount(channels));
        }
        self.sample_rate = i64::from(sample_rate);
        self.channels = channels;
        Ok(())
    }

    pub fn clear_state(&mut self) {
        self.consecutive_silent_frames = 0;
        self.in_silence = false;
        self.notified_this_silence = false;
    }

    pub fn is_silent(&self) -> bool {
        self.in_silence
    }

    /// Scans whole frames of 16-bit little-endian PCM and returns how many long-silence
    /// notifications fired. With `instant_mode` off, or before a valid `configure`, the state is
    /// cleared and nothing is reported.
    pub fn analyze(&mut self, input: &[u8], instant_mode: bool) -> u32 {
        if !instant_mode || self.sample_rate <= 0 || self.channels == 0 {
            self.clear_state();
            return 0;
        }
        let mut notifications = 0u32;
        for frame in input.chunks_exact(2 * self.channels) {
            let peak = frame
                .chunks_exact(2)
                .map(|s| i32::from(i16::from_le_bytes([s[0], s[1]])).abs())
                .max()
                .unwrap_or(0);
            if peak < self.silence_threshold {
                self.consecutive_silent_frames = self.consecutive_silent_frames.saturating_add(1);
                let silent_us = self.consecutive_silent_frames.saturating_mul(1_000_000) / self.sample_rate;
                if silent_us >= self.min_silence_duration_us {
                    self.in_silence = true;
                    if !self.notified_this_silence {
                        self.notified_this_silence = true;
                        notifications += 1;
                    }
                }
            } else {
                self.clear_state();
            }
        }
        notifications
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pcm(frames: usize, channels: usize, value: i16) -> Vec<u8> {
        std::iter::repeat(value.to_le_bytes()).take(frames * channels).flatten().collect()
    }

    fn detector(rate: i32, channels: usize) -> SilenceDetector {
        let mut d = SilenceDetector::new(2_000_000, 256);
        d.configure(rate, channels).unwrap();
        d
    }

    #[test]
    fn fires_exactly_at_the_minimum_duration() {
        let mut d = detector(48_000, 1);
        assert_eq!(d.analyze(&pcm(95_999, 1, 0), true), 0);
        assert!(!d.is_silent());
        assert_eq!(d.analyze(&pcm(1, 1, 0), true), 1);
        assert!(d.is_silent());
    }

    #[test]
    fn notifies_once_per_segment_and_rearms_after_sound() {
        let mut d = detector(48_000, 2);
        assert_eq!(d.analyze(&pcm(96_000, 2, 0), true), 1);
        assert_eq!(d.analyze(&pcm(48_000, 2, 0), true), 0);
        assert_eq!(d.analyze(&pcm(1, 2, 5000), true), 0);
        assert!(!d.is_silent());
        assert_eq!(d.analyze(&pcm(96_000, 2, 10), true), 1);
    }

    #[test]
    fn threshold_boundary_and_most_negative_sample() {
        let mut d = SilenceDetector::new(0, 256);
        d.configure(1000, 1).unwrap();
        assert_eq!(d.analyze(&pcm(1, 1, 255), true), 1);
        d.clear_state();
        assert_eq!(d.analyze(&pcm(1, 1, 256), true), 0);
        assert_eq!(d.analyze(&pcm(1, 1, -255), true), 1);
        d.clear_state();
        assert_eq!(d.analyze(&pcm(1, 1, i16::MIN), true), 0);
    }

    #[test]
    fn one_loud_channel_breaks_the_frame() {
        let mut d = SilenceDetector::new(0, 256);
        d.configure(1000, 2).unwrap();
        let mut frame = 0i16.to_le_bytes().to_vec();
        frame.extend_from_slice(&1000i16.to_le_bytes());
        assert_eq!(d.analyze(&frame, true), 0);
        assert!(!d.is_silent());
    }

    #[test]
    fn splitting_the_input_gives_the_same_result() {
        let data = pcm(96_000, 2, 0);
        let mut whole = detector(48_000, 2);
        let mut parts = detector(48_000, 2);
        let a = whole.analyze(&data, true);
        let b: u32 = data.chunks(2 * 2 * 1000).map(|c| parts.analyze(c, true)).sum();
        assert_eq!((a, b), (1, 1));
    }

    #[test]
    fn instant_mode_off_or_unconfigured_clears_and_reports_nothing() {
        let mut d = detector(48_000, 1);
        assert_eq!(d.analyze(&pcm(96_000, 1, 0), true), 1);
        assert_eq!(d.analyze(&pcm(10, 1, 0), false), 0);
        assert!(!d.is_silent());
        assert_eq!(d.analyze(&pcm(96_000, 1, 0), true), 1);

        let mut raw = SilenceDetector::new(0, 256);
        assert_eq!(raw.analyze(&pcm(10, 1, 0), true), 0);
    }

    #[test]
    fn invalid_configuration_is_rejected_and_disables_analysis() {
        let mut d = detector(48_000, 1);
        assert_eq!(d.configure(0, 1), Err(ConfigError::InvalidSampleRate(0)));
        assert_eq!(d.configure(44_100, 0), Err(ConfigError::InvalidChannelCount(0)));
        assert_eq!(d.analyze(&pcm(200_000, 1, 0), true), 0);
    }

    #[test]
    fn partial_trailing_frame_is_ignored() {
        let mut d = SilenceDetector::new(0, 256);
        d.configure(1000, 2).unwrap();
        assert_eq!(d.analyze(&[0, 0, 0], true), 0);
        assert!(!d.is_silent());
    }
}
