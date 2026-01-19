package com.appsquadz.educryptmedia.utils

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

import java.nio.charset.Charset

class SanitizingPlaylistDataSource(
    private val upstream: DataSource,
    private val sanitizer: (String) -> String // playlist text -> modified text
) : DataSource {

    private var playlistBytes: ByteArray? = null
    private var readPosition = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        // Open original request
        val length = upstream.open(dataSpec)

        // Only sanitize playlist files (.m3u8)
        val isPlaylist = dataSpec.uri.toString().contains(".m3u8")

        println("---> Uri : ${dataSpec.uri}")

        if (!isPlaylist) {
            // Not a playlist → return regular upstream
            playlistBytes = null
            return length
        }

        // Read full playlist into memory
        val rawBytes = readAllBytes(upstream)
        val playlistText = rawBytes.toString(Charset.defaultCharset())

        // Call sanitizer lambda
        val sanitizedText = sanitizer(playlistText)

        playlistBytes = sanitizedText.toByteArray()
        readPosition = 0

        // Return size of sanitized playlist
        return playlistBytes!!.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val data = playlistBytes ?: return upstream.read(buffer, offset, length)

        if (readPosition >= data.size) return -1

        val bytesToRead = minOf(length, data.size - readPosition)
        System.arraycopy(data, readPosition, buffer, offset, bytesToRead)
        readPosition += bytesToRead

        return bytesToRead
    }

    override fun getUri() = upstream.uri

    override fun close() {
        upstream.close()
        playlistBytes = null
        readPosition = 0
    }

    private fun readAllBytes(dataSource: DataSource): ByteArray {
        val buffer = ByteArray(32 * 1024)
        val output = ArrayList<Byte>()

        while (true) {
            val read = dataSource.read(buffer, 0, buffer.size)
            if (read == -1) break
            for (i in 0 until read) output.add(buffer[i])
        }

        return output.toByteArray()
    }
}
