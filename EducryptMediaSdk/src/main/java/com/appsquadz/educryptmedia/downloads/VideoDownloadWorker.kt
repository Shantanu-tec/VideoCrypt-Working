package com.appsquadz.educryptmedia.downloads

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import com.appsquadz.educryptmedia.util.EducryptLogger
import com.appsquadz.educryptmedia.utils.DownloadStatus
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

    private fun getDownloadMetaDao(): DownloadMetaDao {
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
        const val ACTION_FAILED = "DOWNLOAD_FAILED"
        const val ACTION_DOWNLOAD = "DOWNLOAD"
        const val EXTRA_PROGRESS = "PROGRESS"
        const val EXTRA_SPEED = "SPEED"
        const val EXTRA_ETA = "ETA"
        const val EXTRA_DOWNLOADED_BYTES = "DOWNLOADED_BYTES"
        const val EXTRA_TOTAL_BYTES = "TOTAL_BYTES"
        const val EXTRA_ERROR_MESSAGE = "ERROR_MESSAGE"
        const val CHANNEL_NAME = "download_channel"

        const val URL = "url"
        const val VDC_ID = "vdcId"
        const val ACTION = "action"
        const val NOTIFICATION_ID = 42

        // Connection timeouts
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 30_000

        // Adaptive buffer sizes — WiFi uses 128 KB, cellular/metered uses 32 KB.
        // BufferedInputStream and write ByteArray must always use the same size (see getBufferSize()).
        private const val BUFFER_SIZE_WIFI     = 128 * 1024  // 128 KB on WiFi
        private const val BUFFER_SIZE_CELLULAR =  32 * 1024  //  32 KB on cellular/metered

        // HTTP response codes
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_OK = 200
    }

    private var downloadName = ""

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val fileName = inputData.getString(KEY_FILENAME) ?: "video.mp4"
        val vdcId = inputData.getString("vdc_id") ?: ""
        downloadName = inputData.getString("downloadName") ?: ""
        val wannaShowNotification = inputData.getBoolean("notification", true)
        val file = File(applicationContext.getExternalFilesDir(null), "$fileName.mp4")

        val notificationId = vdcId.hashCode().absoluteValue

        val bufferSize = getBufferSize()

        // Check network connectivity first
        if (!isNetworkAvailable()) {
            val errorMessage = "No internet connection"
            EducryptLogger.e(errorMessage)
            if (file.exists()) file.delete()
            broadcastFailed(url, vdcId, errorMessage)
            showNotification("Download Failed - No Internet", -1, notificationId, wannaShowNotification)
            return Result.failure()
        }

        var downloadedBytes: Long = if (file.exists()) file.length() else 0L
        var isResuming = downloadedBytes > 0

        var connection: HttpURLConnection? = null
        var inputStream: BufferedInputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS

                if (isResuming) {
                    setRequestProperty("Range", "bytes=$downloadedBytes-")
                }
                connect()
            }

            val responseCode = connection.responseCode

            // Validate Range request support for resume
            if (isResuming) {
                when (responseCode) {
                    HTTP_PARTIAL_CONTENT -> {
                        // Server supports resume - continue from where we left off
                        EducryptLogger.d("Resuming download from byte $downloadedBytes")
                    }
                    HTTP_OK -> {
                        // Server doesn't support Range requests - start from beginning
                        EducryptLogger.w("Server doesn't support resume, starting from beginning")
                        downloadedBytes = 0L
                        isResuming = false
                        file.delete() // Delete partial file and start fresh
                    }
                    else -> {
                        val errorMessage = "Server error: $responseCode"
                        EducryptLogger.e(errorMessage)
                        if (file.exists()) file.delete()
                        broadcastFailed(url, vdcId, errorMessage)
                        showNotification("Download Failed", -1, notificationId, wannaShowNotification)
                        return Result.failure()
                    }
                }
            } else if (responseCode != HTTP_OK) {
                val errorMessage = "Server error: $responseCode"
                EducryptLogger.e(errorMessage)
                if (file.exists()) file.delete()
                broadcastFailed(url, vdcId, errorMessage)
                showNotification("Download Failed", -1, notificationId, wannaShowNotification)
                return Result.failure()
            }

            // Calculate total size correctly based on response
            val contentLength = connection.contentLengthLong
            val totalSize = if (isResuming && responseCode == HTTP_PARTIAL_CONTENT) {
                contentLength + downloadedBytes
            } else {
                contentLength
            }

            if (totalSize <= 0) {
                val errorMessage = "Invalid content length"
                EducryptLogger.e(errorMessage)
                if (file.exists()) file.delete()
                broadcastFailed(url, vdcId, errorMessage)
                showNotification("Download Failed", -1, notificationId, wannaShowNotification)
                return Result.failure()
            }

            inputStream = BufferedInputStream(connection.inputStream, bufferSize)
            outputStream = FileOutputStream(file, isResuming)

            val buffer = ByteArray(bufferSize)
            var bytesRead: Int
            var lastProgress = -1
            var lastBroadcastTime = System.currentTimeMillis()
            var bytesDownloadedSinceLastUpdate = 0L
            val speedUpdateIntervalMs = 1000L // Update speed every second
            val speedSamples = ArrayDeque<Long>(5)  // Rolling average for ETA smoothing
            var networkCheckCounter = 0

            val downloadMeta = DownloadMeta().apply {
                this.vdcId = vdcId
                this.fileName = fileName
                this.url = url
                this.percentage = "0"
                this.status = DownloadStatus.DOWNLOADING
                this.totalBytes = totalSize
                this.downloadedBytes = 0L
            }

            if (!isResuming) {
                downloadDao.insertOrUpdateData(downloadMeta) {}
                showNotification("Download Started", 0, notificationId, wannaShowNotification)
                broadcastStarted(url, vdcId, totalSize)
            } else {
                // Update status for resumed download
                downloadDao.updateStatus(vdcId, DownloadStatus.DOWNLOADING) {}
                val resumeProgress = ((downloadedBytes * 100) / totalSize).toInt()
                showNotification("Resuming Download...", resumeProgress, notificationId, wannaShowNotification)
            }

            while (inputStream.read(buffer).also { bytesRead = it } != -1 && !isStopped) {
                // Check network every 50 iterations (~1.6 MB on cellular, ~6.4 MB on WiFi)
                // to avoid calling ConnectivityManager.getNetworkCapabilities() on every chunk.
                networkCheckCounter++
                if (networkCheckCounter % 50 == 0 && !isNetworkAvailable()) {
                    val errorMessage = "Network connection lost"
                    EducryptLogger.e(errorMessage)
                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()
                    broadcastRetrying(url, vdcId, errorMessage)
                    showNotification("Download Failed - Connection Lost", -1, notificationId, wannaShowNotification)
                    return Result.retry() // Retry when network is available
                }

                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead
                bytesDownloadedSinceLastUpdate += bytesRead

                val progress = ((downloadedBytes * 100) / totalSize).toInt()
                val currentTime = System.currentTimeMillis()
                val timeDiff = currentTime - lastBroadcastTime

                // Update progress, speed, and ETA periodically
                if (progress != lastProgress || timeDiff >= speedUpdateIntervalMs) {
                    lastProgress = progress

                    // Calculate download speed with 5-sample rolling average for ETA stability
                    val instantSpeed = if (timeDiff > 0) {
                        (bytesDownloadedSinceLastUpdate * 1000) / timeDiff
                    } else 0L

                    if (instantSpeed > 0) {
                        if (speedSamples.size >= 5) speedSamples.removeFirst()
                        speedSamples.addLast(instantSpeed)
                    }
                    val speedBps = if (speedSamples.isNotEmpty())
                        speedSamples.average().toLong() else instantSpeed

                    // Calculate ETA (seconds remaining)
                    val remainingBytes = totalSize - downloadedBytes
                    val etaSeconds = if (speedBps > 0) {
                        remainingBytes / speedBps
                    } else -1L

                    broadcastProgress(progress, url, vdcId, speedBps, etaSeconds, downloadedBytes, totalSize)

                    val speedText = formatSpeed(speedBps)
                    val etaText = if (etaSeconds > 0) formatEta(etaSeconds) else ""
                    val notificationText = if (etaText.isNotEmpty()) {
                        "Downloading... $speedText - $etaText remaining"
                    } else {
                        "Downloading... $speedText"
                    }
                    showNotification(notificationText, progress, notificationId, wannaShowNotification)

                    // Reset for next update
                    lastBroadcastTime = currentTime
                    bytesDownloadedSinceLastUpdate = 0L
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()
            connection.disconnect()

            if (isStopped) {
                showNotification("Download Paused", lastProgress, notificationId, wannaShowNotification)
                downloadDao.updateStatus(vdcId, DownloadStatus.PAUSED) {}
                return Result.failure()
            }

            // Cancel the progress notification and show completion notification
            cancelNotification(notificationId)
            showCompletionNotification(notificationId, wannaShowNotification)
            broadcastCompleted(url, vdcId, totalSize)
            return Result.success()

        } catch (e: java.net.SocketTimeoutException) {
            val errorMessage = "Connection timed out"
            EducryptLogger.e(errorMessage, e)
            broadcastRetrying(url, vdcId, errorMessage)
            showNotification("Download Failed - Timeout", -1, notificationId, wannaShowNotification)
            return Result.retry()
        } catch (e: java.net.UnknownHostException) {
            val errorMessage = "Unable to connect to server"
            EducryptLogger.e(errorMessage, e)
            broadcastRetrying(url, vdcId, errorMessage)
            showNotification("Download Failed - No Connection", -1, notificationId, wannaShowNotification)
            return Result.retry()
        } catch (e: java.io.IOException) {
            val errorMessage = "Network error: ${e.message}"
            EducryptLogger.e(errorMessage, e)
            broadcastRetrying(url, vdcId, errorMessage)
            showNotification("Download Failed", -1, notificationId, wannaShowNotification)
            return Result.retry()
        } catch (e: Exception) {
            val errorMessage = e.message ?: "Unknown error"
            EducryptLogger.e(errorMessage, e)
            if (file.exists()) file.delete()
            broadcastFailed(url, vdcId, errorMessage)
            showNotification("Download Failed", -1, notificationId, wannaShowNotification)
            return Result.failure()
        } finally {
            try {
                inputStream?.close()
                outputStream?.close()
                connection?.disconnect()
            } catch (e: Exception) {
                EducryptLogger.e("Error closing streams", e)
            }
        }
    }

    private fun getBufferSize(): Int {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return BUFFER_SIZE_CELLULAR
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return BUFFER_SIZE_CELLULAR)
            ?: return BUFFER_SIZE_CELLULAR
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            BUFFER_SIZE_WIFI else BUFFER_SIZE_CELLULAR
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return false

        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond >= 1_000_000 -> String.format("%.1f MB/s", bytesPerSecond / 1_000_000.0)
            bytesPerSecond >= 1_000 -> String.format("%.1f KB/s", bytesPerSecond / 1_000.0)
            else -> "$bytesPerSecond B/s"
        }
    }

    private fun formatEta(seconds: Long): String {
        return when {
            seconds >= 3600 -> String.format("%dh %dm", seconds / 3600, (seconds % 3600) / 60)
            seconds >= 60 -> String.format("%dm %ds", seconds / 60, seconds % 60)
            else -> "${seconds}s"
        }
    }

    private fun broadcastProgress(
        progress: Int,
        url: String,
        vdcId: String,
        speedBps: Long,
        etaSeconds: Long,
        downloadedBytes: Long,
        totalBytes: Long
    ) {
        downloadDao.isDataExist(vdcId) { exist ->
            if (exist) {
                downloadDao.updateProgress(vdcId, "$progress", downloadedBytes, DownloadStatus.DOWNLOADING) {}
            }
        }

        // Update LiveData/Flow
        DownloadProgressManager.updateProgress(
            vdcId,
            DownloadProgress(
                vdcId = vdcId,
                progress = progress,
                speedBps = speedBps,
                etaSeconds = etaSeconds,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                status = DownloadStatus.DOWNLOADING
            )
        )

        // Keep LocalBroadcastManager for backward compatibility
        val intent = Intent(ACTION_DOWNLOAD).apply {
            putExtra(ACTION, ACTION_PROGRESS)
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_SPEED, speedBps)
            putExtra(EXTRA_ETA, etaSeconds)
            putExtra(EXTRA_DOWNLOADED_BYTES, downloadedBytes)
            putExtra(EXTRA_TOTAL_BYTES, totalBytes)
            putExtra(URL, url)
            putExtra(VDC_ID, vdcId)
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    private fun broadcastStarted(url: String, vdcId: String, totalBytes: Long) {
        // Update LiveData/Flow
        DownloadProgressManager.updateProgress(
            vdcId,
            DownloadProgress(
                vdcId = vdcId,
                progress = 0,
                speedBps = 0,
                etaSeconds = -1,
                downloadedBytes = 0,
                totalBytes = totalBytes,
                status = DownloadStatus.DOWNLOADING
            )
        )

        val intent = Intent(ACTION_DOWNLOAD).apply {
            putExtra(ACTION, ACTION_STARTED)
            putExtra(URL, url)
            putExtra(VDC_ID, vdcId)
            putExtra(EXTRA_TOTAL_BYTES, totalBytes)
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    private fun broadcastCompleted(url: String, vdcId: String, totalBytes: Long) {
        downloadDao.isDataExist(vdcId) { exist ->
            if (exist) {
                downloadDao.updateProgress(vdcId, "100", totalBytes, DownloadStatus.DOWNLOADED) {}
            }
        }

        // Update LiveData/Flow
        DownloadProgressManager.updateProgress(
            vdcId,
            DownloadProgress(
                vdcId = vdcId,
                progress = 100,
                speedBps = 0,
                etaSeconds = 0,
                downloadedBytes = totalBytes,
                totalBytes = totalBytes,
                status = DownloadStatus.DOWNLOADED
            )
        )

        val intent = Intent(ACTION_DOWNLOAD).apply {
            putExtra(ACTION, ACTION_COMPLETED)
            putExtra(URL, url)
            putExtra(VDC_ID, vdcId)
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    private fun broadcastFailed(url: String, vdcId: String, errorMessage: String = "Download failed") {
        downloadDao.isDataExist(vdcId) { exist ->
            if (exist) {
                downloadDao.updateStatus(vdcId, DownloadStatus.FAILED) {}
            }
        }

        // Update LiveData/Flow
        DownloadProgressManager.updateProgress(
            vdcId,
            DownloadProgress(
                vdcId = vdcId,
                progress = -1,
                speedBps = 0,
                etaSeconds = -1,
                downloadedBytes = 0,
                totalBytes = 0,
                status = DownloadStatus.FAILED,
                errorMessage = errorMessage
            )
        )

        val intent = Intent(ACTION_DOWNLOAD).apply {
            putExtra(ACTION, ACTION_FAILED)
            putExtra(URL, url)
            putExtra(VDC_ID, vdcId)
            putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    /**
     * Used on Result.retry() paths — keeps status as DOWNLOADING so the UI does not
     * flicker to FAILED while WorkManager is waiting to retry.
     */
    private fun broadcastRetrying(url: String, vdcId: String, errorMessage: String) {
        // Do NOT update Realm status — it stays DOWNLOADING; worker will retry
        DownloadProgressManager.updateProgress(
            vdcId,
            DownloadProgress(
                vdcId = vdcId,
                progress = -1,
                speedBps = 0,
                etaSeconds = -1,
                downloadedBytes = 0,
                totalBytes = 0,
                status = DownloadStatus.DOWNLOADING,
                errorMessage = errorMessage
            )
        )

        val intent = Intent(ACTION_DOWNLOAD).apply {
            putExtra(ACTION, ACTION_FAILED)
            putExtra(URL, url)
            putExtra(VDC_ID, vdcId)
            putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    private fun showNotification(
        title: String,
        progress: Int,
        notificationId: Int = NOTIFICATION_ID,
        wannaShowNotification: Boolean = true
    ) {
        if (wannaShowNotification) {
            val isFailed = progress == -1

            val builder = NotificationCompat.Builder(applicationContext, CHANNEL_NAME)
                .setContentTitle(downloadName)
                .setContentText(title)
                .setOnlyAlertOnce(true)
                .setPriority(if (isFailed) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setAutoCancel(false)
                .setProgress(100, progress.coerceIn(0, 100), false)

            if (isFailed) {
                builder.setSmallIcon(android.R.drawable.stat_notify_error)
                builder.setOngoing(false)
                builder.setAutoCancel(true)
                builder.setProgress(0, 0, false)
            }

            if (ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(applicationContext).notify(notificationId, builder.build())
            } else {
                EducryptLogger.w("Notification permission not granted")
            }
        }
    }

    private fun cancelNotification(notificationId: Int) {
        NotificationManagerCompat.from(applicationContext).cancel(notificationId)
    }

    private fun showCompletionNotification(
        notificationId: Int,
        wannaShowNotification: Boolean = true
    ) {
        if (wannaShowNotification) {
            val builder = NotificationCompat.Builder(applicationContext, CHANNEL_NAME)
                .setContentTitle(downloadName)
                .setContentText("Download Complete")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setOngoing(false)
                .setOnlyAlertOnce(false) // Always alert for completion

            if (ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(applicationContext).notify(notificationId, builder.build())
                EducryptLogger.d("Download complete notification shown for: $downloadName")
            } else {
                EducryptLogger.w("Notification permission not granted")
            }
        }
    }
}
