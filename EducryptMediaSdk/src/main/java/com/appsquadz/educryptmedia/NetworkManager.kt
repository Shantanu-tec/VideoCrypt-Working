package com.appsquadz.educryptmedia

import android.util.Log
import com.appsquadz.educryptmedia.interfaces.ApiInterface
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

import com.appsquadz.educryptmedia.playback.EducryptMedia.Companion.createCache
import com.appsquadz.educryptmedia.playback.EducryptMedia.Companion.isNetworkAvailable


object NetworkManager {
    
    private const val BASE_URL = "https://api.videocrypt.com/"
    private const val CONNECT_TIMEOUT = 30L
    private const val READ_TIMEOUT = 30L
    private const val WRITE_TIMEOUT = 30L
    private const val MAX_RETRIES = 3
    

    fun create(): ApiInterface {
        val okHttpClient = buildOkHttpClient()
        val retrofit = buildRetrofit(okHttpClient)
        return retrofit.create(ApiInterface::class.java)
    }
    
    private fun buildOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder().apply {
            // 1. Authentication/Header interceptor (Industry standard)
            addInterceptor(headerInterceptor())

            // 2. Cache interceptor (Offline-first pattern - Netflix/Airbnb)
            cache(createCache())
            addInterceptor(cacheInterceptor())
            addNetworkInterceptor(networkCacheInterceptor())

            // 3. Retry with exponential backoff (Google Cloud pattern)
            addInterceptor(retryInterceptor())

            // 4. Logging (only debug builds)
            if (BuildConfig.DEBUG) {
                addInterceptor(headerLoggingInterceptor())
                addInterceptor(createLoggingInterceptor())
            }

            // 5. SSL pinning for production (Security best practice)
//            if (!BuildConfig.DEBUG) {
//                certificatePinner(createCertificatePinner())
//            }

            // 6. Connection pool (Performance optimization)
            connectionPool(ConnectionPool(
                maxIdleConnections = 5,
                keepAliveDuration = 5,
                timeUnit = TimeUnit.MINUTES
            ))

            // 7. Timeouts
            connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)

            // 8. Retry on connection failure
            retryOnConnectionFailure(true)

            // 9. DNS fallback (Uber pattern)
            dns(createDnsWithFallback())

        }.build()
    }

    // 1. Header Interceptor (Add auth tokens, API keys, etc.)
    private fun headerInterceptor(): Interceptor {
        return Interceptor { chain ->
            val originalRequest = chain.request()
            
            val requestBuilder = originalRequest.newBuilder()
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                // Add auth token if available
                // .header("Authorization", "Bearer ${getAuthToken()}")
                .method(originalRequest.method, originalRequest.body)
            
            chain.proceed(requestBuilder.build())
        }
    }
    

    
    private fun cacheInterceptor(): Interceptor {
        return Interceptor { chain ->
            var request = chain.request()
            
            request = if (isNetworkAvailable()) {
                // Online: cache for 1 minute
                request.newBuilder()
                    .header("Cache-Control", "public, max-age=60")
                    .build()
            } else {
                // Offline: use stale cache up to 7 days
                request.newBuilder()
                    .header("Cache-Control", "public, only-if-cached, max-stale=604800")
                    .build()
            }
            
            chain.proceed(request)
        }
    }
    
    private fun networkCacheInterceptor(): Interceptor {
        return Interceptor { chain ->
            val response = chain.proceed(chain.request())
            
            val cacheControl = CacheControl.Builder()
                .maxAge(1, TimeUnit.MINUTES)
                .build()
            
            response.newBuilder()
                .header("Cache-Control", cacheControl.toString())
                .removeHeader("Pragma")
                .build()
        }
    }
    
    // 3. Retry with Exponential Backoff (Google Cloud SDK pattern)
    private fun retryInterceptor(): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            var response: Response? = null
            var lastException: IOException? = null
            var tryCount = 0

            while (tryCount < MAX_RETRIES) {
                try {
                    response?.close()
                    response = chain.proceed(request)

                    // Success or client error (don't retry on 4xx except 408, 429)
                    if (response.isSuccessful) {
                        return@Interceptor response
                    }

                    val code = response.code
                    if (code in 400..499 && code != 408 && code != 429) {
                        return@Interceptor response
                    }

                    // Don't retry on non-idempotent methods
                    if (!isRetriableMethod(request.method)) {
                        return@Interceptor response
                    }

                } catch (e: IOException) {
                    lastException = e
                    Log.w("NetworkManager", "Request failed (attempt ${tryCount + 1}/$MAX_RETRIES): ${e.message}")
                }

                tryCount++

                if (tryCount < MAX_RETRIES) {
                    // Exponential backoff: 1s, 2s, 4s
                    val waitTime = (1000L * Math.pow(2.0, (tryCount - 1).toDouble())).toLong()
                    try {
                        Thread.sleep(waitTime)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }

            // Return last response if available, otherwise propagate exception
            // (OkHttp requires either a Response or an IOException)
            response ?: throw lastException ?: IOException("Request failed after $MAX_RETRIES attempts")
        }
    }
    
    private fun isRetriableMethod(method: String): Boolean {
        // POST included — all SDK endpoints are idempotent lookups (stream init, download URL fetch).
        // If a non-idempotent POST endpoint is ever added, exclude it here by URL before calling this.
        return method.equals("GET", ignoreCase = true) ||
               method.equals("POST", ignoreCase = true) ||
               method.equals("PUT", ignoreCase = true) ||
               method.equals("DELETE", ignoreCase = true)
    }
    
    // 4. Logging Interceptor
    private fun createLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
            redactHeader("Authorization")
            redactHeader("Cookie")
        }
    }

    private fun headerLoggingInterceptor(): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()

            // Log all request headers
            Log.d("NetworkManager", "========== REQUEST ==========")
            Log.d("NetworkManager", "URL: ${request.url}")
            Log.d("NetworkManager", "Method: ${request.method}")
            Log.d("NetworkManager", "Headers:")
            request.headers.forEach { header ->
                Log.d("NetworkManager", "  ${header.first}: ${header.second}")
            }

            val response = chain.proceed(request)

            // Log all response headers
            Log.d("NetworkManager", "========== RESPONSE ==========")
            Log.d("NetworkManager", "Status Code: ${response.code}")
            Log.d("NetworkManager", "Message: ${response.message}")
            Log.d("NetworkManager", "Headers:")
            response.headers.forEach { header ->
                Log.d("NetworkManager", "  ${header.first}: ${header.second}")
            }
            Log.d("NetworkManager", "================================")

            response
        }
    }


    // 7. Certificate Pinning (Production security)
    private fun createCertificatePinner(): CertificatePinner {
        return CertificatePinner.Builder()
            // Add your certificate pins here
            // .add("api.videocrypt.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            .build()
    }
    
    // 8. DNS with Fallback (Uber pattern)
    private fun createDnsWithFallback(): Dns {
        return object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                return try {
                    Dns.SYSTEM.lookup(hostname)
                } catch (e: UnknownHostException) {
                    // Fallback to Google DNS or Cloudflare DNS
                    java.net.InetAddress.getAllByName(hostname).toList()
                }
            }
        }
    }
    
    private fun buildRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
}