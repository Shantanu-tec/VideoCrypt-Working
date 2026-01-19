package com.appsquadz.educryptmedia.realm.dao

import com.appsquadz.educryptmedia.realm.entity.DownloadMeta

interface DownloadMetaDao {

    fun insertOrUpdateData(downloadMeta: DownloadMeta?,callback:(Boolean) -> Unit)

    fun deleteDataByVdcId(vdcId: String,callback:(Boolean) -> Unit)

    fun getDataByVdcId(vdcId: String): DownloadMeta?

    fun getVideoStatusByVdcId(vdcId: String,callback:(String?) -> Unit)
    fun getVideoPercentageByVdcId(vdcId: String,callback:(String?) -> Unit)

    fun getVideoFileNameByVdcId(vdcId: String,callback:(String?) -> Unit)
    fun getVideoUrlByVdcId(vdcId: String,callback:(String?) -> Unit)

    fun getAllData() : List<DownloadMeta?>?

    fun updatePercentage(vdcId: String,percentage: String,callback:(Boolean) -> Unit)

    fun updateStatus(vdcId: String,status: String,callback:(Boolean) -> Unit)


    fun updatePercentageAndStatus(vdcId: String,percentage: String,status: String,callback:(Boolean) -> Unit)

    fun isDataExist(vdcId: String?,callback:(Boolean) -> Unit)

    fun deleteAllData(callback:(Boolean) -> Unit)

}