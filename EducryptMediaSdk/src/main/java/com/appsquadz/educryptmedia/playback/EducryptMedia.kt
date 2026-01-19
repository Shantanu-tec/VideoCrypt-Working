package com.appsquadz.educryptmedia.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.DrmConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector.Parameters
import androidx.media3.exoplayer.trackselection.MappingTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.appsquadz.educryptmedia.ApiClient
import com.appsquadz.educryptmedia.EncryptionData
import com.appsquadz.educryptmedia.NetworkManager
import com.appsquadz.educryptmedia.downloads.VideoDownloadWorker
import com.appsquadz.educryptmedia.models.Downloads
import com.appsquadz.educryptmedia.models.VideoPlayback
import com.appsquadz.educryptmedia.module.RealmManager
import com.appsquadz.educryptmedia.realm.dao.DownloadMetaDao
import com.appsquadz.educryptmedia.realm.entity.DownloadMeta
import com.appsquadz.educryptmedia.realm.impl.DownloadMetaImpl
import com.appsquadz.educryptmedia.utils.AesDataSource
import com.appsquadz.educryptmedia.utils.MEDIA_TAG
import com.appsquadz.educryptmedia.utils.SanitizingPlaylistDataSourceFactory
import com.appsquadz.educryptmedia.utils.getCipher
import com.appsquadz.educryptmedia.utils.hitApi
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder

class EducryptMedia private constructor(private val context: Context) {


    private fun getDownloadMetaDao(): DownloadMetaDao {
        RealmManager.init(context = context)
        return DownloadMetaImpl(RealmManager.getRealm())
    }

    private val downloadDao by lazy {
        getDownloadMetaDao()
    }

    @UnstableApi
    private var drmSessionManager: DefaultDrmSessionManager? = null

    @UnstableApi
    private var dataSourceFactory: DefaultHttpDataSource.Factory? = null

    @UnstableApi
    private var drmCallback: HttpMediaDrmCallback? = null

    @UnstableApi
    private var mediaItem: MediaItem? = null

    @UnstableApi
    private var mediaSource: MediaSource? = null

    @UnstableApi
    private var dashMediaSourceFactory: DashMediaSource.Factory? = null

    @UnstableApi
    fun getDrmSessionManager(): DefaultDrmSessionManager? {
        return drmSessionManager
    }

    @UnstableApi
    fun getDataSourceFactory(): DefaultHttpDataSource.Factory? {
        return dataSourceFactory
    }

    @UnstableApi
    fun getDrmCallback(): HttpMediaDrmCallback? {
        return drmCallback
    }

    @UnstableApi
    fun getMediaItem(): MediaItem? {
        return mediaItem
    }

    @UnstableApi
    fun getMediaSource(): MediaSource? {
        return mediaSource
    }

    @UnstableApi
    fun getDrmSessionManagerProvider(): DashMediaSource.Factory? {
        return dashMediaSourceFactory
    }


    companion object {
        @Volatile
        private var INSTANCE: EducryptMedia? = null

        private var context: Context?=null

        fun getInstance(context: Context): EducryptMedia {
            this.context = context
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: EducryptMedia(context.applicationContext).also { INSTANCE = it }
            }
        }

        var currentSpeedPosition: Int = 1
        var currentResolutionPosition: Int = 0

        fun isNetworkAvailable(): Boolean {
            val connectivityManager = context?.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? ConnectivityManager ?: return false

            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        fun createCache(): Cache? {
            return context?.let {
                val cacheSize = 10 * 1024 * 1024L // 10 MB
                Cache(it.cacheDir, cacheSize)
            }
        }
    }




    private fun setValuesToDefault() {
        currentSpeedPosition = 1
        currentResolutionPosition = 0
    }

    @UnstableApi
    fun initializeDrmPlayback(videoUrl: String, token: String) {
        setValuesToDefault()
        dataSourceFactory = DefaultHttpDataSource.Factory()
        val postParameters = mutableMapOf<String, String>()
        postParameters["pallycon-customdata-v2"] = token
        dataSourceFactory?.setDefaultRequestProperties(postParameters)
        drmCallback = HttpMediaDrmCallback(drmLicenseUrl, dataSourceFactory!!)

        drmSessionManager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(
                C.WIDEVINE_UUID,
                FrameworkMediaDrm.DEFAULT_PROVIDER
            )
            .build(drmCallback!!)

        val url = "https://d22vhzp5gnxc1w.cloudfront.net/pankaj-dt/drm-dash/vr5/SampleTimeMachine.mpd"

        mediaItem = MediaItem.Builder()
            .setUri(url)
            .setDrmConfiguration(
                DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .build()
            ).build()


        dashMediaSourceFactory = DashMediaSource.Factory(dataSourceFactory!!)

        mediaSource = dashMediaSourceFactory?.setDrmSessionManagerProvider { drmSessionManager!! }
            ?.createMediaSource(mediaItem!!)

    }

    suspend fun sanitizeMasterPlaylist(masterUrl: String): MediaItem {
        val client = OkHttpClient()

        val request = Request.Builder()
            .url(masterUrl)
            .build()

        val response = client.newCall(request).execute()
        val master = response.body!!.string()
        response.close()

        // Remove the CODECS attribute entirely from the playlist
        val sanitized = master.replace(Regex("""CODECS="[^"]+"""", RegexOption.IGNORE_CASE), "")

        val encoded = URLEncoder.encode(sanitized, "UTF-8")
        val dataUri =
            "data:application/vnd.apple.mpegurl;charset=utf-8,$encoded"

        return MediaItem.Builder()
            .setUri(dataUri)
            .setMimeType(MimeTypes.APPLICATION_M3U8) // ✔ Tells Media3 this is HLS
            .build()
    }



    @UnstableApi
    fun initializeNonDrmPlayback(videoUrl: String) {
        Log.e("VideoUrl","videoUrl : $videoUrl")
        println("videoUrl : $videoUrl")
        setValuesToDefault()
//        dataSourceFactory = DefaultHttpDataSource.Factory()
//
//        mediaSource = HlsMediaSource.Factory(dataSourceFactory!!)
//            .createMediaSource(MediaItem.fromUri(videoUrl))

//        withContext(Dispatchers.IO){
//            mediaItem = sanitizeMasterPlaylist(videoUrl)
//        }

//        val httpFactory = DefaultHttpDataSource.Factory()
//
//        val sanitizeFactory = SanitizingPlaylistDataSourceFactory(
//            httpFactory
//        ) { playlistText ->
//            // 👇 modify playlist text here
//            playlistText.replace("bad.cdn.com", "good.cdn.com")
//        }
//
//        val mediaSourceFactory = HlsMediaSource.Factory(sanitizeFactory)
//
//        mediaSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(videoUrl))



        mediaItem = MediaItem.Builder()
            .setUri(videoUrl).build()
    }

    @UnstableApi
    fun initializeNonDrmDownloadPlayback(videoId: String, videoUrl: String) {
        isLive = false
        setValuesToDefault()
        downloadDao.isDataExist(vdcId = videoId) { exist ->
            if (exist) {
                val aesDataSource = AesDataSource(getCipher(videoId.split("_")[2]))

                val factory: DataSource.Factory = DataSource.Factory { aesDataSource }

                mediaSource = ProgressiveMediaSource.Factory(
                    factory,
                    DefaultExtractorsFactory()
                ).createMediaSource(MediaItem.fromUri(videoUrl))
            } else {
                Log.w(MEDIA_TAG, "Download not found: $videoId")
            }
        }

    }

    var isLive = false


    private var drmLicenseUrl = "https://license.videocrypt.com/validateLicense"

    fun setDrmLicenceUrl(drmLicenseUrl: String) {
        this.drmLicenseUrl = drmLicenseUrl
    }

    inner class MediaLoaderBuilder {

        private var videoId: String? = null
        private var accessKey: String? = null
        private var secretKey: String? = null
        private var userId: String? = null
        private var deviceType: String? = null
        private var deviceId: String? = null
        private var version: String? = null
        private var deviceName: String? = null
        private var accountId: String? = null

        private var drmCallback: (() -> Unit)? = null
        private var nonDrmCallback: (() -> Unit)? = null

        fun setVideoId(videoId: String?) = apply { this.videoId = videoId }
        fun setAccessKey(accessKey: String?) = apply { this.accessKey = accessKey }
        fun setSecretKey(secretKey: String?) = apply { this.secretKey = secretKey }
        fun setUserId(userId: String?) = apply { this.userId = userId }
        fun setDeviceType(deviceType: String?) = apply { this.deviceType = deviceType }
        fun setDeviceId(deviceId: String?) = apply { this.deviceId = deviceId }
        fun setVersion(version: String?) = apply { this.version = version }
        fun setDeviceName(deviceName: String?) = apply { this.deviceName = deviceName }
        fun setAccountId(accountId: String?) = apply { this.accountId = accountId }
        fun onDrm(drmCallback: () -> Unit) = apply { this.drmCallback = drmCallback }
        fun onNonDrm(nonDrmCallback: () -> Unit) = apply { this.nonDrmCallback = nonDrmCallback }

        private fun requireField(name: String, value: String?) {
            if (value.isNullOrBlank()) {
                throw IllegalArgumentException("$name must not be null or empty.")
            }
        }

        @UnstableApi
        fun load() = CoroutineScope(Dispatchers.IO).launch {
            try {

                requireField("videoId", videoId)
                requireField("accessKey", accessKey)
                requireField("secretKey", secretKey)
                requireField("userId", userId)
                requireField("deviceType", deviceType)
                requireField("deviceId", deviceId)
                requireField("version", version)
                requireField("deviceName", deviceName)
                requireField("accountId", accountId)

                val encryptionData = EncryptionData().apply {
                    name = videoId
                    flag = "1"
                }

                val call = ApiClient.create().getContentPlayBack(
                    data = encryptionData,
                    accessKey = accessKey!!,
                    secretKey = secretKey!!,
                    userId = userId!!,
                    deviceType = deviceType!!,
                    deviceId = deviceId!!,
                    deviceName = deviceName!!,
                    version = version!!,
                    accountId = accountId!!
                )

                call.hitApi { jsonObject ->
                    try {
                        if (jsonObject.optBoolean("status")) {
                            val playUrl =
                                Gson().fromJson(jsonObject.toString(), VideoPlayback::class.java)
                            isLive = false
                            val url = playUrl?.data?.link?.let {data ->
                                data.live_status?.let { live ->
                                    when (live) {
                                        "0" -> data.file_url
                                        "1" -> {
                                            isLive = true
                                            data.start?.let {
                                                "${data.file_url}?start=${it}"
                                            } ?: data.file_url
                                        }
                                        "2" -> {
                                            if (data.start !=null && data.end !=null){
                                                "${data.file_url}?start=${data.start}&end=${data.end}"
                                            }else{
                                                data.file_url
                                            }
                                        }
                                        else -> data.file_url
                                    }
                                } ?: data.file_url
                            } ?: ""
                            val token = playUrl?.data?.link?.token.orEmpty()

                            CoroutineScope(Dispatchers.Main).launch {
                                if (token.isEmpty()) {
                                    initializeNonDrmPlayback(url)
                                    nonDrmCallback?.invoke()
                                } else {
                                    initializeDrmPlayback(url, token)
                                    drmCallback?.invoke()
                                }

//                                val url = "https://d3h6zjaqbnx4yp.cloudfront.net/isuued_file/176224657492867316486/176224657492867316486_7316486.m3u8"
////                                val numberArray = intArrayOf(2,3,4)
////                                val url = "https://d3vlg4qjb80h8n.cloudfront.net/file_library/videos/channel_vod_non_drm_hls/4591871/176224657492867316486/index_${numberArray.random()}.m3u8"
//
//                                initializeNonDrmPlayback(url)
//                                nonDrmCallback?.invoke()
                            }
                        } else {
                            Log.e(MEDIA_TAG, jsonObject.optString("message"))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
            }
        }
    }


    @UnstableApi
    inner class MediaDownloadBuilder {

        private var videoId: String? = null
        private var accessKey: String? = null
        private var secretKey: String? = null
        private var downloadCallback: ((Downloads) -> Unit)? = null

        fun setVideoId(videoId: String) = apply { this.videoId = videoId }
        fun setAccessKey(accessKey: String) = apply { this.accessKey = accessKey }
        fun setSecretKey(secretKey: String) = apply { this.secretKey = secretKey }
        fun onDownload(callback: (Downloads) -> Unit) = apply { this.downloadCallback = callback }

        fun execute() = CoroutineScope(Dispatchers.IO).launch {
            try {
                val encryptionData = EncryptionData().apply {
                    id = videoId ?: throw IllegalArgumentException("VideoId must be provided")
                }

                val call = ApiClient.create().getDownloadDetails(
                    encryptionData,
                    accessKey = accessKey
                        ?: throw IllegalArgumentException("AccessKey must be provided"),
                    secretKey = secretKey
                        ?: throw IllegalArgumentException("SecretKey must be provided")
                )

                call.hitApi { jsonObject ->
                    try {
                        val downloads =
                            Gson().fromJson(jsonObject.toString(), Downloads::class.java)
                        downloadCallback?.invoke(downloads)
                    } catch (e: Exception) {
                        Log.e(MEDIA_TAG, "Error parsing JSON: ${e.message}")
                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(context, "No Downloads Found", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    fun pauseDownload(vdcId: String) {
        downloadDao.isDataExist(vdcId) { exist ->
            if (exist) {
                downloadDao.updateStatus(vdcId, "Paused") {
                    Log.d(MEDIA_TAG, "Download paused $it for $vdcId")
                }
            }
        }
        cancelWorkerForVdcId(vdcId)
    }

    fun resumeDownload(vdcId: String, url: String, fileName: String) {
        startDownload(vdcId, url, fileName)
    }

    private var wannaShowNotification = true
    private var downloadName = ""

    fun setNotificationVisibility(wannaShowNotification: Boolean) {
        this.wannaShowNotification = wannaShowNotification
    }

    fun setDownloadableName(downloadName: String) {
        this.downloadName = downloadName
    }


    fun setConstraints(
        isRequiresBatteryNotLow: Boolean = false,
        isRequiresStorageNotLow: Boolean = false
    ): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresCharging(false)
            .setRequiresBatteryNotLow(isRequiresBatteryNotLow)
            .setRequiresStorageNotLow(isRequiresStorageNotLow)
            .build()
    }


    fun startDownload(vdcId: String, url: String, fileName: String) {
        val constraints = setConstraints()

        val request = OneTimeWorkRequestBuilder<VideoDownloadWorker>()
            .setInputData(
                workDataOf(
                    VideoDownloadWorker.KEY_URL to url,
                    "vdc_id" to vdcId,
                    VideoDownloadWorker.KEY_FILENAME to fileName,
                    "downloadName" to downloadName,
                    "notification" to wannaShowNotification
                )
            )
            .setConstraints(constraints)
            .addTag(vdcId)
            .build()

        WorkManager.getInstance(context).enqueue(request)

//        observeAllDownloads(context,vdcId)
    }

    fun cancelDownload(vdcId: String) {
        cancelWorkerForVdcId(vdcId)
        removeDownloads(vdcId)
    }

    fun deleteDownload(vdcId: String) {
        removeDownloads(vdcId)
    }


    private fun cancelWorkerForVdcId(vdcId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(vdcId)
    }


    private fun removeDownloads(vdcId: String) {
        downloadDao.isDataExist(vdcId) { exist ->
            if (exist) {
                downloadDao.getVideoFileNameByVdcId(vdcId) { fileName ->
                    if (!fileName.isNullOrEmpty()) {
                        downloadDao.deleteDataByVdcId(vdcId) { success ->
                            if (success) {
                                val file = File(context.getExternalFilesDir(null), "$fileName.mp4")
                                if (file.exists()) {
                                    file.delete().also { success ->
                                        Log.d(
                                            MEDIA_TAG,
                                            if (success) "Deleted: $fileName" else "Failed to delete: $fileName"
                                        )
                                    }
                                } else {
                                    Log.w(MEDIA_TAG, "File not found: $fileName")
                                }
                            } else {
                                Log.w(MEDIA_TAG, "Failed to delete: $vdcId")
                            }
                        }

                    }
                }
            } else {
                Log.w(MEDIA_TAG, "Download not found: $vdcId")
            }
        }
    }


    private fun observeAllDownloads(context: Context, vdcId: String) {
        WorkManager.getInstance(context)
            .getWorkInfosByTagLiveData(vdcId)
            .observeForever { workInfos ->
                workInfos.forEach { workInfo ->
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            println("Download Complete $vdcId")
                        }

                        WorkInfo.State.CANCELLED -> {
                            println("Download Cancelled $vdcId")
                        }

                        WorkInfo.State.FAILED -> {
                            println("Download Failed $vdcId")
                        }

                        else -> {}
                    }
                }
            }
    }


    fun getVideoStatusByVdcId(vdcId: String): String? {
        var status: String? = null
        downloadDao.getVideoStatusByVdcId(vdcId) {
            status = it
        }

        return status
    }


    fun getVideoPercentageByVdcId(vdcId: String): Int? {
        var percentage = -1
        downloadDao.getVideoPercentageByVdcId(vdcId) {
            it?.let { per ->
                percentage = per.toInt()
            }
        }

        return percentage
    }


    fun getVideoFileNameByVdcId(vdcId: String): String? {
        var fileName: String? = null
        downloadDao.getVideoFileNameByVdcId(vdcId) {
            fileName = it
        }

        return fileName
    }


    fun getVideoUrlByVdcId(vdcId: String): String? {
        var videoUrl: String? = null
        downloadDao.getVideoUrlByVdcId(vdcId) {
            videoUrl = it
        }

        return videoUrl
    }


    fun deleteAllDownloads() {
        val list = downloadDao.getAllData()
        list?.forEach { data ->
            data?.let {
                removeDownloads(it.vdcId!!)
            }
        }
    }


    fun getVideoByVdcId(vdcId: String): DownloadMeta? {
        return downloadDao.getDataByVdcId(vdcId)
    }


    fun getAllDownloadedVideo(): List<DownloadMeta?>? {
        return downloadDao.getAllData()
    }


}