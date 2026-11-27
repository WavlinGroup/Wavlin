/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.nowplaying

import com.wavlin.music.models.MediaMetadata

/**
 * Remembers the track shown before the current one. Plain fields on purpose: it is updated while the
 * player composes and must never trigger a recomposition by itself.
 */
class MetadataHistory {
    var last: MediaMetadata? = null
        private set
    var previous: MediaMetadata? = null
        private set

    fun track(current: MediaMetadata) {
        if (last?.id == current.id) return
        previous = last
        last = current
    }
}
