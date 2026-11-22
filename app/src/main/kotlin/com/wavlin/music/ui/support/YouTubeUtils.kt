/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:Suppress("LocalVariableName")

package com.wavlin.music.ui.support

// Compiled once. resize() runs for every thumbnail on every composition, and building these regexes
// each call put Pattern.compile on the UI thread while Home rows scroll in.
private val GOOGLE_USER_CONTENT_RESIZE =
    "https://(?:lh3|yt3)\\.googleusercontent\\.com/.*=w(\\d+)-h(\\d+).*".toRegex()
private val YT3_GGPHT_SIZE = "https://yt3\\.ggpht\\.com/.*=s(\\d+)".toRegex()

fun String.resize(
    width: Int? = null,
    height: Int? = null,
): String {
    if (width == null && height == null) return this
    // Match BOTH lh3 and yt3 googleusercontent: YouTube migrated music/album art from
    // lh3.googleusercontent.com to yt3.googleusercontent.com. Both serve the same =wW-hH resize
    // params; matching only lh3 silently no-ops on the new host, so the player upscales the raw
    // ~60px thumbnail (blurry). Verified live: a yt3 URL + =w544-h544 returns a sharp full-size image.
    GOOGLE_USER_CONTENT_RESIZE
        .matchEntire(this)?.groupValues?.let { group ->
        val (W, H) = group.drop(1).map { it.toInt() }
        var w = width
        var h = height
        if (w != null && h == null) h = (w / W) * H
        if (w == null && h != null) w = (h / H) * W
        return "${split("=w")[0]}=w$w-h$h"
    }
    if (this matches YT3_GGPHT_SIZE) {
        return "$this-s${width ?: height}"
    }
    return this
}
