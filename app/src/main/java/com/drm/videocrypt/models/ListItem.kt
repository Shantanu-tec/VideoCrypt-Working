package com.drm.videocrypt.models

import com.appsquadz.educryptmedia.utils.DownloadStatus
data class ListItem(
    var vdcId: String?=null,
    var text: String?=null,
    var url: String?=null,
    var size: String?=null,
    var percentage: Int = 0,
    var status: String = DownloadStatus.DOWNLOADING
)


