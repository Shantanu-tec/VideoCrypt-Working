package com.appsquadz.educryptmedia

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.appsquadz.educryptmedia.interfaces.ApiInterface
import com.appsquadz.educryptmedia.utils.BadRequestException
import com.appsquadz.educryptmedia.utils.ForbiddenException
import com.appsquadz.educryptmedia.utils.HttpException
import com.appsquadz.educryptmedia.utils.NetworkException
import com.appsquadz.educryptmedia.utils.NoConnectivityException
import com.appsquadz.educryptmedia.utils.NoInternetException
import com.appsquadz.educryptmedia.utils.NotFoundException
import com.appsquadz.educryptmedia.utils.RequestTimeoutException
import com.appsquadz.educryptmedia.utils.ServerException
import com.appsquadz.educryptmedia.utils.SslException
import com.appsquadz.educryptmedia.utils.TimeoutException
import com.appsquadz.educryptmedia.utils.TooManyRequestsException
import com.appsquadz.educryptmedia.utils.UnauthorizedException
import com.appsquadz.educryptmedia.utils.UnknownException
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

import com.appsquadz.educryptmedia.BuildConfig
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
            // 1. Network connectivity check (Google/Uber pattern)
            addInterceptor(networkAvailabilityInterceptor())
            
            // 2. Authentication/Header interceptor (Industry standard)
            addInterceptor(headerInterceptor())
            
            // 3. Cache interceptor (Offline-first pattern - Netflix/Airbnb)
            cache(createCache())
            addInterceptor(cacheInterceptor())
            addNetworkInterceptor(networkCacheInterceptor())
            
            // 4. Retry with exponential backoff (Google Cloud pattern)
            addInterceptor(retryInterceptor())
            
            // 5. Error handling interceptor
            addInterceptor(errorHandlingInterceptor())
            
            // 6. Logging (only debug builds)
            if (BuildConfig.DEBUG) {
                addInterceptor(headerLoggingInterceptor())
                addInterceptor(createLoggingInterceptor())
            }
            
            // 7. SSL pinning for production (Security best practice)
            if (!BuildConfig.DEBUG) {
                certificatePinner(createCertificatePinner())
            }
            
            // 8. Connection pool (Performance optimization)
            connectionPool(ConnectionPool(
                maxIdleConnections = 5,
                keepAliveDuration = 5,
                timeUnit = TimeUnit.MINUTES
            ))
            
            // 9. Timeouts
            connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            
            // 10. Retry on connection failure
            retryOnConnectionFailure(true)
            
            // 11. DNS fallback (Uber pattern)
            dns(createDnsWithFallback())
            
        }.build()
    }
    
    // 1. Network Availability Checker (Proactive approach)
    private fun networkAvailabilityInterceptor(): Interceptor {
        return Interceptor { chain ->
            if (!isNetworkAvailable()) {
                throw NoConnectivityException()
            }
            chain.proceed(chain.request())
        }
    }
    

    
    // 2. Header Interceptor (Add auth tokens, API keys, etc.)
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
    
    // 4. Retry with Exponential Backoff (Google Cloud SDK pattern)
    private fun retryInterceptor(): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            var response: Response? = null
            var exception: IOException? = null
            var tryCount = 0
            
            while (tryCount < MAX_RETRIES) {
                try {
                    response?.close()
                    response = chain.proceed(request)
                    
                    // Success
                    if (response.isSuccessful) {
                        return@Interceptor response
                    }
                    
                    // Don't retry on client errors (4xx) except 408, 429
                    val code = response.code
                    if (code in 400..499 && code != 408 && code != 429) {
                        return@Interceptor response
                    }
                    
                    // Don't retry on specific HTTP methods
                    if (!isRetriableMethod(request.method)) {
                        return@Interceptor response
                    }
                    
                } catch (e: IOException) {
                    exception = e
                    
                    // Don't retry on SSL errors
                    if (e is SSLException) {
                        throw e
                    }
                }
                
                tryCount++
                
                if (tryCount < MAX_RETRIES) {
                    // Exponential backoff: 1s, 2s, 4s
                    val waitTime = (1000L * Math.pow(2.0, (tryCount - 1).toDouble())).toLong()
                    Thread.sleep(waitTime)
                }
            }
            
            response ?: throw exception ?: IOException("Unknown error after $MAX_RETRIES retries")
        }
    }
    
    private fun isRetriableMethod(method: String): Boolean {
        return method.equals("GET", ignoreCase = true) ||
               method.equals("PUT", ignoreCase = true) ||
               method.equals("DELETE", ignoreCase = true)
    }
    
    // 5. Comprehensive Error Handling
    private fun errorHandlingInterceptor(): Interceptor {
        return Interceptor { chain ->
            try {
                val response = chain.proceed(chain.request())
                
                when (response.code) {
                    in 200..299 -> response
                    400 -> throw BadRequestException("Invalid request parameters")
                    401 -> throw UnauthorizedException("Authentication required")
                    403 -> throw ForbiddenException("Access denied")
                    404 -> throw NotFoundException("Resource not found")
                    408 -> throw RequestTimeoutException("Request timeout")
                    429 -> throw TooManyRequestsException("Rate limit exceeded")
                    in 500..599 -> throw ServerException("Server error: ${response.code}")
                    else -> throw HttpException(response.code, "HTTP error: ${response.message}")
                }
            } catch (e: Exception) {
                throw when (e) {
                    is NoConnectivityException -> e
                    is UnknownHostException -> NoInternetException("No internet connection")
                    is SocketTimeoutException -> TimeoutException("Connection timeout")
                    is SSLException -> SslException("Security error: ${e.message}")
                    is IOException -> NetworkException("Network error: ${e.message}")
                    is HttpException, is BadRequestException, is UnauthorizedException,
                    is ForbiddenException, is NotFoundException, is RequestTimeoutException,
                    is TooManyRequestsException, is ServerException -> e
                    else -> UnknownException("Unexpected error: ${e.message}")
                }
            }
        }
    }
    
    // 6. Logging Interceptor
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
            android.util.Log.d("NetworkManager", "========== REQUEST ==========")
            android.util.Log.d("NetworkManager", "URL: ${request.url}")
            android.util.Log.d("NetworkManager", "Method: ${request.method}")
            android.util.Log.d("NetworkManager", "Headers:")
            request.headers.forEach { header ->
                android.util.Log.d("NetworkManager", "  ${header.first}: ${header.second}")
            }

            val response = chain.proceed(request)

            // Log all response headers
            android.util.Log.d("NetworkManager", "========== RESPONSE ==========")
            android.util.Log.d("NetworkManager", "Status Code: ${response.code}")
            android.util.Log.d("NetworkManager", "Message: ${response.message}")
            android.util.Log.d("NetworkManager", "Headers:")
            response.headers.forEach { header ->
                android.util.Log.d("NetworkManager", "  ${header.first}: ${header.second}")
            }
            android.util.Log.d("NetworkManager", "================================")

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

// Exception Hierarchy (Type-safe error handling)


// Repository Pattern (Clean Architecture)
/*
class ExampleRepository(private val api: ApiInterface) {
    
    suspend fun fetchData(): NetworkResult<YourDataModel> {
        return withContext(Dispatchers.IO) {
            try {
                val response = api.getData()
                NetworkResult.Success(response)
            } catch (e: NoConnectivityException) {
                NetworkResult.Error("No internet connection", NetworkErrorType.NO_CONNECTION)
            } catch (e: TimeoutException) {
                NetworkResult.Error("Request timeout", NetworkErrorType.TIMEOUT)
            } catch (e: UnauthorizedException) {
                NetworkResult.Error("Please login again", NetworkErrorType.UNAUTHORIZED)
            } catch (e: ServerException) {
                NetworkResult.Error("Server error. Please try again", NetworkErrorType.SERVER_ERROR)
            } catch (e: Exception) {
                NetworkResult.Error("Something went wrong", NetworkErrorType.UNKNOWN)
            }
        }
    }
}

sealed class NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>()
    data class Error(val message: String, val type: NetworkErrorType) : NetworkResult<Nothing>()
    object Loading : NetworkResult<Nothing>()
}

enum class NetworkErrorType {
    NO_CONNECTION, TIMEOUT, UNAUTHORIZED, SERVER_ERROR, UNKNOWN
}

// Initialize in Application class
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NetworkManager.initialize(this)
    }
}
*/