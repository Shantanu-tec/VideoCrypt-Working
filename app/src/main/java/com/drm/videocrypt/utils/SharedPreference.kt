package com.drm.videocrypt.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.drm.videocrypt.BaseApp
import com.drm.videocrypt.models.ListItem
import com.google.gson.Gson

class SharedPreference {
    private var masterKey: MasterKey = MasterKey.Builder(BaseApp.appContext!!)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private var sharedPreference: SharedPreferences = EncryptedSharedPreferences.create(
        BaseApp.appContext!!,
        "secret_shared_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private val editor: SharedPreferences.Editor? = sharedPreference.edit()


    fun setDownloadData(response: ListItem?) {
        editor!!.putString("Downloadable_Data", Gson().toJson(response))
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
        const val MY_PREFERENCES = "MY_PREFERENCES"
        const val MODE = Context.MODE_PRIVATE
        private var pref: SharedPreference? = null

        @JvmStatic
        val instance: SharedPreference?
            get() {
                if (pref == null) {
                    pref = SharedPreference()
                }
                return pref
            }
    }
}