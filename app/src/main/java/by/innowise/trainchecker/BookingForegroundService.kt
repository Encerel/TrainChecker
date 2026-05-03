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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BookingForegroundService : Service() {
    private class BookingSession(
        val request: BookingRequest,
        val logger: BookingLogger,
        val wakeLock: PowerManager.WakeLock?,
        val wakeLockAcquiredAt: Long
    ) {
        var timeoutJob: Job? = null
        var lastState: String = BookingState.LOAD_ROUTE.name
        var lastMessage: String = "Starting"
        var lastUrl: String = request.routeUrl
        var verificationNotified: Boolean = false
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private val sessions = mutableMapOf<Long, BookingSession>()
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BookingWebViewActivity.ACTION_BOOKING_STATUS) {
                handleBookingStatus(intent)
            }
        }
    }

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BookingWebViewActivity.ACTION_BOOKING_RESULT) {
                handleBookingResult(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            statusReceiver,
            IntentFilter(BookingWebViewActivity.ACTION_BOOKING_STATUS)
        )
        LocalBroadcastManager.getInstance(this).registerReceiver(
            resultReceiver,
            IntentFilter(BookingWebViewActivity.ACTION_BOOKING_RESULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val request = BookingRequest.from(intent)
                if (request == null) {
                    stopSelf(startId)
                } else {
                    startBooking(request)
                }
            }
            ACTION_CANCEL -> {
                val routeId = intent.getLongExtra(EXTRA_ROUTE_ID, -1L)
                val reason = intent.getStringExtra(EXTRA_CANCEL_REASON)
                    ?: "Booking cancelled"
                cancelBooking(routeId, reason)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startBooking(request: BookingRequest) {
        if (sessions.containsKey(request.routeId)) {
            sessions[request.routeId]?.logger?.log(
                "Foreground booking service already runs for this route",
                MonitoringLogLevel.WARNING
            )
            updateNotification(request.routeId)
            return
        }

        val logger = BookingLogger(this, request.routeId)
        val wakeLock = acquireWakeLock(request, logger)
        val session = BookingSession(
            request = request,
            logger = logger,
            wakeLock = wakeLock,
            wakeLockAcquiredAt = if (wakeLock?.isHeld == true) System.currentTimeMillis() else 0L
        )
        sessions[request.routeId] = session

        logger.log(
            "Foreground booking service started. dryRun=${request.dryRun} route=${request.routeName}"
        )
        sendTelegram(
            request,
            "TrainChecker: booking flow started for ${request.routeName.ifBlank { request.primaryTrainNumber }}." +
                if (request.dryRun) "\nDry-run mode is enabled." else ""
        )

        val notification = buildNotification(session)
        try {
            startForeground(notificationId(request.routeId), notification)
        } catch (e: Exception) {
            logger.log(
                "Unable to start foreground notification: ${e.message}",
                MonitoringLogLevel.ERROR
            )
            Log.e(TAG, "Unable to start foreground notification", e)
        }

        launchActivity(session)
        scheduleTimeout(session)
    }

    private fun launchActivity(session: BookingSession) {
        val activityIntent = BookingWebViewActivity.createIntent(this, session.request).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        try {
            startActivity(activityIntent)
            session.logger.log("Booking WebView activity launch requested")
        } catch (e: Exception) {
            session.logger.log(
                "Background activity launch was blocked or failed: ${e.message}. User can open it from notification.",
                MonitoringLogLevel.WARNING
            )
            Log.w(TAG, "Activity launch failed", e)
        }

        notificationManager.notify(notificationId(session.request.routeId), buildNotification(session))
    }

    private fun scheduleTimeout(session: BookingSession) {
        session.timeoutJob = serviceScope.launch {
            delay(BOOKING_TIMEOUT_MS)
            val activeSession = sessions[session.request.routeId] ?: return@launch
            activeSession.logger.log(
                "Foreground booking service timeout after ${BOOKING_TIMEOUT_MS / 60_000L} minutes",
                MonitoringLogLevel.ERROR
            )
            cancelActivity(activeSession.request.routeId, "Booking timeout")
            broadcastServiceResult(
                activeSession.request,
                success = false,
                message = "Booking timeout after ${BOOKING_TIMEOUT_MS / 60_000L} minutes",
                state = BookingState.ERROR.name
            )
            sendTelegram(
                activeSession.request,
                "TrainChecker: booking failed for ${activeSession.request.routeName}.\nBooking timeout."
            )
            finishSession(activeSession.request.routeId)
        }
    }

    private fun handleBookingStatus(intent: Intent) {
        val routeId = intent.getLongExtra(BookingWebViewActivity.EXTRA_ROUTE_ID, -1L)
        val session = sessions[routeId] ?: return

        session.lastState = intent.getStringExtra(BookingWebViewActivity.EXTRA_STATE)
            ?: session.lastState
        session.lastMessage = intent.getStringExtra(BookingWebViewActivity.EXTRA_MESSAGE)
            ?: session.lastMessage
        session.lastUrl = intent.getStringExtra(BookingWebViewActivity.EXTRA_URL)
            ?: session.lastUrl

        if (!session.verificationNotified &&
            session.lastMessage.contains("verification", ignoreCase = true)
        ) {
            session.verificationNotified = true
            sendTelegram(
                session.request,
                "TrainChecker: Verification page detected for ${session.request.routeName}. WebView is waiting."
            )
        }

        notificationManager.notify(notificationId(routeId), buildNotification(session))
    }

    private fun handleBookingResult(intent: Intent) {
        val routeId = intent.getLongExtra(BookingWebViewActivity.EXTRA_ROUTE_ID, -1L)
        val session = sessions[routeId] ?: return

        val success = intent.getBooleanExtra(BookingWebViewActivity.EXTRA_SUCCESS, false)
        val message = intent.getStringExtra(BookingWebViewActivity.EXTRA_MESSAGE).orEmpty()
        val state = intent.getStringExtra(BookingWebViewActivity.EXTRA_STATE).orEmpty()
        val level = if (success) MonitoringLogLevel.SUCCESS else MonitoringLogLevel.ERROR

        session.logger.log(
            "Foreground booking service received result. success=$success state=$state message=$message",
            level
        )
        sendTelegram(session.request, buildResultTelegramMessage(session.request, success, message, state))
        finishSession(routeId)
    }

    private fun cancelBooking(routeId: Long, reason: String) {
        val session = sessions[routeId]
        if (session == null) {
            if (sessions.isEmpty()) stopSelf()
            return
        }

        session.logger.log(reason, MonitoringLogLevel.WARNING)
        cancelActivity(routeId, reason)
        broadcastServiceResult(
            request = session.request,
            success = false,
            message = reason,
            state = BookingState.ERROR.name
        )
        sendTelegram(
            session.request,
            "TrainChecker: booking cancelled for ${session.request.routeName}.\n$reason"
        )
        finishSession(routeId)
    }

    private fun cancelActivity(routeId: Long, reason: String) {
        val intent = Intent(BookingWebViewActivity.ACTION_CANCEL_BOOKING).apply {
            putExtra(BookingWebViewActivity.EXTRA_ROUTE_ID, routeId)
            putExtra(BookingWebViewActivity.EXTRA_MESSAGE, reason)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastServiceResult(
        request: BookingRequest,
        success: Boolean,
        message: String,
        state: String
    ) {
        val intent = Intent(BookingWebViewActivity.ACTION_BOOKING_RESULT).apply {
            putExtra(BookingWebViewActivity.EXTRA_ROUTE_ID, request.routeId)
            putExtra(BookingWebViewActivity.EXTRA_SUCCESS, success)
            putExtra(BookingWebViewActivity.EXTRA_MESSAGE, message)
            putExtra(BookingWebViewActivity.EXTRA_STATE, state)
            putExtra(BookingWebViewActivity.EXTRA_IS_RENEWAL, request.isRenewal)
            putExtra(BookingWebViewActivity.EXTRA_DRY_RUN, request.dryRun)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun finishSession(routeId: Long) {
        val session = sessions.remove(routeId) ?: return
        session.timeoutJob?.cancel()
        releaseWakeLock(session)
        notificationManager.cancel(notificationId(routeId))
        serviceScope.launch {
            delay(500L)
            session.logger.close()
        }

        if (sessions.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            sessions.values.firstOrNull()?.let {
                startForeground(notificationId(it.request.routeId), buildNotification(it))
            }
        }
    }

    private fun acquireWakeLock(
        request: BookingRequest,
        logger: BookingLogger
    ): PowerManager.WakeLock? {
        return try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:BookingForeground:${request.routeId}"
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
                logger.log("WakeLock acquired for foreground booking")
            }
        } catch (e: Exception) {
            logger.log("WakeLock was not acquired: ${e.message}", MonitoringLogLevel.WARNING)
            Log.w(TAG, "WakeLock acquire failed", e)
            null
        }
    }

    private fun releaseWakeLock(session: BookingSession) {
        val wakeLock = session.wakeLock ?: return
        val heldMs = if (session.wakeLockAcquiredAt > 0L) {
            System.currentTimeMillis() - session.wakeLockAcquiredAt
        } else {
            0L
        }
        try {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
            session.logger.log("WakeLock released after ${heldMs}ms")
        } catch (e: Exception) {
            session.logger.log("WakeLock release failed: ${e.message}", MonitoringLogLevel.WARNING)
            Log.w(TAG, "WakeLock release failed", e)
        }
    }

    private fun buildNotification(session: BookingSession): Notification {
        val request = session.request
        val openPendingIntent = PendingIntent.getActivity(
            this,
            pendingRequestCode(request.routeId, REQUEST_OPEN),
            BookingWebViewActivity.createIntent(this, request).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelPendingIntent = PendingIntent.getService(
            this,
            pendingRequestCode(request.routeId, REQUEST_CANCEL),
            createCancelIntent(this, request.routeId, "Booking stopped from notification"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (request.dryRun) {
            "TrainChecker dry-run"
        } else {
            "TrainChecker booking"
        }
        val content = "${session.lastState}: ${session.lastMessage}".take(NOTIFICATION_TEXT_LIMIT)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_train)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content + "\n" + session.lastUrl))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .setFullScreenIntent(openPendingIntent, true)
            .addAction(R.drawable.ic_stat_train, "Open booking", openPendingIntent)
            .addAction(R.drawable.ic_stat_train, "Stop", cancelPendingIntent)
            .build()
    }

    private fun buildResultTelegramMessage(
        request: BookingRequest,
        success: Boolean,
        message: String,
        state: String
    ): String {
        if (success && request.dryRun) {
            return "TrainChecker: dry-run completed for ${request.routeName}.\n$message"
        }
        if (success) {
            return "TrainChecker: WebView booking finished for ${request.routeName}.\n" +
                "$message\nCheck cart/payment on pass.rw.by."
        }
        return "TrainChecker: WebView booking failed for ${request.routeName} at $state.\n$message"
    }

    private fun sendTelegram(request: BookingRequest, message: String) {
        serviceScope.launch(Dispatchers.IO) {
            val sent = TelegramNotifier.send(request.telegramToken, request.chatId, message)
            if (!sent) {
                sessions[request.routeId]?.logger?.log(
                    "Telegram booking notification was not sent",
                    MonitoringLogLevel.WARNING
                )
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ticket booking",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Foreground WebView ticket booking"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun updateNotification(routeId: Long) {
        val session = sessions[routeId] ?: return
        notificationManager.notify(notificationId(routeId), buildNotification(session))
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(resultReceiver)
        sessions.keys.toList().forEach { routeId ->
            finishSession(routeId)
        }
        serviceJob.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BookingForeground"
        private const val CHANNEL_ID = "BookingForegroundServiceChannel"
        private const val NOTIFICATION_BASE_ID = 8000
        private const val REQUEST_OPEN = 100_000
        private const val REQUEST_CANCEL = 200_000
        private const val NOTIFICATION_TEXT_LIMIT = 180
        private const val WAKE_LOCK_TIMEOUT_MS = 35 * 60 * 1000L
        private const val BOOKING_TIMEOUT_MS = 30 * 60 * 1000L

        const val ACTION_START = "by.innowise.trainchecker.action.START_BOOKING_FOREGROUND"
        const val ACTION_CANCEL = "by.innowise.trainchecker.action.CANCEL_BOOKING_FOREGROUND"
        private const val EXTRA_ROUTE_ID = "booking_foreground_route_id"
        private const val EXTRA_CANCEL_REASON = "booking_foreground_cancel_reason"

        fun createStartIntent(context: Context, request: BookingRequest): Intent {
            return request.putInto(Intent(context, BookingForegroundService::class.java).apply {
                action = ACTION_START
            })
        }

        fun createCancelIntent(context: Context, routeId: Long, reason: String): Intent {
            return Intent(context, BookingForegroundService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_ROUTE_ID, routeId)
                putExtra(EXTRA_CANCEL_REASON, reason)
            }
        }

        private fun notificationId(routeId: Long): Int {
            return NOTIFICATION_BASE_ID + (routeId % 1000).toInt()
        }

        private fun pendingRequestCode(routeId: Long, offset: Int): Int {
            return offset + (routeId % 100_000).toInt()
        }
    }
}
