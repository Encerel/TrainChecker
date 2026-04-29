package by.innowise.trainchecker

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "monitoring_logs",
    indices = [
        Index(value = ["routeId", "timestamp"]),
        Index(value = ["timestamp"]),
        Index(value = ["important"]),
        Index(value = ["level"]),
        Index(value = ["category"])
    ]
)
data class MonitoringLogEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val routeId: Long,
    val timestamp: Long,
    val level: MonitoringLogLevel,
    val category: MonitoringLogCategory,
    val message: String,
    val important: Boolean
)

enum class MonitoringLogLevel(val title: String) {
    INFO("Инфо"),
    SUCCESS("Успех"),
    WARNING("Предупреждение"),
    ERROR("Ошибка")
}

enum class MonitoringLogCategory(val title: String) {
    MONITORING("Мониторинг"),
    AVAILABILITY("Места"),
    AUTO_PURCHASE("Авторезерв"),
    TELEGRAM("Telegram"),
    SYSTEM("Система")
}
