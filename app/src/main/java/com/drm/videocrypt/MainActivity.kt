package com.drm.videocrypt

import android.Manifest
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.drm.videocrypt.databinding.ActivityMainBinding
import com.drm.videocrypt.fragments.DownloadFragment
import com.drm.videocrypt.fragments.HomeFragment
import com.drm.videocrypt.models.FragmentModels
import com.drm.videocrypt.models.ListItem
import com.appsquadz.educryptmedia.downloads.DownloadListener
import com.appsquadz.educryptmedia.playback.EducryptMedia
import com.appsquadz.educryptmedia.utils.DownloadStatus
import com.drm.videocrypt.utils.FragmentStateAdapterDemo
import com.drm.videocrypt.utils.SharedPreference
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity(), DownloadListener {

    private lateinit var binding: ActivityMainBinding
    private var educryptMedia: EducryptMedia?=null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        educryptMedia = EducryptMedia.getInstance(this)
        setUI()

        requestPermission()
    }

    private fun requestPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            1001
        )

        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),1002)

    }


    private fun setUI() = binding.apply {
        tabLayout.tabTextColors =
            ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.black, theme))
        val homeTab =
            tabLayout.newTab().setText(ContextCompat.getString(this@MainActivity, R.string.home))
        val feedsTab =
            tabLayout.newTab()
                .setText(ContextCompat.getString(this@MainActivity, R.string.downloads))

        tabLayout.addTab(homeTab)
        tabLayout.addTab(feedsTab)


        val adapter = FragmentStateAdapterDemo(supportFragmentManager, lifecycle)

        adapter.addFragment(
            FragmentModels(
                ContextCompat.getString(
                    this@MainActivity,
                    R.string.home
                ), HomeFragment()
            )
        )
        adapter.addFragment(
            FragmentModels(
                ContextCompat.getString(
                    this@MainActivity,
                    R.string.downloads
                ), DownloadFragment()
            )
        )

        fragmentViewPager.adapter = adapter

        TabLayoutMediator(tabLayout, fragmentViewPager) { tab, position ->
            tab.text = adapter.fragmentList[position].title
        }.attach()

    }

    fun switchToDownloads(){
        binding.fragmentViewPager.currentItem = 1
    }


    override fun pauseDownload(vdcId:String) {
        SharedPreference.instance!!.getDownloadData()?.let {
            val listItems = it
            listItems.status = DownloadStatus.PAUSED
            SharedPreference.instance!!.setDownloadData(listItems)
            educryptMedia?.pauseDownload(vdcId)
        }?:Toast.makeText(this,"Download Not Found",Toast.LENGTH_SHORT).show()
    }

    override fun resumeDownload(vdcId:String,url: String, fileName: String, downloadableName: String) {
        SharedPreference.instance!!.getDownloadData()?.let {
            val listItems = it
            listItems.status = DownloadStatus.RESUMED
            SharedPreference.instance!!.setDownloadData(listItems)
            educryptMedia?.resumeDownload(vdcId,url,fileName)
        }?:Toast.makeText(this,"Download Not Found",Toast.LENGTH_SHORT).show()
    }

    override fun startDownload(vdcId:String,url: String, fileName: String, downloadableName: String) {
        println("file name $fileName")
        println("url $url")

        if (vdcId.isNotEmpty() && url.isNotEmpty()){
            val item = ListItem(vdcId, url.toUri().lastPathSegment, url)
            SharedPreference.instance!!.setDownloadData(item)
            switchToDownloads()
            educryptMedia?.let {
                it.setNotificationVisibility(true)
                it.setDownloadableName(downloadableName)
                it.startDownload(vdcId,url,fileName)
            }

        }else{
            Toast.makeText(this,"Something went wrong",Toast.LENGTH_SHORT).show()
        }

    }

    override fun cancelDownload(vdcId:String) {
        educryptMedia?.cancelDownload(vdcId)
    }

    override fun deleteDownload(vdcId:String) {
        educryptMedia?.deleteDownload(vdcId)
    }



}