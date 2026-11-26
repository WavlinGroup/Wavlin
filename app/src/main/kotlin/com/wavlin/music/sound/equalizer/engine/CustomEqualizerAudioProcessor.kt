package com.wavlin.music.sound.equalizer.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.wavlin.music.sound.equalizer.profiles.ParametricEQ
import com.wavlin.music.sound.equalizer.profiles.ParametricEQBand
import com.wavlin.music.sound.nativeaudio.NativeAudio
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Custom audio processor for ExoPlayer that applies parametric EQ using biquad filters
 * Uses ParametricEQ format from AutoEQ project
 */
@UnstableApi
@SuppressWarnings("Deprecated")
class CustomEqualizerAudioProcessor : AudioProcessor {

    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var isActive = false
    private var equalizerEnabled = false

    private var inputBuffer: ByteBuffer = EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    private var filters: List<BiquadFilter> = emptyList()
    private var preampGain: Double = 1.0  // Linear preamp gain multiplier
    private var pendingProfile: ParametricEQ? = null

    // Rust DSP handle (0 = library missing, Kotlin filters are used instead). nativeActive is true
    // only while the last profile was applied successfully in Rust; then `filters` stays empty.
    private val nativeHandle: Long = if (NativeAudio.isAvailable) NativeAudio.eqCreate() else 0L
    private var nativeActive = false
    private var nativeFilterCount = 0
    private var inputStage: ByteBuffer = EMPTY_BUFFER

    companion object {
        private const val TAG = "CustomEqualizerAudioProcessor"
        private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }

    /**
     * Apply an EQ profile
     */
    @Synchronized
    fun applyProfile(parametricEQ: ParametricEQ) {
        if (sampleRate == 0) {
            // Audio processor not configured yet, store as pending
            Timber.tag(TAG)
                .d("Audio processor not configured yet. Storing profile as pending with ${parametricEQ.bands.size} bands")
            pendingProfile = parametricEQ
            return
        }

        // Convert preamp from dB to linear gain
        preampGain = 10.0.pow(parametricEQ.preamp / 20.0)

        createFilters(parametricEQ.bands, parametricEQ.preamp)
        equalizerEnabled = true

        // Reset filter states to ensure clean transition
        filters.forEach { it.reset() }
        if (nativeActive) NativeAudio.eqResetState(nativeHandle)

        Timber.tag(TAG)
            .d("Applied EQ profile with ${activeFilterCount()} bands and ${parametricEQ.preamp} dB preamp")
    }

    /**
     * Disable the equalizer
     */
    @Synchronized
    fun disable() {
        equalizerEnabled = false
        filters = emptyList()
        preampGain = 1.0
        pendingProfile = null
        nativeActive = false
        nativeFilterCount = 0
        if (nativeHandle != 0L) NativeAudio.eqDisable(nativeHandle)
        Timber.tag(TAG).d("Equalizer disabled")
    }

    /**
     * Check if equalizer is enabled
     */
    fun isEnabled(): Boolean = equalizerEnabled

    /**
     * Create biquad filters from ParametricEQ bands
     * Only creates filters for enabled bands below Nyquist frequency
     * Supports PK (peaking), LSC (low-shelf), and HSC (high-shelf) filter types
     */
    private fun createFilters(bands: List<ParametricEQBand>, preampDb: Double) {
        if (sampleRate == 0) {
            Timber.tag(TAG).w("Cannot create filters: sample rate not set")
            return
        }

        if (nativeHandle != 0L && applyNative(bands, preampDb)) {
            filters = emptyList()
            return
        }

        // Filter out disabled bands and frequencies above Nyquist limit
        filters = bands
            .filter { it.enabled && it.frequency < sampleRate / 2.0 }
            .map { band ->
                BiquadFilter(
                    sampleRate = sampleRate,
                    frequency = band.frequency,
                    gain = band.gain,
                    q = band.q,
                    filterType = band.filterType
                )
            }

        Timber.tag(TAG)
            .d("Created ${filters.size} biquad filters from ${bands.size} bands (PK/LSC/HSC)")
    }

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding

        Timber.tag(TAG)
            .d("Configured: sampleRate=$sampleRate, channels=$channelCount, encoding=$encoding")

        // Apply pending profile if one exists
        pendingProfile?.let { profile ->
            preampGain = 10.0.pow(profile.preamp / 20.0)
            createFilters(profile.bands, profile.preamp)
            equalizerEnabled = true
            pendingProfile = null
            Timber.tag(TAG)
                .d("Applied pending profile with ${activeFilterCount()} bands and ${profile.preamp} dB preamp")
        }

        // Only support 16-bit PCM stereo/mono
        if (encoding != C.ENCODING_PCM_16BIT || channelCount > 2) {
            val exception = AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            throw exception // Rethrow, unsupported
        }

        isActive = true
        return inputAudioFormat
    }

    override fun isActive(): Boolean = isActive

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!equalizerEnabled || activeFilterCount() == 0) {
            // Passthrough mode - directly use input as output
            val remaining = inputBuffer.remaining()
            if (remaining == 0) return

            // Ensure output buffer is large enough
            if (outputBuffer.capacity() < remaining) {
                outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
            } else {
                outputBuffer.clear()
            }
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val inputSize = inputBuffer.remaining()
        if (inputSize == 0) {
            return
        }

        // Ensure we have our own output buffer (reuse if possible to avoid allocations)
        // Note: We MUST NOT use inputBuffer as outputBuffer if we modify it
        if (outputBuffer === EMPTY_BUFFER || outputBuffer === inputBuffer) {
            // Need new buffer - was empty or same as input
            outputBuffer = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder())
        } else if (outputBuffer.capacity() < inputSize) {
            // Need larger buffer
            outputBuffer = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder())
        } else {
            // Reuse existing buffer (most common path)
            outputBuffer.clear()
        }

        // Process audio samples
        when (encoding) {
            C.ENCODING_PCM_16BIT -> if (nativeActive) {
                if (!processNative(inputBuffer, outputBuffer, inputSize)) outputBuffer.put(inputBuffer)
            } else {
                // Ensure the output buffer is ready to receive data
                // We don't set limit() here because putShort will advance position
                processAudioBuffer16Bit(inputBuffer, outputBuffer)
            }
            else -> {
                // Unsupported format, passthrough
                outputBuffer.put(inputBuffer)
            }
        }

        outputBuffer.flip()
        // inputBuffer position is already updated by processAudioBuffer16Bit/put
    }

    /**
     * Process 16-bit PCM audio through all biquad filters
     */
    private fun processAudioBuffer16Bit(input: ByteBuffer, output: ByteBuffer) {
        // Ensure we are reading from the current position
        // Input is ready to be read from position() to limit()
        // Output is ready to be written to from position()

        val sampleCount = input.remaining() / 2 // 2 bytes per 16-bit sample

        repeat(sampleCount / channelCount) {
            when (channelCount) {
                1 -> {
                    // Mono
                    val sample = input.getShort().toDouble() / 32768.0 // Normalize to [-1, 1]
                    var processed = sample

                    // Apply all filters in series
                    for (filter in filters) {
                        processed = filter.processSample(processed)
                    }

                    // Apply preamp gain
                    processed *= preampGain

                    // Clamp and convert back to 16-bit
                    val outputSample = (processed * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
                    output.putShort(outputSample)
                }
                2 -> {
                    // Stereo
                    val leftSample = input.getShort().toDouble() / 32768.0
                    val rightSample = input.getShort().toDouble() / 32768.0

                    var processedLeft = leftSample
                    var processedRight = rightSample

                    // Apply all filters in series
                    for (filter in filters) {
                        val (left, right) = filter.processStereo(processedLeft, processedRight)
                        processedLeft = left
                        processedRight = right
                    }

                    // Apply preamp gain
                    processedLeft *= preampGain
                    processedRight *= preampGain

                    // Clamp and convert back to 16-bit
                    val outputLeft = (processedLeft * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
                    val outputRight = (processedRight * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort()

                    output.putShort(outputLeft)
                    output.putShort(outputRight)
                }
                else -> {
                    // Should not happen as configure rejects > 2 channels
                    repeat(channelCount) {
                        output.putShort(input.getShort())
                    }
                }
            }
        }
    }

    private fun activeFilterCount(): Int = if (nativeActive) nativeFilterCount else filters.size

    /** Hands the profile to Rust. Returns false (Kotlin filters take over) when Rust rejects it. */
    private fun applyNative(bands: List<ParametricEQBand>, preampDb: Double): Boolean {
        val params = DoubleArray(bands.size * 3)
        val types = IntArray(bands.size)
        val enabled = BooleanArray(bands.size)
        bands.forEachIndexed { i, band ->
            params[i * 3] = band.frequency
            params[i * 3 + 1] = band.gain
            params[i * 3 + 2] = band.q
            types[i] = band.filterType.ordinal
            enabled[i] = band.enabled
        }
        val count = NativeAudio.eqApplyProfile(nativeHandle, sampleRate, channelCount, preampDb, params, types, enabled)
        nativeActive = count >= 0
        nativeFilterCount = count.coerceAtLeast(0)
        if (!nativeActive) Timber.tag(TAG).w("Rust EQ rejected the profile, using Kotlin filters")
        else Timber.tag(TAG).d("Created $count native biquad filters from ${bands.size} bands")
        return nativeActive
    }

    /** Runs the Rust EQ on [input] into [output]. Returns false when the input must be passed through. */
    private fun processNative(input: ByteBuffer, output: ByteBuffer, inputSize: Int): Boolean {
        val source: ByteBuffer
        val sourceOffset: Int
        if (input.isDirect) {
            source = input
            sourceOffset = input.position()
        } else {
            if (inputStage.capacity() < inputSize) {
                inputStage = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder())
            } else {
                inputStage.clear()
            }
            inputStage.put(input.duplicate())
            source = inputStage
            sourceOffset = 0
        }
        val written = NativeAudio.eqProcess(nativeHandle, source, sourceOffset, inputSize, output)
        if (written < 0) return false
        input.position(input.limit())
        output.position(written)
        return true
    }

    @Suppress("unused", "ProtectedInFinal")
    protected fun finalize() {
        if (nativeHandle != 0L) NativeAudio.eqDestroy(nativeHandle)
    }

    override fun getOutput(): ByteBuffer {
        // Return output buffer ready for reading (already flipped in queueInput)
        val buffer = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return buffer
    }

    override fun isEnded(): Boolean {
        return inputEnded && outputBuffer.remaining() == 0
    }

    @Deprecated("Deprecated in Java")
    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false

        // Reset filter states
        filters.forEach { it.reset() }
        if (nativeActive) NativeAudio.eqResetState(nativeHandle)
    }

    override fun reset() {
        @Suppress("DEPRECATION")
        flush()
        inputBuffer = EMPTY_BUFFER
        sampleRate = 0
        channelCount = 0
        encoding = C.ENCODING_INVALID
        isActive = false
        filters.forEach { it.reset() }
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }
}
