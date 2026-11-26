//! Linear-interpolation resampler, a port of the loop in `AudioResampler.kt`.
//!
//! Only IEEE add, multiply and divide are used, so the output matches Kotlin exactly.

use crate::{i16_to_le_bytes, le_bytes_to_i16, MAX_SAMPLES};

/// Resamples mono PCM from `input_rate` to `output_rate`. `None` when a rate is not positive, the
/// output would be unreasonably large, or the Kotlin loop would have indexed past the input (it
/// throws there), so Kotlin runs its own code in those cases.
pub fn resample_linear(input: &[i16], input_rate: i32, output_rate: i32) -> Option<Vec<i16>> {
    if input_rate <= 0 || output_rate <= 0 || input.len() > MAX_SAMPLES {
        return None;
    }

    let ratio = output_rate as f64 / input_rate as f64;
    let output_length = (input.len() as f64 * ratio) as i32;
    let output_length = usize::try_from(output_length).ok()?;
    if output_length > MAX_SAMPLES {
        return None;
    }

    // A failed allocation would abort the process, so ask for the memory in a way that can fail.
    let mut output: Vec<i16> = Vec::new();
    output.try_reserve_exact(output_length).ok()?;
    for i in 0..output_length {
        let src_pos = i as f64 / ratio;
        let src_index = src_pos as i32 as usize;
        let fraction = src_pos - src_index as f64;
        let sample = if src_index + 1 < input.len() {
            (input[src_index] as f64 * (1.0 - fraction) + input[src_index + 1] as f64 * fraction) as i32 as i16
        } else if src_index < input.len() {
            input[src_index]
        } else {
            return None;
        };
        output.push(sample);
    }
    Some(output)
}

/// Same as [`resample_linear`] on little-endian 16-bit bytes. A trailing odd byte is ignored, as
/// `ByteBuffer.asShortBuffer()` does in Kotlin.
pub fn resample_linear_le_bytes(input: &[u8], input_rate: i32, output_rate: i32) -> Option<Vec<u8>> {
    let samples = le_bytes_to_i16(input);
    let output = resample_linear(&samples, input_rate, output_rate)?;
    Some(i16_to_le_bytes(&output))
}
