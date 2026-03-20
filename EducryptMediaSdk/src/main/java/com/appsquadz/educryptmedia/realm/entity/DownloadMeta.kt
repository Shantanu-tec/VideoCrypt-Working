package com.appsquadz.educryptmedia.realm.entity

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.PrimaryKey
open class DownloadMeta : RealmObject {

    @PrimaryKey
    var vdcId: String?=null
    var fileName: String?=null
    var url: String?=null
    var percentage: String?=null
    var status: String?=null
    // Added schema v2 — total file size and bytes downloaded so far.
    // Default 0L; migrated records also get 0L (see RealmManager migration).
    var totalBytes: Long = 0L
    var downloadedBytes: Long = 0L

}