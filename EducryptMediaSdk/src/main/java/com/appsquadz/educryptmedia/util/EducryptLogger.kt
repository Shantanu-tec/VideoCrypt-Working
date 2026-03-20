package com.appsquadz.educryptmedia.util

import android.util.Log
import com.appsquadz.educryptmedia.BuildConfig

internal object EducryptLogger {

    private const val TAG = "EducryptMedia"

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    fun i(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    fun w(message: String) {
        if (BuildConfig.DEBUG) Log.w(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.e(TAG, message, throwable)
    }

    fun v(message: String) {
        if (BuildConfig.DEBUG) Log.v(TAG, message)
    }
}
