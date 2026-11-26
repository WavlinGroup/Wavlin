package com.wavlin.music.integration.identify

/**
 * Audio fingerprint generator for Shazam-compatible signatures.
 *
 * Pure Kotlin implementation — no native C++ or FFTW3 dependency required.
 * Uses [ShazamSignatureGenerator] which ports the vibra algorithm to JVM. [NativeShazam] runs the
 * same algorithm in Rust once it has matched the Kotlin output on this device.
 */
object VibraSignature {

    const val REQUIRED_SAMPLE_RATE = 16_000

    /**
     * Generates a Shazam signature from raw PCM audio data.
     *
     * @param samples Raw PCM audio data (mono, 16-bit signed little-endian, 16kHz)
     * @return The encoded signature URI string suitable for the Shazam API
     * @throws IllegalArgumentException if samples is empty or has odd length
     */
    @JvmStatic
    fun fromI16(samples: ByteArray): String {
        // Native code is used only after it has produced the same signature as Kotlin on this device.
        NativeShazam.trustedSignature(samples)?.let { return it }
        val signature = ShazamSignatureGenerator.fromI16(samples)
        if (NativeShazam.needsSignatureCheck) {
            NativeShazam.verifySignature(samples, signature)
        }
        return signature
    }
}
