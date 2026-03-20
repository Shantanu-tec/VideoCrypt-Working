package com.appsquadz.educryptmedia.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.appsquadz.educryptmedia.logger.EducryptEvent
import com.appsquadz.educryptmedia.logger.EducryptEventBus
import com.appsquadz.educryptmedia.util.EducryptLogger

/**
 * Watches for network restoration after a fatal playback error.
 *
 * Registers a [ConnectivityManager.NetworkCallback] on [startWatching] and
 * fires [onRestored] exactly once when a validated internet connection becomes available.
 * The callback is unregistered automatically after the first restoration.
 *
 * Only active while [isWatching] is true — safe to call [stopWatching] at any time.
 */
internal class NetworkRecoveryManager(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var isWatching = false

    private var onNetworkRestored: (() -> Unit)? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Intentionally empty.
            // NET_CAPABILITY_VALIDATED is not yet set at onAvailable() time — Android adds it
            // asynchronously after the internet probe completes. onCapabilitiesChanged() fires
            // once validation succeeds and is the correct place to trigger recovery.
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            if (!isWatching) return

            val isUsable =
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            if (!isUsable) return

            EducryptLogger.d("Network validated — triggering playback recovery")

            // Capture callback BEFORE stopWatching() nulls onNetworkRestored
            val pendingCallback = onNetworkRestored
            stopWatching()

            EducryptEventBus.emit(EducryptEvent.NetworkRestored)
            pendingCallback?.invoke()
        }

        override fun onLost(network: Network) {
            // Network lost again while watching — keep watching
            EducryptLogger.d("Network lost while waiting for recovery")
        }
    }

    /**
     * Start watching for network restoration.
     * [onRestored] fires once on the calling thread (binder thread from ConnectivityManager).
     * Callers must dispatch to the main thread if needed.
     *
     * No-op if already watching.
     */
    fun startWatching(onRestored: () -> Unit) {
        if (isWatching) return
        isWatching = true
        onNetworkRestored = onRestored

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
            EducryptLogger.d("Watching for network restoration")
        } catch (e: Exception) {
            EducryptLogger.e("Failed to register network callback: ${e.message}")
            isWatching = false
            onNetworkRestored = null
        }
    }

    /**
     * Stop watching. Safe to call when not watching. Swallows unregister errors
     * (callback may already be unregistered if the process lost connectivity while
     * the callback was pending).
     */
    fun stopWatching() {
        if (!isWatching) return
        isWatching = false
        onNetworkRestored = null
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Ignore — may already be unregistered
        }
    }

    /** Returns true if a recovery watch is currently active. */
    fun isWatching(): Boolean = isWatching
}
