package by.innowise.trainchecker

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat

object BookingCoordinator {
    private const val TAG = "BookingCoordinator"

    fun start(context: Context, route: MonitoringRoute, rwPassword: String, isRenewal: Boolean = false) {
        val request = BookingRequest.from(route, rwPassword, isRenewal)
        try {
            ContextCompat.startForegroundService(
                context,
                BookingForegroundService.createStartIntent(context, request)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Foreground booking service was not started", e)
        }
    }
}
