use crate::crc32::crc32;
use crate::{
    i16_to_le_bytes, le_bytes_to_i16, resample_linear, resample_linear_le_bytes, signature_from_i16,
    signature_from_le_bytes, MAX_SIGNATURE_SAMPLES,
};

// The expected hashes below come from a line-by-line Java transliteration of the Kotlin code run on
// the same signals (see the test signals below, which use integer math only). They pin the port to
// the Kotlin algorithm. They are not output captured from the Kotlin code running on a device.

fn square(i: i64, period: i64, amp: i64) -> i64 {
    if (i / (period / 2)) % 2 == 0 {
        amp
    } else {
        -amp
    }
}

/// Noise plus saw and square tones, integer math only so every platform makes the same samples.
fn mix(n: usize, div: i64) -> Vec<i16> {
    let mut lcg: u64 = 0;
    (0..n)
        .map(|idx| {
            let i = idx as i64;
            lcg = (lcg * 1664525 + 1013904223) & 0xFFFF_FFFF;
            let noise = ((lcg >> 16) & 0x3FF) as i64 - 512;
            let s = noise
                + (i * 37 % 4096 - 2048)
                + (i * 211 % 2048 - 1024) * 2
                + square(i, 80, 3000)
                + square(i, 18, 1500)
                + square(i, 6, 800);
            (s / div).clamp(-32768, 32767) as i16
        })
        .collect()
}

fn extreme(n: usize) -> Vec<i16> {
    let mut lcg: u64 = 1;
    (0..n)
        .map(|_| {
            lcg = (lcg * 1664525 + 1013904223) & 0xFFFF_FFFF;
            if (lcg >> 31) & 1 == 1 {
                32767
            } else {
                -32768
            }
        })
        .collect()
}

fn fnv(data: &[u8]) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for &b in data {
        h ^= b as u64;
        h = h.wrapping_mul(0x0000_0100_0000_01b3);
    }
    h
}

fn assert_golden(name: &str, bytes: &[u8], len: usize, hash: u64) {
    assert_eq!(bytes.len(), len, "{name}: length");
    assert_eq!(fnv(bytes), hash, "{name}: fnv-1a hash");
}

#[test]
fn crc32_matches_known_vectors() {
    assert_eq!(crc32(b""), 0);
    assert_eq!(crc32(b"123456789"), 0xCBF4_3926);
    assert_eq!(crc32(b"The quick brown fox jumps over the lazy dog"), 0x414F_A339);
}

#[test]
fn signature_matches_kotlin_reference() {
    let cases: [(&str, Vec<i16>, usize, u64); 8] = [
        ("mix20s", mix(320_000, 1), 2984, 0xd972_9996_379d_fe02),
        ("mix3s", mix(48_000, 1), 796, 0x5282_afab_7975_a834),
        ("zero2s", vec![0; 32_000], 56, 0xd885_5b0f_093a_637a),
        ("short100", mix(100, 1), 56, 0x439a_0caf_f214_3c30),
        ("extreme4s", extreme(64_000), 3080, 0x9680_0a04_66d7_df76),
        ("boundary", mix(128 * 47 + 5, 1), 56, 0x157c_347c_5c69_046e),
        ("quiet5s", mix(80_000, 64), 1292, 0x9a0d_15eb_3d3f_4140),
        ("mix10s", mix(160_000, 2), 2516, 0xb976_20e4_4710_f2a3),
    ];
    for (name, pcm, len, hash) in cases {
        let sig = signature_from_i16(&pcm).expect(name);
        assert_golden(name, &sig, len, hash);
    }
}

#[test]
fn signature_header_and_crc_are_consistent() {
    let sig = signature_from_i16(&mix(48_000, 1)).unwrap();
    let word = |at: usize| u32::from_le_bytes(sig[at..at + 4].try_into().unwrap());
    assert_eq!(word(0), 0xcafe_2580);
    assert_eq!(word(12), 0x9411_9c00);
    assert_eq!(word(48), 0x4000_0000);
    // Size field covers everything after the 48-byte header, and the copy inside the body agrees.
    assert_eq!(word(8) as usize, sig.len() - 48);
    assert_eq!(word(52), word(8));
    assert_eq!(word(4), crc32(&sig[8..]));
    // Sample count field: 48000 samples plus 0.24 s at 16 kHz.
    assert_eq!(word(40), 48_000 + 3_840);
}

#[test]
fn signature_is_deterministic() {
    let pcm = mix(40_000, 1);
    assert_eq!(signature_from_i16(&pcm), signature_from_i16(&pcm));
}

#[test]
fn signature_bytes_api_matches_sample_api() {
    let pcm = mix(30_000, 1);
    assert_eq!(signature_from_le_bytes(&i16_to_le_bytes(&pcm)), signature_from_i16(&pcm));
}

#[test]
fn signature_rejects_input_kotlin_would_reject_or_cannot_bound() {
    assert!(signature_from_i16(&[]).is_none());
    assert!(signature_from_le_bytes(&[]).is_none());
    assert!(signature_from_le_bytes(&[1]).is_none());
    assert!(signature_from_le_bytes(&[1, 2, 3]).is_none());
    assert!(signature_from_i16(&vec![0i16; MAX_SIGNATURE_SAMPLES + 1]).is_none());
}

#[test]
fn resample_matches_kotlin_reference() {
    let cases: [(&str, i32, Vec<i16>, usize, u64); 9] = [
        ("44100", 44_100, mix(44_100 * 5, 1), 160_000, 0x1a6f_f211_e693_7c50),
        ("48000", 48_000, mix(48_000 * 5, 1), 160_000, 0x9431_d380_8c7b_1e75),
        ("8000", 8_000, mix(8_000 * 5, 1), 160_000, 0xc322_22ef_c5b6_8222),
        ("22050", 22_050, mix(22_050 * 3, 1), 96_000, 0x0268_efc7_e842_f1e0),
        ("16000", 16_000, mix(16_000, 1), 32_000, 0xad91_bd4d_0a2b_9f35),
        ("48000_1", 48_000, mix(1, 1), 0, 0xcbf2_9ce4_8422_2325),
        ("48000_2", 48_000, mix(2, 1), 0, 0xcbf2_9ce4_8422_2325),
        ("8000_3", 8_000, mix(3, 1), 12, 0xce8b_e50c_dee2_6b58),
        ("11025_extreme", 11_025, extreme(11_025), 32_000, 0x44ae_bc38_07e0_5bd4),
    ];
    for (name, rate, pcm, len, hash) in cases {
        let out = resample_linear(&pcm, rate, 16_000).expect(name);
        assert_golden(name, &i16_to_le_bytes(&out), len, hash);
    }
}

#[test]
fn resample_same_rate_is_identity() {
    let pcm = mix(5_000, 1);
    assert_eq!(resample_linear(&pcm, 16_000, 16_000).unwrap(), pcm);
}

#[test]
fn resample_rejects_bad_rates() {
    let pcm = mix(100, 1);
    assert!(resample_linear(&pcm, 0, 16_000).is_none());
    assert!(resample_linear(&pcm, -48_000, 16_000).is_none());
    assert!(resample_linear(&pcm, 48_000, 0).is_none());
    assert!(resample_linear(&pcm, 48_000, -1).is_none());
}

#[test]
fn resample_bytes_ignores_trailing_odd_byte() {
    let pcm = mix(4_800, 1);
    let mut bytes = i16_to_le_bytes(&pcm);
    let even = resample_linear_le_bytes(&bytes, 48_000, 16_000).unwrap();
    bytes.push(0x7f);
    assert_eq!(resample_linear_le_bytes(&bytes, 48_000, 16_000).unwrap(), even);
    assert_eq!(le_bytes_to_i16(&bytes), pcm);
}
