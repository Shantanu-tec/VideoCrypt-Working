package com.appsquadz.educryptmedia.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import com.appsquadz.educryptmedia.databinding.ItemPlayerSettingsBinding
import com.appsquadz.educryptmedia.models.ResolutionModel

class QualityAdapter(
    private val context: Context,
    private val resolutionList: MutableList<ResolutionModel>,
    private val switchResolution:(indexOfResolution:Int,position : Int) -> Unit
) : RecyclerView.Adapter<QualityAdapter.ViewHolder>() {
    inner class ViewHolder(val binding: ItemPlayerSettingsBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemPlayerSettingsBinding.inflate(LayoutInflater.from(context), parent, false)
    )

    override fun getItemCount(): Int = resolutionList.size

    @UnstableApi
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        with(holder.binding) {
            quality.text = resolutionList[position].text


            mainRl.setOnClickListener {
                resolutionList[position].position?.let {
                    switchResolution(it.toInt(),position)
                } ?: run {
                    switchResolution(0,position)
                }
            }


            if (resolutionList[position].isSelected) {
                selected.isVisible = true
            }
        }
    }

}