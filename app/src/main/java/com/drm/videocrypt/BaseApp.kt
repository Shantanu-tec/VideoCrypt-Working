package com.drm.videocrypt

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.appsquadz.educryptmedia.downloads.VideoDownloadWorker

class BaseApp: Application() {

    companion object {
        var appContext: Context? = null
    }


    override fun onCreate() {
        super.onCreate()
        appContext = this
        createDownloadNotificationChannel(this)
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