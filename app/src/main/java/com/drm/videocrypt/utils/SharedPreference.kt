package com.drm.videocrypt.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.drm.videocrypt.BaseApp
import com.drm.videocrypt.models.ListItem
import com.google.gson.Gson
import java.io.File
import java.security.KeyStore

class SharedPreference {
    private var sharedPreference: SharedPreferences
    private val editor: SharedPreferences.Editor

    init {
        sharedPreference = createEncryptedPreferences()
        editor = sharedPreference.edit()
    }

    private fun createEncryptedPreferences(): SharedPreferences {
        val context = BaseApp.appContext!!
        val prefsName = PREFS_NAME

        return try {
            createEncryptedPrefsInternal(context, prefsName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encrypted prefs, resetting...", e)
            clearAllEncryptedPrefsData(context, prefsName)
            try {
                createEncryptedPrefsInternal(context, prefsName)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed again after reset, falling back to regular prefs", e2)
                // Ultimate fallback: use regular SharedPreferences
                context.getSharedPreferences(prefsName + "_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    private fun createEncryptedPrefsInternal(context: Context, prefsName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            prefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun clearAllEncryptedPrefsData(context: Context, prefsName: String) {
        val sharedPrefsDir = File(context.filesDir.parent, "shared_prefs")

        // 1. Delete the main encrypted prefs file
        deleteFileIfExists(File(sharedPrefsDir, "$prefsName.xml"))

        // 2. Delete the Tink keyset prefs file (EncryptedSharedPreferences stores keyset here)
        deleteFileIfExists(File(sharedPrefsDir, "__androidx_security_crypto_encrypted_prefs__.xml"))

        // 3. Also try deleting keyset file with the prefs name pattern
        deleteFileIfExists(File(sharedPrefsDir, "${prefsName}__androidx_security_crypto_encrypted_prefs__.xml"))

        // 4. Clear SharedPreferences through Android API (clears in-memory cache)
        try {
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("__androidx_security_crypto_encrypted_prefs__", Context.MODE_PRIVATE)
                .edit().clear().commit()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear prefs via API", e)
        }

        // 5. Remove the master key from Android Keystore
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            // Delete the default master key alias
            keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            // Also try the legacy alias pattern
            keyStore.deleteEntry("_androidx_security_master_key_")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete master key from keystore", e)
        }

        Log.i(TAG, "Cleared all encrypted preferences data")
    }

    private fun deleteFileIfExists(file: File) {
        try {
            if (file.exists()) {
                val deleted = file.delete()
                Log.d(TAG, "Deleted ${file.name}: $deleted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete file: ${file.name}", e)
        }
    }


    fun setDownloadData(response: ListItem?) {
        editor.putString("Downloadable_Data", Gson().toJson(response))
        editor.commit()
    }

    fun getDownloadData(): ListItem? {
        var response: ListItem? = null
        val responsestr = sharedPreference.getString("Downloadable_Data", null)
        if (responsestr != null && responsestr.trim { it <= ' ' }.isNotEmpty()) {
            response = Gson().fromJson(
                responsestr,
                ListItem::class.java
            )
        }
        return response
    }

    companion object {
        private const val TAG = "SharedPreference"
        private const val PREFS_NAME = "secret_shared_prefs"
        const val MY_PREFERENCES = "MY_PREFERENCES"
        const val MODE = Context.MODE_PRIVATE

        @Volatile
        private var pref: SharedPreference? = null

        @JvmStatic
        val instance: SharedPreference
            get() = pref ?: synchronized(this) {
                pref ?: SharedPreference().also { pref = it }
            }
    }
}