package com.appsquadz.educryptmedia.models

data class VideoPlayback(
    var status: Boolean,
    var message: String?=null,
    var data : Data?=null
)

data class Data(
    var link: PlayUrls?=null
)

data class PlayUrls(
    var token: String?=null,
    var file_url: String?=null,
    var end: String?=null,
    var live_status: String?=null,
    var player_params: String?=null,
    var start: String?=null,
)



data class Downloads(
    var result: Int?=null,
    var msg: String?=null,
    var data : DownloadsData?=null
)

data class DownloadsData(
    var download_url: List<DownloadableUrl>?=null
)

data class DownloadableUrl(
    var url: String?=null,
    var title: String?=null,
    var size: String?=null
)
