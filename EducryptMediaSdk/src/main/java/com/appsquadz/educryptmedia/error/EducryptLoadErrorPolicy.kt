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
 * Retry schedule (1-based attempt number):
 *   Attempt 1 → delay 1 000 ms  (emits [EducryptEvent.RetryAttempted])
 *   Attempt 2 → delay 2 000 ms  (emits [EducryptEvent.RetryAttempted])
 *   Attempt 3 → delay 4 000 ms  (emits [EducryptEvent.RetryAttempted])
 *   Attempt 4+ → STOP  — [EducryptEvent.ErrorOccurred] surfaces via [EducryptPlayerListener]
 *
 * After [MAX_RETRY_COUNT] retries the policy returns [LoadErrorHandlingPolicy.C.TIME_UNSET],
 * causing ExoPlayer to propagate the error to [Player.Listener.onPlayerError], which is
 * handled by [EducryptPlayerListener] and mapped to [EducryptEvent.ErrorOccurred].
 */
@UnstableApi
internal class EducryptLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {

    companion object {
        private const val MAX_RETRY_COUNT = 3
        private const val BASE_DELAY_MS = 1_000L
        private const val MAX_DELAY_MS = 8_000L
    }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val attempt = loadErrorInfo.errorCount  // 1-based: first error = 1

        if (attempt > MAX_RETRY_COUNT) {
            return C.TIME_UNSET
        }

        // Exponential backoff: 1s, 2s, 4s — capped at MAX_DELAY_MS
        val delayMs = minOf(BASE_DELAY_MS shl (attempt - 1), MAX_DELAY_MS)

        EducryptEventBus.emit(
            EducryptEvent.RetryAttempted(
                attemptNumber = attempt,
                reason = loadErrorInfo.exception.message ?: "Load error",
                delayMs = delayMs
            )
        )

        return delayMs
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = MAX_RETRY_COUNT

}
