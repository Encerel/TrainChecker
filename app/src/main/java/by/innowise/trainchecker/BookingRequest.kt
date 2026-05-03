package by.innowise.trainchecker

import android.content.Intent
import java.util.ArrayList

data class BookingRequest(
    val routeId: Long,
    val routeName: String,
    val routeUrl: String,
    val trainNumbers: List<String>,
    val serviceClasses: List<String>,
    val rwLogin: String,
    val rwPassword: String,
    val passengerLastName: String,
    val passengerFirstName: String,
    val passengerMiddleName: String,
    val passengerDocumentNumber: String,
    val telegramToken: String,
    val chatId: String,
    val isRenewal: Boolean = false,
    val dryRun: Boolean = false,
    val webViewDebugLogsEnabled: Boolean = false
) {
    val primaryTrainNumber: String
        get() = trainNumbers.firstOrNull().orEmpty()

    fun putInto(intent: Intent): Intent {
        return intent
            .putExtra(EXTRA_ROUTE_ID, routeId)
            .putExtra(EXTRA_ROUTE_NAME, routeName)
            .putExtra(EXTRA_ROUTE_URL, routeUrl)
            .putStringArrayListExtra(EXTRA_TRAIN_NUMBERS, ArrayList(trainNumbers))
            .putStringArrayListExtra(EXTRA_SERVICE_CLASSES, ArrayList(serviceClasses))
            .putExtra(EXTRA_RW_LOGIN, rwLogin)
            .putExtra(EXTRA_RW_PASSWORD, rwPassword)
            .putExtra(EXTRA_PASSENGER_LAST_NAME, passengerLastName)
            .putExtra(EXTRA_PASSENGER_FIRST_NAME, passengerFirstName)
            .putExtra(EXTRA_PASSENGER_MIDDLE_NAME, passengerMiddleName)
            .putExtra(EXTRA_PASSENGER_DOCUMENT_NUMBER, passengerDocumentNumber)
            .putExtra(EXTRA_TELEGRAM_TOKEN, telegramToken)
            .putExtra(EXTRA_CHAT_ID, chatId)
            .putExtra(EXTRA_IS_RENEWAL, isRenewal)
            .putExtra(EXTRA_DRY_RUN, dryRun)
            .putExtra(EXTRA_WEBVIEW_DEBUG_LOGS_ENABLED, webViewDebugLogsEnabled)
    }

    companion object {
        private const val EXTRA_ROUTE_ID = "booking_route_id"
        private const val EXTRA_ROUTE_NAME = "booking_route_name"
        private const val EXTRA_ROUTE_URL = "booking_route_url"
        private const val EXTRA_TRAIN_NUMBERS = "booking_train_numbers"
        private const val EXTRA_SERVICE_CLASSES = "booking_service_classes"
        private const val EXTRA_RW_LOGIN = "booking_rw_login"
        private const val EXTRA_RW_PASSWORD = "booking_rw_password"
        private const val EXTRA_PASSENGER_LAST_NAME = "booking_passenger_last_name"
        private const val EXTRA_PASSENGER_FIRST_NAME = "booking_passenger_first_name"
        private const val EXTRA_PASSENGER_MIDDLE_NAME = "booking_passenger_middle_name"
        private const val EXTRA_PASSENGER_DOCUMENT_NUMBER = "booking_passenger_document_number"
        private const val EXTRA_TELEGRAM_TOKEN = "booking_telegram_token"
        private const val EXTRA_CHAT_ID = "booking_chat_id"
        private const val EXTRA_IS_RENEWAL = "booking_is_renewal"
        private const val EXTRA_DRY_RUN = "booking_dry_run"
        private const val EXTRA_WEBVIEW_DEBUG_LOGS_ENABLED = "booking_webview_debug_logs_enabled"

        fun from(route: MonitoringRoute, rwPassword: String, isRenewal: Boolean = false): BookingRequest {
            return BookingRequest(
                routeId = route.id,
                routeName = route.name.ifBlank { route.extractNameFromUrl() },
                routeUrl = route.url,
                trainNumbers = route.trainNumbers,
                serviceClasses = route.serviceClassesForAutoPurchase,
                rwLogin = route.rwLogin,
                rwPassword = rwPassword,
                passengerLastName = route.passengerLastName,
                passengerFirstName = route.passengerFirstName,
                passengerMiddleName = route.passengerMiddleName,
                passengerDocumentNumber = route.passengerDocumentNumber,
                telegramToken = route.telegramToken,
                chatId = route.chatId,
                isRenewal = isRenewal,
                dryRun = route.autoPurchaseDryRun,
                webViewDebugLogsEnabled = route.webViewDebugLogsEnabled
            )
        }

        fun from(intent: Intent): BookingRequest? {
            val routeId = intent.getLongExtra(EXTRA_ROUTE_ID, -1L)
            val routeUrl = intent.getStringExtra(EXTRA_ROUTE_URL).orEmpty()
            if (routeId == -1L || routeUrl.isBlank()) return null

            return BookingRequest(
                routeId = routeId,
                routeName = intent.getStringExtra(EXTRA_ROUTE_NAME).orEmpty(),
                routeUrl = routeUrl,
                trainNumbers = intent.getStringArrayListExtra(EXTRA_TRAIN_NUMBERS).orEmpty(),
                serviceClasses = intent.getStringArrayListExtra(EXTRA_SERVICE_CLASSES).orEmpty()
                    .ifEmpty { listOf(MonitoringRoute.DEFAULT_SERVICE_CLASS) },
                rwLogin = intent.getStringExtra(EXTRA_RW_LOGIN).orEmpty(),
                rwPassword = intent.getStringExtra(EXTRA_RW_PASSWORD).orEmpty(),
                passengerLastName = intent.getStringExtra(EXTRA_PASSENGER_LAST_NAME).orEmpty(),
                passengerFirstName = intent.getStringExtra(EXTRA_PASSENGER_FIRST_NAME).orEmpty(),
                passengerMiddleName = intent.getStringExtra(EXTRA_PASSENGER_MIDDLE_NAME).orEmpty(),
                passengerDocumentNumber = intent.getStringExtra(EXTRA_PASSENGER_DOCUMENT_NUMBER).orEmpty(),
                telegramToken = intent.getStringExtra(EXTRA_TELEGRAM_TOKEN).orEmpty(),
                chatId = intent.getStringExtra(EXTRA_CHAT_ID).orEmpty(),
                isRenewal = intent.getBooleanExtra(EXTRA_IS_RENEWAL, false),
                dryRun = intent.getBooleanExtra(EXTRA_DRY_RUN, false),
                webViewDebugLogsEnabled = intent.getBooleanExtra(
                    EXTRA_WEBVIEW_DEBUG_LOGS_ENABLED,
                    false
                )
            )
        }
    }
}
