package by.innowise.trainchecker

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WebViewTechnicalLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: WebViewTechnicalLogEntry)

    @Query("DELETE FROM webview_technical_logs WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM webview_technical_logs WHERE routeId = :routeId")
    suspend fun deleteByRouteId(routeId: Long)
}
