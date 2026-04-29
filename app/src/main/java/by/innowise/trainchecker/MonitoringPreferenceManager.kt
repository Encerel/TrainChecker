package by.innowise.trainchecker

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object MonitoringPreferenceManager {
    private const val PREF_NAME = "monitoring_prefs"
    private const val ROUTES_KEY = "monitoring_routes"
    private const val TOKEN_KEY = "default_telegram_token"
    private const val CHAT_ID_KEY = "default_chat_id"
    private const val CHAT_ID_HISTORY_KEY = "chat_id_history"
    private val gson = Gson()

    fun saveRoutes(context: Context, routes: List<MonitoringRoute>) {
        val json = gson.toJson(sanitizeRoutesForSave(context, routes))
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(ROUTES_KEY, json)
            .apply()
    }

    fun getRoutes(context: Context): List<MonitoringRoute> {
        val json = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(ROUTES_KEY, null) ?: return emptyList()

        val type = object : TypeToken<List<MonitoringRoute>>() {}.type
        val routes = gson.fromJson<List<MonitoringRoute>>(json, type) ?: emptyList()
        val sanitizedRoutes = sanitizeRoutesForSave(context, routes)
        if (sanitizedRoutes != routes) {
            saveRoutes(context, sanitizedRoutes)
        }
        return sanitizedRoutes
    }

    private fun sanitizeRoutesForSave(
        context: Context,
        routes: List<MonitoringRoute>
    ): List<MonitoringRoute> {
        return routes.map { route ->
            var sanitizedRoute = route
            val legacyPassword = route.legacyRwPassword

            if (legacyPassword.isNotBlank()) {
                RwPasswordManager.savePassword(context, route.id, legacyPassword)
                sanitizedRoute = sanitizedRoute.copy(rwPassword = "")
            }

            val hasEncryptedPassword = RwPasswordManager.hasPassword(context, route.id)
            if (sanitizedRoute.hasSavedRwPassword != hasEncryptedPassword) {
                sanitizedRoute = sanitizedRoute.copy(
                    hasSavedRwPassword = hasEncryptedPassword
                )
            }

            sanitizedRoute
        }
    }

    @Suppress("DEPRECATION")
    private val MonitoringRoute.legacyRwPassword: String
        get() = rwPassword

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

    fun saveChatIdToHistory(context: Context, chatId: String) {
        if (chatId.isBlank()) return
        
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val existingHistory = prefs.getStringSet(CHAT_ID_HISTORY_KEY, emptySet()) ?: emptySet()
        val updatedHistory = existingHistory.toMutableSet().apply { add(chatId) }
        
        prefs.edit()
            .putStringSet(CHAT_ID_HISTORY_KEY, updatedHistory)
            .apply()
    }

    fun getChatIdHistory(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val history = prefs.getStringSet(CHAT_ID_HISTORY_KEY, emptySet()) ?: emptySet()
        return history.toList().sorted()
    }

    fun deleteChatIdFromHistory(context: Context, chatId: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val existingHistory = prefs.getStringSet(CHAT_ID_HISTORY_KEY, emptySet()) ?: emptySet()
        val updatedHistory = existingHistory.toMutableSet().apply { remove(chatId) }
        
        prefs.edit()
            .putStringSet(CHAT_ID_HISTORY_KEY, updatedHistory)
            .apply()
    }

    private const val PASSENGER_DATA_KEY = "default_passenger_data"
    private const val RW_LOGIN_KEY = "default_rw_login"

    fun saveDefaultPassengerData(context: Context, data: AutoPurchaseData) {
        val json = gson.toJson(data.copy(rwPassword = ""))
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PASSENGER_DATA_KEY, json)
            .apply()
    }

    fun getDefaultPassengerData(context: Context): AutoPurchaseData? {
        val json = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(PASSENGER_DATA_KEY, null) ?: return null
        return try {
            gson.fromJson(json, AutoPurchaseData::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun saveDefaultRwLogin(context: Context, login: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(RW_LOGIN_KEY, login)
            .apply()
    }

    fun getDefaultRwLogin(context: Context): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(RW_LOGIN_KEY, null)
    }
}
