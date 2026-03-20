package com.appsquadz.educryptmedia.realm.dao

import com.appsquadz.educryptmedia.realm.entity.ChunkMeta

interface ChunkMetaDao {

    /** Inserts or replaces all chunks for a download (uses UpdatePolicy.ALL). */
    fun insertChunks(chunks: List<ChunkMeta>, callback: (Boolean) -> Unit)

    /** Returns frozen snapshots of all chunk records for [vdcId], ordered by chunkIndex. */
    fun getChunksForVdcId(vdcId: String): List<ChunkMeta>

    /** Marks the chunk [id] as completed and sets downloadedBytes to its full range size. */
    fun markChunkCompleted(id: String, callback: (Boolean) -> Unit)

    /** Updates the in-progress byte count for chunk [id] (called every 512 KB). */
    fun updateChunkProgress(id: String, downloadedBytes: Long, callback: (Boolean) -> Unit)

    /** Deletes all chunk records for [vdcId]. Call after successful or fully-failed download. */
    fun deleteChunksForVdcId(vdcId: String, callback: (Boolean) -> Unit)

    /** Returns the distinct set of vdcIds that have at least one ChunkMeta record. */
    fun getAllVdcIds(): List<String>
}
