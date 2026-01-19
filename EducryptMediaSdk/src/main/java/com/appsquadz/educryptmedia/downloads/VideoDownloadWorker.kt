package com.appsquadz.educryptmedia.downloads

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.appsquadz.educryptmedia.module.RealmManager
import com.appsquadz.educryptmedia.realm.dao.DownloadMetaDao
import com.appsquadz.educryptmedia.realm.entity.DownloadMeta
import com.appsquadz.educryptmedia.realm.impl.DownloadMetaImpl
import com.appsquadz.educryptmedia.utils.DownloadStatus
import com.appsquadz.educryptmedia.utils.MEDIA_TAG
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.absoluteValue

class VideoDownloadWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private fun getDownloadMetaDao(): DownloadMetaDao{
        RealmManager.init(context = appContext)
        return DownloadMetaImpl(RealmManager.getRealm())
    }

    private val downloadDao by lazy {
        getDownloadMetaDao()
    }

    companion object {
        const val KEY_URL = "URL"
        const val KEY_FILENAME = "FILENAME"
        const val ACTION_PROGRESS = "DOWNLOAD_PROGRESS"
        const val ACTION_STARTED = "DOWNLOAD_STARTED"
        const val ACTION_COMPLETED = "DOWNLOAD_COMPLETED"
        const val ACTION_FAILED= "DOWNLOAD_FAILED"
        const val ACTION_DOWNLOAD = "DOWNLOAD"
        const val EXTRA_PROGRESS = "PROGRESS"
        const val CHANNEL_NAME = "download_channel"

        const val URL = "url"

        const val VDC_ID = "vdcId"

        const val ACTION = "action"
        const val NOTIFICATION_ID = 42
    }

    private var downloadName = ""

    override suspend fun doWork(): Result {

        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val fileName = inputData.getString(KEY_FILENAME) ?: "video.mp4"
        val vdc_id = inputData.getString("vdc_id") ?: ""
        downloadName = inputData.getString("downloadName") ?: ""
        val wannaShowNotification = inputData.getBoolean("notification",true)
        val file = File(applicationContext.getExternalFilesDir(null), "$fileName.mp4")

        var downloadedBytes: Long = if (file.exists()) file.length() else 0L
        val notificationId = vdc_id.hashCode().absoluteValue

        try {

            var isDownloadBytesAvailable = false
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                if (downloadedBytes > 0) {
                    isDownloadBytesAvailable = true
                    setRequestProperty("Range", "bytes=$downloadedBytes-")
                }
                connect()
            }

            val totalSize = connection.contentLength + downloadedBytes
            val inputStream = BufferedInputStream(connection.inputStream)
            val outputStream = FileOutputStream(file, true)

            val buffer = ByteArray(1024)
            var bytesRead: Int
            var lastProgress = -1


            val downloadMeta = DownloadMeta().apply {
                this.vdcId = vdc_id
                this.fileName = fileName
                this.url = url
                this.percentage = "0"
                this.status = DownloadStatus.DOWNLOADING
            }

            if (!isDownloadBytesAvailable){
                downloadDao.insertOrUpdateData(downloadMeta){}

                showNotification("Download Started", 0,notificationId.toLong().toInt(),wannaShowNotification)
                broadcastStarted(url,vdc_id)
            }

            while (inputStream.read(buffer).also { bytesRead = it } != -1 && !isStopped) {
                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead

                val progress = ((downloadedBytes * 100) / totalSize).toInt()
                if (progress != lastProgress) {
                    lastProgress = progress
                    broadcastProgress(progress,url,vdc_id)
                    showNotification("Downloading...", progress,notificationId.toLong().toInt(),wannaShowNotification)
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            if (isStopped) {
                showNotification("Download Cancelled", -1,notificationId.toLong().toInt(),wannaShowNotification)
                return Result.failure()
            }

            showNotification("Download Complete", 100,notificationId.toLong().toInt(),wannaShowNotification)
            broadcastCompleted(url,vdc_id)
            return Result.success()

        } catch (e: Exception) {
            showNotification("Download Failed", -1,notificationId.toLong().toInt(),wannaShowNotification)
            broadcastFailed(url,vdc_id)
            e.printStackTrace()
            return Result.failure()
        }
    }

    private fun broadcastProgress(progress: Int,url: String,vdcId: String) {
        downloadDao.isDataExist(vdcId){ exist ->
            if (exist){
                downloadDao.updatePercentageAndStatus(vdcId,"$progress",DownloadStatus.DOWNLOADING){}
            }
        }

        val intent = Intent(ACTION_DOWNLOAD).apply {
            putExtra(ACTION, ACTION_PROGRESS)
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(URL, url)
            putExtra(VDC_ID, vdcId)
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    private fun broadcastStarted(url: String,vdcId: String) {
        val intent = Intent(ACTION_DOWNLOAD).apply {
            putExtra(ACTION, ACTION_STARTED)
            putExtra(URL, url)
            putExtra(VDC_ID, vdcId)
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    private fun broadcastCompleted(url: String,vdcId: String) {
        downloadDao.isDataExist(vdcId){ exist ->
            if (exist){
                downloadDao.updatePercentageAndStatus(vdcId,"100",DownloadStatus.DOWNLOADED){}
            }
        }

        val intent = Intent(ACTION_DOWNLOAD).apply {
            putExtra(ACTION, ACTION_COMPLETED)
            putExtra(URL, url)
            putExtra(VDC_ID, vdcId)
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    private fun broadcastFailed(url: String,vdcId: String) {
        downloadDao.isDataExist(vdcId){ exist ->
            if (exist){
                downloadDao.updateStatus(vdcId,DownloadStatus.FAILED){}
            }
        }
        val intent = Intent(ACTION_DOWNLOAD).apply {
            putExtra(ACTION, ACTION_FAILED)
            putExtra(URL, url)
            putExtra(VDC_ID, vdcId)
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    private fun showNotification(title: String, progress: Int,notificationId: Int = NOTIFICATION_ID,wannaShowNotification:Boolean = true) {
        if (wannaShowNotification){
            val builder = NotificationCompat.Builder(applicationContext, CHANNEL_NAME)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(downloadName)
                .setContentText(title)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            if (progress in 0..99) {
                builder.setProgress(100, progress, false)
            } else {
                builder.setProgress(0, 0, false)
            }
            if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(applicationContext).notify(notificationId, builder.build())
            } else {
                Log.w(MEDIA_TAG, "Notification permission not granted")
            }
        }
    }


}
