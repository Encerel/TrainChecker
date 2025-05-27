package by.innowise.trainchecker

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import by.innowise.trainchecker.databinding.ActivityCreateMonitoringBinding


class CreateMonitoringActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCreateMonitoringBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateMonitoringBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Установка значений по умолчанию, если они есть
        MonitoringPreferenceManager.getDefaultToken(this)?.let {
            binding.editToken.setText(it)
        }
        MonitoringPreferenceManager.getDefaultChatId(this)?.let {
            binding.editChatId.setText(it)
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

            // Сохраняем маршрут
            val currentRoutes = MonitoringPreferenceManager.getRoutes(this).toMutableList()
            currentRoutes.add(newRoute)
            MonitoringPreferenceManager.saveRoutes(this, currentRoutes)

            // Сохраняем токен и chatId как значения по умолчанию
            if (token.isNotBlank()) {
                MonitoringPreferenceManager.saveDefaultToken(this, token)
            }
            if (chatId.isNotBlank()) {
                MonitoringPreferenceManager.saveDefaultChatId(this, chatId)
            }

            finish()
        }
    }
}