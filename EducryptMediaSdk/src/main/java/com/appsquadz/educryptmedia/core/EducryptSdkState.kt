package com.appsquadz.educryptmedia.core

import java.util.concurrent.atomic.AtomicReference

internal enum class SdkState {
    UNINITIALISED,
    READY,
    SHUT_DOWN
}

internal object EducryptSdkState {

    private val state = AtomicReference(SdkState.UNINITIALISED)

    internal fun transitionTo(newState: SdkState): Boolean {
        return when (newState) {
            SdkState.READY ->
                // UNINITIALISED → READY only
                state.compareAndSet(SdkState.UNINITIALISED, SdkState.READY)
            SdkState.SHUT_DOWN -> {
                // READY → SHUT_DOWN only (not UNINITIALISED → SHUT_DOWN)
                state.compareAndSet(SdkState.READY, SdkState.SHUT_DOWN)
            }
            SdkState.UNINITIALISED -> false // never transition back to UNINITIALISED
        }
    }

    internal fun current(): SdkState = state.get()

    internal fun isReady(): Boolean = state.get() == SdkState.READY

    /**
     * Resets to UNINITIALISED — only for use in shutdown() to allow
     * re-initialisation after a clean shutdown.
     */
    internal fun reset() {
        state.set(SdkState.UNINITIALISED)
    }
}
