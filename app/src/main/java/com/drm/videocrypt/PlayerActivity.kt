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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.appsquadz.educryptmedia.logger.EducryptEvent
import com.appsquadz.educryptmedia.playback.EducryptMedia
import com.appsquadz.educryptmedia.playback.PlayerSettingsBottomSheetDialog
import com.drm.videocrypt.databinding.ActivityPlayerBinding
import com.drm.videocrypt.databinding.CustomControllerLayoutBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var controllerBinding: CustomControllerLayoutBinding
    private var isPlay = true

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

        lifecycleScope.launch {
            EducryptMedia.events.collect { event ->
                when (event) {
                    is EducryptEvent.ErrorOccurred  -> showError(event)
                    is EducryptEvent.StallDetected  -> binding.progressBar.isVisible = true
                    is EducryptEvent.StallRecovered -> binding.progressBar.isVisible = false
                    is EducryptEvent.QualityChanged ->
                        Log.d("PlayerActivity", "${event.fromHeight}→${event.toHeight}p reason=${event.reason}")
                    else -> {}
                }
            }
        }
    }

    @UnstableApi
    private fun setPlayer() = binding.apply {
        val player = educryptMedia.getPlayer() ?: return@apply
        playerView.player = player
        playerView.keepScreenOn = true
        /**
         * use this function if you have videoUrl and token on your end
         * educryptMedia.initializeDrmPlayback(videoUrl,token)
         */
        player.setMediaSource(educryptMedia.getMediaSource()!!)
        player.prepare()
        player.playWhenReady = true
        player.addListener(playerEventListener)
    }

    @UnstableApi
    private fun setPlayerNonDrm() = binding.apply {
        val player = educryptMedia.getPlayer() ?: return@apply
        playerView.player = player
        playerView.keepScreenOn = true
        /**
         * use this function if you have videoUrl on your end
         * educryptMedia.initializeNonDrmPlayback(videoUrl)
         */
        player.setMediaItem(educryptMedia.getMediaItem()!!)
        player.prepare()
        player.playWhenReady = true
        player.addListener(playerEventListener)
    }

    @UnstableApi
    private fun setPlayerForDownloads() {
        EducryptMedia.prepareForPlayback()
        educryptMedia.initializeNonDrmDownloadPlayback(
            videoId = videoId,
            videoUrl = videoUrl,
            onReady = {
                val player = educryptMedia.getPlayer() ?: return@initializeNonDrmDownloadPlayback
                binding.playerView.player = player
                binding.playerView.keepScreenOn = true
                player.setMediaSource(educryptMedia.getMediaSource()!!)
                player.prepare()
                player.playWhenReady = true
                player.addListener(playerEventListener)
            },
            onError = { message ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                finish()
            }
        )
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
                        educryptMedia.getPlayer()?.pause()
                        controllerBinding.pauseIv.setImageResource(R.mipmap.play)
                        isPlay = false
                    }
                }
            }
        }

        override fun onRenderedFirstFrame() {
            super.onRenderedFirstFrame()
            if (liveEdgeJob == null) {
                if (educryptMedia.isLive) {
                    val player = educryptMedia.getPlayer() ?: return
                    startLiveEdgeMonitoring(player, controllerBinding.goLiveRl)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)
            // SDK classifies and emits ErrorOccurred — handled by events.collect
        }
    }

    @UnstableApi
    private fun setListeners() = controllerBinding.apply {

        quality.setOnClickListener {
            dialog?.dismiss()
            initializeDialog()
        }

        goLiveRl.setOnClickListener {
            val player = educryptMedia.getPlayer()
            if (player != null) {
                player.seekTo(player.duration - 5000)
            }
        }

        backBtn.setOnClickListener {
            finish()
        }
        pauseIv.setOnClickListener {
            if (isPlay) {
                educryptMedia.getPlayer()?.pause()
                pauseIv.setImageResource(R.mipmap.play)
                isPlay = false
            } else {
                educryptMedia.getPlayer()?.play()
                pauseIv.setImageResource(R.mipmap.pause)
                isPlay = true
            }
        }
        playIv.setOnClickListener {
            educryptMedia.getPlayer()?.play()
            pauseIv.visibility = View.VISIBLE
            playIv.visibility = View.GONE
            pauseIv.requestFocus()
        }
        rewindIv.setOnClickListener {
            val player = educryptMedia.getPlayer()
            if (player != null) {
                if (player.currentPosition > 5000) {
                    player.seekTo(player.currentPosition - 10000)
                } else {
                    player.seekTo(0)
                }
            }
        }
        fastForwardIv.setOnClickListener {
            val player = educryptMedia.getPlayer()
            if (player != null) {
                player.seekTo(player.currentPosition + 10000)
            }
        }

        orientation.setOnClickListener {
            updateScreenOrientation()
        }

        binding.playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { _ ->
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
        educryptMedia.getPlayer()?.pause()
    }

    override fun onResume() {
        super.onResume()
        val player = educryptMedia.getPlayer()
        if (player != null && !player.isPlaying) player.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        educryptMedia.getPlayer()?.pause()
        educryptMedia.stop()
        liveEdgeJob?.cancel()
        liveEdgeJob = null
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
    private var dialog: PlayerSettingsBottomSheetDialog? = null

    @UnstableApi
    private fun initializeDialog() {
        val builder = PlayerSettingsBottomSheetDialog.Builder(this@PlayerActivity)
            .setPlayer(educryptMedia.getPlayer())
            .setSpeedRange(0.5f, 2.5f)
        educryptMedia.getTrackSelector()?.let { builder.setTrackSelector(it) }
        dialog = builder.build()
        dialog?.show(supportFragmentManager, "Player Settings")
    }


    fun ExoPlayer.isBehindLiveEdge(thresholdMs: Long = 20_000L): Boolean {
        if (!isCurrentMediaItemLive) return false
        val liveOffset = this.duration - this.currentPosition
        return liveOffset != C.TIME_UNSET && liveOffset > thresholdMs
    }


    private var liveEdgeJob: Job? = null

    fun startLiveEdgeMonitoring(player: ExoPlayer, goLiveButton: View) {
        liveEdgeJob = lifecycleScope.launch {
            while (isActive) {
                val isBehind = player.isBehindLiveEdge()
                goLiveButton.visibility = if (isBehind) View.VISIBLE else View.GONE
                delay(1000L)
            }
        }
    }

    private fun showError(event: EducryptEvent.ErrorOccurred) {
        val message = when (event.code) {
            "NETWORK_TIMEOUT"     -> "Connection lost. Reconnecting..."
            "NETWORK_UNAVAILABLE" -> "No internet connection."
            "DRM_LICENSE_FAILED"  -> "Unable to load content. Please try again."
            "DRM_LICENSE_EXPIRED" -> "Your session has expired."
            "AUTH_EXPIRED"        -> "Session expired. Please log in again."
            "SOURCE_UNAVAILABLE"  -> "Content unavailable."
            "DECODER_ERROR"       -> "Your device may not support this format."
            else -> if (event.isFatal) "Playback failed." else "Temporary error."
        }
        when {
            event.isRetrying -> { /* SDK retrying — don't interrupt user */ }
            event.isFatal    -> Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            else             -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
