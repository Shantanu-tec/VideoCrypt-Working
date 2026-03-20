package com.appsquadz.educryptmedia.error

/**
 * Typed error classification for the EducryptMedia SDK.
 *
 * Each subtype maps to a stable string [code] that surfaces in
 * [com.appsquadz.educryptmedia.logger.EducryptEvent.ErrorOccurred.code].
 *
 * Codes are stable across SDK versions — do NOT rename them.
 * Add new subtypes at the bottom of each category if needed.
 */
sealed class EducryptError(
    /** Stable string code — safe to persist / send to analytics. */
    val code: String,
    /** Human-readable description for logs and error UI. */
    val message: String
) {

    // ── Source / Network ────────────────────────────────────────────────────

    /** The media source URL returned a 4xx/5xx or could not be reached at all. */
    class SourceUnavailable(message: String) : EducryptError("SOURCE_UNAVAILABLE", message)

    /** A network read or connection timed out during playback or buffering. */
    class NetworkTimeout(message: String) : EducryptError("NETWORK_TIMEOUT", message)

    /** The device has no active network connection. */
    class NetworkUnavailable(message: String) : EducryptError("NETWORK_UNAVAILABLE", message)

    // ── DRM ────────────────────────────────────────────────────────────────

    /** The DRM license server rejected the request (wrong token, domain blocked, etc.). */
    class DrmLicenseFailed(message: String) : EducryptError("DRM_LICENSE_FAILED", message)

    /** The DRM license has expired and renewal was not possible. */
    class DrmLicenseExpired(message: String) : EducryptError("DRM_LICENSE_EXPIRED", message)

    /** The device does not support the required DRM scheme (Widevine L1/L3 missing). */
    class DrmNotSupported(message: String) : EducryptError("DRM_NOT_SUPPORTED", message)

    // ── Auth ────────────────────────────────────────────────────────────────

    /** The PallyCon / VideoCrypt token has expired. Client must refresh credentials. */
    class AuthExpired(message: String) : EducryptError("AUTH_EXPIRED", message)

    /** The provided credentials are malformed or not recognised by the license server. */
    class AuthInvalid(message: String) : EducryptError("AUTH_INVALID", message)

    // ── Format / Decoder ───────────────────────────────────────────────────

    /** The media container or codec is not supported on this device. */
    class UnsupportedFormat(message: String) : EducryptError("UNSUPPORTED_FORMAT", message)

    /** A hardware or software decoder error occurred during rendering. */
    class DecoderError(message: String) : EducryptError("DECODER_ERROR", message)

    // ── Downloads ──────────────────────────────────────────────────────────

    /** A background download failed (I/O error, server error, or worker cancellation). */
    class DownloadFailed(message: String) : EducryptError("DOWNLOAD_FAILED", message)

    /** Insufficient storage space to complete or resume a download. */
    class StorageInsufficient(message: String) : EducryptError("STORAGE_INSUFFICIENT", message)

    // ── Fallback ───────────────────────────────────────────────────────────

    /** Error does not map to any known category. [message] contains the raw cause. */
    class Unknown(message: String) : EducryptError("UNKNOWN", message)
}
