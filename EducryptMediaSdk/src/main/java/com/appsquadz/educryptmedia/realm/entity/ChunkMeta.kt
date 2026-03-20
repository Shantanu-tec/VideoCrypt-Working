package com.appsquadz.educryptmedia.realm.entity

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.PrimaryKey

open class ChunkMeta : RealmObject {
    /** Composite key: "$vdcId-$chunkIndex" — unique per download + chunk position. */
    @PrimaryKey var id: String = ""
    var vdcId: String = ""
    var chunkIndex: Int = 0
    var startByte: Long = 0L
    var endByte: Long = 0L
    /** Bytes downloaded so far for this chunk (updated every 512 KB during download). */
    var downloadedBytes: Long = 0L
    var completed: Boolean = false
}
