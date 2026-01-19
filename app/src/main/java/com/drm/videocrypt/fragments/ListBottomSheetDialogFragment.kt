package com.drm.videocrypt.fragments

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drm.videocrypt.R
import com.drm.videocrypt.adapter.ListItemAdapter
import com.drm.videocrypt.models.ListItem
import com.appsquadz.educryptmedia.downloads.DownloadListener
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ListBottomSheetDialogFragment(
    private val activity: Activity,
    private val listItems: MutableList<ListItem>,
    private val downloadListener: DownloadListener) : BottomSheetDialogFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var closeButton: android.widget.ImageView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_bottom_sheet_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerView)
        closeButton = view.findViewById(R.id.closeButton)

        recyclerView.layoutManager = LinearLayoutManager(context)

        val adapter = ListItemAdapter(activity,listItems,downloadListener){
            dismiss()
        }
        recyclerView.adapter = adapter

        closeButton.setOnClickListener {
            dismiss()
        }
    }
}