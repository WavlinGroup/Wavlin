/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.core.util

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PerformanceHintManager
import android.os.Process
import android.view.FrameMetrics
import android.view.Window
import timber.log.Timber

/**
 * Tells the system how long each UI frame really took, through the Android Dynamic Performance
 * Framework hint session, so the scheduler raises CPU frequency and core placement for the UI
 * thread when frames get heavy instead of leaving the whole job to the GPU. Needs API 31; on
 * older versions or when the device has no hint support it does nothing.
 */
class CpuBoost(
    private val activity: Activity,
) {
    private var session: PerformanceHintManager.Session? = null
    private var metricsThread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private var targetNanos = 0L

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || session != null) return
        runCatching {
            val manager = activity.getSystemService(PerformanceHintManager::class.java) ?: return
            val refreshRate = activity.window.decorView.display?.refreshRate?.takeIf { it >= 30f } ?: 60f
            targetNanos = (1_000_000_000L / refreshRate).toLong()
            val created = manager.createHintSession(intArrayOf(Process.myTid()), targetNanos) ?: return
            session = created

            val thread = HandlerThread("CpuBoostMetrics", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
            metricsThread = thread
            val frameListener =
                Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                    val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                    if (total > 0L) runCatching { session?.reportActualWorkDuration(total) }
                }
            listener = frameListener
            activity.window.addOnFrameMetricsAvailableListener(frameListener, Handler(thread.looper))
        }.onFailure {
            Timber.w(it, "CpuBoost: hint session unavailable")
            stop()
        }
    }

    fun stop() {
        listener?.let { runCatching { activity.window.removeOnFrameMetricsAvailableListener(it) } }
        listener = null
        metricsThread?.quitSafely()
        metricsThread = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { session?.close() }
        }
        session = null
    }
}
