package by.innowise.trainchecker

import android.util.Log
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MonitoringRoute(
    val id: Long = System.currentTimeMillis(),
    val url: String,
    var name: String = "",
    val telegramToken: String,
    val chatId: String,
    val buttonThreshold: Int = 1,
    val checkIntervalSec: Long = 15,
    val healthIntervalMin: Long = 30,
    var isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val logs: MutableList<String> = mutableListOf()
) {
    fun extractNameFromUrl(): String {
        return try {
            val decodedUrl = URLDecoder.decode(url, "UTF-8")

            // Извлекаем города
            val from = extractParam(decodedUrl, "from") ?: "Неизвестно"
            val to = extractParam(decodedUrl, "to") ?: "Неизвестно"

            // Пытаемся извлечь дату в разных форматах
            val date = extractDate(decodedUrl)

            // Форматируем результат
            "$from - $to, $date"
        } catch (e: Exception) {
            Log.e("MonitoringRoute", "Error parsing URL: ${e.message}")
            "Маршрут $id"
        }
    }

    fun getCreationDateFormatted(): String {
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(createdAt))
    }

    // Форматирование интервалов
    fun getIntervalsFormatted(): String {
        return "Проверка: ${checkIntervalSec} сек\nHealthcheck: ${healthIntervalMin} мин"
    }

    private fun extractParam(url: String, paramName: String): String? {
        return Regex("$paramName=([^&]+)").find(url)
            ?.groupValues?.get(1)
            ?.let { URLDecoder.decode(it, "UTF-8") }
    }

    private fun extractDate(url: String): String {
        // Пробуем извлечь date в формате YYYY-MM-DD
        Regex("date=([^&]+)").find(url)?.groupValues?.get(1)?.let {
            return try {
                // Форматируем дату для красивого отображения
                val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val date = inputFormat.parse(it)
                date?.let {
                    val outputFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                    outputFormat.format(it)
                } ?: it
            } catch (e: Exception) {
                it // возвращаем как есть, если не удалось распарсить
            }
        }

        // Пробуем извлечь front_date (например: "15 июня. 2025")
        Regex("front_date=([^&]+)").find(url)?.groupValues?.get(1)?.let {
            return it.replace("+", " ") // заменяем плюсы на пробелы
        }

        return "Неизвестная дата"
    }
}
