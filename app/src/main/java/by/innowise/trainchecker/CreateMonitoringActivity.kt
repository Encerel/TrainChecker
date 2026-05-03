package by.innowise.trainchecker

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.View
import by.innowise.trainchecker.databinding.ActivityCreateMonitoringBinding
import by.innowise.trainchecker.databinding.DialogManageChatIdsBinding
import kotlinx.coroutines.launch


class CreateMonitoringActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCreateMonitoringBinding
    private lateinit var passengerProfileRepository: PassengerProfileRepository
    private var passengerProfiles: List<PassengerProfile> = emptyList()
    private var editingRouteId: Long? = null
    private var isEditMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateMonitoringBinding.inflate(layoutInflater)
        setContentView(binding.root)
        passengerProfileRepository = PassengerProfileRepository(this)

        editingRouteId = intent.getLongExtra("route_id", -1).takeIf { it != -1L }
        isEditMode = editingRouteId != null

        setupChatIdAutocomplete()

        setupAutoPurchaseToggle()
        setupPassengerProfiles()

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

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && ::passengerProfileRepository.isInitialized) {
            loadPassengerProfiles()
        }
    }

    private fun setupAutoPurchaseToggle() {
        binding.switchAutoPurchase.setOnCheckedChangeListener { _, isChecked ->
            binding.autoPurchaseFields.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
    }

    private fun setupPassengerProfiles() {
        binding.autoPassengerProfile.setOnItemClickListener { _, _, position, _ ->
            passengerProfiles.getOrNull(position)?.let { profile ->
                applyPassengerProfile(profile)
            }
        }

        binding.autoPassengerProfile.setOnClickListener {
            if (passengerProfiles.isNotEmpty()) {
                binding.autoPassengerProfile.showDropDown()
            }
        }

        binding.buttonOpenPassengerProfiles.setOnClickListener {
            startActivity(Intent(this, PassengerProfilesActivity::class.java))
        }

        loadPassengerProfiles()
    }

    private fun loadPassengerProfiles() {
        lifecycleScope.launch {
            passengerProfiles = passengerProfileRepository.getAll()
            val adapter = ArrayAdapter(
                this@CreateMonitoringActivity,
                android.R.layout.simple_dropdown_item_1line,
                passengerProfiles.map { it.name }
            )
            binding.autoPassengerProfile.setAdapter(adapter)
        }
    }

    private fun applyPassengerProfile(profile: PassengerProfile) {
        binding.autoPassengerProfile.setText(profile.name, false)
        binding.editChatId.setText(profile.chatId)
    }

    private fun getSelectedPassengerProfile(): PassengerProfile? {
        val profileName = binding.autoPassengerProfile.text.toString().trim()
        return passengerProfiles.find { it.name == profileName }
    }

    private fun loadDefaultPassengerData() {
        MonitoringPreferenceManager.getDefaultPassengerData(this)?.let { data ->
            binding.editServiceClasses.setText(data.serviceClassesInput)
            binding.switchDryRun.isChecked = data.dryRun
        } ?: binding.editServiceClasses.setText(MonitoringRoute.DEFAULT_SERVICE_CLASS)
        if (binding.editServiceClasses.text.isNullOrBlank()) {
            binding.editServiceClasses.setText(MonitoringRoute.DEFAULT_SERVICE_CLASS)
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
        binding.switchDryRun.isChecked = route.autoPurchaseDryRun
        binding.switchWebViewDebugLogs.isChecked = route.webViewDebugLogsEnabled
        binding.editServiceClasses.setText(route.serviceClassesFormatted)
        binding.autoPassengerProfile.setText(route.passengerProfileName, false)
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
        val selectedProfile = getSelectedPassengerProfile()
        return AutoPurchaseData(
            enabled = binding.switchAutoPurchase.isChecked,
            dryRun = binding.switchDryRun.isChecked,
            trainNumber = binding.editTrainNumber.text.toString().trim(),
            serviceClasses = binding.editServiceClasses.text.toString().trim(),
            passengerProfileName = selectedProfile?.name.orEmpty(),
            rwLogin = selectedProfile?.rwLogin.orEmpty(),
            rwPassword = selectedProfile
                ?.let { PassengerProfilePasswordManager.getPassword(this, it.name) }
                .orEmpty(),
            passengerLastName = selectedProfile?.lastName.orEmpty(),
            passengerFirstName = selectedProfile?.firstName.orEmpty(),
            passengerMiddleName = selectedProfile?.middleName.orEmpty(),
            passengerDocumentNumber = selectedProfile?.documentNumber.orEmpty()
        )
    }

    private fun validateAutoPurchaseData(data: AutoPurchaseData): Boolean {
        if (!data.enabled) return true
        
        if (data.trainNumber.isEmpty()) {
            Toast.makeText(this, "Укажите номер поезда для авторезерва", Toast.LENGTH_SHORT).show()
            return false
        }
        if (data.passengerProfileName.isBlank()) {
            Toast.makeText(this, "Выберите профиль пассажира для авторезерва", Toast.LENGTH_SHORT).show()
            return false
        }
        if (data.rwLogin.isEmpty() || data.rwPassword.isBlank()) {
            Toast.makeText(this, "В выбранном профиле укажите логин и пароль pass.rw.by", Toast.LENGTH_SHORT).show()
            return false
        }
        if (data.passengerLastName.isEmpty() ||
            data.passengerFirstName.isEmpty() ||
            data.passengerDocumentNumber.isEmpty()
        ) {
            Toast.makeText(this, "В выбранном профиле заполните ФИО и документ", Toast.LENGTH_SHORT).show()
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

        val password = autoPurchaseData.rwPassword
        val effectiveChatId = if (autoPurchaseData.enabled && getSelectedPassengerProfile() != null) {
            getSelectedPassengerProfile()?.chatId.orEmpty()
        } else {
            chatId
        }
        val newRoute = MonitoringRoute(
            url = url,
            telegramToken = token,
            chatId = effectiveChatId,
            checkIntervalSec = checkInterval,
            healthIntervalMin = healthInterval,
            autoPurchaseEnabled = autoPurchaseData.enabled,
            autoPurchaseDryRun = autoPurchaseData.dryRun,
            webViewDebugLogsEnabled = binding.switchWebViewDebugLogs.isChecked,
            passengerProfileName = autoPurchaseData.passengerProfileName,
            trainNumbers = trainNumbers,
            serviceClasses = serviceClasses,
            rwLogin = autoPurchaseData.rwLogin,
            hasSavedRwPassword = password.isNotBlank(),
            rwPassword = "",
            passengerLastName = autoPurchaseData.passengerLastName,
            passengerFirstName = autoPurchaseData.passengerFirstName,
            passengerMiddleName = autoPurchaseData.passengerMiddleName,
            passengerDocumentNumber = autoPurchaseData.passengerDocumentNumber
        ).apply {
            name = extractNameFromUrl()
        }

        if (password.isNotBlank()) {
            RwPasswordManager.savePassword(this, newRoute.id, password)
        }

        val currentRoutes = MonitoringPreferenceManager.getRoutes(this).toMutableList()
        currentRoutes.add(newRoute)
        MonitoringPreferenceManager.saveRoutes(this, currentRoutes)

        if (token.isNotBlank()) {
            MonitoringPreferenceManager.saveDefaultToken(this, token)
        }
        if (effectiveChatId.isNotBlank()) {
            MonitoringPreferenceManager.saveDefaultChatId(this, effectiveChatId)
            MonitoringPreferenceManager.saveChatIdToHistory(this, effectiveChatId)
        }

        // Сохраняем данные пассажира как дефолтные
        if (autoPurchaseData.enabled) {
            MonitoringPreferenceManager.saveDefaultPassengerData(this, autoPurchaseData)
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

        val password = autoPurchaseData.rwPassword
        val effectiveChatId = if (autoPurchaseData.enabled && getSelectedPassengerProfile() != null) {
            getSelectedPassengerProfile()?.chatId.orEmpty()
        } else {
            chatId
        }
        val hasSavedPassword = if (password.isNotBlank()) {
            RwPasswordManager.savePassword(this, existingRoute.id, password)
            true
        } else {
            existingRoute.hasSavedRwPassword || RwPasswordManager.hasPassword(this, existingRoute.id)
        }

        val updatedRoute = existingRoute.copy(
            url = url,
            telegramToken = token,
            chatId = effectiveChatId,
            checkIntervalSec = checkInterval,
            healthIntervalMin = healthInterval,
            autoPurchaseEnabled = autoPurchaseData.enabled,
            autoPurchaseDryRun = autoPurchaseData.dryRun,
            webViewDebugLogsEnabled = binding.switchWebViewDebugLogs.isChecked,
            passengerProfileName = autoPurchaseData.passengerProfileName,
            trainNumbers = trainNumbers,
            serviceClasses = serviceClasses,
            rwLogin = autoPurchaseData.rwLogin,
            hasSavedRwPassword = hasSavedPassword,
            rwPassword = "",
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
        if (effectiveChatId.isNotBlank()) {
            MonitoringPreferenceManager.saveDefaultChatId(this, effectiveChatId)
            MonitoringPreferenceManager.saveChatIdToHistory(this, effectiveChatId)
        }

        if (autoPurchaseData.enabled) {
            MonitoringPreferenceManager.saveDefaultPassengerData(this, autoPurchaseData)
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
