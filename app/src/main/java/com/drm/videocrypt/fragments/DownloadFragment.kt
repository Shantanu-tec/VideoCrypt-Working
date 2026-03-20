package com.drm.videocrypt.fragments

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.appsquadz.educryptmedia.downloads.VideoDownloadWorker
import com.appsquadz.educryptmedia.utils.DownloadStatus
import com.drm.videocrypt.MainActivity
import com.drm.videocrypt.adapter.DownloadsAdapter
import com.drm.videocrypt.databinding.FragmentDownloadsBinding
import com.drm.videocrypt.utils.SharedPreference
import java.io.File

class DownloadFragment : Fragment() {

    private lateinit var binding: FragmentDownloadsBinding
    private var downloadVideoAdapter: DownloadsAdapter?=null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentDownloadsBinding.inflate(inflater,container,false)
        return binding.root
    }

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.getStringExtra(VideoDownloadWorker.ACTION)
            when (action) {
                VideoDownloadWorker.ACTION_PROGRESS -> {
                    val progress = intent.getIntExtra(VideoDownloadWorker.EXTRA_PROGRESS, 0)
                    val bundle = Bundle().apply {
                        putInt("percentage", progress)
                    }
                    downloadVideoAdapter?.notifyItemChanged(0, bundle)
                }
                VideoDownloadWorker.ACTION_STARTED -> {
                    initUI()
                }
                VideoDownloadWorker.ACTION_COMPLETED -> {
                    SharedPreference.instance.getDownloadData()?.let {
                        val listItems = it
                        listItems.status = DownloadStatus.DOWNLOADED
                        listItems.percentage = 100
                        SharedPreference.instance.setDownloadData(listItems)
                        downloadVideoAdapter?.updateData(SharedPreference.instance.getDownloadData()!!)
                    }?: Toast.makeText(requireActivity(),"Something went wrong", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(requireActivity())
            .registerReceiver(receiver, IntentFilter(VideoDownloadWorker.ACTION_DOWNLOAD))
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(requireActivity()).unregisterReceiver(receiver)
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        initUI()
    }

    private fun initUI(){
        SharedPreference.instance.getDownloadData()?.let { downloadData ->
            // Verify file exists for completed downloads
            if (downloadData.status == DownloadStatus.DOWNLOADED) {
                val fileName = downloadData.url?.toUri()?.lastPathSegment
                if (!fileName.isNullOrEmpty()) {
                    val file = File(requireActivity().getExternalFilesDir(null), "$fileName.mp4")
                    if (!file.exists()) {
                        // File was deleted externally, clear the record
                        Log.i("DownloadFragment", "File not found, clearing stale download: ${downloadData.vdcId}")
                        SharedPreference.instance.setDownloadData(null)
                        updateUI(false)
                        return
                    }
                }
            }

            val listItems = mutableListOf(downloadData)
            binding.recyclerViewDownloads.layoutManager = LinearLayoutManager(requireActivity())
            downloadVideoAdapter = DownloadsAdapter(requireActivity(), listItems, this@DownloadFragment, requireActivity() as MainActivity)
            binding.recyclerViewDownloads.adapter = downloadVideoAdapter
            updateUI(true)
        } ?: updateUI(false)
    }


    fun updateUI(wannaShow:Boolean){
        binding.noDownloadFoundCL.isVisible = !wannaShow
        binding.downloadFoundCL.isVisible = wannaShow
    }



}