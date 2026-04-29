package by.innowise.trainchecker

import androidx.room.TypeConverter

class MonitoringLogConverters {
    @TypeConverter
    fun levelToString(level: MonitoringLogLevel): String = level.name

    @TypeConverter
    fun stringToLevel(value: String): MonitoringLogLevel {
        return enumValueOrDefault(value, MonitoringLogLevel.INFO)
    }

    @TypeConverter
    fun categoryToString(category: MonitoringLogCategory): String = category.name

    @TypeConverter
    fun stringToCategory(value: String): MonitoringLogCategory {
        return enumValueOrDefault(value, MonitoringLogCategory.SYSTEM)
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        value: String,
        defaultValue: T
    ): T {
        return runCatching { enumValueOf<T>(value) }.getOrDefault(defaultValue)
    }
}
