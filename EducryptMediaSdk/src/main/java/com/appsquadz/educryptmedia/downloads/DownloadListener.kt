package com.appsquadz.educryptmedia.downloads

interface DownloadListener {
    fun pauseDownload(vdcId: String)

    fun resumeDownload(
        vdcId: String,
        url: String,
        fileName: String,
        downloadableName: String,
        onError: ((String) -> Unit)? = null,
        onSuccess: (() -> Unit)? = null
    )

    fun startDownload(
        vdcId: String,
        url: String,
        fileName: String,
        downloadableName: String,
        onError: ((String) -> Unit)? = null,
        onSuccess: (() -> Unit)? = null
    )

    fun cancelDownload(vdcId: String)

    fun deleteDownload(vdcId: String)
}