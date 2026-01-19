package com.appsquadz.educryptmedia.module

import android.content.Context
import com.appsquadz.educryptmedia.realm.entity.DownloadMeta
import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration
import java.io.File

object RealmManager {
    private var realmInstance: Realm? = null

    fun init(context: Context) {
        if (realmInstance == null) {
            val config = RealmConfiguration.Builder(
                setOf(
                    DownloadMeta::class
                )
            ).schemaVersion(1)
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
