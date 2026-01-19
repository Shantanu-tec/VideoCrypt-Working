package com.appsquadz.educryptmedia.interfaces

import com.appsquadz.educryptmedia.EncryptionData
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST


interface ApiInterface {

    @POST(Apis.getContentPlayBack)
    suspend fun getContentPlayBack(
        @Body data: EncryptionData,
        @Header("accessKey") accessKey: String,
        @Header("secretKey") secretKey: String,
        @Header("user-id") userId: String,
        @Header("device-type") deviceType: String,
        @Header("device-id") deviceId: String,
        @Header("version") version: String,
        @Header("device-name") deviceName: String,
        @Header("account-id") accountId: String,
    ): Response<String?>?


    @POST(Apis.getDownloads)
    suspend fun getDownloadDetails(
        @Body data: EncryptionData,
        @Header("accessKey") accessKey: String,
        @Header("secretKey") secretKey: String,
    ): Response<String?>?


}