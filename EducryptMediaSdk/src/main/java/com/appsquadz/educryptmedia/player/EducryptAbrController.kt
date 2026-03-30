package com.appsquadz.educryptmedia.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.appsquadz.educryptmedia.logger.EducryptEvent
import com.appsquadz.educryptmedia.logger.EducryptEventBus

/**
 * OTT-grade Adaptive Bitrate controller — Hybrid BBA-2 + dash.js DYNAMIC strategy.
 *
 * Two-phase strategy:
 *   **Phase 1 (startup, bufferMs < [STARTUP_BUFFER_THRESHOLD_MS])**
 *       Throughput-based: select the highest [QualityTier] whose bitrate fits within
 *       EWMA-smoothed bandwidth × [BANDWIDTH_SAFETY_FACTOR].
 *
 *   **Phase 2 (steady state, bufferMs ≥ [STARTUP_BUFFER_THRESHOLD_MS])**
 *       Buffer-zone-based with bandwidth ceiling:
 *         CRITICAL (< [BUFFER_CRITICAL_MS])  → drop 2 tiers immediately
 *         LOW      (< [BUFFER_LOW_MS])       → drop 1 tier
 *         STABLE   (< [BUFFER_HEALTHY_MS])   → hold
 *         HEALTHY  (< [BUFFER_EXCESS_MS])    → upshift if bandwidth allows + hysteresis guard
 *         EXCESS   (≥ [BUFFER_EXCESS_MS])    → upshift freely if bandwidth allows
 *
 * Cross-cutting rules:
 *   - EWMA bandwidth (α = 0.3) with [BANDWIDTH_SAFETY_FACTOR] = 0.7 for upshift decisions
 *   - Stall → drop 2 tiers + reset EWMA to 50 % + clear upshift timer
 *   - Upshift guard: min [UPSHIFT_HOLD_MS] between consecutive upshifts
 *   - Switch interval: min [MIN_SWITCH_INTERVAL_MS] between any quality switches
 *   - Live streams: all buffer thresholds halved
 *   - Safe mode exit: cautious re-entry at [PROBE_INTERVAL_CAUTIOUS_MS] for [CAUTIOUS_REENTRY_DURATION_MS]
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

    /** Represents a single video quality option derived from available ExoPlayer tracks. */
    private data class QualityTier(
        val index: Int,
        val height: Int,
        /** Bitrate in bps. From [Format.bitrate] when set; else height² × 2.5 fallback. */
        val bitrate: Long
    )

    companion object {
        /** Normal probe interval — bandwidth + buffer evaluated every this many ms. */
        private const val RAMP_UP_STABLE_PLAYBACK_MS = 5_000L

        /** Cautious probe interval applied for [CAUTIOUS_REENTRY_DURATION_MS] after safe mode exit. */
        private const val PROBE_INTERVAL_CAUTIOUS_MS = 8_000L

        /** Duration of cautious re-entry window after exiting safe mode. */
        private const val CAUTIOUS_REENTRY_DURATION_MS = 60_000L

        /** Exit safe mode after this long of stable playback at the safe quality tier. */
        private const val SAFE_MODE_EXIT_STABLE_MS = 5 * 60_000L  // 5 minutes

        /** EWMA smoothing factor for bandwidth estimation. Lower = smoother / slower to react. */
        private const val EWMA_ALPHA = 0.3f

        /**
         * Multiply smoothed bandwidth by this before comparing against tier bitrates.
         * Prevents selecting a tier right at the bandwidth edge (20 % headroom).
         */
        private const val BANDWIDTH_SAFETY_FACTOR = 0.7f

        /** Buffer below this threshold → Phase 1 (throughput-based selection). */
        private const val STARTUP_BUFFER_THRESHOLD_MS = 10_000L

        /** Buffer below this → drop 2 tiers immediately (Phase 2 CRITICAL zone). */
        private const val BUFFER_CRITICAL_MS = 2_000L

        /** Buffer below this → drop 1 tier (Phase 2 LOW zone). */
        private const val BUFFER_LOW_MS = 4_000L

        /** Buffer below this in Phase 2 → hold quality (neither drop nor upshift). */
        private const val BUFFER_HEALTHY_MS = 15_000L

        /** Buffer at or above this in Phase 2 → upshift freely (still bandwidth-constrained). */
        private const val BUFFER_EXCESS_MS = 25_000L

        /** Raised excess threshold used on WEAK signal — requires more buffer runway before upshifting freely. */
        private const val BUFFER_EXCESS_MS_WEAK = 35_000L

        /**
         * Minimum time that must elapse after the last upshift before the next upshift.
         * Prevents rapid quality oscillation on unstable connections.
         */
        private const val UPSHIFT_HOLD_MS = 3_000L

        /** Minimum time between any two quality switches (up or down). */
        private const val MIN_SWITCH_INTERVAL_MS = 2_000L
    }

    /** On WEAK signal, require more buffer before freely upshifting. */
    private val excessBufferThresholdMs: Long
        get() = if (EducryptEventBus.currentSignalStrength == "WEAK") BUFFER_EXCESS_MS_WEAK else BUFFER_EXCESS_MS

    /** On WEAK signal, require more buffer runway before any upshift is considered safe. */
    private val safeUpshiftBufferMs: Long
        get() = if (EducryptEventBus.currentSignalStrength == "WEAK") 12_000L else 8_000L

    private val handler = Handler(Looper.getMainLooper())

    // ── Safe mode state ────────────────────────────────────────────────────
    private var isInSafeMode = false
    private var safeModeEnteredAt = 0L
    private var lastStablePlaybackStart = 0L

    // ── Track state ────────────────────────────────────────────────────────
    private var tracksInitialized = false
    private var currentQualityIndex = -1                       // -1 = auto (before tracks available)
    private var availableQualities: List<QualityTier> = emptyList()

    // ── EWMA bandwidth ─────────────────────────────────────────────────────
    private var smoothedBandwidth = 0L                         // 0 = not yet seeded

    // ── Hysteresis / switch guard ──────────────────────────────────────────
    private var lastSwitchTimeMs = 0L
    private var lastUpshiftTimeMs = 0L

    // ── Cautious re-entry after safe mode exit ─────────────────────────────
    private var cautiousReentryEndMs = 0L

    /** Named so [handler.removeCallbacks] can cancel a pending probe before rescheduling. */
    private val probeRunnable: Runnable = Runnable { doProbe() }

    /** Named to avoid the anonymous-lambda bug where removeCallbacks has no stable reference. */
    private val safeModeExitRunnable: Runnable = Runnable { checkSafeModeExit() }

    // ── Public entry points ────────────────────────────────────────────────

    /**
     * Call once when ExoPlayer transitions to STATE_READY and tracks are available.
     * Builds [QualityTier] list, selects mid quality as conservative start,
     * then schedules the first bandwidth probe.
     */
    fun onTracksAvailable() {
        if (tracksInitialized) return

        // Don't commit until real heights are available. onTracksChanged fires during
        // intermediate states where format.height = 0. Retry on the next call.
        val tiers = buildQualityTiers()
        if (tiers.isEmpty()) return

        tracksInitialized = true
        availableQualities = tiers

        var startIndex = availableQualities.size / 2
        // On weak signal, cap starting quality to 360p to avoid an immediate drop stall
        if (EducryptEventBus.currentSignalStrength == "WEAK") {
            val cappedIndex = availableQualities.indexOfLast { it.height <= 360 }
            if (cappedIndex >= 0 && cappedIndex < startIndex) {
                startIndex = cappedIndex
            }
        }
        applyQuality(startIndex, reason = "Initial — conservative start")

        scheduleNextProbe()
    }

    /** Called by [StallRecoveryManager] when a stall exceeds the 8 s threshold. */
    fun onStallDetected(stallCount: Int) {
        if (isInSafeMode) return
        // Drop 2 tiers, halve EWMA bandwidth, reset upshift guard
        val target = (currentQualityIndex - 2).coerceAtLeast(0)
        applyQuality(target, reason = "Stall detected (count: $stallCount) — drop 2 tiers")
        smoothedBandwidth = (smoothedBandwidth * 0.5f).toLong()
        lastUpshiftTimeMs = 0L
        handler.removeCallbacks(probeRunnable)
        scheduleNextProbe()
    }

    /** Called by [StallRecoveryManager] when 3+ stalls occur within 60 s. */
    fun onSafeModeRequired() {
        if (isInSafeMode) return

        isInSafeMode = true
        safeModeEnteredAt = System.currentTimeMillis()
        lastStablePlaybackStart = 0L

        handler.removeCallbacks(probeRunnable)
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
        smoothedBandwidth = 0L
        lastSwitchTimeMs = 0L
        lastUpshiftTimeMs = 0L
        cautiousReentryEndMs = 0L
        restoreAutoSelection()
    }

    // ── Probe logic ────────────────────────────────────────────────────────

    private fun doProbe() {
        if (availableQualities.isEmpty()) return

        // 1. Update EWMA bandwidth estimate
        val rawBw = bandwidthMeter.bitrateEstimate
        smoothedBandwidth = if (smoothedBandwidth == 0L) rawBw
        else ((EWMA_ALPHA * rawBw) + ((1f - EWMA_ALPHA) * smoothedBandwidth)).toLong()

        EducryptEventBus.emit(EducryptEvent.BandwidthEstimated(bandwidthBps = smoothedBandwidth))

        if (isInSafeMode) {
            scheduleNextProbe()
            return
        }

        val now = System.currentTimeMillis()
        val effectiveBw = (smoothedBandwidth * BANDWIDTH_SAFETY_FACTOR).toLong()

        // 2. Compute live-adjusted buffer thresholds (halve all for live streams)
        val isLive = player.isCurrentMediaItemLive
        val factor = if (isLive) 0.5f else 1.0f
        val criticalMs = (BUFFER_CRITICAL_MS * factor).toLong()
        val lowMs      = (BUFFER_LOW_MS * factor).toLong()
        val startupMs  = (STARTUP_BUFFER_THRESHOLD_MS * factor).toLong()
        val healthyMs  = (BUFFER_HEALTHY_MS * factor).toLong()
        val excessMs   = (excessBufferThresholdMs * factor).toLong()

        val bufferMs = player.bufferedPosition - player.currentPosition

        // 3. Emergency drops — override phase logic (no MIN_SWITCH_INTERVAL guard for drops)
        when {
            bufferMs < criticalMs -> {
                val target = (currentQualityIndex - 2).coerceAtLeast(0)
                if (target < currentQualityIndex) {
                    applyQuality(target, reason = "Buffer CRITICAL: ${bufferMs}ms — drop 2 tiers")
                    lastSwitchTimeMs = now
                }
                scheduleNextProbe()
                return
            }
            bufferMs < lowMs -> {
                if (currentQualityIndex > 0) {
                    applyQuality(currentQualityIndex - 1, reason = "Buffer LOW: ${bufferMs}ms — drop 1 tier")
                    lastSwitchTimeMs = now
                }
                scheduleNextProbe()
                return
            }
        }

        // 4. Phase-based upshift / hold decision
        when {
            bufferMs < startupMs -> {
                // Phase 1 — throughput-based: pick the highest tier fitting effective bandwidth
                val targetIndex = bandwidthToQualityIndex(effectiveBw)
                val switchAllowed = (now - lastSwitchTimeMs) >= MIN_SWITCH_INTERVAL_MS
                if (switchAllowed && targetIndex != currentQualityIndex) {
                    val isUpshift = targetIndex > currentQualityIndex
                    val upshiftAllowed = !isUpshift || ((now - lastUpshiftTimeMs) >= UPSHIFT_HOLD_MS && canSafelyUpshift())
                    if (upshiftAllowed) {
                        val dir = if (isUpshift) "up" else "down"
                        applyQuality(
                            targetIndex,
                            reason = "Phase 1 startup ($dir): ${smoothedBandwidth / 1000} Kbps, buf ${bufferMs}ms"
                        )
                        lastSwitchTimeMs = now
                        if (isUpshift) lastUpshiftTimeMs = now
                    }
                }
            }

            bufferMs < healthyMs -> {
                // Phase 2 STABLE — buffer is adequate but not plentiful; hold current quality
            }

            bufferMs < excessMs -> {
                // Phase 2 HEALTHY — upshift one tier if bandwidth supports it + hysteresis guard
                val switchAllowed = (now - lastSwitchTimeMs) >= MIN_SWITCH_INTERVAL_MS
                val upshiftAllowed = (now - lastUpshiftTimeMs) >= UPSHIFT_HOLD_MS
                if (switchAllowed && upshiftAllowed && canSafelyUpshift() && currentQualityIndex < availableQualities.size - 1) {
                    val next = availableQualities[currentQualityIndex + 1]
                    if (next.bitrate <= effectiveBw) {
                        applyQuality(
                            currentQualityIndex + 1,
                            reason = "Phase 2 HEALTHY upshift: buf ${bufferMs}ms, bw ${smoothedBandwidth / 1000} Kbps"
                        )
                        lastSwitchTimeMs = now
                        lastUpshiftTimeMs = now
                    }
                }
            }

            else -> {
                // Phase 2 EXCESS — upshift freely; upshift hold waived, bandwidth still the ceiling
                val switchAllowed = (now - lastSwitchTimeMs) >= MIN_SWITCH_INTERVAL_MS
                if (switchAllowed && canSafelyUpshift() && currentQualityIndex < availableQualities.size - 1) {
                    val next = availableQualities[currentQualityIndex + 1]
                    if (next.bitrate <= effectiveBw) {
                        applyQuality(
                            currentQualityIndex + 1,
                            reason = "Phase 2 EXCESS upshift: buf ${bufferMs}ms, bw ${smoothedBandwidth / 1000} Kbps"
                        )
                        lastSwitchTimeMs = now
                        lastUpshiftTimeMs = now
                    }
                }
            }
        }

        scheduleNextProbe()
    }

    private fun scheduleNextProbe() {
        handler.removeCallbacks(probeRunnable)
        val interval = if (System.currentTimeMillis() < cautiousReentryEndMs)
            PROBE_INTERVAL_CAUTIOUS_MS
        else
            RAMP_UP_STABLE_PLAYBACK_MS
        handler.postDelayed(probeRunnable, interval)
    }

    // ── Safe mode helpers ──────────────────────────────────────────────────

    private fun scheduleSafeModeExitCheck() {
        handler.removeCallbacks(safeModeExitRunnable)
        handler.postDelayed(safeModeExitRunnable, 60_000L)
    }

    private fun checkSafeModeExit() {
        if (!isInSafeMode) return
        val stableFor = if (lastStablePlaybackStart > 0L)
            System.currentTimeMillis() - lastStablePlaybackStart
        else 0L
        if (stableFor >= SAFE_MODE_EXIT_STABLE_MS) {
            exitSafeMode()
        } else {
            scheduleSafeModeExitCheck()
        }
    }

    private fun exitSafeMode() {
        val stableMs = System.currentTimeMillis() - safeModeEnteredAt
        isInSafeMode = false
        cautiousReentryEndMs = System.currentTimeMillis() + CAUTIOUS_REENTRY_DURATION_MS
        EducryptEventBus.emit(EducryptEvent.SafeModeExited(stablePlaybackMs = stableMs))
        // Step up one tier as the re-entry gesture; cautious probe interval governs subsequent probes
        if (currentQualityIndex < availableQualities.size - 1) {
            applyQuality(
                currentQualityIndex + 1,
                reason = "Safe mode exit — step up after ${stableMs / 60_000} min stable"
            )
        }
        scheduleNextProbe()
    }

    // ── Quality application ────────────────────────────────────────────────

    /**
     * Applies a quality tier using **constraint-based track selection** so that
     * [AdaptiveTrackSelection] remains active and transitions happen at segment boundaries
     * without flushing already-buffered content or triggering a loading spinner.
     *
     * - **Downshift**: ceiling at [targetHeight], no floor — buffered segments at the old
     *   (higher) quality keep playing while new segments download at the new (lower) quality.
     * - **Upshift**: both floor and ceiling at [targetHeight] — pins the target quality so
     *   ExoPlayer doesn't stay at the old lower quality, while still crossing over smoothly
     *   at the next segment boundary.
     *
     * [clearOverridesOfType] ensures any prior [TrackSelectionOverride] (e.g. from the
     * settings UI) is removed before the new constraint takes effect.
     */
    private fun applyQuality(index: Int, reason: String) {
        if (availableQualities.isEmpty()) return
        val clamped = index.coerceIn(0, availableQualities.size - 1)
        val targetHeight = availableQualities.getOrElse(clamped) { return }.height
        if (targetHeight <= 0) return

        // Capture fromHeight BEFORE updating currentQualityIndex
        val fromHeight = availableQualities.getOrNull(currentQualityIndex)?.height ?: 0

        currentQualityIndex = clamped

        val isUpshift = fromHeight > 0 && targetHeight > fromHeight
        trackSelector.parameters = trackSelector.parameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setMaxVideoSize(Int.MAX_VALUE, targetHeight)
            .setMinVideoSize(0, if (isUpshift) targetHeight else 0)
            .build()

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

    // ── Upshift safety guard ───────────────────────────────────────────────

    /**
     * Returns true only when the player has at least 8 s of buffered content ahead.
     * Upshifts need this runway so the player can absorb the segment-boundary transition
     * time while new (higher-quality) segments download — without risking a rebuffer stall.
     */
    private fun canSafelyUpshift(): Boolean {
        val bufferedMs = player.bufferedPosition - player.currentPosition
        return bufferedMs >= safeUpshiftBufferMs
    }

    // ── Tier building and bandwidth mapping ────────────────────────────────

    /**
     * Builds a sorted [QualityTier] list from currently available ExoPlayer tracks.
     * Bitrate source: [Format.bitrate] when set; otherwise height² × 2.5 bps estimate.
     * Returns empty list if tracks are not yet resolved (height = 0 state).
     */
    private fun buildQualityTiers(): List<QualityTier> {
        val tierMap = mutableMapOf<Int, Long>()   // height → best bitrate seen for that height
        val tracks = player.currentTracks
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                val fmt = group.getTrackFormat(i)
                if (fmt.height <= 0) continue
                val bitrate = if (fmt.bitrate != Format.NO_VALUE) fmt.bitrate.toLong()
                else fmt.height.toLong() * fmt.height * 5 / 2   // height² × 2.5 bps fallback
                tierMap[fmt.height] = maxOf(tierMap.getOrDefault(fmt.height, 0L), bitrate)
            }
        }
        return tierMap.entries
            .sortedBy { it.key }
            .mapIndexed { idx, (height, bitrate) -> QualityTier(idx, height, bitrate) }
    }

    /**
     * Returns the highest [QualityTier] index whose [QualityTier.bitrate] does not exceed
     * [effectiveBw]. Returns 0 if all tiers exceed available bandwidth.
     */
    private fun bandwidthToQualityIndex(effectiveBw: Long): Int {
        var best = 0
        for (i in availableQualities.indices) {
            if (availableQualities[i].bitrate <= effectiveBw) best = i
        }
        return best
    }
}
