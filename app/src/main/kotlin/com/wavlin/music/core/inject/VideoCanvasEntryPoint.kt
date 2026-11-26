/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.core.inject

import com.wavlin.music.media.canvas.VideoCanvasManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface VideoCanvasEntryPoint {
    fun videoCanvasManager(): VideoCanvasManager
}

