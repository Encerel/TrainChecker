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

    @Test
    fun testParseServiceClasses_withCommas() {
        val result = MonitoringRoute.parseServiceClasses("2П, 3Д, 3У")
        assertEquals(listOf("2П", "3Д", "3У"), result)
    }

    @Test
    fun testParseServiceClasses_normalizesCase() {
        val result = MonitoringRoute.parseServiceClasses("2п, 3д")
        assertEquals(listOf("2П", "3Д"), result)
    }

    @Test
    fun testServiceClassesFormatted_default() {
        val route = MonitoringRoute(
            url = "https://example.com",
            telegramToken = "token",
            chatId = "123"
        )
        assertEquals("2П", route.serviceClassesFormatted)
    }

    @Test
    fun testServiceClassesFormatted_customList() {
        val route = MonitoringRoute(
            url = "https://example.com",
            telegramToken = "token",
            chatId = "123",
            serviceClasses = listOf("2П", "3Д")
        )
        assertEquals("2П, 3Д", route.serviceClassesFormatted)
    }
}
