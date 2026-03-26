package com.appsquadz.educryptmedia.player

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.appsquadz.educryptmedia.logger.EducryptEvent
import com.appsquadz.educryptmedia.logger.EducryptEventBus
import com.appsquadz.educryptmedia.util.EducryptLogger

/**
 * Builds and emits [EducryptEvent.PlayerMetaSnapshot] + [EducryptEvent.NetworkMetaSnapshot]
 * together at every playback lifecycle trigger point.
 *
 * INTERNAL — not exposed to SDK consumers.
 * All restricted API calls are wrapped in try/catch returning safe fallback values.
 * No additional permissions are required or declared.
 */
@UnstableApi
internal object MetaSnapshotBuilder {

    /**
     * Build and emit both snapshots together.
     * Call this at every trigger point in [com.appsquadz.educryptmedia.playback.EducryptMedia].
     *
     * @param trigger one of: LOADING / DRM_READY / READY / ERROR / STALL_RECOVERY / NETWORK_RECOVERY
     */
    fun emit(
        context: Context,
        videoId: String,
        videoUrl: String,
        isDrm: Boolean,
        isLive: Boolean,
        player: ExoPlayer?,
        bandwidthMeter: DefaultBandwidthMeter?,
        trigger: String,
        drmToken: String = ""
    ) {
        EducryptEventBus.emit(buildPlayerSnapshot(videoId, videoUrl, isDrm, isLive, player, trigger, drmToken))
        EducryptEventBus.emit(buildNetworkSnapshot(context, bandwidthMeter))
    }

    // ── Player snapshot ──────────────────────────────────────────────────────

    private fun buildPlayerSnapshot(
        videoId: String,
        videoUrl: String,
        isDrm: Boolean,
        isLive: Boolean,
        player: ExoPlayer?,
        trigger: String,
        drmToken: String = ""
    ): EducryptEvent.PlayerMetaSnapshot {
        var height = 0
        var width = 0
        var bitrate = 0
        var mimeType = ""

        player?.let { p ->
            try {
                val tracks = p.currentTracks
                for (group in tracks.groups) {
                    if (group.type != C.TRACK_TYPE_VIDEO) continue
                    for (i in 0 until group.length) {
                        if (!group.isTrackSelected(i)) continue
                        val format = group.getTrackFormat(i)
                        height = format.height.takeIf { it > 0 } ?: 0
                        width = format.width.takeIf { it > 0 } ?: 0
                        bitrate = format.bitrate.takeIf { it > 0 } ?: 0
                        mimeType = format.sampleMimeType ?: ""
                        break
                    }
                    if (height > 0) break
                }
            } catch (e: Exception) {
                EducryptLogger.w("PlayerMetaSnapshot: failed to read track info — ${e.message}")
            }
        }

        return EducryptEvent.PlayerMetaSnapshot(
            videoId = videoId,
            videoUrl = videoUrl,
            isDrm = isDrm,
            isLive = isLive,
            currentResolutionHeight = height,
            currentResolutionWidth = width,
            currentBitrateBps = bitrate,
            mimeType = mimeType,
            playbackTrigger = trigger,
            drmToken = drmToken
        )
    }

    // ── Network snapshot ─────────────────────────────────────────────────────

    private fun buildNetworkSnapshot(
        context: Context,
        bandwidthMeter: DefaultBandwidthMeter?
    ): EducryptEvent.NetworkMetaSnapshot {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }

        val transportType = when {
            caps == null -> "UNKNOWN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "UNKNOWN"
        }

        // NET_CAPABILITY_NOT_METERED means it IS unmetered — invert for isMetered
        val isMetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false

        val downstreamKbps = caps?.linkDownstreamBandwidthKbps ?: -1
        val upstreamKbps = caps?.linkUpstreamBandwidthKbps ?: -1

        val operatorName = try {
            tm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: ""
        } catch (e: Exception) { "" }

        val isRoaming = try {
            tm?.isNetworkRoaming ?: false
        } catch (e: Exception) { false }

        val networkGeneration = getNetworkGeneration(tm, caps)
        val signalStrength = getSignalStrength(tm)

        return EducryptEvent.NetworkMetaSnapshot(
            transportType = transportType,
            operatorName = operatorName,
            networkGeneration = networkGeneration,
            isMetered = isMetered,
            isRoaming = isRoaming,
            downstreamBandwidthKbps = downstreamKbps,
            upstreamBandwidthKbps = upstreamKbps,
            estimatedBandwidthBps = bandwidthMeter?.bitrateEstimate ?: 0L,
            signalStrength = signalStrength
        )
    }

    /**
     * Network generation (2G / 3G / 4G / 5G).
     * On API < 30: [TelephonyManager.networkType] requires READ_PHONE_STATE — not declared,
     * wrapped in try/catch, returns "UNKNOWN" on SecurityException.
     * On API 30+: [TelephonyManager.dataNetworkType] available without special permission.
     */
    @SuppressLint("MissingPermission")
    private fun getNetworkGeneration(
        tm: TelephonyManager?,
        caps: NetworkCapabilities?
    ): String {
        if (tm == null) return "UNKNOWN"
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return "WIFI"

        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                tm.dataNetworkType
            } else {
                @Suppress("DEPRECATION")
                tm.networkType
            }

            when (type) {
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT,
                TelephonyManager.NETWORK_TYPE_IDEN -> "2G"

                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_EVDO_B,
                TelephonyManager.NETWORK_TYPE_EHRPD,
                TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"

                TelephonyManager.NETWORK_TYPE_LTE -> "4G"

                TelephonyManager.NETWORK_TYPE_NR -> "5G"

                else -> "UNKNOWN"
            }
        } catch (e: SecurityException) {
            "UNKNOWN"  // READ_PHONE_STATE not granted on API < 30
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }

    /**
     * Signal strength classification using [TelephonyManager.signalStrength] (API 28+).
     * No permission required. Returns "UNKNOWN" on older APIs or if unavailable.
     */
    private fun getSignalStrength(tm: TelephonyManager?): String {
        if (tm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "UNKNOWN"
        return try {
            when (tm.signalStrength?.level) {
                4, 5 -> "STRONG"   // SIGNAL_STRENGTH_GREAT
                3    -> "MODERATE" // SIGNAL_STRENGTH_GOOD
                1, 2 -> "WEAK"     // SIGNAL_STRENGTH_POOR / MODERATE
                0    -> "WEAK"     // SIGNAL_STRENGTH_NONE_OR_UNKNOWN
                else -> "UNKNOWN"
            }
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }
}
