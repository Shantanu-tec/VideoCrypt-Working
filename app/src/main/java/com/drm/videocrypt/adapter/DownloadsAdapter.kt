package com.drm.videocrypt.adapter

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.drm.videocrypt.PlayerActivity
import com.drm.videocrypt.databinding.ItemDownloadsBinding
import com.drm.videocrypt.fragments.DownloadFragment
import com.drm.videocrypt.models.ListItem
import com.appsquadz.educryptmedia.downloads.DownloadListener
import com.appsquadz.educryptmedia.utils.DownloadStatus
import com.appsquadz.educryptmedia.utils.getDownloadableFile
import com.appsquadz.educryptmedia.utils.getDownloadablePath
import com.appsquadz.educryptmedia.utils.isDownloadExistForVdcId
import com.drm.videocrypt.MainActivity
import java.io.File

class DownloadsAdapter(
    private val activity: Activity,
    private val items: MutableList<ListItem>,
    private val fragment: DownloadFragment,
    private val downloadListener: DownloadListener
): RecyclerView.Adapter<DownloadsAdapter.ViewHolder>() {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ) = ViewHolder(
        ItemDownloadsBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any?>) {
        if(payloads.isEmpty()) {
            onBindViewHolder(holder, position)
        } else {
            val payload = payloads[0] as? Bundle
            payload?.let {
                val progress = it.getInt("percentage")
                holder.binding.apply {
                    progressBar.progress = progress
                    progressBar.isVisible = progress != 100
                    deleteDownload.isVisible = progress == 100
                    items[position].let { item ->
                        pauseDownload.isVisible = item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.RESUMED
                        resumeDownload.isVisible = item.status == DownloadStatus.PAUSED
                        cancelDownload.isVisible = item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.RESUMED || item.status == DownloadStatus.PAUSED
                    }
                }
            }
        }
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int
    ) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int  = items.size

    inner class ViewHolder(val binding: ItemDownloadsBinding): RecyclerView.ViewHolder(binding.root){
        fun bind(item: ListItem) {
            binding.apply {
                val fileName = item.url?.toUri()?.lastPathSegment?:"UnKnown"
                name.text = item.vdcId?:"UnKnown"
                progressBar.isVisible = item.percentage != 100
                deleteDownload.isVisible = item.percentage == 100
                pauseDownload.isVisible = item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.RESUMED
                resumeDownload.isVisible = item.status == DownloadStatus.PAUSED
                cancelDownload.isVisible = item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.RESUMED || item.status == DownloadStatus.PAUSED
                itemContainer.setOnClickListener {
                    print("fileName : $fileName.mp4")
                    if(activity.isDownloadExistForVdcId(item.vdcId,fileName)){
                        print("download_url : ${activity.getDownloadableFile(fileName).absolutePath}")
                        val intent = Intent(activity, PlayerActivity::class.java)
                        intent.putExtra("videoId", item.vdcId?:"")
                        intent.putExtra("download", true)
                        intent.putExtra("download_url", activity.getDownloadablePath(fileName))
                        activity.startActivity(intent)
                    }else{
                        Toast.makeText(activity, "File Not Found", Toast.LENGTH_SHORT).show()
                    }
                }
                deleteDownload.setOnClickListener {
                    fragment.updateUI(false)
                    downloadListener.deleteDownload(vdcId = item.vdcId!!)
                }

                cancelDownload.setOnClickListener {
                    fragment.updateUI(false)
                    downloadListener.cancelDownload(vdcId = item.vdcId!!)
                }

                pauseDownload.setOnClickListener {
                    item.status = DownloadStatus.PAUSED
                    downloadListener.pauseDownload(vdcId = item.vdcId!!)
                    updateData(item)
                }

                resumeDownload.setOnClickListener {
                    item.status = DownloadStatus.RESUMED
                    downloadListener.resumeDownload(vdcId = item.vdcId!!, url = item.url!!, fileName = fileName, downloadableName = fileName)
                    updateData(item)
                }
            }
        }
    }


    fun updateData(item: ListItem){
        items.clear()
        items.add(item)
        notifyDataSetChanged()
    }

}