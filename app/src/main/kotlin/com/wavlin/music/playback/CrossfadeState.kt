/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.playback

/**
 * What the crossfade is doing right now. Published by [MusicService] from the same loop that sets
 * the player volumes, so the UI follows the real audio overlap and not a separate timer.
 */
sealed interface CrossfadeState {
    data object Idle : CrossfadeState

    /** [progress] runs 0..1 over the fade; 0 means the incoming track just started. */
    data class Fading(
        val outgoingMediaId: String?,
        val incomingMediaId: String?,
        val progress: Float,
    ) : CrossfadeState
}
