package by.innowise.trainchecker

import android.content.Context

class MonitoringLogRepository(context: Context) {
    private val dao = TrainCheckerDatabase.getInstance(context).monitoringLogDao()

    suspend fun insert(
        routeId: Long,
        message: String,
        timestamp: Long = System.currentTimeMillis(),
        level: MonitoringLogLevel = MonitoringLogLevel.INFO,
        category: MonitoringLogCategory = MonitoringLogCategory.MONITORING,
        important: Boolean = true
    ) {
        dao.insert(
            MonitoringLogEntry(
                routeId = routeId,
                timestamp = timestamp,
                level = level,
                category = category,
                message = message,
                important = important
            )
        )
        deleteOlderThan(System.currentTimeMillis() - RETENTION_MS)
    }

    suspend fun getRecentLogs(
        routeId: Long,
        query: String = "",
        levels: Set<MonitoringLogLevel> = MonitoringLogLevel.entries.toSet(),
        categories: Set<MonitoringLogCategory> = MonitoringLogCategory.entries.toSet()
    ): List<MonitoringLogEntry> {
        if (levels.isEmpty() || categories.isEmpty()) return emptyList()

        return dao.getRecentLogs(
            routeId = routeId,
            since = System.currentTimeMillis() - RETENTION_MS,
            query = query.trim(),
            levels = levels.map { it.name },
            categories = categories.map { it.name },
            limit = MAX_LOGS_PER_ROUTE
        )
    }

    suspend fun deleteOlderThan(before: Long) {
        dao.deleteOlderThan(before)
    }

    suspend fun deleteByRouteId(routeId: Long) {
        dao.deleteByRouteId(routeId)
    }

    companion object {
        const val RETENTION_MS = 24 * 60 * 60 * 1000L
        const val MAX_LOGS_PER_ROUTE = 400
    }
}
