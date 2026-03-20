package com.appsquadz.educryptmedia.realm.impl

import com.appsquadz.educryptmedia.realm.dao.DownloadMetaDao
import com.appsquadz.educryptmedia.realm.entity.DownloadMeta
import com.appsquadz.educryptmedia.util.EducryptLogger
import io.realm.kotlin.Realm
import io.realm.kotlin.UpdatePolicy
import io.realm.kotlin.ext.query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DownloadMetaImpl(val realm: Realm) : DownloadMetaDao {
    override  fun insertOrUpdateData(
        downloadMeta: DownloadMeta?,
        callback: (Boolean) -> Unit
    ) {
        try {
            downloadMeta?.let {
                isDataExist(vdcId = it.vdcId){ exist ->
                    if (exist){
                        updatePercentageAndStatus(vdcId = it.vdcId!!,
                            percentage = it.percentage!!,
                            status = it.status!!,callback = callback)
                    }else{
                        realm.writeBlocking {
                            copyToRealm(it, UpdatePolicy.ALL)
                            callback(true)
                        }
                    }
                }
            }?:callback(false)

        }catch (e:Exception){
            callback(false)
            e.printStackTrace()
        }

    }

    override fun deleteDataByVdcId(
        vdcId: String,
        callback: (Boolean) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                realm.write {
                    val download = this.query<DownloadMeta>("vdcId == $0", vdcId)
                        .find()
                        .firstOrNull()

                    if (download != null) {
                        val latest = findLatest(download)
                        if (latest != null) {
                            delete(latest)
                            callback(true)
                        } else {
                            callback(false)
                        }
                    } else {
                        callback(false)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                callback(false)
            }
        }
    }


    override fun getDataByVdcId(
        vdcId: String
    ) : DownloadMeta? {
        return try {
            realm.query<DownloadMeta>(query = "vdcId == $0",vdcId).find().firstOrNull()
        } catch (e: Exception) {
            EducryptLogger.e("getDataByVdcId failed for $vdcId", e)
            null
        }
    }

    override fun getVideoStatusByVdcId(
        vdcId: String,
        callback: (String?) -> Unit
    ) {
        try {
            val download = realm.query<DownloadMeta>(query = "vdcId == $0",vdcId).find().firstOrNull()
            download?.let {
                callback(it.status)
            }?:callback(null)
        }catch (e:Exception){
            callback(null)
            e.printStackTrace()
        }
    }

    override fun getVideoPercentageByVdcId(
        vdcId: String,
        callback: (String?) -> Unit
    ) {
        try {
            val download = realm.query<DownloadMeta>(query = "vdcId == $0",vdcId).find().firstOrNull()
            download?.let {
                callback(it.percentage)
            }?:callback(null)
        }catch (e:Exception){
            callback(null)
            e.printStackTrace()
        }
    }

    override fun getVideoFileNameByVdcId(
        vdcId: String,
        callback: (String?) -> Unit
    ) {
        try {
            val download = realm.query<DownloadMeta>(query = "vdcId == $0",vdcId).find().firstOrNull()
            download?.let {
                callback(it.fileName)
            }?:callback(null)
        }catch (e:Exception){
            callback(null)
            e.printStackTrace()
        }
    }

    override fun getVideoUrlByVdcId(
        vdcId: String,
        callback: (String?) -> Unit
    ) {
        try {
            val download = realm.query<DownloadMeta>(query = "vdcId == $0",vdcId).find().firstOrNull()
            download?.let {
                callback(it.url)
            }?:callback(null)
        }catch (e:Exception){
            callback(null)
            e.printStackTrace()
        }
    }

    override fun getAllData() : List<DownloadMeta?>? {
        try {
            return realm.query<DownloadMeta>().find().toList()
        }catch (e:Exception){
            return null
            e.printStackTrace()
        }
    }

    override fun updatePercentage(
        vdcId: String,
        percentage: String,
        callback: (Boolean) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                realm.write {
                    val download = this.query<DownloadMeta>("vdcId == $0", vdcId).find().firstOrNull()
                    if (download != null) {
                        download.percentage = percentage
                        callback(true)
                    } else {
                        callback(false)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                callback(false)
            }
        }
    }

    override fun updateStatus(
        vdcId: String,
        status: String,
        callback: (Boolean) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                realm.write {
                    val download = this.query<DownloadMeta>("vdcId == $0", vdcId).find().firstOrNull()
                    if (download != null) {
                        download.status = status
                        callback(true)
                    } else {
                        callback(false)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                callback(false)
            }
        }
    }


    override fun updatePercentageAndStatus(
        vdcId: String,
        percentage: String,
        status: String,
        callback: (Boolean) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                realm.write {
                    val download = this.query<DownloadMeta>("vdcId == $0", vdcId)
                        .find()
                        .firstOrNull()

                    if (download != null) {
                        download.percentage = percentage
                        download.status = status
                        callback(true)
                    } else {
                        callback(false)
                    }
                }
            } catch (e: Exception) {
                callback(false)
                e.printStackTrace()
            }
        }
    }

    override fun updateProgress(
        vdcId: String,
        percentage: String,
        downloadedBytes: Long,
        status: String,
        callback: (Boolean) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                realm.write {
                    val download = this.query<DownloadMeta>("vdcId == $0", vdcId)
                        .find()
                        .firstOrNull()

                    if (download != null) {
                        download.percentage = percentage
                        download.downloadedBytes = downloadedBytes
                        download.status = status
                        callback(true)
                    } else {
                        callback(false)
                    }
                }
            } catch (e: Exception) {
                callback(false)
                e.printStackTrace()
            }
        }
    }

    override fun isDataExist(vdcId: String?, callback: (Boolean) -> Unit) {
        try {
            val download = realm.query<DownloadMeta>(query = "vdcId == $0",vdcId).find().firstOrNull()
            download?.let {
                callback(true)
            }?:callback(false)
        }catch (e:Exception){
            callback(false)
            e.printStackTrace()
        }
    }

    override fun deleteAllData(callback: (Boolean) -> Unit) {
        try {
            realm.writeBlocking {
                val frogsLeftInTheRealm = this.query<DownloadMeta>().find()
                delete(frogsLeftInTheRealm)
            }
            callback(true)
        } catch (e: Exception) {
            e.printStackTrace()
            callback(false)
        }
    }
}