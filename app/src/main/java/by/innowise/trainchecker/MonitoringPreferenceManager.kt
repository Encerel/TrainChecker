package by.innowise.trainchecker

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object MonitoringPreferenceManager {
    private const val PREF_NAME = "monitoring_prefs"
    private const val ROUTES_KEY = "monitoring_routes"
    private const val TOKEN_KEY = "default_telegram_token"
    private const val CHAT_ID_KEY = "default_chat_id"
    private val gson = Gson()

    fun saveRoutes(context: Context, routes: List<MonitoringRoute>) {
        val json = gson.toJson(routes)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(ROUTES_KEY, json)
            .apply()
    }

    fun getRoutes(context: Context): List<MonitoringRoute> {
        val json = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(ROUTES_KEY, null) ?: return emptyList()

        val type = object : TypeToken<List<MonitoringRoute>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    fun saveDefaultToken(context: Context, token: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(TOKEN_KEY, token)
            .apply()
    }

    fun getDefaultToken(context: Context): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(TOKEN_KEY, null)
    }

    fun saveDefaultChatId(context: Context, chatId: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(CHAT_ID_KEY, chatId)
            .apply()
    }

    fun getDefaultChatId(context: Context): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(CHAT_ID_KEY, null)
    }
}