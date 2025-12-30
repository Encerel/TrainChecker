package by.innowise.trainchecker

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
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
        }

        binding.buttonCreate.setOnClickListener {
            val url = binding.editUrl.text.toString().trim()
            if (url.isEmpty() || !url.contains("pass.rw.by/ru/route")) {
                Toast.makeText(this, "Введите URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val token = binding.editToken.text.toString()
            val chatId = binding.editChatId.text.toString()
            val buttonThreshold = binding.editButtonThreshold.text.toString().toIntOrNull() ?: 1
            val checkInterval = binding.editCheckInterval.text.toString().toLongOrNull() ?: 15L
            val healthInterval = binding.editHealthInterval.text.toString().toLongOrNull() ?: 30L

            if (isEditMode) {
                updateRoute(url, token, chatId, buttonThreshold, checkInterval, healthInterval)
            } else {
                createRoute(url, token, chatId, buttonThreshold, checkInterval, healthInterval)
            }
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
        binding.editButtonThreshold.setText(route.buttonThreshold.toString())
        binding.editCheckInterval.setText(route.checkIntervalSec.toString())
        binding.editHealthInterval.setText(route.healthIntervalMin.toString())
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

    private fun createRoute(url: String, token: String, chatId: String, 
                           buttonThreshold: Int, checkInterval: Long, healthInterval: Long) {
        val newRoute = MonitoringRoute(
            url = url,
            telegramToken = token,
            chatId = chatId,
            buttonThreshold = buttonThreshold,
            checkIntervalSec = checkInterval,
            healthIntervalMin = healthInterval
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

        Toast.makeText(this, "Маршрут создан", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun updateRoute(url: String, token: String, chatId: String,
                           buttonThreshold: Int, checkInterval: Long, healthInterval: Long) {
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

        val updatedRoute = existingRoute.copy(
            url = url,
            telegramToken = token,
            chatId = chatId,
            buttonThreshold = buttonThreshold,
            checkIntervalSec = checkInterval,
            healthIntervalMin = healthInterval
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