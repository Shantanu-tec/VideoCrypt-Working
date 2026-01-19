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

}