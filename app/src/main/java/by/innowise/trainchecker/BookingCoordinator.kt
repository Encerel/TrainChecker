package by.innowise.trainchecker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

object BookingCoordinator {
    private const val TAG = "BookingCoordinator"
    private const val CHANNEL_ID = "BookingWebViewChannel"
    private const val BASE_NOTIFICATION_ID = 7000

    fun start(context: Context, route: MonitoringRoute, rwPassword: String, isRenewal: Boolean = false) {
        val request = BookingRequest.from(route, rwPassword, isRenewal)
        val activityIntent = BookingWebViewActivity.createIntent(context, request).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        showLaunchNotification(context, request, activityIntent)

        try {
            context.startActivity(activityIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Background activity launch was not allowed", e)
        }
    }

    private fun showLaunchNotification(
        context: Context,
        request: BookingRequest,
        activityIntent: Intent
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel(manager)

        val pendingIntent = PendingIntent.getActivity(
            context,
            request.routeId.toInt(),
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_train)
            .setContentTitle("TrainChecker booking")
            .setContentText("Opening WebView for ${request.routeName.ifBlank { request.primaryTrainNumber }}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        try {
            manager.notify(notificationId(request.routeId), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission is not granted", e)
        }
    }

    private fun createChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ticket booking",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Foreground WebView ticket booking"
        }
        manager.createNotificationChannel(channel)
    }

    private fun notificationId(routeId: Long): Int {
        return BASE_NOTIFICATION_ID + (routeId % 1000).toInt()
    }
}
