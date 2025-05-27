package by.innowise.trainchecker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import by.innowise.trainchecker.databinding.ActivityMainBinding
import java.net.URLDecoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val logDateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra("log_message") ?: return
            appendLog(msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val savedToken = TelegramPreferenceManager.getToken(this)
        val savedChatId = TelegramPreferenceManager.getChatId(this)

        if (savedToken != null) {
            binding.editToken.setText(savedToken)
        }

        if (savedChatId != null) {
            binding.editChatId.setText(savedChatId)
        }

        binding.buttonStart.setOnClickListener {
            val token = binding.editToken.text.toString()
            val chatId = binding.editChatId.text.toString()

            if (token.isNotBlank()) {
                TelegramPreferenceManager.saveToken(this, token)
            }

            if (chatId.isNotBlank()) {
                TelegramPreferenceManager.saveChatId(this, chatId)
            }

            val usedToken = token.ifBlank { TelegramPreferenceManager.getToken(this).orEmpty() }
            val usedChatId = chatId.ifBlank { TelegramPreferenceManager.getChatId(this).orEmpty() }

            val url = binding.editUrl.text.toString()

            val decodedUrl = try {
                URLDecoder.decode(url, "UTF-8")
            } catch (e: Exception) {
                Log.e("MainActivity", "Ошибка декодирования URL: ${e.message}")
                url // если ошибка — используем исходный URL
            }

            // Логируем для проверки
            Log.d("MainActivity", "Исходный URL: $url")
            Log.d("MainActivity", "Декодированный URL: $decodedUrl")

            val buttonThreshold = binding.editButtonThreshold.text.toString().toIntOrNull() ?: 1
            val checkInterval = binding.editCheckInterval.text.toString().toLongOrNull() ?: 15L
            val healthInterval = binding.editHealthInterval.text.toString().toLongOrNull() ?: 5L

            val intent = Intent(this, MonitorService::class.java).apply {
                action = MonitorService.ACTION_START
                putExtra("url", url)
                putExtra("token", usedToken)
                putExtra("chatId", usedChatId)
                putExtra("buttonThreshold", buttonThreshold)
                putExtra("checkInterval", checkInterval)
                putExtra("healthInterval", healthInterval)
            }

            startForegroundService(intent)
        }
        binding.buttonStop.setOnClickListener {
            val stopIntent = Intent(this, MonitorService::class.java).apply {
                action = MonitorService.ACTION_STOP
            }
            startService(stopIntent)
        }

    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(logReceiver, IntentFilter("LOG_UPDATE"))
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logReceiver)
    }

    fun appendLog(message: String) {
        val time = LocalDateTime.now().format(logDateFormatter)
        runOnUiThread {
            val newLog = "$message. Время проверки: $time\n"
            binding.logTextView.append(newLog)
        }
    }
}
