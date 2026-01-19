package com.appsquadz.educryptmedia


import com.appsquadz.educryptmedia.interfaces.ApiInterface
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    fun create(): ApiInterface {
        val okHttpClient = OkHttpClient.Builder()
        try {


            okHttpClient.addInterceptor(Interceptor {

                val requestBuilder = it.request().newBuilder()

                val request = requestBuilder.build()

                return@Interceptor it.proceed(request)
            }).connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

        } catch (e: Exception) {
            e.printStackTrace()
        }


        val logging = HttpLoggingInterceptor()

        logging.setLevel(HttpLoggingInterceptor.Level.BODY)

        okHttpClient.addInterceptor(logging)

        val httpClient = okHttpClient.build()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.videocrypt.com/")
            .client(httpClient)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(ApiInterface::class.java)
    }
}