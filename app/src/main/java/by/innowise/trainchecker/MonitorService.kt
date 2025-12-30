package by.innowise.trainchecker

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random


class MonitorService : Service() {
    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_STOP_ALL = "ACTION_STOP_ALL"
        const val CHANNEL_ID = "MonitorServiceChannel"
        const val NOTIF_ID = 1

        fun getActiveRoutes(context: Context): List<Long> {
            val prefs = context.getSharedPreferences("monitor_service", Context.MODE_PRIVATE)
            return prefs.getStringSet("active_routes", emptySet())?.map { it.toLong() } ?: emptyList()
        }
    }

    private val activeMonitors = mutableMapOf<Long, Job>()
    private val client = OkHttpClient()
    private val gson = Gson()
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val routeId = intent.getLongExtra("route_id", -1)
                if (routeId != -1L) {
                    startMonitoring(routeId)
                }
            }
            ACTION_STOP -> {
                val routeId = intent.getLongExtra("route_id", -1)
                if (routeId == -1L) {
                    stopAllMonitoring()
                } else {
                    stopMonitoring(routeId)
                }
            }
            ACTION_STOP_ALL -> {
                stopAllMonitoring()
                stopSelf()
            }
        }
        return START_STICKY
    }



    private fun startMonitoring(routeId: Long) {

        if (activeMonitors.containsKey(routeId)) return

        val routes = MonitoringPreferenceManager.getRoutes(this)
        val route = routes.find { it.id == routeId } ?: return

        if (routes.none { it.id == routeId }) {
            stopMonitoring(routeId)
            return
        }

        val job = CoroutineScope(Dispatchers.IO).launch {
            var lastStatus = false
            var lastHeartbeatTime = System.currentTimeMillis()

            while (isActive) {
                try {
                    delay(1000L + Random.nextLong(4000L))

                    val request = Request.Builder()
                        .url(route.url)
                        .build()

                    val response = client.newCall(request).execute()
                    val html = response.body?.string() ?: ""

                    val doc = Jsoup.parse(html)
                    val forms = doc.select("form.js-sch-item-form")
                    val buttonCount = forms.count { form ->
                        form.selectFirst("a.btn.btn-index:contains(Выбрать места)") != null
                    }

                    if (buttonCount >= route.buttonThreshold) {
                        if (!lastStatus) {
                            val message = "✅ На маршруте ${route.name} найдено $buttonCount поездов со свободными местами!\nСсылка: ${route.url}"
                            sendTelegramMessage(route, message)
                            sendLog(routeId, "МЕСТА НАЙДЕНЫ. Количество кнопок: $buttonCount")
                        }
                        lastStatus = true
                    } else {
                        sendLog(routeId, "Места НЕ найдены. Количество кнопок: $buttonCount")
                        if (lastStatus) {
                            sendTelegramMessage(route, "❌ На маршруте ${route.name} места закончились")
                        }
                        lastStatus = false
                    }

                    // Healthcheck
                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeatTime >= route.healthIntervalMin * 60 * 1000) {
                        sendTelegramMessage(route, "🟢 Мониторинг маршрута ${route.name} активен")
                        lastHeartbeatTime = now
                    }
                } catch (e: Exception) {
                    val errorMessage = e.message ?: ""
                    sendLog(routeId, "Ошибка: $errorMessage")
                    
                    val shouldNotify = !errorMessage.contains("timeout", ignoreCase = true) &&
                                      !errorMessage.contains("StandaloneCoroutine", ignoreCase = true) &&
                                      !errorMessage.contains("SocketTimeoutException", ignoreCase = true)
                    
                    if (shouldNotify) {
                        sendTelegramMessage(route, "⚠ Ошибка при проверке маршрута ${route.name}: $errorMessage")
                    }
                }
                delay(route.checkIntervalSec * 1000)


            }
        }

        activeMonitors[routeId] = job
        updateNotification()
        sendLog(routeId, "Мониторинг запущен")
        updateRouteStatus(routeId, true)
    }

    private fun stopMonitoring(routeId: Long) {
        activeMonitors[routeId]?.cancel()
        activeMonitors.remove(routeId)
        updateRouteStatus(routeId, false)
        updateNotification()
        sendLog(routeId, "Мониторинг остановлен")
    }

    private fun stopAllMonitoring() {
        activeMonitors.values.forEach { it.cancel() }
        activeMonitors.clear()
        updateNotification()
        getSharedPreferences("monitor_service", Context.MODE_PRIVATE).edit()
            .remove("active_routes")
            .apply()
    }

    private fun sendTelegramMessage(route: MonitoringRoute, message: String) {
        try {
            val encodedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8.toString())
            val url = "https://api.telegram.org/bot${route.telegramToken}/sendMessage?chat_id=${route.chatId}&text=$encodedMessage"

            val request = Request.Builder().url(url).build()
            client.newCall(request).execute()
        } catch (e: Exception) {
            Log.e("MonitorService", "Ошибка отправки в Telegram", e)
        }
    }

    private fun sendLog(routeId: Long, message: String) {
        val time = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME)
        val logMessage = "$time: $message"

        val intent = Intent("LOG_UPDATE").apply {
            putExtra("route_id", routeId)
            putExtra("log_message", logMessage)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun updateRouteStatus(routeId: Long, isActive: Boolean) {
        // 1. Обновляем в SharedPreferences
        val routes = MonitoringPreferenceManager.getRoutes(this).toMutableList()
        routes.replaceAll {
            if (it.id == routeId) it.copy(isActive = isActive) else it
        }
        MonitoringPreferenceManager.saveRoutes(this, routes)

        val intent = Intent("ROUTE_STATUS_UPDATE").apply {
            putExtra("route_id", routeId)
            putExtra("is_active", isActive)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun updateNotification() {
        if (activeMonitors.isEmpty()) {
            stopForeground(true)
            return
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TrainChecker: ${activeMonitors.size} активных маршрутов")
            .setSmallIcon(R.drawable.ic_notification_clear_all)
            .setContentIntent(createNotificationIntent())
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notification)
        updateActiveRoutes()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Мониторинг маршрутов",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TrainChecker")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_menu_crop)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateActiveRoutes() {
        getSharedPreferences("monitor_service", Context.MODE_PRIVATE).edit()
            .putStringSet("active_routes", activeMonitors.keys.map { it.toString() }.toSet())
            .apply()
    }

    private fun createNotificationIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "OPEN_FROM_NOTIFICATION"
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }



    override fun onDestroy() {
        super.onDestroy()
        stopAllMonitoring()
    }
}