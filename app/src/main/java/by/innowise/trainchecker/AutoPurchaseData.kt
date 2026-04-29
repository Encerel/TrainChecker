package by.innowise.trainchecker

data class AutoPurchaseData(
    val enabled: Boolean = false,
    val trainNumber: String = "",
    val serviceClasses: String? = null,
    val passengerProfileName: String = "",
    val rwLogin: String = "",
    val rwPassword: String = "",
    val passengerLastName: String = "",
    val passengerFirstName: String = "",
    val passengerMiddleName: String = "",
    val passengerDocumentNumber: String = ""
) {
    val lastName: String get() = passengerLastName
    val firstName: String get() = passengerFirstName
    val middleName: String get() = passengerMiddleName
    val documentNumber: String get() = passengerDocumentNumber
    val serviceClassesInput: String
        get() = serviceClasses
            ?.takeIf { it.isNotBlank() }
            ?: MonitoringRoute.DEFAULT_SERVICE_CLASS
}
