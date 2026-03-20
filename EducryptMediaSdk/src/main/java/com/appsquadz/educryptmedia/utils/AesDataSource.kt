package com.appsquadz.educryptmedia.utils

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import okio.IOException
import java.io.File
import javax.crypto.Cipher
import javax.crypto.CipherInputStream

@OptIn(UnstableApi::class)
class AesDataSource(
    private val cipher: Cipher
) : DataSource {

    private var inputStream: CipherInputStream? = null
    private lateinit var uri: Uri

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        uri.path ?: return 0

        val file = File(uri.path!!).canonicalFile
        inputStream = CipherInputStream(file.inputStream(), cipher)
        if (dataSpec.position != 0L) {
            inputStream?.forceSkip(dataSpec.position) // Needed for skipping
        }

        return dataSpec.length
    }

    @Throws(IOException::class)
    override fun read(target: ByteArray, offset: Int, length: Int): Int =
        if (length == 0) {
            0
        } else {
            inputStream?.read(target, offset, length) ?: 0
        }

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun getUri(): Uri = uri

    override fun close() {
        inputStream?.close()
    }
}
