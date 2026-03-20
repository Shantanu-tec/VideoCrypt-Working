package com.appsquadz.educryptmedia.utils

import android.app.Activity
import android.text.TextUtils
import androidx.core.net.toUri
import com.appsquadz.educryptmedia.util.EducryptLogger
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector.Parameters
import androidx.media3.exoplayer.trackselection.MappingTrackSelector
import org.json.JSONObject
import retrofit2.Response
import java.io.File
import java.security.GeneralSecurityException
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

fun CipherInputStream.forceSkip(bytesToSkip: Long): Long {
    var processedBytes = 0L
    while (processedBytes < bytesToSkip) {
        read()
        processedBytes++
    }

    return processedBytes
}


@Throws(GeneralSecurityException::class)
fun getCipher(token: String): Cipher {
    val iv: String = AES.generateLibVectorAPI(token)
    val key: String = AES.generateLibkeyAPI(token)

    val AesKeyData: ByteArray = key.toByteArray()
    val InitializationVectorData = iv.toByteArray()
    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    val keySpec = SecretKeySpec(AesKeyData, "AES")
    cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(InitializationVectorData))
    return cipher
}


fun Response<String?>?.hitApi(
    invokeOnCompletion: (JSONObject) -> Unit,
    onError: ((String) -> Unit)? = null
) {
    try {
        if (this == null) {
            onError?.invoke("No response received from server")
            return
        }

        val jsonObject = if (this.isSuccessful) {
            JSONObject(this.body().toString())
        } else {
            val errorBody = this.errorBody()?.string()
            if (!errorBody.isNullOrEmpty()) {
                try {
                    JSONObject(errorBody)
                } catch (e: Exception) {
                    onError?.invoke("Server error: ${this.code()}")
                    return
                }
            } else {
                onError?.invoke("Server error: ${this.code()}")
                return
            }
        }
        invokeOnCompletion.invoke(jsonObject)
    } catch (e: java.net.UnknownHostException) {
        e.printStackTrace()
        onError?.invoke("Unable to connect to server. Please check your internet connection.")
    } catch (e: java.net.SocketTimeoutException) {
        e.printStackTrace()
        onError?.invoke("Connection timed out. Please try again.")
    } catch (e: java.io.IOException) {
        e.printStackTrace()
        onError?.invoke("Network error: ${e.message ?: "Please check your connection"}")
    } catch (e: Exception) {
        e.printStackTrace()
        onError?.invoke(e.message ?: "An unexpected error occurred")
    }
}

@UnstableApi
fun getResolution(trackSelector: DefaultTrackSelector, loop: (String, String) -> Unit) {
    if (trackSelector.currentMappedTrackInfo != null) {
        val mappedTrackInfo = trackSelector.currentMappedTrackInfo
        if (mappedTrackInfo != null) {
            val trackGroupArray = mappedTrackInfo.getTrackGroups(0)
            val trackCount = trackGroupArray.length
            for (i in 0 until trackCount) {
                for (j in 0 until trackGroupArray[i].length) {
                    if (!TextUtils.isEmpty("${trackGroupArray[i].getFormat(j).height}")) {
                        loop.invoke("${trackGroupArray[i].getFormat(j).height}p",j.toString())
                    }
                }
            }
        }
    }
}



const val MEDIA_TAG = "EducryptMedia"


fun Activity.isDownloadExistForVdcId(vdcId: String?, fileName: String?) : Boolean{
    try {
        val file = File(this.getExternalFilesDir(null), "$fileName.mp4")
        return file.exists()
    }catch (e: Exception){
        e.printStackTrace()
        return false
    }
}

fun Activity.getDownloadablePath(fileName: String?): String{
    try {
        val file = File(this.getExternalFilesDir(null), "$fileName.mp4")
        return if (file.exists()){
            file.toString()
        }else{
            ""
        }
    }catch (e: Exception){
        e.printStackTrace()
        return ""
    }
}

fun Activity.getDownloadableFile(fileName: String?): File{
    try {
        val file = File(this.getExternalFilesDir(null), "$fileName.mp4")
        return if (file.exists()){
            file
        }else{
            throw Exception("File not found")
        }
    }catch (e: Exception){
        e.printStackTrace()
        throw e
    }
}


@UnstableApi
fun switchBitrateToAll(trackSelector: DefaultTrackSelector){
//    if (trackSelector.currentMappedTrackInfo != null) {
//        val mappedTrackInfo = trackSelector.currentMappedTrackInfo
//        val parameters = trackSelector.parameters
//        val builder = parameters.buildUpon()
//        builder.clearSelectionOverride(0, mappedTrackInfo!!.getTrackGroups(0))
//        trackSelector.setParameters(builder)
//    }

    val parameters = trackSelector.parameters.buildUpon()
        .clearOverrides() // Clears all manual overrides for ALL renderer types
        .build()

    // Set the new parameters, which removes the forced selection
    trackSelector.setParameters(parameters)
}

/**
 * Switches the video quality to a specific index (0, 1, 2, or 3) within the adaptive group.
 * @param variantIndex The index of the desired quality (e.g., 3 for 720p).
 */
@UnstableApi
fun switchBitrateAccordingly(trackSelector: DefaultTrackSelector, variantIndex: Int) {
    val mappedTrackInfo = trackSelector.currentMappedTrackInfo ?: return

    // 1. FIND THE VIDEO RENDERER INDEX
    var videoRendererIndex = -1
    for (i in 0 until mappedTrackInfo.rendererCount) {
        if (mappedTrackInfo.getRendererType(i) == C.TRACK_TYPE_VIDEO) {
            videoRendererIndex = i
            break
        }
    }

    if (videoRendererIndex == -1) {
        EducryptLogger.e("Video Renderer not found!")
        return
    }

    // 2. GET THE TRACK GROUP FOR THE VIDEO RENDERER
    // For HLS adaptive streams, there is usually only one TrackGroup for video variants.
    val trackGroups = mappedTrackInfo.getTrackGroups(videoRendererIndex)
    if (trackGroups.length == 0) {
        EducryptLogger.e("No video track groups available!")
        return
    }
    val videoTrackGroup = trackGroups.get(0)

    // 3. CREATE THE SELECTION OVERRIDE
    // We create an override for the single video TrackGroup, selecting ONLY the desired index.
    val override = TrackSelectionOverride(
        videoTrackGroup,
        listOf(variantIndex) // Select only the desired track (0, 1, 2, or 3)
    )

    // 4. APPLY PARAMETERS: Clear ALL previous overrides, then set the new one
    val newParameters = trackSelector.parameters.buildUpon()
        .clearOverrides()
        .setOverrideForType(override)
        .build()

    trackSelector.setParameters(newParameters)
}

//@UnstableApi
//fun switchBitrateAccordingly(trackSelector: DefaultTrackSelector,index:Int){
//    if (trackSelector.currentMappedTrackInfo != null) {
//        val mappedTrackInfo = trackSelector.currentMappedTrackInfo
//        val parameters = trackSelector.parameters
//        val builder = parameters.buildUpon()
//        for (i in 0 until mappedTrackInfo!!.rendererCount) {
//            builder.clearSelectionOverrides(i).setRendererDisabled(
//                i, false
//            )
//        }
//        selectionOverride(index, builder, mappedTrackInfo)
//        trackSelector.setParameters(builder)
//    }
//}

@UnstableApi
private fun selectionOverride(
    position: Int,
    builder: Parameters.Builder,
    mappedTrackInfo: MappingTrackSelector.MappedTrackInfo
) {
    builder.setSelectionOverride(
        0,
        mappedTrackInfo.getTrackGroups(0),
        DefaultTrackSelector.SelectionOverride(
            0, position
        )
    )
}

fun formatFileSize(bytes: Long): String {
    val kb = 1024
    val mb = kb * 1024
    val gb = mb * 1024

    return when {
        bytes >= gb -> String.format(Locale.getDefault(),"%.2f GB", bytes.toDouble() / gb)
        bytes >= mb -> String.format(Locale.getDefault(),"%.2f MB", bytes.toDouble() / mb)
        bytes >= kb -> String.format(Locale.getDefault(),"%.2f KB", bytes.toDouble() / kb)
        else -> "$bytes B"
    }
}
