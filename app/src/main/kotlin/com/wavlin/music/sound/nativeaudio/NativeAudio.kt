/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.sound.nativeaudio

import timber.log.Timber
import java.nio.ByteBuffer

/**
 * JNI bridge to `libwavlin_audio.so` (Rust crate `rust/crates/audio-jni`).
 *
 * The library is optional at runtime: when it is missing, [isAvailable] is false and the audio
 * processors keep using their Kotlin DSP. Handles are created once per processor and destroyed
 * once, when the processor is garbage collected.
 *
 * Buffers passed to the process functions must be direct. Offsets and lengths are in bytes.
 * Process functions return the bytes written, or -1 on failure.
 */
object NativeAudio {

    private const val TAG = "NativeAudio"

    @JvmField
    val isAvailable: Boolean = try {
        System.loadLibrary("wavlin_audio")
        true
    } catch (e: UnsatisfiedLinkError) {
        Timber.tag(TAG).w(e, "libwavlin_audio.so not loaded, using Kotlin audio DSP")
        false
    }

    @JvmStatic external fun reverbCreate(): Long
    @JvmStatic external fun reverbDestroy(handle: Long)
    @JvmStatic external fun reverbSetParameters(handle: Long, room: Float, damping: Float, wetLevel: Float)
    @JvmStatic external fun reverbSetEnabled(handle: Long, enabled: Boolean)
    @JvmStatic external fun reverbConfigure(handle: Long, sampleRate: Int, channelCount: Int): Boolean
    @JvmStatic external fun reverbClearState(handle: Long)
    @JvmStatic external fun reverbProcess(
        handle: Long,
        input: ByteBuffer,
        inputOffset: Int,
        length: Int,
        output: ByteBuffer,
        isFloat: Boolean,
    ): Int

    @JvmStatic external fun eqCreate(): Long
    @JvmStatic external fun eqDestroy(handle: Long)

    /**
     * @param params frequency, gain, q for each band, flattened
     * @param types `FilterType.ordinal` for each band
     * @return surviving filter count, or -1 when the profile could not be applied
     */
    @JvmStatic external fun eqApplyProfile(
        handle: Long,
        sampleRate: Int,
        channelCount: Int,
        preampDb: Double,
        params: DoubleArray,
        types: IntArray,
        enabled: BooleanArray,
    ): Int

    @JvmStatic external fun eqDisable(handle: Long)
    @JvmStatic external fun eqResetState(handle: Long)
    @JvmStatic external fun eqProcess(handle: Long, input: ByteBuffer, inputOffset: Int, length: Int, output: ByteBuffer): Int

    /** Sample format codes for [normalizerProcess]. These are this bridge's own codes, not Media3 constants. */
    const val FORMAT_S16 = 0
    const val FORMAT_S24 = 1
    const val FORMAT_S32 = 2
    const val FORMAT_FLOAT = 3

    @JvmStatic external fun normalizerCreate(): Long
    @JvmStatic external fun normalizerDestroy(handle: Long)
    @JvmStatic external fun normalizerSetEnabled(handle: Long, enabled: Boolean)
    @JvmStatic external fun normalizerSetTargetGain(handle: Long, gainMb: Int)

    /** [length] must be a whole number of samples. Returns the bytes written to [output], or -1. */
    @JvmStatic external fun normalizerProcess(
        handle: Long,
        input: ByteBuffer,
        inputOffset: Int,
        length: Int,
        output: ByteBuffer,
        formatCode: Int,
    ): Int

    @JvmStatic external fun silenceCreate(minSilenceDurationUs: Long, silenceThreshold: Int): Long
    @JvmStatic external fun silenceDestroy(handle: Long)
    @JvmStatic external fun silenceConfigure(handle: Long, sampleRate: Int, channelCount: Int): Boolean
    @JvmStatic external fun silenceClearState(handle: Long)
    @JvmStatic external fun silenceIsSilent(handle: Long): Boolean

    /** Reads 16-bit PCM without modifying it. Returns the long-silence notifications to deliver, or -1. */
    @JvmStatic external fun silenceAnalyze(
        handle: Long,
        input: ByteBuffer,
        inputOffset: Int,
        length: Int,
        instantMode: Boolean,
    ): Int
}
