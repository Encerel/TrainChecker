package by.innowise.trainchecker

import org.junit.Test
import org.junit.Assert.*

/**
 * Тесты для функционала парсинга номеров поездов
 */
class MonitoringRouteTest {
    
    @Test
    fun testParseTrainNumbers_withCommas() {
        val result = MonitoringRoute.parseTrainNumbers("872Б, 860Б, 658Б")
        assertEquals(listOf("872Б", "860Б", "658Б"), result)
    }
    
    @Test
    fun testParseTrainNumbers_withSemicolons() {
        val result = MonitoringRoute.parseTrainNumbers("872Б; 860Б; 658Б")
        assertEquals(listOf("872Б", "860Б", "658Б"), result)
    }
    
    @Test
    fun testParseTrainNumbers_withPeriods() {
        val result = MonitoringRoute.parseTrainNumbers("872Б. 860Б. 658Б")
        assertEquals(listOf("872Б", "860Б", "658Б"), result)
    }
    
    @Test
    fun testParseTrainNumbers_withSpaces() {
        val result = MonitoringRoute.parseTrainNumbers("872Б 860Б 658Б")
        assertEquals(listOf("872Б", "860Б", "658Б"), result)
    }
    
    @Test
    fun testParseTrainNumbers_mixedDelimiters() {
        val result = MonitoringRoute.parseTrainNumbers("872Б, 860Б; 658Б. 123А")
        assertEquals(listOf("872Б", "860Б", "658Б", "123А"), result)
    }
    
    @Test
    fun testParseTrainNumbers_withExtraSpaces() {
        val result = MonitoringRoute.parseTrainNumbers("  872Б  ,  860Б  ,  658Б  ")
        assertEquals(listOf("872Б", "860Б", "658Б"), result)
    }
    
    @Test
    fun testParseTrainNumbers_singleTrain() {
        val result = MonitoringRoute.parseTrainNumbers("872Б")
        assertEquals(listOf("872Б"), result)
    }
    
    @Test
    fun testParseTrainNumbers_emptyString() {
        val result = MonitoringRoute.parseTrainNumbers("")
        assertEquals(emptyList<String>(), result)
    }
    
    @Test
    fun testTrainNumbersFormatted() {
        val route = MonitoringRoute(
            url = "https://example.com",
            telegramToken = "token",
            chatId = "123",
            trainNumbers = listOf("872Б", "860Б", "658Б")
        )
        assertEquals("872Б, 860Б, 658Б", route.trainNumbersFormatted)
    }
    
    @Test
    fun testTrainNumber_backwardCompatibility() {
        val route = MonitoringRoute(
            url = "https://example.com",
            telegramToken = "token",
            chatId = "123",
            trainNumbers = listOf("872Б", "860Б", "658Б")
        )
        assertEquals("872Б", route.trainNumber)
    }
    
    @Test
    fun testTrainNumber_emptyList() {
        val route = MonitoringRoute(
            url = "https://example.com",
            telegramToken = "token",
            chatId = "123",
            trainNumbers = emptyList()
        )
        assertEquals("", route.trainNumber)
    }
}
