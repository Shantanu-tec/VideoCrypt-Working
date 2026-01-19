package com.appsquadz.educryptmedia.downloads

interface DownloadListener {
    fun pauseDownload(vdcId:String)
    fun resumeDownload(vdcId:String,url: String, fileName: String, downloadableName: String)
    fun startDownload(vdcId:String,url: String, fileName: String, downloadableName: String)
    fun cancelDownload(vdcId:String)
    fun deleteDownload(vdcId:String)
}