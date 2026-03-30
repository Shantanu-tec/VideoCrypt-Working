package com.appsquadz.educryptmedia.error

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.appsquadz.educryptmedia.logger.EducryptEvent
import com.appsquadz.educryptmedia.logger.EducryptEventBus

/**
 * Custom [DefaultLoadErrorHandlingPolicy] for DRM / DASH media sources.
 *
 * Retry schedule on normal signal (1-based attempt number):
 *   Attempt 1 → delay 1 000 ms  (emits [EducryptEvent.RetryAttempted])
 *   Attempt 2 → delay 2 000 ms  (emits [EducryptEvent.RetryAttempted])
 *   Attempt 3 → delay 4 000 ms  (emits [EducryptEvent.RetryAttempted])
 *   Attempt 4+ → STOP  — [EducryptEvent.ErrorOccurred] surfaces via [EducryptPlayerListener]
 *
 * On WEAK signal: up to 5 retries, delays extend to 1 / 2 / 4 / 8 / 16 s.
 *
 * After [maxRetryCount] retries the policy returns [C.TIME_UNSET],
 * causing ExoPlayer to propagate the error to [Player.Listener.onPlayerError], which is
 * handled by [EducryptPlayerListener] and mapped to [EducryptEvent.ErrorOccurred].
 */
@UnstableApi
internal class EducryptLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {

    companion object {
        private const val MAX_RETRY_COUNT_NORMAL = 3
        private const val MAX_RETRY_COUNT_WEAK   = 5
        private const val BASE_DELAY_MS = 1_000L
        private const val MAX_DELAY_MS = 16_000L

        private fun maxRetryCount(): Int =
            if (EducryptEventBus.currentSignalStrength == "WEAK")
                MAX_RETRY_COUNT_WEAK else MAX_RETRY_COUNT_NORMAL
    }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val attempt = loadErrorInfo.errorCount  // 1-based: first error = 1

        if (attempt > maxRetryCount()) {
            return C.TIME_UNSET
        }

        // Exponential backoff: 1s, 2s, 4s — capped at MAX_DELAY_MS
        val delayMs = minOf(BASE_DELAY_MS shl (attempt - 1), MAX_DELAY_MS)

        EducryptEventBus.emit(
            EducryptEvent.RetryAttempted(
                attemptNumber = attempt,
                reason = loadErrorInfo.exception.message ?: "Load error",
                delayMs = delayMs,
                failedUrl = loadErrorInfo.loadEventInfo.uri.toString(),
                dataType = dataTypeName(loadErrorInfo.mediaLoadData.dataType)
            )
        )

        return delayMs
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = MAX_RETRY_COUNT_WEAK

    private fun dataTypeName(dataType: Int): String = when (dataType) {
        C.DATA_TYPE_MEDIA                -> "MEDIA"
        C.DATA_TYPE_MANIFEST             -> "MANIFEST"
        C.DATA_TYPE_DRM                  -> "DRM_LICENSE"
        C.DATA_TYPE_AD                   -> "AD"
        C.DATA_TYPE_TIME_SYNCHRONIZATION -> "TIME_SYNC"
        else                             -> "UNKNOWN($dataType)"
    }

}
