package com.appsquadz.educryptmedia.module

import android.content.Context
import com.appsquadz.educryptmedia.realm.entity.DownloadMeta
import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration
import io.realm.kotlin.migration.AutomaticSchemaMigration
import java.io.File

object RealmManager {
    private var realmInstance: Realm? = null

    fun init(context: Context) {
        if (realmInstance == null) {
            val config = RealmConfiguration.Builder(
                setOf(
                    DownloadMeta::class
                )
            ).schemaVersion(2)
                .migration(AutomaticSchemaMigration { context ->
                    // v1 → v2: totalBytes and downloadedBytes added to DownloadMeta.
                    // Realm Kotlin applies 0L defaults automatically for new non-nullable Long
                    // fields; explicit sets below ensure consistency for all existing records.
                    if (context.oldRealm.schemaVersion() < 2L) {
                        context.newRealm.query("DownloadMeta").find().forEach { obj ->
                            obj.set("totalBytes", 0L)
                            obj.set("downloadedBytes", 0L)
                        }
                    }
                })
                .directory(File(context.filesDir, "realm").absolutePath)
                .name("educrypt.realm").build()

            realmInstance = Realm.open(config)
        }
    }

    fun getRealm(): Realm {
        return realmInstance ?: throw IllegalStateException("RealmManager not initialized. Call init(context) first.")
    }

    fun close() {
        realmInstance?.close()
        realmInstance = null
    }
}
