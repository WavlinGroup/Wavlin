/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.sound.nativeaudio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Makes an input buffer readable by [NativeAudio], which needs direct buffers. A direct buffer is
 * used in place; any other is copied into a reusable direct buffer. The input's position and limit
 * are never changed. Not thread-safe: use one instance per processor, from the audio thread.
 */
internal class DirectInputStage {

    private var stage: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

    /** Byte offset of the first input byte inside the buffer returned by the last [prepare]. */
    var offset: Int = 0
        private set

    fun prepare(input: ByteBuffer): ByteBuffer {
        if (input.isDirect) {
            offset = input.position()
            return input
        }
        val remaining = input.remaining()
        if (stage.capacity() < remaining) {
            stage = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        } else {
            stage.clear()
        }
        stage.put(input.duplicate())
        offset = 0
        return stage
    }
}
