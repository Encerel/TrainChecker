package by.innowise.trainchecker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import by.innowise.trainchecker.databinding.ActivityMainBinding
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

        binding.buttonStart.setOnClickListener {
            val url = binding.editUrl.text.toString()
            val token = binding.editToken.text.toString()
            val chatId = binding.editChatId.text.toString()
            val buttonThreshold = binding.editButtonThreshold.text.toString().toIntOrNull() ?: 1
            val checkInterval = binding.editCheckInterval.text.toString().toLongOrNull() ?: 15L
            val healthInterval = binding.editHealthInterval.text.toString().toLongOrNull() ?: 5L

            val intent = Intent(this, MonitorService::class.java).apply {
                action = MonitorService.ACTION_START
                putExtra("url", url)
                putExtra("token", token)
                putExtra("chatId", chatId)
                putExtra("buttonThreshold", buttonThreshold)
                putExtra("checkInterval", checkInterval)
                putExtra("healthInterval", healthInterval)
            }

            startForegroundService(intent)
        }

        binding.buttonStop.setOnClickListener {
            val intent = Intent(this, MonitorService::class.java)
            intent.action = MonitorService.ACTION_STOP
            startForegroundService(intent) // чтобы корректно отправить команду сервису
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
