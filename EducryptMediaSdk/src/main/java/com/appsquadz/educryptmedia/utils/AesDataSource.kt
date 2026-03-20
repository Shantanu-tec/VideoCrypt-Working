package com.appsquadz.educryptmedia.utils

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.appsquadz.educryptmedia.util.EducryptLogger
import java.io.File
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec

@OptIn(UnstableApi::class)
internal class AesDataSource(
    private val token: String
) : DataSource {

    companion object {
        private const val BLOCK_SIZE = 16        // AES block size — cryptographic constant
        private const val READ_BUFFER_SIZE = 8192 // 8 KB — must be multiple of BLOCK_SIZE (8192 = 512×16)
    }

    private var randomAccessFile: RandomAccessFile? = null
    private var cipher: Cipher? = null
    private var uri: Uri = Uri.EMPTY

    // Holds excess decrypted bytes from block-aligned reads
    // CBC cipher.update() only outputs complete 16-byte blocks. When we read
    // more bytes than requested to maintain alignment, the surplus is buffered here
    // and drained on the next read() call.
    private var overflow: ByteArray = ByteArray(0)
    private var overflowOffset: Int = 0

    override fun open(dataSpec: DataSpec): Long {
        // Close any existing handle before opening a new one
        randomAccessFile?.close()
        randomAccessFile = null

        uri = dataSpec.uri
        val path = uri.path ?: return 0

        val file = File(path).canonicalFile
        val raf = RandomAccessFile(file, "r")
        randomAccessFile = raf
        overflow = ByteArray(0)
        overflowOffset = 0

        val position = dataSpec.position

        if (position == 0L) {
            // Position 0 — use getCipher() exactly as before, unchanged path
            cipher = getCipher(token)
            raf.seek(0)
            EducryptLogger.d("AesDataSource: open at 0")
        } else {
            // O(1) CBC block seek:
            // IV for block N = raw ciphertext bytes at (N-1)*16 in the file
            val blockIndex  = position / BLOCK_SIZE
            val blockOffset = (position % BLOCK_SIZE).toInt()

            val seekIv: ByteArray = if (blockIndex == 0L) {
                // Still in first block — use original IV
                AES.generateLibVectorAPI(token).toByteArray()
            } else {
                // Read 16 raw ciphertext bytes from file — these are the IV
                val ivBytes = ByteArray(BLOCK_SIZE)
                raf.seek((blockIndex - 1) * BLOCK_SIZE)
                raf.readFully(ivBytes)
                ivBytes
            }

            cipher = getCipherWithIv(token, seekIv)
            raf.seek(blockIndex * BLOCK_SIZE)

            // If seek landed mid-block, decrypt the full block and buffer
            // the bytes from blockOffset onward
            if (blockOffset > 0) {
                val rawBlock = ByteArray(BLOCK_SIZE)
                val bytesRead = raf.read(rawBlock)
                if (bytesRead == BLOCK_SIZE) {
                    val decrypted = cipher!!.update(rawBlock) ?: ByteArray(0)
                    if (decrypted.size > blockOffset) {
                        overflow = decrypted
                        overflowOffset = blockOffset
                    }
                }
            }

            EducryptLogger.d("AesDataSource: O(1) seek to $position " +
                "(block=$blockIndex offset=$blockOffset)")
        }

        return dataSpec.length
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val raf = randomAccessFile ?: return -1
        val c   = cipher           ?: return -1

        var written = 0

        // 1. Drain overflow buffer first
        if (overflowOffset < overflow.size) {
            val available = overflow.size - overflowOffset
            val toCopy    = minOf(available, length)
            System.arraycopy(overflow, overflowOffset, target, offset, toCopy)
            overflowOffset += toCopy
            written        += toCopy
            if (overflowOffset >= overflow.size) {
                overflow       = ByteArray(0)
                overflowOffset = 0
            }
            if (written == length) return written
        }

        // 2. Read block-aligned chunks from file and decrypt
        // CRITICAL: rawToRead must be a multiple of BLOCK_SIZE.
        // cipher.update() with a non-multiple leaves partial state in the cipher,
        // misaligning every subsequent call and producing garbage output.
        val remaining  = length - written
        val rawToRead  = minOf(remaining, READ_BUFFER_SIZE)
        // Round up to nearest multiple of BLOCK_SIZE
        val alignedRead = ((rawToRead + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE

        val rawBuffer = ByteArray(alignedRead)
        val rawRead   = raf.read(rawBuffer, 0, alignedRead)
        if (rawRead <= 0) return if (written > 0) written else rawRead

        // Pad to block boundary if file ended mid-block
        val alignedRawRead = ((rawRead + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE
        val toDecrypt = if (alignedRawRead > rawRead) {
            rawBuffer.copyOf(alignedRawRead) // zero-padded to block boundary
        } else {
            rawBuffer.copyOf(rawRead)
        }

        val decrypted = c.update(toDecrypt) ?: ByteArray(0)
        val toCopy    = minOf(decrypted.size, remaining)
        System.arraycopy(decrypted, 0, target, offset + written, toCopy)
        written += toCopy

        // Buffer any surplus decrypted bytes for the next read() call
        if (decrypted.size > toCopy) {
            overflow       = decrypted
            overflowOffset = toCopy
        }

        return written
    }

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun getUri(): Uri = uri

    override fun close() {
        randomAccessFile?.close()
        randomAccessFile = null
        cipher         = null
        overflow       = ByteArray(0)
        overflowOffset = 0
        EducryptLogger.d("AesDataSource: closed")
    }
}
