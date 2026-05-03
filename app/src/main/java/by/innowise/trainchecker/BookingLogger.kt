package by.innowise.trainchecker

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class BookingLogger(
    context: Context,
    private val routeId: Long,
    private val webViewDebugLogsEnabled: Boolean = false
) {
    private val appContext = context.applicationContext
    private val repository = MonitoringLogRepository(appContext)
    private val webViewTechnicalLogRepository = WebViewTechnicalLogRepository(appContext)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        if (webViewDebugLogsEnabled) {
            scope.launch {
                webViewTechnicalLogRepository.deleteExpired()
            }
        }
    }

    fun log(
        message: String,
        level: MonitoringLogLevel = MonitoringLogLevel.INFO,
        important: Boolean = true
    ) {
        val timestamp = System.currentTimeMillis()
        val time = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME)
        val intent = Intent("LOG_UPDATE").apply {
            putExtra("route_id", routeId)
            putExtra("log_message", "$time: $message")
            putExtra("raw_message", message)
            putExtra("timestamp", timestamp)
            putExtra("level", level.name)
            putExtra("category", MonitoringLogCategory.AUTO_PURCHASE.name)
            putExtra("important", important)
        }

        scope.launch {
            if (important) {
                repository.insert(
                    routeId = routeId,
                    message = message,
                    timestamp = timestamp,
                    level = level,
                    category = MonitoringLogCategory.AUTO_PURCHASE,
                    important = true
                )
            }
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent)
        }
    }

    fun technical(
        message: String,
        state: String = "",
        action: String = ""
    ) {
        if (!webViewDebugLogsEnabled) return

        val timestamp = System.currentTimeMillis()
        scope.launch {
            webViewTechnicalLogRepository.insert(
                routeId = routeId,
                message = message,
                timestamp = timestamp,
                state = state,
                action = action
            )
        }
    }

    fun close() {
        scope.cancel()
    }
}
