package by.innowise.trainchecker

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.View
import by.innowise.trainchecker.databinding.ActivityCreateMonitoringBinding
import by.innowise.trainchecker.databinding.DialogManageChatIdsBinding


class CreateMonitoringActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCreateMonitoringBinding
    private var editingRouteId: Long? = null
    private var isEditMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateMonitoringBinding.inflate(layoutInflater)
        setContentView(binding.root)

        editingRouteId = intent.getLongExtra("route_id", -1).takeIf { it != -1L }
        isEditMode = editingRouteId != null

        setupChatIdAutocomplete()

        setupAutoPurchaseToggle()

        if (isEditMode) {
            loadRouteForEditing()
            binding.buttonCreate.text = "Сохранить изменения"
        } else {
            MonitoringPreferenceManager.getDefaultToken(this)?.let {
                binding.editToken.setText(it)
            }
            MonitoringPreferenceManager.getDefaultChatId(this)?.let {
                binding.editChatId.setText(it)
            }
            loadDefaultPassengerData()
        }

        binding.buttonCreate.setOnClickListener {
            val url = binding.editUrl.text.toString().trim()
            if (url.isEmpty() || !url.contains("pass.rw.by/ru/route")) {
                Toast.makeText(this, "Введите URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val token = binding.editToken.text.toString()
            val chatId = binding.editChatId.text.toString()
            val checkInterval = binding.editCheckInterval.text.toString().toLongOrNull() ?: 15L
            val healthInterval = binding.editHealthInterval.text.toString().toLongOrNull() ?: 30L

            if (isEditMode) {
                updateRoute(url, token, chatId, checkInterval, healthInterval)
            } else {
                createRoute(url, token, chatId, checkInterval, healthInterval)
            }
        }
    }

    private fun setupAutoPurchaseToggle() {
        binding.switchAutoPurchase.setOnCheckedChangeListener { _, isChecked ->
            binding.autoPurchaseFields.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
    }

    private fun loadDefaultPassengerData() {
        MonitoringPreferenceManager.getDefaultPassengerData(this)?.let { data ->
            binding.editServiceClasses.setText(data.serviceClassesInput)
            binding.editPassengerLastName.setText(data.lastName)
            binding.editPassengerFirstName.setText(data.firstName)
            binding.editPassengerMiddleName.setText(data.middleName)
            binding.editPassengerDocument.setText(data.documentNumber)
        } ?: binding.editServiceClasses.setText(MonitoringRoute.DEFAULT_SERVICE_CLASS)
        if (binding.editServiceClasses.text.isNullOrBlank()) {
            binding.editServiceClasses.setText(MonitoringRoute.DEFAULT_SERVICE_CLASS)
        }
        MonitoringPreferenceManager.getDefaultRwLogin(this)?.let {
            binding.editRwLogin.setText(it)
        }
    }

    private fun loadRouteForEditing() {
        val routes = MonitoringPreferenceManager.getRoutes(this)
        val route = routes.find { it.id == editingRouteId } ?: run {
            Toast.makeText(this, "Маршрут не найден", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.editUrl.setText(route.url)
        binding.editToken.setText(route.telegramToken)
        binding.editChatId.setText(route.chatId)
        binding.editCheckInterval.setText(route.checkIntervalSec.toString())
        binding.editHealthInterval.setText(route.healthIntervalMin.toString())
        binding.editTrainNumber.setText(route.trainNumbersFormatted)

        // Загрузка данных авторезерва
        binding.switchAutoPurchase.isChecked = route.autoPurchaseEnabled
        binding.autoPurchaseFields.visibility = if (route.autoPurchaseEnabled) View.VISIBLE else View.GONE
        binding.editServiceClasses.setText(route.serviceClassesFormatted)
        binding.editRwLogin.setText(route.rwLogin)
        binding.editRwPassword.setText(route.rwPassword)
        binding.editPassengerLastName.setText(route.passengerLastName)
        binding.editPassengerFirstName.setText(route.passengerFirstName)
        binding.editPassengerMiddleName.setText(route.passengerMiddleName)
        binding.editPassengerDocument.setText(route.passengerDocumentNumber)
    }

    private fun setupChatIdAutocomplete() {
        updateChatIdAdapter()
        
        binding.editChatId.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && binding.editChatId.text.isEmpty()) {
                binding.editChatId.showDropDown()
            }
        }
        
        binding.editChatId.setOnClickListener {
            if (binding.editChatId.text.isEmpty()) {
                binding.editChatId.showDropDown()
            }
        }
        
        binding.buttonManageChatIds.setOnClickListener {
            showManageChatIdsDialog()
        }
    }
    
    private fun updateChatIdAdapter() {
        val chatIdHistory = MonitoringPreferenceManager.getChatIdHistory(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, chatIdHistory)
        binding.editChatId.setAdapter(adapter)
    }
    
    private fun showManageChatIdsDialog() {
        val dialogBinding = DialogManageChatIdsBinding.inflate(layoutInflater)
        val chatIdHistory = MonitoringPreferenceManager.getChatIdHistory(this).toMutableList()
        
        if (chatIdHistory.isEmpty()) {
            Toast.makeText(this, "История Chat ID пуста", Toast.LENGTH_SHORT).show()
            return
        }
        
        lateinit var chatIdAdapter: ChatIdAdapter
        
        chatIdAdapter = ChatIdAdapter(chatIdHistory) { chatId ->
            AlertDialog.Builder(this)
                .setTitle("Удалить Chat ID?")
                .setMessage("Вы уверены, что хотите удалить \"$chatId\" из истории?")
                .setPositiveButton("Удалить") { _, _ ->
                    MonitoringPreferenceManager.deleteChatIdFromHistory(this, chatId)
                    chatIdAdapter.removeItem(chatId)
                    updateChatIdAdapter()
                    Toast.makeText(this, "Chat ID удален", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        
        dialogBinding.chatIdRecyclerView.layoutManager = LinearLayoutManager(this)
        dialogBinding.chatIdRecyclerView.adapter = chatIdAdapter
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()
        
        dialogBinding.buttonClose.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }

    private fun getAutoPurchaseData(): AutoPurchaseData {
        return AutoPurchaseData(
            enabled = binding.switchAutoPurchase.isChecked,
            trainNumber = binding.editTrainNumber.text.toString().trim(),
            serviceClasses = binding.editServiceClasses.text.toString().trim(),
            rwLogin = binding.editRwLogin.text.toString().trim(),
            rwPassword = binding.editRwPassword.text.toString(),
            passengerLastName = binding.editPassengerLastName.text.toString().trim(),
            passengerFirstName = binding.editPassengerFirstName.text.toString().trim(),
            passengerMiddleName = binding.editPassengerMiddleName.text.toString().trim(),
            passengerDocumentNumber = binding.editPassengerDocument.text.toString().trim()
        )
    }

    private fun validateAutoPurchaseData(data: AutoPurchaseData): Boolean {
        if (!data.enabled) return true
        
        if (data.trainNumber.isEmpty()) {
            Toast.makeText(this, "Укажите номер поезда для авторезерва", Toast.LENGTH_SHORT).show()
            return false
        }
        if (data.rwLogin.isEmpty() || data.rwPassword.isEmpty()) {
            Toast.makeText(this, "Укажите логин и пароль pass.rw.by", Toast.LENGTH_SHORT).show()
            return false
        }
        if (data.passengerLastName.isEmpty() || data.passengerFirstName.isEmpty()) {
            Toast.makeText(this, "Укажите ФИО пассажира", Toast.LENGTH_SHORT).show()
            return false
        }
        if (data.passengerDocumentNumber.isEmpty()) {
            Toast.makeText(this, "Укажите номер документа", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun createRoute(url: String, token: String, chatId: String, 
                           checkInterval: Long, healthInterval: Long) {
        val autoPurchaseData = getAutoPurchaseData()
        if (!validateAutoPurchaseData(autoPurchaseData)) return

        // Парсим номера поездов из поля ввода (которое теперь в binding.editTrainNumber, но мы берем из data)
        // ВНИМАНИЕ: editTrainNumber теперь общий, но мы его читали в getAutoPurchaseData().
        // Лучше прочитать напрямую, так как валидация autoPurchaseData может быть не нужна если авторезерв выключен
        val trainNumberInput = binding.editTrainNumber.text.toString().trim()
        val trainNumbers = MonitoringRoute.parseTrainNumbers(trainNumberInput)
        val serviceClasses = MonitoringRoute.parseServiceClasses(autoPurchaseData.serviceClassesInput)

        val newRoute = MonitoringRoute(
            url = url,
            telegramToken = token,
            chatId = chatId,
            checkIntervalSec = checkInterval,
            healthIntervalMin = healthInterval,
            autoPurchaseEnabled = autoPurchaseData.enabled,
            trainNumbers = trainNumbers,
            serviceClasses = serviceClasses,
            rwLogin = autoPurchaseData.rwLogin,
            rwPassword = autoPurchaseData.rwPassword,
            passengerLastName = autoPurchaseData.passengerLastName,
            passengerFirstName = autoPurchaseData.passengerFirstName,
            passengerMiddleName = autoPurchaseData.passengerMiddleName,
            passengerDocumentNumber = autoPurchaseData.passengerDocumentNumber
        ).apply {
            name = extractNameFromUrl()
        }

        val currentRoutes = MonitoringPreferenceManager.getRoutes(this).toMutableList()
        currentRoutes.add(newRoute)
        MonitoringPreferenceManager.saveRoutes(this, currentRoutes)

        if (token.isNotBlank()) {
            MonitoringPreferenceManager.saveDefaultToken(this, token)
        }
        if (chatId.isNotBlank()) {
            MonitoringPreferenceManager.saveDefaultChatId(this, chatId)
            MonitoringPreferenceManager.saveChatIdToHistory(this, chatId)
        }

        // Сохраняем данные пассажира как дефолтные
        if (autoPurchaseData.enabled) {
            MonitoringPreferenceManager.saveDefaultPassengerData(this, autoPurchaseData)
            MonitoringPreferenceManager.saveDefaultRwLogin(this, autoPurchaseData.rwLogin)
        }

        Toast.makeText(this, "Маршрут создан", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun updateRoute(url: String, token: String, chatId: String,
                           checkInterval: Long, healthInterval: Long) {
        val routes = MonitoringPreferenceManager.getRoutes(this).toMutableList()
        val routeIndex = routes.indexOfFirst { it.id == editingRouteId }
        
        if (routeIndex == -1) {
            Toast.makeText(this, "Маршрут не найден", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val existingRoute = routes[routeIndex]
        val wasActive = existingRoute.isActive
        
        if (wasActive) {
            val stopIntent = Intent(this, MonitorService::class.java).apply {
                action = MonitorService.ACTION_STOP
                putExtra("route_id", editingRouteId)
            }
            startService(stopIntent)
        }

        val autoPurchaseData = getAutoPurchaseData()
        if (!validateAutoPurchaseData(autoPurchaseData)) return
        
        val trainNumberInput = binding.editTrainNumber.text.toString().trim()
        val trainNumbers = MonitoringRoute.parseTrainNumbers(trainNumberInput)
        val serviceClasses = MonitoringRoute.parseServiceClasses(autoPurchaseData.serviceClassesInput)

        val updatedRoute = existingRoute.copy(
            url = url,
            telegramToken = token,
            chatId = chatId,
            checkIntervalSec = checkInterval,
            healthIntervalMin = healthInterval,
            autoPurchaseEnabled = autoPurchaseData.enabled,
            trainNumbers = trainNumbers,
            serviceClasses = serviceClasses,
            rwLogin = autoPurchaseData.rwLogin,
            rwPassword = autoPurchaseData.rwPassword,
            passengerLastName = autoPurchaseData.passengerLastName,
            passengerFirstName = autoPurchaseData.passengerFirstName,
            passengerMiddleName = autoPurchaseData.passengerMiddleName,
            passengerDocumentNumber = autoPurchaseData.passengerDocumentNumber
        ).apply {
            name = extractNameFromUrl()
        }

        routes[routeIndex] = updatedRoute
        MonitoringPreferenceManager.saveRoutes(this, routes)

        if (token.isNotBlank()) {
            MonitoringPreferenceManager.saveDefaultToken(this, token)
        }
        if (chatId.isNotBlank()) {
            MonitoringPreferenceManager.saveDefaultChatId(this, chatId)
            MonitoringPreferenceManager.saveChatIdToHistory(this, chatId)
        }

        if (autoPurchaseData.enabled) {
            MonitoringPreferenceManager.saveDefaultPassengerData(this, autoPurchaseData)
            MonitoringPreferenceManager.saveDefaultRwLogin(this, autoPurchaseData.rwLogin)
        }

        if (wasActive) {
            val startIntent = Intent(this, MonitorService::class.java).apply {
                action = MonitorService.ACTION_START
                putExtra("route_id", editingRouteId)
            }
            startService(startIntent)
        }

        Toast.makeText(this, "Маршрут обновлен", Toast.LENGTH_SHORT).show()
        finish()
    }
}
