package com.appsquadz.educryptmedia.downloads

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.appsquadz.educryptmedia.logger.EducryptEvent
import com.appsquadz.educryptmedia.logger.EducryptEventBus
import com.appsquadz.educryptmedia.utils.DownloadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Data class representing download progress information
 */
data class DownloadProgress(
    val vdcId: String,
    val progress: Int,
    val speedBps: Long,
    val etaSeconds: Long,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val status: String,
    val errorMessage: String? = null
) {
    /**
     * Get formatted download speed string
     */
    fun getFormattedSpeed(): String {
        return when {
            speedBps >= 1_000_000 -> String.format("%.1f MB/s", speedBps / 1_000_000.0)
            speedBps >= 1_000 -> String.format("%.1f KB/s", speedBps / 1_000.0)
            else -> "$speedBps B/s"
        }
    }

    /**
     * Get formatted ETA string
     */
    fun getFormattedEta(): String {
        return when {
            etaSeconds < 0 -> "Calculating..."
            etaSeconds >= 3600 -> String.format("%dh %dm", etaSeconds / 3600, (etaSeconds % 3600) / 60)
            etaSeconds >= 60 -> String.format("%dm %ds", etaSeconds / 60, etaSeconds % 60)
            else -> "${etaSeconds}s"
        }
    }

    /**
     * Get formatted downloaded size string
     */
    fun getFormattedDownloadedSize(): String {
        return formatBytes(downloadedBytes)
    }

    /**
     * Get formatted total size string
     */
    fun getFormattedTotalSize(): String {
        return formatBytes(totalBytes)
    }

    /**
     * Get formatted progress string (e.g., "45.2 MB / 100.0 MB")
     */
    fun getFormattedProgressSize(): String {
        return "${getFormattedDownloadedSize()} / ${getFormattedTotalSize()}"
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}

/**
 * Singleton manager for download progress using LiveData and Flow
 * Provides both LiveData (for Java/older code) and StateFlow (for Kotlin coroutines)
 */
object DownloadProgressManager {

    // Map of vdcId to MutableLiveData for individual download tracking
    private val progressLiveDataMap = mutableMapOf<String, MutableLiveData<DownloadProgress>>()

    // Map of vdcId to MutableStateFlow for individual download tracking (Kotlin Flow)
    private val progressFlowMap = mutableMapOf<String, MutableStateFlow<DownloadProgress?>>()

    // LiveData for all downloads (aggregated)
    private val _allDownloadsLiveData = MutableLiveData<Map<String, DownloadProgress>>()
    val allDownloadsLiveData: LiveData<Map<String, DownloadProgress>> = _allDownloadsLiveData

    // StateFlow for all downloads (aggregated)
    private val _allDownloadsFlow = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val allDownloadsFlow: StateFlow<Map<String, DownloadProgress>> = _allDownloadsFlow.asStateFlow()

    // Current active downloads map
    private val activeDownloads = mutableMapOf<String, DownloadProgress>()

    /**
     * Get LiveData for a specific download by vdcId
     */
    fun getProgressLiveData(vdcId: String): LiveData<DownloadProgress> {
        return progressLiveDataMap.getOrPut(vdcId) {
            MutableLiveData()
        }
    }

    /**
     * Get StateFlow for a specific download by vdcId
     */
    fun getProgressFlow(vdcId: String): StateFlow<DownloadProgress?> {
        return progressFlowMap.getOrPut(vdcId) {
            MutableStateFlow(null)
        }.asStateFlow()
    }

    /**
     * Update progress for a specific download
     * This is called from VideoDownloadWorker
     */
    internal fun updateProgress(vdcId: String, progress: DownloadProgress) {
        val previousStatus = activeDownloads[vdcId]?.status
        val previousProgress = activeDownloads[vdcId]?.progress ?: -1

        // Update individual LiveData
        progressLiveDataMap.getOrPut(vdcId) {
            MutableLiveData()
        }.postValue(progress)

        // Update individual StateFlow
        progressFlowMap.getOrPut(vdcId) {
            MutableStateFlow(null)
        }.value = progress

        // Update aggregated map
        activeDownloads[vdcId] = progress
        _allDownloadsLiveData.postValue(activeDownloads.toMap())
        _allDownloadsFlow.value = activeDownloads.toMap()

        // Emit status-transition events
        if (progress.status != previousStatus) {
            when (progress.status) {
                DownloadStatus.DOWNLOADED -> EducryptEventBus.emit(EducryptEvent.DownloadCompleted(vdcId))
                DownloadStatus.FAILED -> EducryptEventBus.emit(
                    EducryptEvent.DownloadFailed(vdcId, progress.errorMessage ?: "Download failed")
                )
            }
        }

        // Emit progress milestones (25%, 50%, 75%) to avoid flooding the listener
        val milestone = when {
            progress.progress >= 75 && previousProgress < 75 -> 75
            progress.progress >= 50 && previousProgress < 50 -> 50
            progress.progress >= 25 && previousProgress < 25 -> 25
            else -> -1
        }
        if (milestone > 0) {
            EducryptEventBus.emit(EducryptEvent.DownloadProgressChanged(vdcId, progress.progress, progress.status))
        }
    }

    /**
     * Remove a download from tracking (when completed or cancelled)
     */
    fun removeDownload(vdcId: String) {
        progressLiveDataMap.remove(vdcId)
        progressFlowMap.remove(vdcId)
        activeDownloads.remove(vdcId)
        _allDownloadsLiveData.postValue(activeDownloads.toMap())
        _allDownloadsFlow.value = activeDownloads.toMap()
    }

    /**
     * Get current progress for a specific download (non-reactive)
     */
    fun getCurrentProgress(vdcId: String): DownloadProgress? {
        return activeDownloads[vdcId]
    }

    /**
     * Get all active downloads (non-reactive)
     */
    fun getAllActiveDownloads(): Map<String, DownloadProgress> {
        return activeDownloads.toMap()
    }

    /**
     * Get count of active downloads
     */
    fun getActiveDownloadCount(): Int {
        return activeDownloads.count { it.value.status == DownloadStatus.DOWNLOADING }
    }

    /**
     * Check if a download is currently active
     */
    fun isDownloadActive(vdcId: String): Boolean {
        return activeDownloads[vdcId]?.status == DownloadStatus.DOWNLOADING
    }

    /**
     * Clear all tracking data
     */
    fun clearAll() {
        progressLiveDataMap.clear()
        progressFlowMap.clear()
        activeDownloads.clear()
        _allDownloadsLiveData.postValue(emptyMap())
        _allDownloadsFlow.value = emptyMap()
    }
}
