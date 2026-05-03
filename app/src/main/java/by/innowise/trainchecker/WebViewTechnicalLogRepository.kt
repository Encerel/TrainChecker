package by.innowise.trainchecker

import android.content.Context

class WebViewTechnicalLogRepository(context: Context) {
    private val dao = TrainCheckerDatabase.getInstance(context).webViewTechnicalLogDao()

    suspend fun insert(
        routeId: Long,
        message: String,
        timestamp: Long = System.currentTimeMillis(),
        state: String = "",
        action: String = ""
    ) {
        dao.insert(
            WebViewTechnicalLogEntry(
                routeId = routeId,
                timestamp = timestamp,
                state = state.take(COLUMN_VALUE_LIMIT),
                action = action.take(COLUMN_VALUE_LIMIT),
                message = message.take(MESSAGE_VALUE_LIMIT)
            )
        )
        cleanupIfNeeded()
    }

    suspend fun deleteByRouteId(routeId: Long) {
        dao.deleteByRouteId(routeId)
    }

    suspend fun deleteExpired() {
        dao.deleteOlderThan(System.currentTimeMillis() - RETENTION_MS)
    }

    private suspend fun cleanupIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastCleanupAt < CLEANUP_INTERVAL_MS) return

        lastCleanupAt = now
        dao.deleteOlderThan(now - RETENTION_MS)
    }

    companion object {
        const val RETENTION_MS = 20 * 60 * 1000L
        private const val CLEANUP_INTERVAL_MS = 60 * 1000L
        private const val COLUMN_VALUE_LIMIT = 80
        private const val MESSAGE_VALUE_LIMIT = 4_000

        @Volatile
        private var lastCleanupAt: Long = 0L
    }
}
