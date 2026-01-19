package com.drm.videocrypt.adapter

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.recyclerview.widget.RecyclerView
import com.drm.videocrypt.MainActivity
import com.drm.videocrypt.models.ListItem
import com.appsquadz.educryptmedia.downloads.DownloadListener
import com.appsquadz.educryptmedia.utils.formatFileSize
import com.drm.videocrypt.R
import com.drm.videocrypt.databinding.DownloadItemBinding

class ListItemAdapter(
    private val activity: Activity,
    private val items: List<ListItem>,
    private val downloadListener: DownloadListener,
    private val callback: () -> Unit
) :
    RecyclerView.Adapter<ListItemAdapter.ItemViewHolder>() {

    inner class ItemViewHolder(private val binding: DownloadItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ListItem) {
            binding.apply {
                val s = buildString {
                    append("(")
                    append(formatFileSize(item.size?.toLong()?:0))
                    append(")")
                }
                size.text = s
                bitrate.text = item.text?.replace("p30","p")
                mainCL.setOnClickListener {
                    callback.invoke()
                    downloadListener.startDownload(
                        item.vdcId ?: "",
                        item.url ?: "",
                        item.url?.toUri()?.lastPathSegment ?: "",
                        item.url?.toUri()?.lastPathSegment ?: ""
                    )
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ItemViewHolder(
        DownloadItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}