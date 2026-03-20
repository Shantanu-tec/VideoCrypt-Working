package com.appsquadz.educryptmedia.core

import android.os.Looper
import com.appsquadz.educryptmedia.logger.EducryptEvent
import com.appsquadz.educryptmedia.logger.EducryptEventBus

internal object EducryptGuard {

    /**
     * Call at the start of every public method.
     * Returns true if SDK is ready to accept the call.
     * Returns false and emits SdkError if not — caller must return immediately.
     *
     * Usage:
     *   fun pauseDownload(vdcId: String) {
     *       if (!EducryptGuard.checkReady("pauseDownload")) return
     *       ...
     *   }
     */
    internal fun checkReady(methodName: String): Boolean {
        return when (EducryptSdkState.current()) {
            SdkState.UNINITIALISED -> {
                EducryptEventBus.emit(
                    EducryptEvent.SdkError(
                        code = "SDK_NOT_INITIALISED",
                        message = "EducryptMedia.init(context) must be called " +
                                  "before $methodName(). Add it to Application.onCreate()."
                    )
                )
                false
            }
            SdkState.SHUT_DOWN -> {
                EducryptEventBus.emit(
                    EducryptEvent.SdkError(
                        code = "SDK_SHUT_DOWN",
                        message = "$methodName() called after shutdown(). " +
                                  "Call init() again to restart the SDK."
                    )
                )
                false
            }
            SdkState.READY -> true
        }
    }

    /**
     * Validates a String parameter — null, blank, or empty all fail.
     * Emits SdkError and returns false if invalid.
     */
    internal fun checkString(
        value: String?,
        paramName: String,
        methodName: String
    ): Boolean {
        if (value.isNullOrBlank()) {
            EducryptEventBus.emit(
                EducryptEvent.SdkError(
                    code = "INVALID_INPUT",
                    message = "$methodName() received null or empty $paramName."
                )
            )
            return false
        }
        return true
    }

    /**
     * Validates an integer parameter within an allowed range.
     */
    internal fun checkIntRange(
        value: Int,
        min: Int,
        max: Int,
        paramName: String,
        methodName: String
    ): Boolean {
        if (value !in min..max) {
            EducryptEventBus.emit(
                EducryptEvent.SdkError(
                    code = "INVALID_INPUT",
                    message = "$methodName() received out-of-range $paramName: $value. " +
                              "Expected: $min..$max"
                )
            )
            return false
        }
        return true
    }

    /**
     * Asserts the caller is on the main thread.
     * ExoPlayer requires main thread for all player operations.
     * Emits SdkError and returns false if called from a background thread.
     *
     * Without this guard, concurrent calls from background threads could create
     * two ExoPlayer instances simultaneously, leaving one unreferenced and
     * never released — memory leak.
     */
    internal fun checkMainThread(methodName: String): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            EducryptEventBus.emit(
                EducryptEvent.SdkError(
                    code = "WRONG_THREAD",
                    message = "$methodName() must be called on the main thread. " +
                              "ExoPlayer requires main thread for all player operations."
                )
            )
            return false
        }
        return true
    }
}
