package com.appsquadz.educryptmedia.adapter

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import com.appsquadz.educryptmedia.databinding.ItemPlayerSettingsBinding
import com.appsquadz.educryptmedia.models.ResolutionModel
import com.appsquadz.educryptmedia.models.SpeedModel

class SpeedAdapter(
    private val context: Context,
    private val speedList: MutableList<SpeedModel>,
    private val switchSpeed: (position: Int, text: String) -> Unit
    ) :
    RecyclerView.Adapter<SpeedAdapter.ViewHolder>() {
    inner class ViewHolder(val binding: ItemPlayerSettingsBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemPlayerSettingsBinding.inflate(LayoutInflater.from(context), parent, false)
    )

    override fun getItemCount(): Int = speedList.size

    @UnstableApi
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        with(holder.binding) {
            val speed = speedList[position]
            quality.text = speed.text

            mainRl.setOnClickListener {
                switchSpeed(position, speed.text!!)
            }
            selected.isVisible = speed.isSelected
        }
    }
}