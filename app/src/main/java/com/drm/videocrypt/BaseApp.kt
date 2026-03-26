package com.drm.videocrypt

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.net.toUri
import com.appsquadz.educryptmedia.downloads.VideoDownloadWorker
import com.appsquadz.educryptmedia.logger.EducryptEvent
import com.appsquadz.educryptmedia.playback.EducryptMedia
import com.drm.videocrypt.utils.SharedPreference
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BaseApp: Application() {

    // Application-scoped coroutine. SupervisorJob prevents one failure
    // from cancelling all other app-level coroutines.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        var appContext: Context? = null
        private const val TAG = "BaseApp"
    }


    override fun onCreate() {
        super.onCreate()
        appContext = this

        // SDK init — one line. SDK manages everything from here.
        EducryptMedia.init(this)

        createDownloadNotificationChannel(this)

        // Always-on collection. Lives for the process lifetime.
        // Every SDK event arrives here regardless of which screen is active.
        appScope.launch {
            EducryptMedia.events.collect { event ->
                when (event) {
                    is EducryptEvent.SdkError ->
                        Log.e(TAG, "[SDK_ERROR] ${event.code}: ${event.message}")
                    is EducryptEvent.ErrorOccurred ->
                        Log.e(TAG, "[ERROR] ${event.code} fatal=${event.isFatal} retrying=${event.isRetrying} " +
                            "exoCode=${event.exoPlayerErrorCode} http=${event.httpStatusCode} " +
                            "msg=${event.message}")
                    is EducryptEvent.NetworkRestored ->
                        Log.i(TAG, "[NETWORK] restored — attempting playback recovery")
                    is EducryptEvent.RetryAttempted ->
                        Log.w(TAG, "[RETRY] #${event.attemptNumber} delay=${event.delayMs}ms " +
                            "type=${event.dataType} url=${event.failedUrl} reason=${event.reason}")
                    is EducryptEvent.DrmReady ->
                        Log.i(TAG, "[DRM] infrastructure ready licenseUrl=${event.licenseUrl}")
                    is EducryptEvent.DrmLicenseAcquired ->
                        Log.i(TAG, "[DRM] license acquired videoId=${event.videoId} licenseUrl=${event.licenseUrl}")
                    is EducryptEvent.StallDetected ->
                        Log.w(TAG, "[STALL] count=${event.stallCount}")
                    is EducryptEvent.SafeModeEntered ->
                        Log.w(TAG, "[SAFE_MODE] entered: ${event.reason}")
                    is EducryptEvent.SafeModeExited ->
                        Log.d(TAG, "[SAFE_MODE] exited after ${event.stablePlaybackMs}ms")
                    is EducryptEvent.QualityChanged ->
                        Log.d(TAG, "[QUALITY] ${event.fromHeight}p→${event.toHeight}p reason=${event.reason}")
                    is EducryptEvent.BandwidthEstimated ->
                        Log.d(TAG, "[BW] ${formatBandwidth(event.bandwidthBps)}")
                    is EducryptEvent.DownloadProgressChanged ->
                        Log.d(TAG, "[DL] progress: ${event.vdcId} ${event.progress}% status=${event.status}")
                    is EducryptEvent.DownloadCompleted ->
                        Log.d(TAG, "[DL] done: ${event.vdcId}")
                    is EducryptEvent.DownloadFailed ->
                        Log.e(TAG, "[DL] failed: ${event.vdcId} — ${event.message}")
                    is EducryptEvent.DownloadCancelled ->
                        Log.i(TAG, "[DL] cancelled: ${event.vdcId}")
                    is EducryptEvent.DownloadDeleted ->
                        Log.i(TAG, "[DL] deleted: ${event.vdcId}")
                    is EducryptEvent.PlayerMetaSnapshot ->
                        Log.d(TAG, "[PLAYER_META] trigger=${event.playbackTrigger} " +
                            "videoId=${event.videoId} " +
                            "url=${event.videoUrl} " +
                            "drm=${event.isDrm} live=${event.isLive} " +
                            "${event.currentResolutionWidth}x${event.currentResolutionHeight} " +
                            "${formatBandwidth(event.currentBitrateBps.toLong())} " +
                            "mime=${event.mimeType} " +
                            "token=${event.drmToken.ifEmpty { "<none>" }}")
                    is EducryptEvent.NetworkMetaSnapshot ->
                        Log.d(TAG, "[NETWORK_META] ${event.transportType} " +
                            "gen=${event.networkGeneration} " +
                            "op=${event.operatorName} " +
                            "roaming=${event.isRoaming} " +
                            "metered=${event.isMetered} " +
                            "signal=${event.signalStrength} " +
                            "down=${formatBandwidth(event.downstreamBandwidthKbps * 1000L)} " +
                            "up=${formatBandwidth(event.upstreamBandwidthKbps * 1000L)} " +
                            "est=${formatBandwidth(event.estimatedBandwidthBps)})")
                    else ->
                        Log.v(TAG, "[EVENT] ${event::class.simpleName}")
                }
            }
        }

        // Clean up stale downloads on app startup
        cleanupStaleDownloads()
    }

    private fun cleanupStaleDownloads() {
        try {
            // Clean up Realm database stale records
            EducryptMedia.getInstance(this).cleanupStaleDownloads { removedCount ->
                if (removedCount > 0) {
                    Log.i(TAG, "Cleaned up $removedCount stale Realm records")
                }
            }

            // Clean up SharedPreference if file doesn't exist
            cleanupSharedPreferenceDownload()
        } catch (e: Exception) {
            Log.e(TAG, "Error during stale download cleanup", e)
        }
    }

    private fun cleanupSharedPreferenceDownload() {
        try {
            val downloadData = SharedPreference.instance.getDownloadData()
            if (downloadData != null) {
                val fileName = downloadData.url?.toUri()?.lastPathSegment
                if (!fileName.isNullOrEmpty()) {
                    val file = File(getExternalFilesDir(null), "$fileName.mp4")
                    if (!file.exists() && downloadData.status == "downloaded") {
                        // File was deleted, clear the SharedPreference
                        Log.i(TAG, "Clearing stale SharedPreference download: ${downloadData.vdcId}")
                        SharedPreference.instance.setDownloadData(null)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning SharedPreference download", e)
        }
    }

    private fun formatBandwidth(bps: Long): String {
        val kbps = bps / 1000
        return if (kbps >= 1000) {
            val mbps = kbps / 1000.0
            "%.1fMbps".format(mbps)
        } else {
            "${kbps}Kbps"
        }
    }

    fun createDownloadNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                VideoDownloadWorker.CHANNEL_NAME,
                "Video Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Download progress notifications"
            }

            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}