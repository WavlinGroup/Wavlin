//! Shazam signature generator, a port of `ShazamSignatureGenerator.kt` (itself a port of vibra).
//!
//! Input is mono 16 kHz signed 16-bit PCM. Output is the raw signature buffer; Kotlin adds the
//! `data:audio/vnd.shazam.sig;base64,` prefix and the Base64 encoding.

use crate::crc32::crc32;
use crate::fft::{Rfft, FFT_OUTPUT_SIZE, FFT_SIZE};
use crate::{le_bytes_to_i16, MAX_SIGNATURE_SAMPLES, REQUIRED_SAMPLE_RATE};
use std::f64::consts::PI;

const RING_BUF_SIZE: usize = 256;
const MAX_PEAKS: i32 = 255;
const MAX_TIME_SECONDS: f64 = 12.0;
const SAMPLES_PER_PASS: usize = 128;

const BAND_250_520: usize = 0;
const BAND_520_1450: usize = 1;
const BAND_1450_3500: usize = 2;
const BAND_3500_5500: usize = 3;

const TIME_SPREAD_OFFSETS: [i32; 3] = [-1, -3, -6];
const NEIGHBOR_OFFSETS: [i32; 8] = [-10, -7, -4, -3, 1, 2, 5, 8];
const OTHER_OFFSETS: [i32; 14] = [-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249];

struct FrequencyPeak {
    fft_pass_number: i32,
    peak_magnitude: i32,
    corrected_peak_frequency_bin: i32,
}

struct Generator {
    hanning: Vec<f64>,
    fft: Rfft,
    windowed: Box<[f64; FFT_SIZE]>,
    samples_ring: Vec<i32>,
    samples_pos: usize,
    fft_outputs: Vec<f64>,
    fft_pos: usize,
    spread_ffts: Vec<f64>,
    spread_pos: usize,
    spread_num_written: i32,
    spread_scratch: Vec<f64>,
    num_samples: i32,
    band_peaks: [Vec<FrequencyPeak>; 4],
    total_peaks: i32,
}

/// Ring index `(base + offset) mod RING_BUF_SIZE` for a possibly negative offset.
fn ring_index(base: usize, offset: i32) -> usize {
    (base as i32 + offset).rem_euclid(RING_BUF_SIZE as i32) as usize
}

fn row(buf: &[f64], index: usize) -> &[f64] {
    &buf[index * FFT_OUTPUT_SIZE..(index + 1) * FFT_OUTPUT_SIZE]
}

impl Generator {
    fn new() -> Self {
        // w[i] = 0.5 * (1 - cos(2*pi*(i+1)/2049))
        let hanning = (0..FFT_SIZE).map(|i| 0.5 * (1.0 - (2.0 * PI * (i as f64 + 1.0) / 2049.0).cos())).collect();
        Generator {
            hanning,
            fft: Rfft::new(),
            windowed: Box::new([0.0; FFT_SIZE]),
            samples_ring: vec![0; FFT_SIZE],
            samples_pos: 0,
            fft_outputs: vec![0.0; RING_BUF_SIZE * FFT_OUTPUT_SIZE],
            fft_pos: 0,
            spread_ffts: vec![0.0; RING_BUF_SIZE * FFT_OUTPUT_SIZE],
            spread_pos: 0,
            spread_num_written: 0,
            spread_scratch: vec![0.0; FFT_OUTPUT_SIZE],
            num_samples: 0,
            band_peaks: [Vec::new(), Vec::new(), Vec::new(), Vec::new()],
            total_peaks: 0,
        }
    }

    fn process(&mut self, pcm: &[i16]) {
        let mut offset = 0usize;
        while offset + SAMPLES_PER_PASS <= pcm.len() {
            // Same stopping rule as the C++ original: both the time and the peak count must be reached.
            let elapsed_sec = self.num_samples as f64 / REQUIRED_SAMPLE_RATE as f64;
            if elapsed_sec >= MAX_TIME_SECONDS && self.total_peaks >= MAX_PEAKS {
                break;
            }

            self.num_samples += SAMPLES_PER_PASS as i32;
            self.feed_samples(&pcm[offset..offset + SAMPLES_PER_PASS]);
            self.do_fft();
            self.do_peak_spreading_and_recognition();
            offset += SAMPLES_PER_PASS;
        }
    }

    fn feed_samples(&mut self, block: &[i16]) {
        for &s in block {
            self.samples_ring[self.samples_pos] = s as i32;
            self.samples_pos = (self.samples_pos + 1) % FFT_SIZE;
        }
    }

    fn do_fft(&mut self) {
        for i in 0..FFT_SIZE {
            self.windowed[i] = self.samples_ring[(self.samples_pos + i) % FFT_SIZE] as f64 * self.hanning[i];
        }
        let start = self.fft_pos * FFT_OUTPUT_SIZE;
        self.fft.magnitudes(&self.windowed, &mut self.fft_outputs[start..start + FFT_OUTPUT_SIZE]);
        self.fft_pos = (self.fft_pos + 1) % RING_BUF_SIZE;
    }

    fn do_peak_spreading_and_recognition(&mut self) {
        self.do_peak_spreading();
        if self.spread_num_written >= 47 {
            self.do_peak_recognition();
        }
    }

    fn do_peak_spreading(&mut self) {
        let last_fft_idx = (self.fft_pos + RING_BUF_SIZE - 1) % RING_BUF_SIZE;
        self.spread_scratch.copy_from_slice(row(&self.fft_outputs, last_fft_idx));

        // Frequency spreading: 3-point running max, forward pass.
        let spread = &mut self.spread_scratch;
        for pos in 0..FFT_OUTPUT_SIZE - 2 {
            spread[pos] = spread[pos].max(spread[pos + 1].max(spread[pos + 2]));
        }

        // Time spreading: only the older entries change; the new entry keeps frequency spreading only.
        let indices = TIME_SPREAD_OFFSETS.map(|offset| ring_index(self.spread_pos, offset));
        for pos in 0..FFT_OUTPUT_SIZE {
            let mut max_val = spread[pos];
            for &idx in &indices {
                let cell = &mut self.spread_ffts[idx * FFT_OUTPUT_SIZE + pos];
                if *cell > max_val {
                    max_val = *cell;
                }
                *cell = max_val;
            }
        }

        let start = self.spread_pos * FFT_OUTPUT_SIZE;
        self.spread_ffts[start..start + FFT_OUTPUT_SIZE].copy_from_slice(&self.spread_scratch);
        self.spread_pos = (self.spread_pos + 1) % RING_BUF_SIZE;
        self.spread_num_written += 1;
    }

    fn do_peak_recognition(&mut self) {
        let fft_minus_46 = row(&self.fft_outputs, (self.fft_pos + RING_BUF_SIZE * 2 - 46) % RING_BUF_SIZE);
        let spread_minus_49 = row(&self.spread_ffts, (self.spread_pos + RING_BUF_SIZE * 2 - 49) % RING_BUF_SIZE);
        let other_rows = OTHER_OFFSETS.map(|offset| ring_index(self.spread_pos, offset));

        for bin_pos in 10..FFT_OUTPUT_SIZE - 8 {
            let fft_val = fft_minus_46[bin_pos];
            if fft_val < 1.0 / 64.0 || fft_val < spread_minus_49[bin_pos] {
                continue;
            }

            let mut max_neighbor_spread49 = 0.0f64;
            for offset in NEIGHBOR_OFFSETS {
                let v = spread_minus_49[(bin_pos as i32 + offset) as usize];
                if v > max_neighbor_spread49 {
                    max_neighbor_spread49 = v;
                }
            }
            if fft_val <= max_neighbor_spread49 {
                continue;
            }

            let mut max_neighbor_other = max_neighbor_spread49;
            for &spread_idx in &other_rows {
                let v = self.spread_ffts[spread_idx * FFT_OUTPUT_SIZE + bin_pos - 1];
                if v > max_neighbor_other {
                    max_neighbor_other = v;
                }
            }
            if fft_val <= max_neighbor_other {
                continue;
            }

            let fft_number = self.spread_num_written - 46;

            let peak_mag = fft_val.max(1.0 / 64.0).ln() * 1477.3 + 6144.0;
            let peak_mag_before = fft_minus_46[bin_pos - 1].max(1.0 / 64.0).ln() * 1477.3 + 6144.0;
            let peak_mag_after = fft_minus_46[bin_pos + 1].max(1.0 / 64.0).ln() * 1477.3 + 6144.0;

            let peak_variation1 = peak_mag * 2.0 - peak_mag_before - peak_mag_after;
            let peak_variation2 = (peak_mag_after - peak_mag_before) * 32.0 / peak_variation1;

            let corrected_bin = bin_pos as f64 * 64.0 + peak_variation2;
            let frequency_hz = corrected_bin * (16000.0 / 2.0 / 1024.0 / 64.0);

            // Written as a chain of comparisons, like the Kotlin `when`, so NaN and infinity fall out
            // of the same branches.
            let band = if frequency_hz < 250.0 {
                continue;
            } else if frequency_hz < 520.0 {
                BAND_250_520
            } else if frequency_hz < 1450.0 {
                BAND_520_1450
            } else if frequency_hz < 3500.0 {
                BAND_1450_3500
            } else if frequency_hz <= 5500.0 {
                BAND_3500_5500
            } else {
                continue;
            };

            self.band_peaks[band].push(FrequencyPeak {
                fft_pass_number: fft_number,
                peak_magnitude: peak_mag as i32,
                corrected_peak_frequency_bin: corrected_bin as i32,
            });
            self.total_peaks += 1;
        }
    }

    fn encode(&self) -> Vec<u8> {
        let mut contents: Vec<u8> = Vec::new();

        for band_id in 0..4usize {
            let peaks = &self.band_peaks[band_id];
            if peaks.is_empty() {
                continue;
            }

            let mut peak_buf: Vec<u8> = Vec::new();
            let mut prev_fft_pass_number = 0i32;

            for peak in peaks {
                let diff = peak.fft_pass_number - prev_fft_pass_number;
                if diff >= 255 {
                    peak_buf.push(0xFF);
                    write_le32(&mut peak_buf, peak.fft_pass_number);
                    prev_fft_pass_number = peak.fft_pass_number;
                }
                peak_buf.push((peak.fft_pass_number - prev_fft_pass_number) as u8);
                write_le16(&mut peak_buf, peak.peak_magnitude);
                write_le16(&mut peak_buf, peak.corrected_peak_frequency_bin);
                prev_fft_pass_number = peak.fft_pass_number;
            }

            write_le32(&mut contents, 0x6003_0040 + band_id as i32);
            write_le32(&mut contents, peak_buf.len() as i32);
            contents.extend_from_slice(&peak_buf);

            let pad_bytes = (4 - peak_buf.len() % 4) % 4;
            contents.extend(std::iter::repeat(0u8).take(pad_bytes));
        }

        let size_minus_header = contents.len() as i32 + 8;
        let samples_and_offset = (self.num_samples as f64 + REQUIRED_SAMPLE_RATE as f64 * 0.24) as i32;

        let mut full: Vec<u8> = Vec::with_capacity(56 + contents.len());
        write_le32(&mut full, 0xcafe_2580u32 as i32); // magic1
        write_le32(&mut full, 0); // crc32 placeholder
        write_le32(&mut full, size_minus_header);
        write_le32(&mut full, 0x9411_9c00u32 as i32); // magic2
        write_le32(&mut full, 0);
        write_le32(&mut full, 0);
        write_le32(&mut full, 0);
        write_le32(&mut full, 3 << 27); // shifted_sample_rate_id
        write_le32(&mut full, 0);
        write_le32(&mut full, 0);
        write_le32(&mut full, samples_and_offset);
        write_le32(&mut full, (15 << 19) + 0x40000); // fixed_value
        write_le32(&mut full, 0x4000_0000);
        write_le32(&mut full, contents.len() as i32 + 8);
        full.extend_from_slice(&contents);

        // CRC over everything after magic1 and the crc field itself.
        let crc = crc32(&full[8..]);
        full[4..8].copy_from_slice(&crc.to_le_bytes());
        full
    }
}

fn write_le32(out: &mut Vec<u8>, value: i32) {
    out.extend_from_slice(&value.to_le_bytes());
}

/// Low 16 bits of `value`, little-endian, like `writeLittleEndian16` in Kotlin.
fn write_le16(out: &mut Vec<u8>, value: i32) {
    out.extend_from_slice(&(value as u32 as u16).to_le_bytes());
}

/// Signature buffer for mono 16 kHz PCM. `None` for empty input or input longer than ten minutes.
pub fn signature_from_i16(pcm: &[i16]) -> Option<Vec<u8>> {
    if pcm.is_empty() || pcm.len() > MAX_SIGNATURE_SAMPLES {
        return None;
    }
    let mut generator = Generator::new();
    generator.process(pcm);
    Some(generator.encode())
}

/// Same as [`signature_from_i16`] for little-endian bytes. Kotlin requires an even, non-empty
/// length, so anything else is `None` and Kotlin throws its own exception.
pub fn signature_from_le_bytes(bytes: &[u8]) -> Option<Vec<u8>> {
    if bytes.len() < 2 || bytes.len() % 2 != 0 {
        return None;
    }
    signature_from_i16(&le_bytes_to_i16(bytes))
}
