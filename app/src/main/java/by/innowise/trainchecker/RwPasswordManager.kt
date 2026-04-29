package by.innowise.trainchecker

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object RwPasswordManager {
    private const val PREF_NAME = "rw_passwords_encrypted"
    private const val KEY_PREFIX = "route_password_"

    fun savePassword(context: Context, routeId: Long, password: String) {
        if (password.isBlank()) return

        prefs(context).edit()
            .putString(key(routeId), password)
            .apply()
    }

    fun getPassword(context: Context, routeId: Long): String? {
        return prefs(context).getString(key(routeId), null)
            ?.takeIf { it.isNotBlank() }
    }

    fun hasPassword(context: Context, routeId: Long): Boolean {
        return !getPassword(context, routeId).isNullOrBlank()
    }

    fun deletePassword(context: Context, routeId: Long) {
        prefs(context).edit()
            .remove(key(routeId))
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context.applicationContext,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun key(routeId: Long): String = "$KEY_PREFIX$routeId"
}
