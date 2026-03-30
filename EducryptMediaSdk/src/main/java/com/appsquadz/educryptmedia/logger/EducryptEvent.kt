package com.appsquadz.educryptmedia.logger

/**
 * Sealed hierarchy of events emitted by the EducryptMedia SDK.
 * Collect the live stream via [com.appsquadz.educryptmedia.playback.EducryptMedia.events].
 */
sealed class EducryptEvent {
    /**
     * Unique identifier for the current app launch session.
     * Generated once on EducryptMedia.init() and never changes for the process lifetime.
     * Use to group all events from a single app launch.
     */
    var appSessionId: String = ""

    /**
     * Unique identifier for the current playback session.
     * Generated fresh on each load() call. Cleared between videos.
     * Use to group all events belonging to a single video playback.
     */
    var playbackSessionId: String = ""

    // ── Playback lifecycle ──────────────────────────────────────────────────

    /** Fired when playback is initialised (DRM or non-DRM, live or VOD). */
    data class PlaybackStarted(val videoUrl: String, val isDrm: Boolean) : EducryptEvent()

    /** Fired when a DRM session is fully established and the media source is ready. */
    data class DrmReady(
        val videoUrl: String,
        val licenseUrl: String = ""
    ) : EducryptEvent()

    /**
     * Fired when the Widevine license has been successfully acquired from the license server.
     * This fires after the actual HTTP POST to [licenseUrl] succeeds and ExoPlayer has loaded
     * the DRM keys — meaning content can now decrypt and play.
     * [videoId] correlates with the current playback session.
     */
    data class DrmLicenseAcquired(
        val videoId: String,
        val licenseUrl: String
    ) : EducryptEvent()

    /**
     * Fired when the DRM session manager encounters an error during key acquisition
     * or session management. This fires independently of [PlaybackError] /
     * [ErrorOccurred] — it originates from the DRM session layer, not the player layer.
     * Use to diagnose DRM failures that occur before or outside of normal playback errors.
     * May fire multiple times per session if the player retries license acquisition.
     *
     * [videoId] correlates with the current playback session.
     * [errorMessage] is the raw exception message from the DRM session manager.
     * [errorCode] is the ExoPlayer DRM error code if available, -1 otherwise.
     */
    data class DrmSessionError(
        val videoId: String,
        val errorMessage: String,
        val errorCode: Int = -1
    ) : EducryptEvent()

    /**
     * Fired when the session status flips between HEALTHY and DEGRADED.
     * DEGRADED: any ErrorOccurred or DrmSessionError fired this playback session.
     * HEALTHY: reset at the start of every new load().
     * [reason] is the event class name that triggered the status change
     * (e.g. "ErrorOccurred", "DrmSessionError") or "NewSession" on reset.
     */
    data class SessionStatusChanged(
        val status: SessionStatus,
        val reason: String
    ) : EducryptEvent()

    // ── Buffering & stalls ──────────────────────────────────────────────────

    /** Fired when the player starts or stops buffering (ExoPlayer isLoading changes). */
    data class PlaybackBuffering(val isBuffering: Boolean) : EducryptEvent()

    /**
     * Fired by [com.appsquadz.educryptmedia.player.StallRecoveryManager] when
     * buffering has exceeded the stall threshold (default 8 s).
     * [stallCount] is the running count within the current 60 s window.
     */
    data class StallDetected(
        val positionMs: Long,
        val stallCount: Int
    ) : EducryptEvent()

    /**
     * Fired when the player recovers from a stall (buffering ends and playback resumes).
     * [stallDurationMs] is the total time spent in the buffering state.
     */
    data class StallRecovered(
        val positionMs: Long,
        val stallDurationMs: Long
    ) : EducryptEvent()

    // ── Quality & ABR ───────────────────────────────────────────────────────

    /**
     * Fired when the active video quality track changes.
     * [fromHeight] / [toHeight] are pixel heights (e.g. 720, 1080). 0 = unknown.
     * [reason] is a human-readable explanation (e.g. "Stall detected", "Bandwidth probe").
     * [qualityLabel] is the legacy label kept for backward compatibility ("720p").
     */
    data class QualityChanged(
        val qualityLabel: String,
        val fromHeight: Int = 0,
        val toHeight: Int = 0,
        val reason: String = ""
    ) : EducryptEvent()

    /**
     * Fired by [com.appsquadz.educryptmedia.player.EducryptAbrController] each time
     * the bandwidth meter produces a new estimate during a bandwidth probe.
     */
    data class BandwidthEstimated(
        val bandwidthBps: Long
    ) : EducryptEvent()

    /**
     * Fired when [stallCount] reaches MAX_STALLS_BEFORE_SAFE_MODE (3) within 60 s.
     * Phase 4 locks to the lowest quality track and emits this event.
     */
    data class SafeModeEntered(
        val reason: String
    ) : EducryptEvent()

    /**
     * Fired when the player exits safe mode after [SAFE_MODE_EXIT_STABLE_MS] (5 min) of stable playback.
     * [stablePlaybackMs] is the elapsed ms since safe mode was entered.
     */
    data class SafeModeExited(
        val stablePlaybackMs: Long
    ) : EducryptEvent()

    // ── Errors & retries ───────────────────────────────────────────────────

    /** Fired when ExoPlayer reports a fatal playback error. Kept for backward compatibility. */
    data class PlaybackError(val message: String, val cause: Throwable? = null) : EducryptEvent()

    /**
     * Fired when a playback or DRM error occurs, classified by [EducryptError] code.
     * Emitted alongside [PlaybackError] — both fire for every error. DO NOT remove [PlaybackError].
     * [code] maps to a [com.appsquadz.educryptmedia.error.EducryptError] subtype code string.
     */
    data class ErrorOccurred(
        val code: String,
        val message: String,
        val isFatal: Boolean,
        val isRetrying: Boolean,
        val exoPlayerErrorCode: Int = -1,
        val httpStatusCode: Int = -1,
        val cause: Throwable? = null
    ) : EducryptEvent()

    /**
     * Fired when network connectivity is restored after a fatal NETWORK_UNAVAILABLE error.
     * The SDK automatically calls [ExoPlayer.prepare] when this fires — no client action needed.
     * Collect this to show a "reconnecting…" indicator if desired.
     */
    object NetworkRestored : EducryptEvent()

    /**
     * Fired by [com.appsquadz.educryptmedia.error.EducryptLoadErrorPolicy] before each retry.
     * [attemptNumber] is 1-based. After attemptNumber == MAX_RETRY_COUNT, error surfaces via [ErrorOccurred].
     */
    data class RetryAttempted(
        val attemptNumber: Int,
        val reason: String,
        val delayMs: Long,
        val failedUrl: String = "",
        val dataType: String = ""
    ) : EducryptEvent()

    // ── Downloads ───────────────────────────────────────────────────────────

    /** Fired when a download is enqueued via WorkManager. */
    data class DownloadStarted(val vdcId: String) : EducryptEvent()

    /** Fired at progress milestones (25 %, 50 %, 75 %) and on status changes. */
    data class DownloadProgressChanged(
        val vdcId: String,
        val progress: Int,
        val status: String
    ) : EducryptEvent()

    /** Fired when a download reaches [com.appsquadz.educryptmedia.utils.DownloadStatus.DOWNLOADED]. */
    data class DownloadCompleted(val vdcId: String) : EducryptEvent()

    /** Fired when a download reaches [com.appsquadz.educryptmedia.utils.DownloadStatus.FAILED]. */
    data class DownloadFailed(val vdcId: String, val message: String) : EducryptEvent()

    /** Fired when [com.appsquadz.educryptmedia.playback.EducryptMedia.pauseDownload] is called. */
    data class DownloadPaused(val vdcId: String) : EducryptEvent()

    /** Fired when [com.appsquadz.educryptmedia.playback.EducryptMedia.cancelDownload] is called. */
    data class DownloadCancelled(val vdcId: String) : EducryptEvent()

    /** Fired when [com.appsquadz.educryptmedia.playback.EducryptMedia.deleteDownload] is called. */
    data class DownloadDeleted(val vdcId: String) : EducryptEvent()

    // ── Snapshots ────────────────────────────────────────────────────────────

    /**
     * Player metadata snapshot. Emitted at key playback lifecycle moments.
     * Use for: diagnostics, crash context, session analytics.
     *
     * [playbackTrigger] describes what caused this snapshot:
     *   LOADING          — MediaLoaderBuilder.load() called, player initialised
     *   DRM_READY        — DRM media source configured
     *   READY            — non-DRM media item set
     *   ERROR            — playback error occurred
     *   STALL_RECOVERY   — stall detected, quality drop triggered
     *   NETWORK_RECOVERY — player reinitialised after network loss
     *
     * Track info ([currentResolutionHeight], [currentBitrateBps]) is 0 at LOADING/READY
     * triggers — ExoPlayer has not yet selected tracks. Always populated at
     * ERROR / STALL_RECOVERY / NETWORK_RECOVERY triggers.
     */
    data class PlayerMetaSnapshot(
        val videoId: String,
        val videoUrl: String,
        val isDrm: Boolean,
        val isLive: Boolean,
        val currentResolutionHeight: Int,   // 0 if not yet known
        val currentResolutionWidth: Int,    // 0 if not yet known
        val currentBitrateBps: Int,         // 0 if not yet known
        val mimeType: String,               // empty if not yet known
        val playbackTrigger: String,        // LOADING / DRM_READY / READY / ERROR / STALL_RECOVERY / NETWORK_RECOVERY
        val drmToken: String = ""           // PallyCon token for DRM sessions; empty for non-DRM
    ) : EducryptEvent()

    /**
     * Network metadata snapshot. Always emitted paired with [PlayerMetaSnapshot].
     *
     * Fields that require elevated permissions or unavailable APIs return
     * "UNKNOWN" or -1 rather than crashing. No additional permissions are
     * declared in the SDK manifest.
     */
    data class NetworkMetaSnapshot(
        val transportType: String,          // WIFI / CELLULAR / ETHERNET / UNKNOWN
        val operatorName: String,           // carrier name, empty on WiFi
        val networkGeneration: String,      // 2G / 3G / 4G / 5G / WIFI / UNKNOWN
        val isMetered: Boolean,
        val isRoaming: Boolean,
        val downstreamBandwidthKbps: Int,   // from NetworkCapabilities, -1 if unknown
        val upstreamBandwidthKbps: Int,     // from NetworkCapabilities, -1 if unknown
        val estimatedBandwidthBps: Long,    // from DefaultBandwidthMeter (what ABR uses)
        val signalStrength: String,         // STRONG / MODERATE / WEAK / UNKNOWN
        val localIpAddress: String = ""     // device local IP, empty if unavailable
    ) : EducryptEvent()

    // ── SDK / custom ────────────────────────────────────────────────────────

    /** Client-defined event for app-specific analytics. */
    data class Custom(
        val name: String,
        val params: Map<String, String> = emptyMap()
    ) : EducryptEvent()

    /**
     * Emitted when the client calls the SDK incorrectly.
     * Not a playback error — a usage error.
     * Client should log these during development and fix the integration.
     *
     * Common codes:
     *   SDK_NOT_INITIALISED — init() was not called before this method
     *   SDK_SHUT_DOWN       — method called after shutdown()
     *   INVALID_INPUT       — null, blank, or out-of-range parameter
     *   WRONG_THREAD        — player method called from background thread
     */
    data class SdkError(
        val code: String,
        val message: String
    ) : EducryptEvent()
}
