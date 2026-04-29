package by.innowise.trainchecker

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MonitoringLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: MonitoringLogEntry)

    @Query(
        """
        SELECT * FROM monitoring_logs
        WHERE routeId = :routeId
          AND timestamp >= :since
          AND (:query = '' OR message LIKE '%' || :query || '%')
          AND level IN (:levels)
          AND category IN (:categories)
        ORDER BY timestamp DESC
        """
    )
    suspend fun getRecentLogs(
        routeId: Long,
        since: Long,
        query: String,
        levels: List<String>,
        categories: List<String>
    ): List<MonitoringLogEntry>

    @Query("DELETE FROM monitoring_logs WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM monitoring_logs WHERE routeId = :routeId")
    suspend fun deleteByRouteId(routeId: Long)
}
