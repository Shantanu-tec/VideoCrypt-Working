package com.drm.videocrypt

import android.content.Context

fun dpToPx(dp: Int, context: Context): Int {
    val density = context.resources.displayMetrics.density
    return (dp * density).toInt()
}

