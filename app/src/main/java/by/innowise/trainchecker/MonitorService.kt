package by.innowise.trainchecker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

class MonitorService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val CHANNEL_ID = "MonitorServiceChannel"
        const val NOTIF_ID = 1

        private const val TELEGRAM_BOT_TOKEN = "8174644064:AAHxzUnEWjyb0NpMSMMNHPOUQ7TaPPTgB_0"
        private const val CHAT_ID = "587148605"
        private const val MONITORING_URL = "https://pass.rw.by/ru/route/?from=Пинск&from_exp=2100180&from_esr=133202&to=Минск&to_exp=2100000&to_esr=140210&date=2025-06-15&type=1"
        private val USER_AGENTS = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)",
            "Mozilla/5.0 (Linux; Android 10; SM-G980F)"
        )
    }

    private val client = OkHttpClient()
    private var monitoringJob: Job? = null
    private var lastStatus = false
    private val logDateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("TrainChecker активен"))

        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("Мониторинг запущен"))

        if (monitoringJob?.isActive == true) return

        sendLogToActivity("🚂 Мониторинг мест на поезда запущен! Отслеживаю URL: $MONITORING_URL")
        monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    // Случайная задержка 1-5 сек для обхода блокировок
                    delay(1000L + Random.nextLong(4000L))

                    val userAgent = USER_AGENTS.random()
                    val request = Request.Builder()
                        .url(MONITORING_URL)
                        .header("User-Agent", userAgent)
                        .build()

                    val response = client.newCall(request).execute()
                    val html = response.body?.string() ?: ""

                    val doc = Jsoup.parse(html)
                    val forms = doc.select("form.js-sch-item-form")
                    var buttonCount = 0

                    for (form in forms) {
                        val button = form.selectFirst("a.btn.btn-index:contains(Выбрать места)")
                        if (button != null) buttonCount++
                    }

                    if (buttonCount >= 2) {
                        if (!lastStatus) {
                            val message = "✅ Найдено $buttonCount поезда со свободными местами!\nСсылка для бронирования: $MONITORING_URL"
                            sendTelegramMessage(message)
                            sendLogToActivity("МЕСТА НАЙДЕНЫ. Количество кнопок: $buttonCount")
                        }
                        lastStatus = true
                    } else {
                        sendLogToActivity("Места НЕ найдены. Количество кнопок: $buttonCount")
                        Log.d("MonitorService", "Запрос отправлен в ${LocalDateTime.now()}")
                        if (lastStatus) {
                            sendTelegramMessage("❌ Места закончились")
                        }
                        lastStatus = false
                    }
                } catch (e: Exception) {
                    sendTelegramMessage("⚠ Ошибка при проверке мест: ${e.message}")
                    sendLogToActivity("Ошибка: ${e.message}")
                }
                delay(15_000L) // 15 секунд между проверками
            }
        }
    }

    private fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        stopForeground(true)
        stopSelf()
        sendLogToActivity("Мониторинг остановлен пользователем")
    }

    private fun sendTelegramMessage(text: String) {
        try {
            val url = "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage" +
                    "?chat_id=$CHAT_ID&text=${java.net.URLEncoder.encode(text, "UTF-8")}"

            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().close()
        } catch (e: Exception) {
            sendLogToActivity("Ошибка отправки в Telegram: ${e.message}")
        }
    }

    private fun sendLogToActivity(message: String) {
        val intent = Intent("LOG_UPDATE")
        intent.putExtra("log_message", message)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Мониторинг мест",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        // Intent для открытия Activity при тапе на уведомление
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TrainChecker")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)  // Можно заменить на свой ресурс
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

}
