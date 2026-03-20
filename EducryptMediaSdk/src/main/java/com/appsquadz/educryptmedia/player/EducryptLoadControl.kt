package com.appsquadz.educryptmedia.player

import androidx.media3.exoplayer.DefaultLoadControl

/**
 * Tuned [DefaultLoadControl] configuration for mobile OTT playback.
 *
 * ExoPlayer defaults (for reference):
 *   minBufferMs         = 50_000  (50 s)
 *   maxBufferMs         = 50_000  (50 s)
 *   bufferForPlaybackMs =  2_500  (2.5 s — too small for mobile, causes early stalls)
 *   rebufferMs          =  5_000  (5 s)
 *
 * Tuned values — rationale:
 *   - Lower min buffer (15 s) reduces initial load time while providing enough runway for
 *     bandwidth estimation on mobile networks.
 *   - Higher bufferForPlaybackMs (3 s) vs ExoPlayer default avoids starting playback too early
 *     when the first 2.5 s were buffered at a burst rate that can't be sustained.
 *   - bufferForRebufferMs (5 s) matches the ExoPlayer default — enough data to resume smoothly.
 *   - Max buffer (50 s) unchanged — keep ExoPlayer's ceiling to avoid memory pressure.
 *
 * Constraints that must hold or ExoPlayer will throw at runtime:
 *   bufferForPlaybackMs  < minBufferMs  ✅  3_000 < 15_000
 *   bufferForRebufferMs  < minBufferMs  ✅  5_000 < 15_000
 *   minBufferMs          < maxBufferMs  ✅ 15_000 < 50_000
 *
 * ⚠️ Do NOT change these values without testing on mid-range and low-end Android devices
 * (1–2 GB RAM, HSPA / 3G networks). The values are starting points calibrated from
 * Phase 1 logger data — adjust based on real stall frequency from [StallRecoveryManager].
 */
internal object EducryptLoadControl {

    private const val MIN_BUFFER_MS = 15_000          // 15 s min buffer
    private const val MAX_BUFFER_MS = 50_000          // 50 s max buffer
    private const val BUFFER_FOR_PLAYBACK_MS = 3_000  // 3 s before initial playback starts
    private const val BUFFER_FOR_REBUFFER_MS = 5_000  // 5 s to recover from a stall

    fun build(): DefaultLoadControl {
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_REBUFFER_MS
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }
}
