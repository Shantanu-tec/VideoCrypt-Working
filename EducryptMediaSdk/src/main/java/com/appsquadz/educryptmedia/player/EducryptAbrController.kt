package com.appsquadz.educryptmedia.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.appsquadz.educryptmedia.logger.EducryptEvent
import com.appsquadz.educryptmedia.logger.EducryptEventBus

/**
 * OTT-grade Adaptive Bitrate controller.
 *
 * Strategy (mirrors Netflix / Prime Video approach):
 *   1. **Start conservative** — begin at mid quality, not highest
 *   2. **Bandwidth probe** — measure real throughput every [RAMP_UP_STABLE_PLAYBACK_MS]
 *   3. **Ramp up gradually** — step up one tier every probe cycle when bandwidth allows
 *   4. **Drop fast** — on stall, immediately drop one tier via [onStallDetected]
 *   5. **Safe mode** — after 3 stalls in 60 s, lock to lowest quality via [onSafeModeRequired]
 *   6. **Safe mode exit** — after [SAFE_MODE_EXIT_STABLE_MS] stable playback, step up + resume ramp-up
 *
 * All callbacks and handler posts run on the main thread.
 * Must be [reset] when a new video starts — wired via [EducryptMedia.setValuesToDefault].
 */
@UnstableApi
internal class EducryptAbrController(
    private val player: ExoPlayer,
    private val trackSelector: DefaultTrackSelector,
    private val bandwidthMeter: DefaultBandwidthMeter
) {

    companion object {
        /** Wait this long of stable playback before probing bandwidth and stepping up quality. */
        private const val RAMP_UP_STABLE_PLAYBACK_MS = 5_000L

        /** Exit safe mode after this long of stable playback at the safe quality tier. */
        private const val SAFE_MODE_EXIT_STABLE_MS = 5 * 60_000L  // 5 minutes

        /** Bandwidth → quality tier thresholds (bits per second). */
        private const val BANDWIDTH_SAFE_BPS   =   500_000L  //  500 Kbps → safe quality (index 0)
        private const val BANDWIDTH_LOW_BPS    = 1_000_000L  //    1 Mbps → low tier (~25 %)
        private const val BANDWIDTH_MEDIUM_BPS = 2_500_000L  //  2.5 Mbps → mid tier (~50 %)
        private const val BANDWIDTH_HIGH_BPS   = 5_000_000L  //    5 Mbps → high tier (~75 %)
        // above BANDWIDTH_HIGH_BPS → highest available quality
    }

    private val handler = Handler(Looper.getMainLooper())

    private var isInSafeMode: Boolean = false
    private var safeModeEnteredAt: Long = 0L
    private var lastStablePlaybackStart: Long = 0L
    private var tracksInitialized: Boolean = false

    private var currentQualityIndex: Int = -1          // -1 = auto (before tracks available)
    private var availableQualities: List<Int> = emptyList()  // pixel heights, ascending

    /** Named Runnable so [handler.removeCallbacks] can cancel a pending probe before rescheduling. */
    private val rampUpRunnable: Runnable = Runnable { doRampUp() }

    // ── Public entry points ────────────────────────────────────────────────

    /**
     * Call once when ExoPlayer transitions to STATE_READY and tracks are available.
     * Reads available video heights, selects mid quality as conservative start,
     * then schedules the first bandwidth probe.
     */
    fun onTracksAvailable() {
        if (tracksInitialized) return

        // Don't commit until real heights are available. onTracksChanged fires during
        // intermediate states where format.height = 0. Retry on the next call.
        val heights = getAvailableVideoHeights()
        if (heights.isEmpty()) return

        tracksInitialized = true
        availableQualities = heights

        // Start conservative — mid quality tier
        val startIndex = availableQualities.size / 2
        applyQuality(startIndex, reason = "Initial — conservative start")

        scheduleRampUp()
    }

    /** Called by [StallRecoveryManager] when a stall exceeds the 8 s threshold. */
    fun onStallDetected(stallCount: Int) {
        if (isInSafeMode) return
        dropQuality(reason = "Stall detected (count: $stallCount)")
    }

    /** Called by [StallRecoveryManager] when 3+ stalls occur within 60 s. */
    fun onSafeModeRequired() {
        if (isInSafeMode) return

        isInSafeMode = true
        safeModeEnteredAt = System.currentTimeMillis()
        lastStablePlaybackStart = 0L

        applyQuality(0, reason = "Safe mode — locking to lowest quality")

        EducryptEventBus.emit(EducryptEvent.SafeModeEntered(reason = "3+ stalls in 60 s window"))

        scheduleSafeModeExitCheck()
    }

    /**
     * Called by [com.appsquadz.educryptmedia.logger.EducryptPlayerListener]
     * when the player enters STATE_READY (stable playback resumed).
     */
    fun onStablePlayback() {
        if (lastStablePlaybackStart == 0L) {
            lastStablePlaybackStart = System.currentTimeMillis()
        }
        if (isInSafeMode) {
            val stableFor = System.currentTimeMillis() - lastStablePlaybackStart
            if (stableFor >= SAFE_MODE_EXIT_STABLE_MS) {
                exitSafeMode()
            }
        }
    }

    /** Reset all state. Called from [EducryptMedia.setValuesToDefault] on new playback. */
    fun reset() {
        handler.removeCallbacksAndMessages(null)
        isInSafeMode = false
        safeModeEnteredAt = 0L
        lastStablePlaybackStart = 0L
        tracksInitialized = false
        currentQualityIndex = -1
        availableQualities = emptyList()
        restoreAutoSelection()
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private fun dropQuality(reason: String) {
        if (currentQualityIndex <= 0) return
        applyQuality(currentQualityIndex - 1, reason = reason)
        // QualityChanged is emitted inside applyQuality()
    }

    private fun raiseQuality(reason: String) {
        if (currentQualityIndex >= availableQualities.size - 1) return
        applyQuality(currentQualityIndex + 1, reason = reason)
        // QualityChanged is emitted inside applyQuality()
    }

    private fun applyQuality(index: Int, reason: String) {
        if (availableQualities.isEmpty()) return
        val clamped = index.coerceIn(0, availableQualities.size - 1)
        val targetHeight = availableQualities.getOrElse(clamped) { return }
        if (targetHeight <= 0) return

        // Capture fromHeight BEFORE updating currentQualityIndex
        val fromHeight = availableQualities.getOrNull(currentQualityIndex) ?: 0

        currentQualityIndex = clamped

        // Use TrackSelectionOverride to force the exact track — setMaxVideoSize only sets a
        // ceiling and ExoPlayer's internal ABR may stay below it. Override forces the selection.
        var applied = false
        val tracks = player.currentTracks
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                if (group.getTrackFormat(i).height == targetHeight) {
                    trackSelector.parameters = trackSelector.parameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .build()
                    applied = true
                    break
                }
            }
            if (applied) break
        }

        if (!applied) {
            // Fallback: no exact height match found (tracks not yet resolved) — use height cap
            trackSelector.parameters = trackSelector.parameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .setMaxVideoSize(Int.MAX_VALUE, targetHeight)
                .build()
        }

        // Emit only when height actually changes and both heights are meaningful
        if (fromHeight > 0 && fromHeight != targetHeight && reason.isNotEmpty()) {
            EducryptEventBus.emit(
                EducryptEvent.QualityChanged(
                    qualityLabel = "${targetHeight}p",
                    fromHeight   = fromHeight,
                    toHeight     = targetHeight,
                    reason       = reason
                )
            )
        }
    }

    private fun restoreAutoSelection() {
        trackSelector.parameters = trackSelector.parameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
            .setMinVideoSize(0, 0)
            .build()
        currentQualityIndex = -1
    }

    private fun scheduleRampUp() {
        // Cancel any pending probe before posting a new one.
        // Without removeCallbacks, each applyQuality() call (which changes trackSelector.parameters
        // and triggers onTracksChanged → onTracksAvailable guard) would accumulate timers.
        handler.removeCallbacks(rampUpRunnable)
        handler.postDelayed(rampUpRunnable, RAMP_UP_STABLE_PLAYBACK_MS)
    }

    private fun doRampUp() {
        if (isInSafeMode || availableQualities.isEmpty()) return

        val bandwidth = bandwidthMeter.bitrateEstimate
        EducryptEventBus.emit(EducryptEvent.BandwidthEstimated(bandwidthBps = bandwidth))

        val targetIndex = bandwidthToQualityIndex(bandwidth)
        if (targetIndex > currentQualityIndex) {
            raiseQuality(reason = "Bandwidth probe: ${bandwidth / 1000} Kbps → step up")
        }

        scheduleRampUp()
    }

    private fun scheduleSafeModeExitCheck() {
        handler.postDelayed({
            if (!isInSafeMode) return@postDelayed
            val stableFor = if (lastStablePlaybackStart > 0L)
                System.currentTimeMillis() - lastStablePlaybackStart
            else 0L
            if (stableFor >= SAFE_MODE_EXIT_STABLE_MS) {
                exitSafeMode()
            } else {
                scheduleSafeModeExitCheck()
            }
        }, 60_000L)
    }

    private fun exitSafeMode() {
        val stableMs = System.currentTimeMillis() - safeModeEnteredAt
        isInSafeMode = false
        EducryptEventBus.emit(EducryptEvent.SafeModeExited(stablePlaybackMs = stableMs))
        raiseQuality(reason = "Safe mode exit — stable for ${stableMs / 60_000} min")
        scheduleRampUp()
    }

    private fun bandwidthToQualityIndex(bandwidthBps: Long): Int {
        if (availableQualities.isEmpty()) return 0
        return when {
            bandwidthBps < BANDWIDTH_SAFE_BPS   -> 0
            bandwidthBps < BANDWIDTH_LOW_BPS    -> (availableQualities.size * 0.25).toInt()
            bandwidthBps < BANDWIDTH_MEDIUM_BPS -> (availableQualities.size * 0.50).toInt()
            bandwidthBps < BANDWIDTH_HIGH_BPS   -> (availableQualities.size * 0.75).toInt()
            else                                -> availableQualities.size - 1
        }.coerceIn(0, availableQualities.size - 1)
    }

    private fun getAvailableVideoHeights(): List<Int> {
        val heights = mutableSetOf<Int>()
        val tracks = player.currentTracks
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_VIDEO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    if (format.height > 0) heights.add(format.height)
                }
            }
        }
        return heights.sorted()
    }
}
