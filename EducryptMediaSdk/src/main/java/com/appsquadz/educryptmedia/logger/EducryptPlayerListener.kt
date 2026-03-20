package com.appsquadz.educryptmedia.logger

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.appsquadz.educryptmedia.error.EducryptExoPlayerErrorMapper
import com.appsquadz.educryptmedia.player.EducryptAbrController
import com.appsquadz.educryptmedia.player.StallRecoveryManager

/**
 * A [Player.Listener] that forwards ExoPlayer events to [EducryptEventBus].
 *
 * [stallRecoveryManager] drives the 8 s stall watchdog (Phase 3).
 * [abrController] handles quality ladder and safe mode (Phase 4).
 *
 * Error handling emits two events per error (DO NOT merge or remove [PlaybackError]):
 *   1. [EducryptEvent.PlaybackError] — for backward compat with Phase 1 clients
 *   2. [EducryptEvent.ErrorOccurred] — classified via [EducryptExoPlayerErrorMapper]
 * See CLAUDE.md Gotchas for rationale.
 */
@UnstableApi
internal class EducryptPlayerListener(
    private val stallRecoveryManager: StallRecoveryManager? = null,
    private val abrController: EducryptAbrController? = null,
    /** Invoked on fatal network errors to trigger [NetworkRecoveryManager]. */
    private val onFatalError: (() -> Unit)? = null,
    /** Invoked on any player error to emit a [com.appsquadz.educryptmedia.logger.EducryptEvent.PlayerMetaSnapshot]. */
    private val onEmitSnapshot: ((trigger: String) -> Unit)? = null
) : Player.Listener {

    private var bufferingStartTime: Long = 0L

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                bufferingStartTime = System.currentTimeMillis()
                stallRecoveryManager?.onBufferingStarted()
            }
            Player.STATE_READY -> {
                if (bufferingStartTime > 0L) {
                    val stallDurationMs = System.currentTimeMillis() - bufferingStartTime
                    EducryptEventBus.emit(
                        EducryptEvent.StallRecovered(
                            positionMs = 0L,
                            stallDurationMs = stallDurationMs
                        )
                    )
                    bufferingStartTime = 0L
                }
                stallRecoveryManager?.onBufferingEnded()
                abrController?.onStablePlayback()
            }
            else -> { /* STATE_IDLE and STATE_ENDED need no action */ }
        }
    }

    override fun onIsLoadingChanged(isLoading: Boolean) {
        EducryptEventBus.emit(EducryptEvent.PlaybackBuffering(isLoading))
    }

    override fun onPlayerError(error: PlaybackException) {
        // Emit the raw PlaybackError for backward-compat listeners that pattern-match on it.
        EducryptEventBus.emit(
            EducryptEvent.PlaybackError(
                message = error.message ?: "Playback error",
                cause = error
            )
        )
        // Emit the classified ErrorOccurred event for structured error handling.
        val classified = EducryptExoPlayerErrorMapper.map(error)
        EducryptEventBus.emit(
            EducryptEvent.ErrorOccurred(
                code = classified.code,
                message = classified.message,
                isFatal = true,
                isRetrying = false
            )
        )

        // Emit snapshot for every error — captures resolution/bitrate/network state at failure time.
        onEmitSnapshot?.invoke("ERROR")

        // Trigger network recovery only for network-related errors.
        // DRM, decoder, and auth errors do not benefit from a network recovery watch —
        // they would re-error immediately when prepare() is called again.
        val isNetworkError = classified.code == "NETWORK_UNAVAILABLE" ||
                             classified.code == "NETWORK_TIMEOUT" ||
                             classified.code == "SOURCE_UNAVAILABLE"
        if (isNetworkError) {
            onFatalError?.invoke()
        }
    }

    override fun onTracksChanged(tracks: Tracks) {
        // QualityChanged is emitted by EducryptAbrController.applyQuality() only.
        // Emitting here produced 0p→0p events during track transitions (format.height = 0
        // while ExoPlayer switches tracks). Removed.

        // Notify ABR controller that tracks are available — triggers conservative quality start
        if (tracks.containsType(C.TRACK_TYPE_VIDEO)) {
            abrController?.onTracksAvailable()
        }
    }
}
