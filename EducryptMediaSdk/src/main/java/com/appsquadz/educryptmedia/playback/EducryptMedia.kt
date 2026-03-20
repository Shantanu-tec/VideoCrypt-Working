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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
import com.appsquadz.educryptmedia.EncryptionData
import com.appsquadz.educryptmedia.NetworkManager
import com.appsquadz.educryptmedia.downloads.DownloadProgress
import com.appsquadz.educryptmedia.downloads.DownloadProgressManager
import androidx.media3.exoplayer.ExoPlayer
import com.appsquadz.educryptmedia.core.EducryptGuard
import com.appsquadz.educryptmedia.error.EducryptLoadErrorPolicy
import com.appsquadz.educryptmedia.lifecycle.EducryptLifecycleManager
import com.appsquadz.educryptmedia.player.EducryptAbrController
import com.appsquadz.educryptmedia.player.EducryptLoadControl
import com.appsquadz.educryptmedia.player.MetaSnapshotBuilder
import com.appsquadz.educryptmedia.player.NetworkRecoveryManager
import com.appsquadz.educryptmedia.player.StallRecoveryManager
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.appsquadz.educryptmedia.logger.EducryptEvent
import com.appsquadz.educryptmedia.logger.EducryptEventBus
import com.appsquadz.educryptmedia.logger.EducryptPlayerListener
import kotlinx.coroutines.flow.SharedFlow
import com.appsquadz.educryptmedia.downloads.VideoDownloadWorker
import com.appsquadz.educryptmedia.models.Downloads
import com.appsquadz.educryptmedia.models.VideoPlayback
import com.appsquadz.educryptmedia.module.RealmManager
import com.appsquadz.educryptmedia.realm.dao.DownloadMetaDao
import com.appsquadz.educryptmedia.realm.entity.DownloadMeta
import com.appsquadz.educryptmedia.realm.impl.DownloadMetaImpl
import com.appsquadz.educryptmedia.utils.AesDataSource
import com.appsquadz.educryptmedia.utils.DownloadStatus
import com.appsquadz.educryptmedia.utils.MEDIA_TAG
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

    /** VideoId for the current playback session — set by [MediaLoaderBuilder.load]. */
    private var currentVideoId: String = ""

    /** True when current session is DRM-protected — set alongside the init functions. */
    private var isDrmPlayback: Boolean = false

    /** URL of the currently playing video — stored in init functions, used by snapshot triggers. */
    private var currentVideoUrl: String = ""

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

    // ── Logger / Player state ─────────────────────────────────────────────────

    @UnstableApi
    private var trackSelector: DefaultTrackSelector? = null

    @UnstableApi
    private var bandwidthMeter: DefaultBandwidthMeter? = null

    @UnstableApi
    private var stallRecoveryManager: StallRecoveryManager? = null

    @UnstableApi
    private var abrController: EducryptAbrController? = null

    @UnstableApi
    private var playerListener: EducryptPlayerListener = EducryptPlayerListener()

    /**
     * Returns the SDK-managed [EducryptPlayerListener] (internal use only).
     * Clients access the event stream via [EducryptMedia.events] instead.
     */
    @UnstableApi
    internal fun getPlayerListener(): EducryptPlayerListener = playerListener

    /** Watches for network restoration after a fatal network error. */
    private val networkRecoveryManager by lazy { NetworkRecoveryManager(context) }

    /**
     * Called when a fatal, non-retryable network error occurs.
     * Starts watching for network restoration. When a validated network returns,
     * [attemptPlaybackRecovery] is called automatically on the main thread.
     * No-op if no media is loaded or a watch is already active.
     */
    @UnstableApi
    internal fun onFatalPlaybackError() {
        val hasMedia = mediaSource != null || mediaItem != null
        if (!hasMedia) return
        if (networkRecoveryManager.isWatching()) return

        // Capture position now — ExoPlayer holds the last valid position even in ERROR/IDLE state.
        // For live streams the position is meaningless (live edge is always "now"), so skip it.
        val resumePositionMs = if (isLive) 0L else (player?.currentPosition ?: 0L)

        networkRecoveryManager.startWatching {
            val scope = EducryptLifecycleManager.scope()
            if (scope == null) {
                Log.w(MEDIA_TAG, "Network restored but SDK scope is null — skipping recovery")
                return@startWatching
            }
            scope.launch {
                attemptPlaybackRecovery(resumePositionMs)
            }
        }
    }

    @UnstableApi
    private fun attemptPlaybackRecovery(resumePositionMs: Long) {
        val currentPlayer = player ?: run {
            Log.w(MEDIA_TAG, "Playback recovery: player is null — skipping")
            return
        }
        try {
            val src = mediaSource
            val item = mediaItem
            when {
                src != null -> {
                    currentPlayer.setMediaSource(src, resumePositionMs)
                    currentPlayer.prepare()
                    currentPlayer.playWhenReady = true
                    Log.d(MEDIA_TAG, "Playback recovery: prepared with mediaSource at ${resumePositionMs}ms")
                    MetaSnapshotBuilder.emit(
                        context = context,
                        videoId = currentVideoId,
                        videoUrl = currentVideoUrl,
                        isDrm = isDrmPlayback,
                        isLive = isLive,
                        player = player,
                        bandwidthMeter = bandwidthMeter,
                        trigger = "NETWORK_RECOVERY"
                    )
                }
                item != null -> {
                    currentPlayer.setMediaItem(item, resumePositionMs)
                    currentPlayer.prepare()
                    currentPlayer.playWhenReady = true
                    Log.d(MEDIA_TAG, "Playback recovery: prepared with mediaItem at ${resumePositionMs}ms")
                    MetaSnapshotBuilder.emit(
                        context = context,
                        videoId = currentVideoId,
                        videoUrl = currentVideoUrl,
                        isDrm = isDrmPlayback,
                        isLive = isLive,
                        player = player,
                        bandwidthMeter = bandwidthMeter,
                        trigger = "NETWORK_RECOVERY"
                    )
                }
                else -> Log.w(MEDIA_TAG, "Playback recovery: no media source available")
            }
        } catch (e: Exception) {
            Log.e(MEDIA_TAG, "Playback recovery failed: ${e.message}")
        }
    }

    /**
     * Emit a custom event through the SDK event bus.
     * Use this for app-specific analytics that do not map to built-in events.
     * Requires SDK to be initialised — emits [EducryptEvent.SdkError] otherwise.
     */
    fun logEvent(name: String, params: Map<String, String> = emptyMap()) {
        if (!EducryptGuard.checkReady("logEvent")) return
        if (!EducryptGuard.checkString(name, "name", "logEvent")) return
        EducryptEventBus.emit(EducryptEvent.Custom(name, params))
    }

    // ── Player lifecycle ──────────────────────────────────────────────────────

    @UnstableApi
    private var player: ExoPlayer? = null

    /**
     * Returns the SDK-managed [ExoPlayer] instance, or null if
     * [MediaLoaderBuilder.load] has not been called yet.
     *
     * Only non-null after [MediaLoaderBuilder.load] has been called and before
     * [stop] or [shutdown] is invoked.
     *
     * WARNING: Do not call [ExoPlayer.release] directly — use [stop] instead.
     * Calling release() directly will desync SDK internal state.
     *
     * Clients using their own ExoPlayer (legacy pattern) are unaffected — continue using
     * [getMediaSource] / [getMediaItem] as before.
     */
    @UnstableApi
    fun getPlayer(): ExoPlayer? = player

    /**
     * Returns the SDK-managed [DefaultTrackSelector], or null if
     * [MediaLoaderBuilder.load] has not been called yet.
     * Used by [PlayerSettingsBottomSheetDialog] to show quality/audio options.
     */
    @UnstableApi
    fun getTrackSelector(): DefaultTrackSelector? = trackSelector

    /**
     * Releases the SDK-managed [ExoPlayer] and resets all Phase 3/4 components.
     * Call from the client's `onDestroy` when leaving the player screen.
     * Do NOT call [ExoPlayer.release] directly — use this method to keep SDK state consistent.
     */
    @UnstableApi
    fun stop() {
        releasePlayer()
    }

    @UnstableApi
    private fun initPlayer() {
        val selector = DefaultTrackSelector(context)
        val meter = DefaultBandwidthMeter.Builder(context).build()

        val builtPlayer = ExoPlayer.Builder(context)
            .setLoadControl(EducryptLoadControl.build())
            .setTrackSelector(selector)
            .setBandwidthMeter(meter)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setLoadErrorHandlingPolicy(EducryptLoadErrorPolicy())
            )
            .build()

        val abr = EducryptAbrController(builtPlayer, selector, meter)
        val stall = StallRecoveryManager(builtPlayer).also { mgr ->
            mgr.onStallDetected = { stallCount ->
                abr.onStallDetected(stallCount)
                MetaSnapshotBuilder.emit(
                    context = context,
                    videoId = currentVideoId,
                    videoUrl = currentVideoUrl,
                    isDrm = isDrmPlayback,
                    isLive = isLive,
                    player = player,
                    bandwidthMeter = bandwidthMeter,
                    trigger = "STALL_RECOVERY"
                )
            }
            mgr.onSafeModeRequired = {
                abr.onSafeModeRequired()
            }
        }

        trackSelector = selector
        bandwidthMeter = meter
        abrController = abr
        stallRecoveryManager = stall
        playerListener = EducryptPlayerListener(
            stallRecoveryManager = stall,
            abrController = abr,
            onFatalError = { onFatalPlaybackError() },
            onEmitSnapshot = { trigger ->
                MetaSnapshotBuilder.emit(
                    context = context,
                    videoId = currentVideoId,
                    videoUrl = currentVideoUrl,
                    isDrm = isDrmPlayback,
                    isLive = isLive,
                    player = player,
                    bandwidthMeter = bandwidthMeter,
                    trigger = trigger
                )
            }
        )

        builtPlayer.addListener(playerListener)
        player = builtPlayer
    }

    @UnstableApi
    internal fun releasePlayer() {
        networkRecoveryManager.stopWatching()
        try {
            player?.stop()
            player?.release()
        } catch (e: Exception) {
            // Ignore — player may already be released
        } finally {
            player = null
            abrController?.reset()
            abrController = null
            stallRecoveryManager?.reset()
            stallRecoveryManager = null
            trackSelector = null
            bandwidthMeter = null
            currentVideoId = ""
            isDrmPlayback = false
            currentVideoUrl = ""
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: EducryptMedia? = null

        private var context: Context? = null

        // Maximum concurrent downloads allowed
        private const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 3
        private var _maxConcurrentDownloads: Int = DEFAULT_MAX_CONCURRENT_DOWNLOADS
        val maxConcurrentDownloads: Int get() = _maxConcurrentDownloads

        /**
         * Initialise the SDK. Call once in Application.onCreate().
         * Idempotent — safe to call multiple times (second call is ignored).
         * Enforces applicationContext internally regardless of what is passed.
         *
         * After this call, SDK transitions to READY state and events can be collected.
         */
        fun init(context: Context) {
            val appContext = context.applicationContext
            getInstance(appContext)  // ensure singleton created with applicationContext
            EducryptLifecycleManager.init()
        }

        fun getInstance(context: Context): EducryptMedia {
            this.context = context
            val instance = INSTANCE ?: synchronized(this) {
                INSTANCE ?: EducryptMedia(context.applicationContext).also { INSTANCE = it }
            }
            // Auto-initialise lifecycle so existing callers using only getInstance() work
            EducryptLifecycleManager.init()
            return instance
        }

        /**
         * Live stream of all SDK events.
         * Collect from any lifecycle-aware scope (viewModelScope, lifecycleScope).
         * replay=0 — collectors miss events emitted before they start.
         * Use [recentEvents] to backfill missed events.
         *
         * IMPORTANT: Do not collect on GlobalScope — it never cancels.
         *
         * Example:
         *   viewModelScope.launch {
         *       EducryptMedia.events.collect { event ->
         *           when (event) {
         *               is EducryptEvent.DrmReady          -> analytics.log("drm_ready")
         *               is EducryptEvent.DownloadCompleted -> analytics.log("done")
         *               is EducryptEvent.SdkError          -> Log.e("SDK", event.message)
         *               else -> {}
         *           }
         *       }
         *   }
         */
        val events: SharedFlow<EducryptEvent>
            get() = EducryptEventBus.events

        /**
         * Live stream of all SDK events with monotonic sequence numbers.
         * Use when strict ordering between concurrent playback and download events
         * is required. Sort received events by IndexedValue.index.
         * Most clients only need [events].
         */
        val indexedEvents: SharedFlow<IndexedValue<EducryptEvent>>
            get() = EducryptEventBus.indexedEvents

        /**
         * Returns the last [count] events from the current session (max 200).
         * Thread-safe. Safe to call in any SDK state including before init().
         * Buffer is always in strict sequence order.
         *
         * Use for: crash reports, diagnostic dumps, late-start collection backfill.
         */
        fun recentEvents(count: Int = 50): List<EducryptEvent> =
            EducryptEventBus.recentEvents(count)

        /**
         * Returns the last [count] events with sequence numbers.
         * Use for ordered diagnostic dumps when concurrent operations were active.
         */
        fun recentIndexedEvents(count: Int = 50): List<IndexedValue<EducryptEvent>> =
            EducryptEventBus.recentIndexedEvents(count)

        /**
         * Release all SDK resources. Idempotent — safe to call multiple times.
         * Automatically called on process death via ProcessLifecycleOwner.
         * After shutdown, calling init() again restarts the SDK cleanly.
         */
        @UnstableApi
        fun shutdown() {
            INSTANCE?.releasePlayer()
            EducryptLifecycleManager.shutdown()
        }

        /**
         * Prepares the SDK-managed player for offline/download playback initiated
         * directly (not via [MediaLoaderBuilder.load]).
         *
         * Call this before [EducryptMedia.initializeNonDrmDownloadPlayback] when
         * playing a downloaded file, so the player exists with full Phase 3/4 infrastructure.
         *
         * Must be called on the main thread.
         */
        @UnstableApi
        fun prepareForPlayback() {
            INSTANCE?.releasePlayer()
            INSTANCE?.initPlayer()
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

        /**
         * Set maximum number of concurrent downloads
         * @param max Maximum concurrent downloads (1-10)
         */
        fun setMaxConcurrentDownloads(max: Int) {
            _maxConcurrentDownloads = max.coerceIn(1, 10)
        }
    }




    private fun setValuesToDefault() {
        networkRecoveryManager.stopWatching()
        currentSpeedPosition = 1
        currentResolutionPosition = 0
        stallRecoveryManager?.reset()
        abrController?.reset()
    }

    @UnstableApi
    fun initializeDrmPlayback(videoUrl: String, token: String) {
        if (!EducryptGuard.checkReady("initializeDrmPlayback")) return
        if (!EducryptGuard.checkMainThread("initializeDrmPlayback")) return
        if (!EducryptGuard.checkString(videoUrl, "videoUrl", "initializeDrmPlayback")) return
        if (!EducryptGuard.checkString(token, "token", "initializeDrmPlayback")) return
        setValuesToDefault()
        currentVideoUrl = videoUrl
        dataSourceFactory = DefaultHttpDataSource.Factory()
        val localDataSourceFactory = checkNotNull(dataSourceFactory) {
            "dataSourceFactory is null in initializeDrmPlayback — internal SDK error"
        }
        val postParameters = mutableMapOf<String, String>()
        postParameters["pallycon-customdata-v2"] = token
        localDataSourceFactory.setDefaultRequestProperties(postParameters)
        drmCallback = HttpMediaDrmCallback(drmLicenseUrl, localDataSourceFactory)
        val localDrmCallback = checkNotNull(drmCallback) {
            "drmCallback is null in initializeDrmPlayback — internal SDK error"
        }

        drmSessionManager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(
                C.WIDEVINE_UUID,
                FrameworkMediaDrm.DEFAULT_PROVIDER
            )
            .build(localDrmCallback)

//        val url = "https://d22vhzp5gnxc1w.cloudfront.net/pankaj-dt/drm-dash/vr5/SampleTimeMachine.mpd"

        mediaItem = MediaItem.Builder()
            .setUri(videoUrl)
            .setDrmConfiguration(
                DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .build()
            ).build()


        dashMediaSourceFactory = DashMediaSource.Factory(localDataSourceFactory)
            .setLoadErrorHandlingPolicy(EducryptLoadErrorPolicy())

        val localDrmSessionManager = checkNotNull(drmSessionManager) {
            "drmSessionManager is null in initializeDrmPlayback — internal SDK error"
        }
        val localMediaItem = checkNotNull(mediaItem) {
            "mediaItem is null in initializeDrmPlayback — internal SDK error"
        }
        mediaSource = dashMediaSourceFactory?.setDrmSessionManagerProvider { localDrmSessionManager }
            ?.createMediaSource(localMediaItem)

        EducryptEventBus.emit(EducryptEvent.DrmReady(videoUrl))
        MetaSnapshotBuilder.emit(
            context = context,
            videoId = currentVideoId,
            videoUrl = videoUrl,
            isDrm = true,
            isLive = isLive,
            player = player,
            bandwidthMeter = bandwidthMeter,
            trigger = "DRM_READY"
        )
    }



    @UnstableApi
    fun initializeNonDrmPlayback(videoUrl: String) {
        if (!EducryptGuard.checkReady("initializeNonDrmPlayback")) return
        if (!EducryptGuard.checkMainThread("initializeNonDrmPlayback")) return
        if (!EducryptGuard.checkString(videoUrl, "videoUrl", "initializeNonDrmPlayback")) return
//        Log.e("VideoUrl","videoUrl : $videoUrl")
//        println("videoUrl : $videoUrl")
        setValuesToDefault()
        currentVideoUrl = videoUrl

        mediaItem = MediaItem.Builder()
            .setUri(videoUrl).build()

        EducryptEventBus.emit(EducryptEvent.PlaybackStarted(videoUrl, isDrm = false))
        MetaSnapshotBuilder.emit(
            context = context,
            videoId = currentVideoId,
            videoUrl = videoUrl,
            isDrm = false,
            isLive = isLive,
            player = player,
            bandwidthMeter = bandwidthMeter,
            trigger = "READY"
        )
    }

    @UnstableApi
    fun initializeNonDrmDownloadPlayback(
        videoId: String,
        videoUrl: String,
        onReady: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (!EducryptGuard.checkReady("initializeNonDrmDownloadPlayback")) return
        if (!EducryptGuard.checkMainThread("initializeNonDrmDownloadPlayback")) return
        if (!EducryptGuard.checkString(videoId, "videoId", "initializeNonDrmDownloadPlayback")) return
        if (!EducryptGuard.checkString(videoUrl, "videoUrl", "initializeNonDrmDownloadPlayback")) return
        isLive = false
        setValuesToDefault()
        downloadDao.isDataExist(vdcId = videoId) { exist ->
            if (exist) {
                val aesDataSource = AesDataSource(getCipher(videoId.split("_")[2]))

                val factory: DataSource.Factory = DataSource.Factory { aesDataSource }

                mediaSource = ProgressiveMediaSource.Factory(
                    factory,
                    DefaultExtractorsFactory()
                )
                    .setLoadErrorHandlingPolicy(EducryptLoadErrorPolicy())
                    .createMediaSource(MediaItem.fromUri(videoUrl))

                EducryptEventBus.emit(EducryptEvent.PlaybackStarted(videoUrl, isDrm = false))
                onReady?.invoke()
            } else {
                Log.w(MEDIA_TAG, "Download not found: $videoId")
                onError?.invoke("Download not found for: $videoId")
            }
        }

    }

    var isLive = false


    private var drmLicenseUrl = "https://license.videocrypt.com/validateLicense"

    fun setDrmLicenceUrl(drmLicenseUrl: String) {
        if (!EducryptGuard.checkReady("setDrmLicenceUrl")) return
        if (!EducryptGuard.checkString(drmLicenseUrl, "drmLicenseUrl", "setDrmLicenceUrl")) return
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
        private var errorCallback: ((String) -> Unit)? = null

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
        fun onError(errorCallback: (String) -> Unit) = apply { this.errorCallback = errorCallback }

        private fun requireField(name: String, value: String?) {
            if (value.isNullOrBlank()) {
                throw IllegalArgumentException("$name must not be null or empty.")
            }
        }

        @UnstableApi
        fun load() = CoroutineScope(Dispatchers.IO).launch {
            if (!EducryptGuard.checkReady("MediaLoaderBuilder.load")) {
                withContext(Dispatchers.Main) {
                    errorCallback?.invoke("SDK not initialised. Call EducryptMedia.init(context) first.")
                }
                return@launch
            }
            try {
                // Check network connectivity first
                if (!isNetworkAvailable()) {
                    withContext(Dispatchers.Main) {
                        errorCallback?.invoke("No internet connection. Please check your network and try again.")
                    }
                    return@launch
                }

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

                val call = NetworkManager.create().getContentPlayBack(
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

                call.hitApi(
                    invokeOnCompletion = { jsonObject ->
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
                                    releasePlayer()   // clean up any existing player (also resets currentVideoId/isDrmPlayback)
                                    initPlayer()      // create fresh player for this playback

                                    // Store session metadata AFTER releasePlayer() cleared them,
                                    // BEFORE init functions so snapshots inside them see correct values.
                                    currentVideoId = videoId ?: ""
                                    isDrmPlayback = token.isNotEmpty()

                                    // Trigger 1: LOADING — player ready, media not yet configured
                                    MetaSnapshotBuilder.emit(
                                        context = context,
                                        videoId = currentVideoId,
                                        videoUrl = url,
                                        isDrm = isDrmPlayback,
                                        isLive = isLive,
                                        player = player,
                                        bandwidthMeter = bandwidthMeter,
                                        trigger = "LOADING"
                                    )

                                    if (token.isEmpty()) {
                                        initializeNonDrmPlayback(url)
                                        nonDrmCallback?.invoke()
                                    } else {
                                        initializeDrmPlayback(url, token)
                                        drmCallback?.invoke()
                                    }
                                }
                            } else {
                                val message = jsonObject.optString("message", "Failed to load video")
                                Log.e(MEDIA_TAG, message)
                                CoroutineScope(Dispatchers.Main).launch {
                                    errorCallback?.invoke(message)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            CoroutineScope(Dispatchers.Main).launch {
                                errorCallback?.invoke(e.message ?: "Error processing response")
                            }
                        }
                    },
                    onError = { errorMessage ->
                        CoroutineScope(Dispatchers.Main).launch {
                            errorCallback?.invoke(errorMessage)
                        }
                    }
                )

            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    errorCallback?.invoke(e.message ?: "Invalid parameters")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    errorCallback?.invoke(e.message ?: "Network error occurred")
                }
            }
        }
    }


    @UnstableApi
    inner class MediaDownloadBuilder {

        private var videoId: String? = null
        private var accessKey: String? = null
        private var secretKey: String? = null
        private var downloadCallback: ((Downloads) -> Unit)? = null
        private var errorCallback: ((String) -> Unit)? = null

        fun setVideoId(videoId: String) = apply { this.videoId = videoId }
        fun setAccessKey(accessKey: String) = apply { this.accessKey = accessKey }
        fun setSecretKey(secretKey: String) = apply { this.secretKey = secretKey }
        fun onDownload(callback: (Downloads) -> Unit) = apply { this.downloadCallback = callback }
        fun onError(errorCallback: (String) -> Unit) = apply { this.errorCallback = errorCallback }

        fun execute() = CoroutineScope(Dispatchers.IO).launch {
            if (!EducryptGuard.checkReady("MediaDownloadBuilder.execute")) {
                withContext(Dispatchers.Main) {
                    errorCallback?.invoke("SDK not initialised. Call EducryptMedia.init(context) first.")
                }
                return@launch
            }
            try {
                // Check network connectivity first
                if (!isNetworkAvailable()) {
                    withContext(Dispatchers.Main) {
                        errorCallback?.invoke("No internet connection. Please check your network and try again.")
                    }
                    return@launch
                }

                val encryptionData = EncryptionData().apply {
                    id = videoId ?: throw IllegalArgumentException("VideoId must be provided")
                }

                val call = NetworkManager.create().getDownloadDetails(
                    encryptionData,
                    accessKey = accessKey
                        ?: throw IllegalArgumentException("AccessKey must be provided"),
                    secretKey = secretKey
                        ?: throw IllegalArgumentException("SecretKey must be provided")
                )

                call.hitApi(
                    invokeOnCompletion = { jsonObject ->
                        try {
                            if (jsonObject.optBoolean("status", true)) {
                                val downloads =
                                    Gson().fromJson(jsonObject.toString(), Downloads::class.java)
                                downloadCallback?.invoke(downloads)
                            } else {
                                val message = jsonObject.optString("message", "Failed to get download details")
                                Log.e(MEDIA_TAG, message)
                                CoroutineScope(Dispatchers.Main).launch {
                                    errorCallback?.invoke(message)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(MEDIA_TAG, "Error parsing JSON: ${e.message}")
                            CoroutineScope(Dispatchers.Main).launch {
                                errorCallback?.invoke("No Downloads Found")
                            }
                        }
                    },
                    onError = { errorMessage ->
                        CoroutineScope(Dispatchers.Main).launch {
                            errorCallback?.invoke(errorMessage)
                        }
                    }
                )

            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    errorCallback?.invoke(e.message ?: "Invalid parameters")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    errorCallback?.invoke(e.message ?: "Network error occurred")
                }
            }
        }
    }


    fun pauseDownload(vdcId: String) {
        if (!EducryptGuard.checkReady("pauseDownload")) return
        if (!EducryptGuard.checkString(vdcId, "vdcId", "pauseDownload")) return
        downloadDao.isDataExist(vdcId) { exist ->
            if (exist) {
                downloadDao.updateStatus(vdcId, DownloadStatus.PAUSED) {
                    Log.d(MEDIA_TAG, "Download paused $it for $vdcId")
                }
            }
        }
        cancelWorkerForVdcId(vdcId)
        // Update in-memory progress map so isDownloadActive() returns false for this vdcId.
        // Without this, resumeDownload() → startDownload() → isDownloadActive() returns true
        // (stale DOWNLOADING entry) and blocks the resume with "already in progress".
        val currentProgress = DownloadProgressManager.getCurrentProgress(vdcId)
        if (currentProgress != null) {
            DownloadProgressManager.updateProgress(vdcId, currentProgress.copy(status = DownloadStatus.PAUSED))
        }
        EducryptEventBus.emit(EducryptEvent.DownloadPaused(vdcId))
    }

    /**
     * Resume a paused download
     * @param vdcId Unique video ID
     * @param url Download URL
     * @param fileName File name for saving
     * @param onError Optional callback for error handling
     * @param onSuccess Optional callback when download resumes successfully
     */
    fun resumeDownload(
        vdcId: String,
        url: String,
        fileName: String,
        onError: ((String) -> Unit)? = null,
        onSuccess: (() -> Unit)? = null
    ) {
        if (!EducryptGuard.checkReady("resumeDownload")) return
        if (!EducryptGuard.checkString(vdcId, "vdcId", "resumeDownload")) return
        if (!EducryptGuard.checkString(url, "url", "resumeDownload")) return
        if (!EducryptGuard.checkString(fileName, "fileName", "resumeDownload")) return
        startDownload(vdcId, url, fileName, onError, onSuccess)
    }

    private var wannaShowNotification = true
    private var downloadName = ""

    fun setNotificationVisibility(wannaShowNotification: Boolean) {
        if (!EducryptGuard.checkReady("setNotificationVisibility")) return
        this.wannaShowNotification = wannaShowNotification
    }

    fun setDownloadableName(downloadName: String) {
        if (!EducryptGuard.checkReady("setDownloadableName")) return
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


    /**
     * Start a download with error callback support
     * @param vdcId Unique video ID
     * @param url Download URL
     * @param fileName File name for saving
     * @param onError Optional callback for error handling
     * @param onSuccess Optional callback when download starts successfully
     */
    fun startDownload(
        vdcId: String,
        url: String,
        fileName: String,
        onError: ((String) -> Unit)? = null,
        onSuccess: (() -> Unit)? = null
    ) {
        if (!EducryptGuard.checkReady("startDownload")) { onError?.invoke("SDK not initialised"); return }
        if (!EducryptGuard.checkString(vdcId, "vdcId", "startDownload")) { onError?.invoke("vdcId is empty"); return }
        if (!EducryptGuard.checkString(url, "url", "startDownload")) { onError?.invoke("url is empty"); return }
        if (!EducryptGuard.checkString(fileName, "fileName", "startDownload")) { onError?.invoke("fileName is empty"); return }
        // Check network connectivity first
        if (!isNetworkAvailable()) {
            val errorMessage = "No internet connection. Please check your network and try again."
            Log.e(MEDIA_TAG, errorMessage)
            onError?.invoke(errorMessage)
            return
        }

        // Check concurrent download limit
        val activeDownloadCount = DownloadProgressManager.getActiveDownloadCount()
        if (activeDownloadCount >= maxConcurrentDownloads) {
            val errorMessage = "Maximum concurrent downloads reached ($maxConcurrentDownloads). Please wait for a download to complete."
            Log.w(MEDIA_TAG, errorMessage)
            onError?.invoke(errorMessage)
            return
        }

        // Check if this download is already in progress
        if (DownloadProgressManager.isDownloadActive(vdcId)) {
            val errorMessage = "This download is already in progress."
            Log.w(MEDIA_TAG, errorMessage)
            onError?.invoke(errorMessage)
            return
        }

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

        Log.d(MEDIA_TAG, "Download started for $vdcId")
        EducryptEventBus.emit(EducryptEvent.DownloadStarted(vdcId))
        onSuccess?.invoke()
    }

    fun cancelDownload(vdcId: String) {
        if (!EducryptGuard.checkReady("cancelDownload")) return
        if (!EducryptGuard.checkString(vdcId, "vdcId", "cancelDownload")) return
        cancelWorkerForVdcId(vdcId)
        removeDownloads(vdcId)
        DownloadProgressManager.removeDownload(vdcId)
        EducryptEventBus.emit(EducryptEvent.DownloadCancelled(vdcId))
    }

    fun deleteDownload(vdcId: String) {
        if (!EducryptGuard.checkReady("deleteDownload")) return
        if (!EducryptGuard.checkString(vdcId, "vdcId", "deleteDownload")) return
        removeDownloads(vdcId)
        DownloadProgressManager.removeDownload(vdcId)
        EducryptEventBus.emit(EducryptEvent.DownloadDeleted(vdcId))
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
        if (!EducryptGuard.checkReady("getVideoStatusByVdcId")) return null
        if (!EducryptGuard.checkString(vdcId, "vdcId", "getVideoStatusByVdcId")) return null
        var status: String? = null
        downloadDao.getVideoStatusByVdcId(vdcId) {
            status = it
        }

        // If status shows downloaded, verify the file actually exists
        if (status == DownloadStatus.DOWNLOADED) {
            downloadDao.getVideoFileNameByVdcId(vdcId) { fileName ->
                if (!fileName.isNullOrEmpty()) {
                    val file = File(context.getExternalFilesDir(null), "$fileName.mp4")
                    if (!file.exists()) {
                        // File was deleted externally, clean up Realm record
                        Log.w(MEDIA_TAG, "File not found for $vdcId, cleaning up stale record")
                        downloadDao.deleteDataByVdcId(vdcId) { success ->
                            if (success) {
                                Log.d(MEDIA_TAG, "Cleaned up stale download record: $vdcId")
                            }
                        }
                        status = null
                    }
                }
            }
        }

        return status
    }


    fun getVideoPercentageByVdcId(vdcId: String): Int? {
        if (!EducryptGuard.checkReady("getVideoPercentageByVdcId")) return null
        if (!EducryptGuard.checkString(vdcId, "vdcId", "getVideoPercentageByVdcId")) return null
        var percentage = -1
        downloadDao.getVideoPercentageByVdcId(vdcId) {
            it?.let { per ->
                percentage = per.toInt()
            }
        }

        return percentage
    }


    fun getVideoFileNameByVdcId(vdcId: String): String? {
        if (!EducryptGuard.checkReady("getVideoFileNameByVdcId")) return null
        if (!EducryptGuard.checkString(vdcId, "vdcId", "getVideoFileNameByVdcId")) return null
        var fileName: String? = null
        downloadDao.getVideoFileNameByVdcId(vdcId) {
            fileName = it
        }

        return fileName
    }


    fun getVideoUrlByVdcId(vdcId: String): String? {
        if (!EducryptGuard.checkReady("getVideoUrlByVdcId")) return null
        if (!EducryptGuard.checkString(vdcId, "vdcId", "getVideoUrlByVdcId")) return null
        var videoUrl: String? = null
        downloadDao.getVideoUrlByVdcId(vdcId) {
            videoUrl = it
        }

        return videoUrl
    }


    fun deleteAllDownloads() {
        if (!EducryptGuard.checkReady("deleteAllDownloads")) return
        val list = downloadDao.getAllData()
        list?.forEach { data ->
            data?.let {
                removeDownloads(it.vdcId!!)
            }
        }
        DownloadProgressManager.clearAll()
    }


    fun getVideoByVdcId(vdcId: String): DownloadMeta? {
        if (!EducryptGuard.checkReady("getVideoByVdcId")) return null
        if (!EducryptGuard.checkString(vdcId, "vdcId", "getVideoByVdcId")) return null
        return downloadDao.getDataByVdcId(vdcId)
    }


    fun getAllDownloadedVideo(): List<DownloadMeta?>? {
        if (!EducryptGuard.checkReady("getAllDownloadedVideo")) return null
        return downloadDao.getAllData()
    }

    /**
     * Cleans up stale download records where the physical file no longer exists.
     * Call this on app startup or before displaying the downloads list.
     * @param onComplete Optional callback when cleanup is finished with count of removed records
     */
    fun cleanupStaleDownloads(onComplete: ((Int) -> Unit)? = null) {
        if (!EducryptGuard.checkReady("cleanupStaleDownloads")) { onComplete?.invoke(0); return }
        CoroutineScope(Dispatchers.IO).launch {
            var removedCount = 0
            val allDownloads = downloadDao.getAllData()

            allDownloads?.forEach { download ->
                download?.let { meta ->
                    val fileName = meta.fileName
                    val vdcId = meta.vdcId
                    val status = meta.status

                    // Only check completed downloads
                    if (status == DownloadStatus.DOWNLOADED && !fileName.isNullOrEmpty() && !vdcId.isNullOrEmpty()) {
                        val file = File(context.getExternalFilesDir(null), "$fileName.mp4")
                        if (!file.exists()) {
                            Log.w(MEDIA_TAG, "Stale download found: $vdcId (file: $fileName)")
                            downloadDao.deleteDataByVdcId(vdcId) { success ->
                                if (success) {
                                    removedCount++
                                    Log.d(MEDIA_TAG, "Removed stale record: $vdcId")
                                }
                            }
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (removedCount > 0) {
                    Log.i(MEDIA_TAG, "Cleaned up $removedCount stale download records")
                }
                onComplete?.invoke(removedCount)
            }
        }
    }

    /**
     * Checks if a download exists and the file is present on disk
     * @param vdcId Unique video ID
     * @return true if both Realm record and physical file exist
     */
    fun isDownloadValid(vdcId: String): Boolean {
        if (!EducryptGuard.checkReady("isDownloadValid")) return false
        if (!EducryptGuard.checkString(vdcId, "vdcId", "isDownloadValid")) return false
        val meta = downloadDao.getDataByVdcId(vdcId) ?: return false
        val fileName = meta.fileName ?: return false
        val file = File(context.getExternalFilesDir(null), "$fileName.mp4")
        return file.exists()
    }


}