package com.drm.videocrypt

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.ui.PlayerView
import com.appsquadz.educryptmedia.playback.EducryptMedia
import com.drm.videocrypt.databinding.ActivityPlayerBinding
import com.drm.videocrypt.databinding.CustomControllerLayoutBinding
import com.appsquadz.educryptmedia.models.SpeedModel
import com.appsquadz.educryptmedia.playback.PlayerSettingsBottomSheetDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch



class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var controllerBinding: CustomControllerLayoutBinding
    private var exoPlayer: ExoPlayer? = null
    private var isPlay = true

    @UnstableApi
    private lateinit var trackSelector: DefaultTrackSelector

    private var videoUrl = ""


    private var videoId = ""

    private var isPortrait = true

    private var wantsToPlayDownloadableUrl = false

    private lateinit var educryptMedia: EducryptMedia

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        controllerBinding = CustomControllerLayoutBinding.bind(binding.root)

        setContentView(binding.root)

        intent?.let {
            videoId = it.getStringExtra("videoId") ?: ""
            wantsToPlayDownloadableUrl = it.getBooleanExtra("download", false)
            videoUrl = it.getStringExtra("download_url") ?: ""
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        educryptMedia = EducryptMedia.getInstance(this)

//        initXrSession()

        if (!wantsToPlayDownloadableUrl) {
            educryptMedia.MediaLoaderBuilder()
                .setVideoId(videoId)
                .setAccessKey(Const.ACCESS_KEY)
                .setSecretKey(Const.SECRET_KEY)
                .setUserId(Const.USER_ID)
                .setDeviceType(Const.DEVICE_TYPE)
                .setDeviceId("71d3548555586126ed7071102e663619")
                .setVersion(Const.VERSION)
                .setDeviceName("MiBox")
                .setAccountId(Const.ACCOUNT_ID)
                .onDrm {
                    setPlayer()
                }
                .onNonDrm {
                    setPlayerNonDrm()
                }
                .load()
        } else {
            setPlayerForDownloads()
        }

        setListeners()

    }


    @UnstableApi
    private fun setPlayer() = binding.apply {

        initializePlayer()
        /**
         * use this function if you have videoUrl and token on your end
         * educryptMedia.initializeDrmPlayback(videoUrl,token)
         *
         */

        exoPlayer?.setMediaSource(educryptMedia.getMediaSource()!!)



        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true

        exoPlayer?.addListener(playerEventListener)
    }


    @UnstableApi
    private fun setPlayerNonDrm() = binding.apply {
        initializePlayer()
        /**
         * use this function if you have videoUrl on your end
         * educryptMedia.initializeNonDrmPlayback(videoUrl)
         *
         */
        exoPlayer?.setMediaItem(educryptMedia.getMediaItem()!!)
//        exoPlayer?.setMediaSource(educryptMedia.getMediaSource()!!)

        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true

        exoPlayer?.addListener(playerEventListener)

        exoPlayer?.addAnalyticsListener(EventLogger(trackSelector))
    }

    @UnstableApi
    private fun setPlayerForDownloads() = binding.apply {
        educryptMedia.initializeNonDrmDownloadPlayback(videoId,videoUrl)
        exoPlayer = ExoPlayer.Builder(this@PlayerActivity)
            .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build()

        playerView.player = exoPlayer
        playerView.keepScreenOn = true

        exoPlayer?.setMediaSource(educryptMedia.getMediaSource()!!)

        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true

        exoPlayer?.addListener(playerEventListener)
    }

    private val playerEventListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)
            binding.apply {
                when (playbackState) {
                    ExoPlayer.STATE_READY -> {
                        progressBar.isVisible = false
                    }
                    ExoPlayer.STATE_BUFFERING -> {
                        progressBar.isVisible = true
                    }
                    ExoPlayer.STATE_ENDED -> {
                        progressBar.isVisible = false
                        exoPlayer?.pause()
                        controllerBinding.pauseIv.setImageResource(R.mipmap.play)
                        isPlay = false
                    }
                }
            }
        }

        override fun onRenderedFirstFrame() {
            super.onRenderedFirstFrame()
            if (liveEdgeJob==null){
//                println("isLIve ${educryptMedia.isLive}")
                if (educryptMedia.isLive){
                    startLiveEdgeMonitoring(exoPlayer!!,controllerBinding.goLiveRl)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)
            Toast.makeText(this@PlayerActivity,error.localizedMessage, Toast.LENGTH_LONG).show()
        }

        override fun onTracksChanged(tracks: Tracks) {
            super.onTracksChanged(tracks)
//            for (group in tracks.groups) {
//                Log.e("--->","tracks : ${group.mediaTrackGroup}")
//            }

            val videoTrack = tracks.groups
                .firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
                ?.takeIf { it.isSelected }

            videoTrack?.mediaTrackGroup?.let { group ->
                for (i in 0 until group.length) {
                    if (videoTrack.isTrackSelected(i)) {
                        val format = group.getFormat(i)
                        Log.e("--->", "Current video bitrate: ${format.bitrate} bps")
                        Log.e("--->", "Current video height: ${format.height} bps")
                    }
                }
            }
        }

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            super.onTrackSelectionParametersChanged(parameters)

            Log.e("EventLogger","parameter is : ${parameters.preferredAudioLanguages} && ${parameters.overrides.toList()}")
        }


        override fun onPlayerErrorChanged(error: PlaybackException?) {
            super.onPlayerErrorChanged(error)
            Log.e("EventLogger",error?.localizedMessage?:"")
            error?.printStackTrace()
        }
    }

    @UnstableApi
    private fun setListeners() = controllerBinding.apply {

        quality.setOnClickListener {
            dialog?.dismiss()
            initializeDialog()
        }


        goLiveRl.setOnClickListener {
            if (exoPlayer!=null){
                exoPlayer?.seekTo(exoPlayer!!.duration - 5000)
            }
        }


        backBtn.setOnClickListener {
            finish()
        }
        pauseIv.setOnClickListener {
            if (isPlay) {
                exoPlayer?.pause()
                pauseIv.setImageResource(R.mipmap.play)
                isPlay = false
            } else {
                exoPlayer?.play()
                pauseIv.setImageResource(R.mipmap.pause)
                isPlay = true
            }
        }
        playIv.setOnClickListener {
            exoPlayer?.play()
            pauseIv.visibility = View.VISIBLE
            playIv.visibility = View.GONE
            pauseIv.requestFocus()
        }
        rewindIv.setOnClickListener {
            if (exoPlayer != null) {
                if (exoPlayer!!.currentPosition > 5000) {
                    exoPlayer!!.seekTo(exoPlayer!!.currentPosition - 10000)
                } else {
                    exoPlayer!!.seekTo(0)
                }
            }
        }
        fastForwardIv.setOnClickListener {
            if (exoPlayer != null) {
                exoPlayer!!.seekTo(exoPlayer!!.currentPosition + 10000)
            }
        }


        orientation.setOnClickListener {
            updateScreenOrientation()
        }

        binding.playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            recyclerViewQuality.isVisible = false
        })
    }


    private fun updateScreenOrientation() {
        if (isPortrait) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            isPortrait = false
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            isPortrait = true
        }
    }


    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onResume() {
        super.onResume()
        if (exoPlayer != null && !exoPlayer!!.isPlaying) {
            exoPlayer?.play()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.pause()
        exoPlayer?.release()
        liveEdgeJob?.cancel()
        liveEdgeJob == null
    }


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (newConfig.orientation != Configuration.ORIENTATION_PORTRAIT) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

            if (supportActionBar != null) {
                supportActionBar!!.hide()
            }

            val layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            binding.playerView.layoutParams = layoutParams

            controllerBinding.orientation.setImageResource(R.mipmap.resize)
        } else {
            window.decorView.systemUiVisibility = 0
            val param = binding.playerView.layoutParams
            param.width = ViewGroup.LayoutParams.MATCH_PARENT
            param.height = dpToPx(240, this@PlayerActivity)
            binding.playerView.layoutParams = param
            controllerBinding.orientation.setImageResource(R.mipmap.fullscreen)
        }
    }


    @UnstableApi
    private fun initializePlayer() = binding.apply {
        trackSelector = DefaultTrackSelector(this@PlayerActivity)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(5000, 10000, 5000, 5000)
            .setTargetBufferBytes(20 * 1024 * 1024) // 20MB buffer
            .build()

//        val initialParameters = trackSelector.buildUponParameters()
//            .setAllowVideoMixedDecoderSupportAdaptiveness(true)
//            .setForceLowestBitrate(false)
//            .setForceHighestSupportedBitrate(false)
//            .build()
//
//        trackSelector.setParameters(initialParameters)

        exoPlayer = ExoPlayer.Builder(this@PlayerActivity).setTrackSelector(trackSelector).setLoadControl(loadControl)
            .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build()

//        val maxSafeBitrate = 700000 // 700kbps (just above 240p's 693.8k)
//        val maxSafeHeight = 240
//
//        val initialParameters = trackSelector.parameters.buildUpon()
//            .setMaxVideoSize(maxSafeHeight, maxSafeHeight)
//            .setMaxVideoBitrate(maxSafeBitrate)
//            .build()
//
//        trackSelector.setParameters(initialParameters)

        playerView.player = exoPlayer
        playerView.keepScreenOn = true
    }



    @UnstableApi
    private var dialog: PlayerSettingsBottomSheetDialog?=null


    private val list = mutableListOf(
        SpeedModel("0.5x",false),
        SpeedModel("0.75x",false),
        SpeedModel("1.0x",true),
        SpeedModel("1.25x",false),
        SpeedModel("1.5x",false),
        SpeedModel("1.75x",false),
        SpeedModel("2.0x",false),
        SpeedModel("2.25x",false),
        SpeedModel("2.5x",false),
        SpeedModel("2.75x",false),
        SpeedModel("3.0x",false),
        SpeedModel("3.25x",false),
        SpeedModel("3.5x",false),
        SpeedModel("3.75x",false),
    )

    @UnstableApi
    private fun initializeDialog(){
        dialog = PlayerSettingsBottomSheetDialog.Builder(this@PlayerActivity)
            .setPlayer(exoPlayer)
            .setTrackSelector(trackSelector)
//            .setSpeedRange(0.5f,2.5f)
            .setSpeedList(list)
            .build()

        dialog?.show(supportFragmentManager,"Player Settings")
    }


    fun ExoPlayer.isBehindLiveEdge(thresholdMs: Long = 20_000L): Boolean {
        if (!isCurrentMediaItemLive) return false

        if (exoPlayer == null) return false
        val liveOffset = exoPlayer!!.duration - exoPlayer!!.currentPosition
//        println("liveOffset $liveOffset")
        return liveOffset != C.TIME_UNSET && liveOffset > thresholdMs
    }


    private var liveEdgeJob: Job? = null

    fun startLiveEdgeMonitoring(player: ExoPlayer, goLiveButton: View) {
        liveEdgeJob = lifecycleScope.launch {
            while (isActive) {
                val isBehind = player.isBehindLiveEdge()
//                println("isBehind $isBehind")
                goLiveButton.visibility = if (isBehind) View.VISIBLE else View.GONE

                delay(1000L)
            }
        }
    }
}