package com.appsquadz.educryptmedia.player

import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import com.appsquadz.educryptmedia.logger.EducryptEvent
import com.appsquadz.educryptmedia.logger.EducryptEventBus

/**
 * Active stall detection and recovery for [ExoPlayer].
 *
 * Monitors buffering duration via a [Handler] watchdog.
 * When buffering exceeds [STALL_THRESHOLD_MS], a stall is declared.
 * When [MAX_STALLS_BEFORE_SAFE_MODE] stalls occur within [SAFE_MODE_WINDOW_MS],
 * safe mode is signalled via [onSafeModeRequired] (Phase 4 wires quality drop here).
 *
 * All callbacks run on the main thread (watchdog posts to [Looper.getMainLooper()]).
 *
 * Must be [reset] whenever a new video starts to clear stall history.
 */
internal class StallRecoveryManager(
    private val player: ExoPlayer
) {

    companion object {
        private const val STALL_THRESHOLD_MS = 8_000L      // declare stall after 8 s buffering
        private const val CHECK_INTERVAL_MS = 2_000L       // poll every 2 s
        private const val MAX_STALLS_BEFORE_SAFE_MODE = 3  // safe mode after 3 stalls in the window
        private const val SAFE_MODE_WINDOW_MS = 60_000L    // stall count resets after 60 s
    }

    private val handler = Handler(Looper.getMainLooper())

    private var bufferingStartTime: Long = 0L
    private var isMonitoring: Boolean = false
    private var stallCount: Int = 0
    private var stallWindowStartTime: Long = 0L

    /** Called by [com.appsquadz.educryptmedia.logger.EducryptPlayerListener] when stall detected. */
    var onStallDetected: ((stallCount: Int) -> Unit)? = null

    /** Called when the stall threshold within the window is exceeded. Phase 4 hooks here. */
    var onSafeModeRequired: (() -> Unit)? = null

    fun onBufferingStarted() {
        bufferingStartTime = System.currentTimeMillis()
        startWatchdog()
    }

    fun onBufferingEnded() {
        bufferingStartTime = 0L
        stopWatchdog()
    }

    fun reset() {
        stopWatchdog()
        stallCount = 0
        stallWindowStartTime = 0L
        bufferingStartTime = 0L
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (bufferingStartTime == 0L) return

            val bufferingDuration = System.currentTimeMillis() - bufferingStartTime
            if (bufferingDuration >= STALL_THRESHOLD_MS) {
                handleStall()
                return  // stop after declaring stall — watchdog restarted on next buffering cycle
            }

            if (isMonitoring) {
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }

    private fun startWatchdog() {
        isMonitoring = true
        handler.postDelayed(watchdogRunnable, CHECK_INTERVAL_MS)
    }

    private fun stopWatchdog() {
        isMonitoring = false
        handler.removeCallbacks(watchdogRunnable)
    }

    private fun handleStall() {
        stopWatchdog()

        val now = System.currentTimeMillis()

        // Reset the stall window if it has expired
        if (stallWindowStartTime == 0L) stallWindowStartTime = now
        if (now - stallWindowStartTime > SAFE_MODE_WINDOW_MS) {
            stallCount = 0
            stallWindowStartTime = now
        }

        stallCount++

        EducryptEventBus.emit(
            EducryptEvent.StallDetected(
                positionMs = player.currentPosition,
                stallCount = stallCount
            )
        )

        onStallDetected?.invoke(stallCount)

        if (stallCount >= MAX_STALLS_BEFORE_SAFE_MODE) {
            EducryptEventBus.emit(
                EducryptEvent.SafeModeEntered(
                    reason = "$stallCount stalls in ${SAFE_MODE_WINDOW_MS / 1000}s window"
                )
            )
            onSafeModeRequired?.invoke()
        }
    }
}
