package by.innowise.trainchecker

import android.content.Context

object TelegramPreferenceManager {
    private const val PREF_NAME = "telegram_prefs"
    private const val TOKEN_KEY = "telegram_token"
    private const val CHAT_ID_KEY = "chat_id"

    fun saveToken(context: Context, token: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(TOKEN_KEY, token).apply()
    }

    fun saveChatId(context: Context, chatId: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(CHAT_ID_KEY, chatId).apply()
    }

    fun getToken(context: Context): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(TOKEN_KEY, null)
    }

    fun getChatId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(CHAT_ID_KEY, null)
    }
}