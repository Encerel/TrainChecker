package by.innowise.trainchecker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
        private const val CART_RESERVATION_TIMEOUT_MS = 20 * 60 * 1000L
        private const val CART_RESERVATION_GRACE_MS = 10 * 1000L
        private const val BOOKING_WEBVIEW_TIMEOUT_MS = 30 * 60 * 1000L

        fun getActiveRoutes(context: Context): List<Long> {
            val prefs = context.getSharedPreferences("monitor_service", Context.MODE_PRIVATE)
            return prefs.getStringSet("active_routes", emptySet())?.map { it.toLong() } ?: emptyList()
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val activeMonitors = mutableMapOf<Long, Job>()
    private val activeAutoPurchases = mutableMapOf<Long, Job>()
    private val autoPurchaseRenewals = mutableMapOf<Long, Job>()
    private val client = OkHttpClient()
    private val logRepository by lazy { MonitoringLogRepository(this) }
    private val passengerProfileRepository by lazy { PassengerProfileRepository(this) }
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val bookingResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BookingWebViewActivity.ACTION_BOOKING_RESULT) {
                handleBookingResult(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            bookingResultReceiver,
            IntentFilter(BookingWebViewActivity.ACTION_BOOKING_RESULT)
        )
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

        val job = serviceScope.launch {
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
                    // Ищем строки поездов по div.sch-table__row-wrap (содержит номер поезда и действие выбора мест)
                    val trainRows = doc.select("div.sch-table__row-wrap")
                    
                    // Находим конкретные поезда с доступными местами
                    // Если список trainNumbers не пуст, мы будем учитывать ТОЛЬКО эти поезда для подсчета
                    val matchingAvailableTrains = mutableListOf<String>()
                    
                    val availableRows = trainRows.filter { row ->
                        row.selectFirst("a.btn.btn-index:contains(Выбрать места)") != null ||
                        row.selectFirst("a.btn.btn-index:contains(ВЫБРАТЬ МЕСТА)") != null
                    }
                    
                    if (route.trainNumbers.isNotEmpty()) {
                        // Если указаны конкретные поезда - фильтруем
                        availableRows.forEach { row ->
                            val trainNumber = row.attr("data-train-number").trim().ifEmpty {
                                row.selectFirst("span.train-number")?.text()?.trim()
                            }
                            
                            if (!trainNumber.isNullOrEmpty()) {
                                val normalizedFound = trainNumber.replace("\\s+".toRegex(), "").uppercase()
                                val matchedTrain = route.trainNumbers.find { target ->
                                    normalizedFound == target.replace("\\s+".toRegex(), "").uppercase()
                                }
                                if (matchedTrain != null) {
                                    matchingAvailableTrains.add(trainNumber)
                                }
                            }
                        }
                    } else {
                        // Если поезда не указаны - считаем все доступные (старое поведение)
                        availableRows.forEach { row ->
                            val trainNumber = row.attr("data-train-number").trim().ifEmpty {
                                row.selectFirst("span.train-number")?.text()?.trim()
                            }
                            if (!trainNumber.isNullOrEmpty()) {
                                matchingAvailableTrains.add(trainNumber)
                            }
                        }
                    }

                    val availableTrainCount = matchingAvailableTrains.size
                    val shouldNotify = matchingAvailableTrains.isNotEmpty()

                    if (shouldNotify) {
                        if (!lastStatus) {
                            val trainsInfo = if (matchingAvailableTrains.isNotEmpty()) {
                                "\nПоезда: ${matchingAvailableTrains.joinToString(", ")}"
                            } else ""
                            
                            val message = "✅ На маршруте ${route.name} найдено $availableTrainCount поездов со свободными местами!$trainsInfo\nСсылка: ${route.url}"
                            sendTelegramMessage(route, message)
                            
                            val logMessage = "МЕСТА НАЙДЕНЫ. Подходящих поездов: $availableTrainCount. Поезда: ${matchingAvailableTrains.joinToString(", ")}"
                            sendLog(
                                routeId = routeId,
                                message = logMessage,
                                level = MonitoringLogLevel.SUCCESS,
                                category = MonitoringLogCategory.AVAILABILITY
                            )
                            
                            // Авторезерв если включен
                            if (route.autoPurchaseEnabled && route.trainNumber.isNotEmpty()) {
                                sendLog(
                                    routeId = routeId,
                                    message = "Запуск авторезерва для поезда ${route.trainNumber}...",
                                    category = MonitoringLogCategory.AUTO_PURCHASE
                                )
                                attemptAutoPurchaseWebView(route)
                            }
                        }
                        lastStatus = true
                    } else {
                        if (route.trainNumbers.isNotEmpty() && availableRows.isNotEmpty()) {
                            val foundTrains = availableRows.mapNotNull { row ->
                                row.attr("data-train-number").trim().ifEmpty {
                                    row.selectFirst("span.train-number")?.text()?.trim()
                                }
                            }.filter { it.isNotEmpty() }
                            val foundTrainsStr = if (foundTrains.isNotEmpty()) {
                                " Найдены места для: ${foundTrains.joinToString(", ")}"
                            } else {
                                ""
                            }
                            sendLog(
                                routeId = routeId,
                                message = "Места есть (${availableRows.size} шт), но не для поездов: ${route.trainNumbersFormatted}.$foundTrainsStr",
                                category = MonitoringLogCategory.AVAILABILITY,
                                important = false
                            )
                        } else {
                            sendLog(
                                routeId = routeId,
                                message = "Места НЕ найдены (подходящих: $availableTrainCount)",
                                category = MonitoringLogCategory.AVAILABILITY,
                                important = false
                            )
                        }
                        
                        if (lastStatus) {
                            sendTelegramMessage(route, "❌ На маршруте ${route.name} места закончились")
                            sendLog(
                                routeId = routeId,
                                message = "Места закончились после предыдущего обнаружения",
                                level = MonitoringLogLevel.WARNING,
                                category = MonitoringLogCategory.AVAILABILITY
                            )
                        }
                        lastStatus = false
                    }

                    // Healthcheck
                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeatTime >= route.healthIntervalMin * 60 * 1000) {
                        sendTelegramMessage(route, "🟢 Мониторинг маршрута ${route.name} активен")
                        sendLog(
                            routeId = routeId,
                            message = "Мониторинг маршрута ${route.name} активен",
                            category = MonitoringLogCategory.MONITORING,
                            important = false
                        )
                        lastHeartbeatTime = now
                    }
                } catch (e: Exception) {
                    val errorMessage = e.message ?: ""
                    sendLog(
                        routeId = routeId,
                        message = "Ошибка: $errorMessage",
                        level = MonitoringLogLevel.ERROR,
                        category = MonitoringLogCategory.SYSTEM
                    )
                }
                delay(route.checkIntervalSec * 1000)


            }
        }

        activeMonitors[routeId] = job
        updateNotification()
        sendLog(routeId, "Мониторинг запущен", category = MonitoringLogCategory.MONITORING)
        updateRouteStatus(routeId, true)
    }

    private fun stopMonitoring(routeId: Long) {
        activeMonitors[routeId]?.cancel()
        activeMonitors.remove(routeId)
        cancelAutoPurchaseJobs(routeId)
        updateRouteStatus(routeId, false)
        updateNotification()
        sendLog(routeId, "Мониторинг остановлен", category = MonitoringLogCategory.MONITORING)
    }

    private fun stopAllMonitoring() {
        activeMonitors.values.forEach { it.cancel() }
        activeMonitors.clear()
        activeAutoPurchases.values.forEach { it.cancel() }
        activeAutoPurchases.clear()
        autoPurchaseRenewals.values.forEach { it.cancel() }
        autoPurchaseRenewals.clear()
        updateNotification()
        getSharedPreferences("monitor_service", Context.MODE_PRIVATE).edit()
            .remove("active_routes")
            .apply()
    }

    private fun attemptAutoPurchaseWebView(route: MonitoringRoute, isRenewal: Boolean = false) {
        if (activeAutoPurchases[route.id]?.isActive == true) {
            sendLog(
                routeId = route.id,
                message = "Авторезерв уже выполняется, повторный запуск пропущен",
                category = MonitoringLogCategory.AUTO_PURCHASE
            )
            return
        }

        val job = serviceScope.launch {
            try {
                val currentRoute = getActiveAutoPurchaseRoute(route.id)
                if (currentRoute == null) {
                    sendLog(
                        routeId = route.id,
                        message = "Авторезерв отменен: маршрут остановлен или авторезерв выключен",
                        level = MonitoringLogLevel.WARNING,
                        category = MonitoringLogCategory.AUTO_PURCHASE
                    )
                    return@launch
                }

                val startMessage = if (isRenewal) {
                    "Повторный авторезерв: запуск WebView после окончания брони..."
                } else {
                    "Авторезерв: запуск WebView-сценария..."
                }
                sendLog(
                    routeId = route.id,
                    message = startMessage,
                    category = MonitoringLogCategory.AUTO_PURCHASE
                )

                val resolvedAutoPurchase = resolveAutoPurchaseRoute(currentRoute) ?: return@launch
                BookingCoordinator.start(
                    context = this@MonitorService,
                    route = resolvedAutoPurchase.route,
                    rwPassword = resolvedAutoPurchase.password,
                    isRenewal = isRenewal
                )
                sendLog(
                    routeId = route.id,
                    message = "WebView-сценарий бронирования запущен. Ожидаю результат от Activity.",
                    category = MonitoringLogCategory.AUTO_PURCHASE
                )

                delay(BOOKING_WEBVIEW_TIMEOUT_MS)
                sendLog(
                    routeId = route.id,
                    message = "WebView-сценарий не прислал результат за 30 минут",
                    level = MonitoringLogLevel.ERROR,
                    category = MonitoringLogCategory.AUTO_PURCHASE
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("MonitorService", "WebView auto purchase error", e)
                sendLog(
                    routeId = route.id,
                    message = "Авторезерв WebView ИСКЛЮЧЕНИЕ: ${e.message}",
                    level = MonitoringLogLevel.ERROR,
                    category = MonitoringLogCategory.AUTO_PURCHASE
                )
            } finally {
                if (activeAutoPurchases[route.id] == this.coroutineContext[Job]) {
                    activeAutoPurchases.remove(route.id)
                }
            }
        }
        activeAutoPurchases[route.id] = job
    }

    private fun attemptAutoPurchase(route: MonitoringRoute, isRenewal: Boolean = false) {
        if (activeAutoPurchases[route.id]?.isActive == true) {
            sendLog(
                routeId = route.id,
                message = "Авторезерв уже выполняется, повторный запуск пропущен",
                category = MonitoringLogCategory.AUTO_PURCHASE
            )
            return
        }

        val job = serviceScope.launch {
            try {
                val currentRoute = getActiveAutoPurchaseRoute(route.id)
                if (currentRoute == null) {
                    sendLog(
                        routeId = route.id,
                        message = "Авторезерв отменен: маршрут остановлен или авторезерв выключен",
                        level = MonitoringLogLevel.WARNING,
                        category = MonitoringLogCategory.AUTO_PURCHASE
                    )
                    return@launch
                }

                val startMessage = if (isRenewal) {
                    "Повторный авторезерв: начало процесса после окончания брони..."
                } else {
                    "Авторезерв: начало процесса..."
                }
                sendLog(
                    routeId = route.id,
                    message = startMessage,
                    category = MonitoringLogCategory.AUTO_PURCHASE
                )
                
                val resolvedAutoPurchase = resolveAutoPurchaseRoute(currentRoute) ?: return@launch
                val automation = TicketPurchaseAutomation(
                    resolvedAutoPurchase.route,
                    resolvedAutoPurchase.password
                )
                val result = automation.attemptPurchase()
                
                when (result) {
                    is TicketPurchaseAutomation.PurchaseResult.Success -> {
                        sendLog(
                            routeId = route.id,
                            message = "Авторезерв УСПЕШЕН: ${result.message}",
                            level = MonitoringLogLevel.SUCCESS,
                            category = MonitoringLogCategory.AUTO_PURCHASE
                        )
                        sendTelegramMessage(
                            resolvedAutoPurchase.route,
                            "🎉 Заказ по маршруту ${resolvedAutoPurchase.route.name} успешно зарезервирован.\n" +
                                "${result.message}\n" +
                                "Проверьте корзину заказов на pass.rw.by.\n" +
                                "Если вы оплатили билет, остановите мониторинг маршрута. Если не успеете оплатить за 20 минут, приложение попробует зарезервировать билет снова."
                        )
                        scheduleAutoPurchaseRenewal(resolvedAutoPurchase.route)
                    }
                    is TicketPurchaseAutomation.PurchaseResult.Error -> {
                        sendLog(
                            routeId = route.id,
                            message = "Авторезерв ОШИБКА на шаге ${result.step}: ${result.message}",
                            level = MonitoringLogLevel.ERROR,
                            category = MonitoringLogCategory.AUTO_PURCHASE
                        )
                    }
                    is TicketPurchaseAutomation.PurchaseResult.NeedsLogin -> {
                        sendLog(
                            routeId = route.id,
                            message = "Авторезерв: требуется авторизация, повторная попытка...",
                            level = MonitoringLogLevel.WARNING,
                            category = MonitoringLogCategory.AUTO_PURCHASE
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("MonitorService", "Auto purchase error", e)
                sendLog(
                    routeId = route.id,
                    message = "Авторезерв ИСКЛЮЧЕНИЕ: ${e.message}",
                    level = MonitoringLogLevel.ERROR,
                    category = MonitoringLogCategory.AUTO_PURCHASE
                )
            } finally {
                if (activeAutoPurchases[route.id] == this.coroutineContext[Job]) {
                    activeAutoPurchases.remove(route.id)
                }
            }
        }
        activeAutoPurchases[route.id] = job
    }

    private suspend fun resolveAutoPurchaseRoute(route: MonitoringRoute): ResolvedAutoPurchase? {
        if (route.passengerProfileName.isNotBlank()) {
            val profile = passengerProfileRepository.getByName(route.passengerProfileName)
            if (profile == null) {
                sendLog(
                    routeId = route.id,
                    message = "Авторезерв ОШИБКА: профиль \"${route.passengerProfileName}\" не найден",
                    level = MonitoringLogLevel.ERROR,
                    category = MonitoringLogCategory.AUTO_PURCHASE
                )
                return null
            }

            val password = PassengerProfilePasswordManager.getPassword(this, profile.name)
            if (password.isNullOrBlank()) {
                sendLog(
                    routeId = route.id,
                    message = "Авторезерв ОШИБКА: пароль pass.rw.by не найден в профиле \"${profile.name}\"",
                    level = MonitoringLogLevel.ERROR,
                    category = MonitoringLogCategory.AUTO_PURCHASE
                )
                return null
            }

            return ResolvedAutoPurchase(
                route = route.copy(
                    chatId = profile.chatId,
                    rwLogin = profile.rwLogin,
                    hasSavedRwPassword = true,
                    rwPassword = "",
                    passengerLastName = profile.lastName,
                    passengerFirstName = profile.firstName,
                    passengerMiddleName = profile.middleName,
                    passengerDocumentNumber = profile.documentNumber
                ),
                password = password
            )
        }

        val password = RwPasswordManager.getPassword(this, route.id)
        if (password.isNullOrBlank()) {
            sendLog(
                routeId = route.id,
                message = "Авторезерв ОШИБКА: пароль pass.rw.by не найден в защищенном хранилище",
                level = MonitoringLogLevel.ERROR,
                category = MonitoringLogCategory.AUTO_PURCHASE
            )
            return null
        }

        return ResolvedAutoPurchase(route, password)
    }

    private data class ResolvedAutoPurchase(
        val route: MonitoringRoute,
        val password: String
    )

    private fun scheduleAutoPurchaseRenewal(route: MonitoringRoute) {
        autoPurchaseRenewals[route.id]?.cancel()

        val delayMs = CART_RESERVATION_TIMEOUT_MS + CART_RESERVATION_GRACE_MS
        val job = serviceScope.launch {
            try {
                sendLog(
                    routeId = route.id,
                    message = "Повторный авторезерв запланирован через 20 мин 10 сек",
                    category = MonitoringLogCategory.AUTO_PURCHASE
                )
                delay(delayMs)

                val currentRoute = getActiveAutoPurchaseRoute(route.id)
                if (currentRoute == null) {
                    sendLog(
                        routeId = route.id,
                        message = "Повторный авторезерв отменен: маршрут остановлен или авторезерв выключен",
                        level = MonitoringLogLevel.WARNING,
                        category = MonitoringLogCategory.AUTO_PURCHASE
                    )
                    return@launch
                }

                sendLog(
                    routeId = route.id,
                    message = "Срок брони истек. Пробую зарезервировать билет снова...",
                    category = MonitoringLogCategory.AUTO_PURCHASE
                )
                attemptAutoPurchaseWebView(currentRoute, isRenewal = true)
            } finally {
                if (autoPurchaseRenewals[route.id] == this.coroutineContext[Job]) {
                    autoPurchaseRenewals.remove(route.id)
                }
            }
        }
        autoPurchaseRenewals[route.id] = job
    }

    private fun getActiveAutoPurchaseRoute(routeId: Long): MonitoringRoute? {
        return MonitoringPreferenceManager.getRoutes(this)
            .find { route ->
                route.id == routeId &&
                    route.isActive &&
                    route.autoPurchaseEnabled &&
                    route.trainNumber.isNotEmpty()
            }
    }

    private fun handleBookingResult(intent: Intent) {
        val routeId = intent.getLongExtra(BookingWebViewActivity.EXTRA_ROUTE_ID, -1L)
        if (routeId == -1L) return

        val success = intent.getBooleanExtra(BookingWebViewActivity.EXTRA_SUCCESS, false)
        val message = intent.getStringExtra(BookingWebViewActivity.EXTRA_MESSAGE).orEmpty()
        val state = intent.getStringExtra(BookingWebViewActivity.EXTRA_STATE).orEmpty()

        activeAutoPurchases[routeId]?.cancel()
        activeAutoPurchases.remove(routeId)

        if (success) {
            sendLog(
                routeId = routeId,
                message = "Авторезерв WebView УСПЕШЕН: $message",
                level = MonitoringLogLevel.SUCCESS,
                category = MonitoringLogCategory.AUTO_PURCHASE
            )
            getActiveAutoPurchaseRoute(routeId)?.let { currentRoute ->
                scheduleAutoPurchaseRenewal(currentRoute)
            }
        } else {
            sendLog(
                routeId = routeId,
                message = "Авторезерв WebView ОШИБКА на шаге $state: $message",
                level = MonitoringLogLevel.ERROR,
                category = MonitoringLogCategory.AUTO_PURCHASE
            )
        }
    }

    private fun cancelAutoPurchaseJobs(routeId: Long) {
        activeAutoPurchases[routeId]?.cancel()
        activeAutoPurchases.remove(routeId)
        autoPurchaseRenewals[routeId]?.cancel()
        autoPurchaseRenewals.remove(routeId)
    }

    private fun sendTelegramMessage(route: MonitoringRoute, message: String) {
        try {
            val encodedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8.toString())
            val url = "https://api.telegram.org/bot${route.telegramToken}/sendMessage?chat_id=${route.chatId}&text=$encodedMessage"

            val request = Request.Builder().url(url).build()
            client.newCall(request).execute()
        } catch (e: Exception) {
            Log.e("MonitorService", "Ошибка отправки в Telegram", e)
            sendLog(
                routeId = route.id,
                message = "Ошибка отправки в Telegram: ${e.message}",
                level = MonitoringLogLevel.ERROR,
                category = MonitoringLogCategory.TELEGRAM
            )
        }
    }

    private fun sendLog(
        routeId: Long,
        message: String,
        level: MonitoringLogLevel = MonitoringLogLevel.INFO,
        category: MonitoringLogCategory = MonitoringLogCategory.MONITORING,
        important: Boolean = true
    ) {
        val timestamp = System.currentTimeMillis()
        val time = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME)
        val logMessage = "$time: $message"

        val intent = Intent("LOG_UPDATE").apply {
            putExtra("route_id", routeId)
            putExtra("log_message", logMessage)
            putExtra("raw_message", message)
            putExtra("timestamp", timestamp)
            putExtra("level", level.name)
            putExtra("category", category.name)
            putExtra("important", important)
        }

        if (important) {
            serviceScope.launch {
                logRepository.insert(
                    routeId = routeId,
                    message = message,
                    timestamp = timestamp,
                    level = level,
                    category = category,
                    important = true
                )
                LocalBroadcastManager.getInstance(this@MonitorService).sendBroadcast(intent)
            }
        } else {
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
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
            .setSmallIcon(R.drawable.ic_stat_train)
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
            .setSmallIcon(R.drawable.ic_stat_train)
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
        LocalBroadcastManager.getInstance(this).unregisterReceiver(bookingResultReceiver)
        stopAllMonitoring()
        serviceJob.cancel()
    }
}
