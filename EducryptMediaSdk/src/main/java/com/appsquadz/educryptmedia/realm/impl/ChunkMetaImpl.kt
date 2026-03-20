package com.appsquadz.educryptmedia.realm.impl

import com.appsquadz.educryptmedia.realm.dao.ChunkMetaDao
import com.appsquadz.educryptmedia.realm.entity.ChunkMeta
import com.appsquadz.educryptmedia.util.EducryptLogger
import io.realm.kotlin.Realm
import io.realm.kotlin.UpdatePolicy
import io.realm.kotlin.ext.query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ChunkMetaImpl(private val realm: Realm) : ChunkMetaDao {

    override fun insertChunks(chunks: List<ChunkMeta>, callback: (Boolean) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                realm.write {
                    chunks.forEach { copyToRealm(it, UpdatePolicy.ALL) }
                }
                callback(true)
            } catch (e: Exception) {
                EducryptLogger.e("insertChunks failed", e)
                callback(false)
            }
        }
    }

    override fun getChunksForVdcId(vdcId: String): List<ChunkMeta> {
        return try {
            realm.query<ChunkMeta>("vdcId == $0", vdcId)
                .find()
                .sortedBy { it.chunkIndex }
        } catch (e: Exception) {
            EducryptLogger.e("getChunksForVdcId failed for $vdcId", e)
            emptyList()
        }
    }

    override fun markChunkCompleted(id: String, callback: (Boolean) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                realm.write {
                    val chunk = query<ChunkMeta>("id == $0", id).find().firstOrNull()
                    if (chunk != null) {
                        chunk.completed = true
                        chunk.downloadedBytes = chunk.endByte - chunk.startByte + 1
                        callback(true)
                    } else {
                        callback(false)
                    }
                }
            } catch (e: Exception) {
                EducryptLogger.e("markChunkCompleted failed for $id", e)
                callback(false)
            }
        }
    }

    override fun updateChunkProgress(id: String, downloadedBytes: Long, callback: (Boolean) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                realm.write {
                    val chunk = query<ChunkMeta>("id == $0", id).find().firstOrNull()
                    if (chunk != null) {
                        chunk.downloadedBytes = downloadedBytes
                        callback(true)
                    } else {
                        callback(false)
                    }
                }
            } catch (e: Exception) {
                EducryptLogger.e("updateChunkProgress failed for $id", e)
                callback(false)
            }
        }
    }

    override fun deleteChunksForVdcId(vdcId: String, callback: (Boolean) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                realm.write {
                    val chunks = query<ChunkMeta>("vdcId == $0", vdcId).find()
                    delete(chunks)
                }
                callback(true)
            } catch (e: Exception) {
                EducryptLogger.e("deleteChunksForVdcId failed for $vdcId", e)
                callback(false)
            }
        }
    }

    override fun getAllVdcIds(): List<String> {
        return try {
            realm.query<ChunkMeta>().find()
                .map { it.vdcId }
                .distinct()
        } catch (e: Exception) {
            EducryptLogger.e("getAllVdcIds failed", e)
            emptyList()
        }
    }
}
