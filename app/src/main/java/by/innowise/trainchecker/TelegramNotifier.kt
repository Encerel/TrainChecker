package by.innowise.trainchecker

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object TelegramNotifier {
    private const val TAG = "TelegramNotifier"
    private val client = OkHttpClient()

    suspend fun send(token: String, chatId: String, message: String): Boolean = withContext(Dispatchers.IO) {
        if (token.isBlank() || chatId.isBlank() || message.isBlank()) return@withContext false

        return@withContext try {
            val encodedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8.toString())
            val url = "https://api.telegram.org/bot$token/sendMessage?chat_id=$chatId&text=$encodedMessage"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Telegram send failed", e)
            false
        }
    }
}
