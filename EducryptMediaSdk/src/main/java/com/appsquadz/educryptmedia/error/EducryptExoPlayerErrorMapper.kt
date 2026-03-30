package com.appsquadz.educryptmedia.error

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

/**
 * Maps a Media3 [PlaybackException] to a typed [EducryptError].
 *
 * Mapping strategy:
 *  1. ExoPlayer integer error codes (most specific — checked first)
 *  2. HTTP status code extraction via [extractHttpStatusCode] for source / DRM errors —
 *     uses [HttpDataSource.InvalidResponseCodeException] walked through the cause chain;
 *     no string parsing. HTTP status is included in [EducryptError.message] and also
 *     available to clients via [com.appsquadz.educryptmedia.logger.EducryptEvent.ErrorOccurred.httpStatusCode].
 *  3. Fall-through to [EducryptError.Unknown]
 *
 * Note: [HttpDataSource.InvalidResponseCodeException] is used (not the base
 * [HttpDataSource.HttpDataSourceException]) because only the InvalidResponseCodeException
 * subclass carries a [responseCode] field.
 *
 * DRM license acquisition failures always map to [EducryptError.DrmLicenseFailed] regardless
 * of HTTP status — clients distinguish 401/403 via [ErrorOccurred.httpStatusCode] if needed.
 *
 * This class is internal to the SDK — clients receive only the stable [EducryptError.code]
 * string via [com.appsquadz.educryptmedia.logger.EducryptEvent.ErrorOccurred].
 */
@UnstableApi
internal object EducryptExoPlayerErrorMapper {

    fun map(error: PlaybackException): EducryptError {
        return when (error.errorCode) {

            // ── Network ──────────────────────────────────────────────────────
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                EducryptError.NetworkTimeout(error.message ?: "Network connection timed out")

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                EducryptError.NetworkUnavailable(error.message ?: "Network connection failed")

            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> {
                val httpStatus = extractHttpStatusCode(error)
                EducryptError.SourceUnavailable(
                    if (httpStatus > 0) "Media source unavailable (HTTP $httpStatus)"
                    else error.message ?: "Media source unavailable"
                )
            }

            // ── DRM ──────────────────────────────────────────────────────────
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED ->
                EducryptError.DrmNotSupported(error.message ?: "DRM scheme not supported on this device")

            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED -> {
                // Always DrmLicenseFailed — HTTP status (401/403/etc.) is available to clients
                // via ErrorOccurred.httpStatusCode; no need to re-interpret as auth errors here.
                val httpStatus = extractHttpStatusCode(error)
                val rawMessage = error.message ?: "DRM license acquisition failed"
                EducryptError.DrmLicenseFailed(
                    if (httpStatus > 0) "DRM license acquisition failed (HTTP $httpStatus) — $rawMessage"
                    else rawMessage
                )
            }

            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED ->
                EducryptError.DrmLicenseFailed(error.message ?: "DRM provisioning failed")

            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR ->
                EducryptError.DrmLicenseFailed(error.message ?: "DRM content error")

            PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION ->
                EducryptError.DrmLicenseFailed(error.message ?: "DRM operation not allowed")

            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR ->
                EducryptError.DrmLicenseFailed(error.message ?: "DRM system error")

            // ── Format ───────────────────────────────────────────────────────
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                EducryptError.UnsupportedFormat(error.message ?: "Unsupported media format")

            // ── Decoder ──────────────────────────────────────────────────────
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
                EducryptError.DecoderError(error.message ?: "Decoder error")

            // ── Fallback ─────────────────────────────────────────────────────
            else -> EducryptError.Unknown(error.message ?: "Unknown playback error (code ${error.errorCode})")
        }
    }

    /**
     * Walks the [Throwable] cause chain looking for an
     * [HttpDataSource.InvalidResponseCodeException] and returns its HTTP status code.
     * Returns -1 if no such exception is found.
     *
     * [HttpDataSource.InvalidResponseCodeException] is the Media3 exception type that
     * carries a [responseCode] field — it is a subtype of [HttpDataSource.HttpDataSourceException].
     */
    internal fun extractHttpStatusCode(exception: PlaybackException): Int {
        var cause: Throwable? = exception
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return cause.responseCode
            }
            cause = cause.cause
        }
        return -1
    }
}
