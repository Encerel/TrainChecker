package by.innowise.trainchecker

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object PassengerProfilePasswordManager {
    private const val PREF_NAME = "passenger_profile_passwords_encrypted"
    private const val KEY_PREFIX = "profile_password_"

    fun savePassword(context: Context, profileName: String, password: String) {
        if (profileName.isBlank() || password.isBlank()) return

        prefs(context).edit()
            .putString(key(profileName), password)
            .apply()
    }

    fun getPassword(context: Context, profileName: String): String? {
        if (profileName.isBlank()) return null

        return prefs(context).getString(key(profileName), null)
            ?.takeIf { it.isNotBlank() }
    }

    fun hasPassword(context: Context, profileName: String): Boolean {
        return !getPassword(context, profileName).isNullOrBlank()
    }

    fun movePassword(context: Context, oldProfileName: String, newProfileName: String) {
        if (oldProfileName == newProfileName) return

        val password = getPassword(context, oldProfileName) ?: return
        savePassword(context, newProfileName, password)
        deletePassword(context, oldProfileName)
    }

    fun deletePassword(context: Context, profileName: String) {
        if (profileName.isBlank()) return

        prefs(context).edit()
            .remove(key(profileName))
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

    private fun key(profileName: String): String = "$KEY_PREFIX$profileName"
}
