package com.drm.videocrypt

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.appsquadz.educryptmedia.downloads.VideoDownloadWorker
import com.appsquadz.educryptmedia.logger.EducryptEvent
import com.appsquadz.educryptmedia.playback.EducryptMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BaseApp: Application() {

    companion object {
        var appContext: Context? = null
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        appContext = this
        EducryptMedia.init(this)
        createDownloadNotificationChannel(this)

        try {
            EducryptMedia.getInstance(this).cleanupStaleDownloads { removedCount ->
                if (removedCount > 0) Log.i("App", "Cleaned $removedCount stale records")
            }
        } catch (e: Exception) {
            Log.e("App", "Stale download cleanup failed", e)
        }

        appScope.launch {
            EducryptMedia.events.collect { event ->
                when (event) {
                    is EducryptEvent.SdkError ->
                        Log.e("EducryptSDK", "[SDK_ERROR] ${event.code}: ${event.message}")
                    is EducryptEvent.ErrorOccurred ->
                        Log.e("EducryptSDK", "[ERROR] ${event.code} fatal=${event.isFatal} retrying=${event.isRetrying}")
                    is EducryptEvent.RetryAttempted ->
                        Log.w("EducryptSDK", "[RETRY] #${event.attemptNumber} delay=${event.delayMs}ms reason=${event.reason}")
                    is EducryptEvent.StallDetected ->
                        Log.w("EducryptSDK", "[STALL] count=${event.stallCount}")
                    is EducryptEvent.SafeModeEntered ->
                        Log.w("EducryptSDK", "[SAFE_MODE] entered: ${event.reason}")
                    is EducryptEvent.SafeModeExited ->
                        Log.d("EducryptSDK", "[SAFE_MODE] exited after ${event.stablePlaybackMs}ms")
                    is EducryptEvent.QualityChanged ->
                        Log.d("EducryptSDK", "[QUALITY] ${event.fromHeight}p→${event.toHeight}p reason=${event.reason}")
                    is EducryptEvent.BandwidthEstimated ->
                        Log.d("EducryptSDK", "[BW] ${event.bandwidthBps / 1000}Kbps")
                    is EducryptEvent.DownloadProgressChanged ->
                        Log.d("EducryptSDK", "[DL] progress: ${event.vdcId} ${event.progress}% status=${event.status}")
                    is EducryptEvent.DownloadCompleted ->
                        Log.d("EducryptSDK", "[DL] done: ${event.vdcId}")
                    is EducryptEvent.DownloadFailed ->
                        Log.e("EducryptSDK", "[DL] failed: ${event.vdcId} — ${event.message}")
                    is EducryptEvent.DownloadCancelled ->
                        Log.i("EducryptSDK", "[DL] cancelled: ${event.vdcId}")
                    is EducryptEvent.NetworkRestored ->
                        Log.i("EducryptSDK", "[NETWORK] restored — attempting playback recovery")

                    is EducryptEvent.DownloadDeleted ->
                        Log.i("EducryptSDK", "[DL] deleted: ${event.vdcId}")

                    is EducryptEvent.PlayerMetaSnapshot ->
                        Log.d("EducryptSDK", "[PLAYER_META] trigger=${event.playbackTrigger} " +
                                "videoId=${event.videoId} url=${event.videoUrl} " +
                                "drm=${event.isDrm} live=${event.isLive} " +
                                "${event.currentResolutionWidth}x${event.currentResolutionHeight} " +
                                "${event.currentBitrateBps / 1000}Kbps mime=${event.mimeType}")

                    is EducryptEvent.NetworkMetaSnapshot ->
                        Log.d("EducryptSDK", "[NETWORK_META] ${event.transportType} " +
                                "gen=${event.networkGeneration} op=${event.operatorName} " +
                                "roaming=${event.isRoaming} metered=${event.isMetered} " +
                                "signal=${event.signalStrength} " +
                                "down=${event.downstreamBandwidthKbps}Kbps " +
                                "up=${event.upstreamBandwidthKbps}Kbps " +
                                "est=${event.estimatedBandwidthBps / 1000}Kbps")
                    else ->
                        Log.v("EducryptSDK", "[EVENT] ${event::class.simpleName}")
                }
            }
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
