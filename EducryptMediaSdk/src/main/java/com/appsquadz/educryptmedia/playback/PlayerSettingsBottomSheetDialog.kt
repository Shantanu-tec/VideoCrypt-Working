package com.appsquadz.educryptmedia.playback

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.appsquadz.educryptmedia.R
import com.appsquadz.educryptmedia.adapter.QualityAdapter
import com.appsquadz.educryptmedia.adapter.SpeedAdapter
import com.appsquadz.educryptmedia.models.ResolutionModel
import com.appsquadz.educryptmedia.models.SpeedModel
import com.appsquadz.educryptmedia.playback.EducryptMedia.Companion.currentResolutionPosition
import com.appsquadz.educryptmedia.playback.EducryptMedia.Companion.currentSpeedPosition
import com.appsquadz.educryptmedia.utils.MEDIA_TAG
import com.appsquadz.educryptmedia.utils.getResolution
import com.appsquadz.educryptmedia.utils.switchBitrateAccordingly
import com.appsquadz.educryptmedia.utils.switchBitrateToAll
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

@UnstableApi
class PlayerSettingsBottomSheetDialog private constructor(
    private val activity: Activity,
    private val trackSelector: DefaultTrackSelector?,
    private val exoPlayer: ExoPlayer?,
    private val resolutionList: MutableList<ResolutionModel>,
    private val speedList: MutableList<SpeedModel>
) : BottomSheetDialogFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var closeButton: ImageView
    private lateinit var qualityTextView: TextView
    private lateinit var speedTextView: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_player_setting, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerView)
        closeButton = view.findViewById(R.id.closeButton)
        qualityTextView = view.findViewById(R.id.qualityTextView)
        speedTextView = view.findViewById(R.id.speedTextView)

        closeButton.setOnClickListener { dismiss() }

        qualityTextView.setOnClickListener {
            qualityTextView.background = ResourcesCompat.getDrawable(activity.resources,R.drawable.item_settings_bg_selected,activity.theme)
            speedTextView.background = ResourcesCompat.getDrawable(activity.resources,R.drawable.item_settings_bg,activity.theme)
            setQualityAdapter()
        }
        speedTextView.setOnClickListener {
            qualityTextView.background = ResourcesCompat.getDrawable(activity.resources,R.drawable.item_settings_bg,activity.theme)
            speedTextView.background = ResourcesCompat.getDrawable(activity.resources,R.drawable.item_settings_bg_selected,activity.theme)
            setSpeedAdapter()
        }

        setQualityAdapter()

    }

    override fun onStart() {
        super.onStart()

        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)

            val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val desiredHeight = if (isLandscape) {
                // Set 80% of screen height
                (resources.displayMetrics.heightPixels * 0.8).toInt()

            }else{
                (resources.displayMetrics.heightPixels * 0.41).toInt()
            }

            it.layoutParams.height = desiredHeight
            it.requestLayout()
            behavior.peekHeight = desiredHeight

            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun setQualityAdapter() {
        Log.e("--->","---> quality adapter")
        recyclerView.layoutManager = LinearLayoutManager(context)
        val adapter = QualityAdapter(activity, resolutionList) { resolutionIndex, position ->
            resolutionList.forEach { it.isSelected = false }
            resolutionList[position].isSelected = true
            currentResolutionPosition = position

            Log.e("--->","map : ${trackSelector?.currentMappedTrackInfo}")


            if (position == 0) {
                trackSelector?.let { switchBitrateToAll(it) }
            } else {
                trackSelector?.currentMappedTrackInfo?.let {
                    switchBitrateAccordingly(trackSelector, resolutionIndex)
                } ?: Log.e("--->","---> currentMappedTrackInfo is null")
            }

            dismiss()
        }
        recyclerView.adapter = adapter
    }

    private fun setSpeedAdapter() {
        recyclerView.layoutManager = LinearLayoutManager(context)
        val adapter = SpeedAdapter(activity, speedList) { position, text ->
            speedList.forEach { it.isSelected = false }
            speedList[position].isSelected = true
            currentSpeedPosition = position

            val speed = text.removeSuffix("x").toFloatOrNull() ?: 1.0f
            exoPlayer?.setPlaybackSpeed(speed)

            dismiss()
        }
        recyclerView.adapter = adapter
    }


    @UnstableApi
    class Builder(private val activity: Activity) {
        private var resolutionList: MutableList<ResolutionModel> = mutableListOf()
        private var speedList: MutableList<SpeedModel> = mutableListOf()
        private var trackSelector: DefaultTrackSelector? = null
        private var exoPlayer: ExoPlayer? = null

        private var maxSpeed = 3.0f
        private var minSpeed = 0.5f

        fun setPlayer(exoPlayer: ExoPlayer?) = apply {
            this.exoPlayer = exoPlayer
        }

        fun setTrackSelector(selector: DefaultTrackSelector) = apply {
            this.trackSelector = selector
            val list = mutableListOf<ResolutionModel>()
            getResolution(selector) { bitrate, index ->
                list.add(ResolutionModel(bitrate, false, index))
            }

            list.add(0, ResolutionModel("Auto"))
            list[currentResolutionPosition].isSelected = true
            this.resolutionList = list
        }

        fun setSpeedRange(min: Float, max: Float) = apply {
            minSpeed = min.coerceAtLeast(0.5f)
            maxSpeed = max.coerceAtMost(3.0f)

            if (min < 0.5f) Log.w(MEDIA_TAG, "Minimum speed capped at 0.5x")
            if (max > 3.5f) Log.w(MEDIA_TAG, "Maximum speed capped at 3.0x")

            val list = ArrayList<SpeedModel>()
            var speed = minSpeed
            while (speed <= maxSpeed + 0.001f) {
                list.add(SpeedModel(String.format("%.1fx", speed), false))
                speed += 0.5f
            }

            if (list.isNotEmpty() && currentSpeedPosition in list.indices) {
                list[currentSpeedPosition].isSelected = true
            }
            this.speedList = list
        }



        fun setSpeedList(list: MutableList<SpeedModel>) = apply {

            for (position in 0 until list.size) {
                if (list[position].isSelected){
                    currentSpeedPosition = position
                }
            }

            this.speedList = list
        }

        fun build(): PlayerSettingsBottomSheetDialog {
            return PlayerSettingsBottomSheetDialog(
                activity,
                trackSelector,
                exoPlayer,
                resolutionList,
                speedList
            )
        }
    }
}
