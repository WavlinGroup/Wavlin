/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.support

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember

@Composable
fun LazyListState.isScrollingUp(): Boolean {
    // Plain array, not state: the derivation below runs on every scroll frame, and writing snapshot
    // state from inside it re-triggered the derivation and the apply observers each frame.
    val previous = remember(this) { intArrayOf(firstVisibleItemIndex, firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            val index = firstVisibleItemIndex
            val offset = firstVisibleItemScrollOffset
            if (previous[0] != index) {
                previous[0] > index
            } else {
                previous[1] >= offset
            }.also {
                previous[0] = index
                previous[1] = offset
            }
        }
    }.value
}

@Composable
fun LazyGridState.isScrollingUp(): Boolean {
    // Plain array, not state: the derivation below runs on every scroll frame, and writing snapshot
    // state from inside it re-triggered the derivation and the apply observers each frame.
    val previous = remember(this) { intArrayOf(firstVisibleItemIndex, firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            val index = firstVisibleItemIndex
            val offset = firstVisibleItemScrollOffset
            if (previous[0] != index) {
                previous[0] > index
            } else {
                previous[1] >= offset
            }.also {
                previous[0] = index
                previous[1] = offset
            }
        }
    }.value
}

@Composable
fun ScrollState.isScrollingUp(): Boolean {
    val previousScrollOffset = remember(this) { intArrayOf(value) }
    return remember(this) {
        derivedStateOf {
            val offset = value
            (previousScrollOffset[0] >= offset).also {
                previousScrollOffset[0] = offset
            }
        }
    }.value
}
