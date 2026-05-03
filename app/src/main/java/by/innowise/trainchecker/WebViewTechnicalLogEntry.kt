package by.innowise.trainchecker

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "webview_technical_logs",
    indices = [
        Index(value = ["routeId", "timestamp"]),
        Index(value = ["timestamp"]),
        Index(value = ["state"]),
        Index(value = ["action"])
    ]
)
data class WebViewTechnicalLogEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val routeId: Long,
    val timestamp: Long,
    val state: String,
    val action: String,
    val message: String
)
