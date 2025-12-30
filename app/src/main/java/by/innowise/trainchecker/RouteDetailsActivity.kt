package by.innowise.trainchecker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import by.innowise.trainchecker.databinding.ActivityRouteDetailsBinding
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


class RouteDetailsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRouteDetailsBinding
    private lateinit var route: MonitoringRoute
    private val logReceiver = object : BroadcastReceiver() {
        @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
        override fun onReceive(context: Context?, intent: Intent?) {
            val routeId = intent?.getLongExtra("route_id", -1) ?: return
            if (routeId == route.id) {
                val msg = intent.getStringExtra("log_message") ?: return
                appendLog(msg)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRouteDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val routeId = intent.getLongExtra("route_id", -1)
        route = MonitoringPreferenceManager.getRoutes(this)
            .find { it.id == routeId } ?: run {
            finish()
            return
        }

        setupUI()
        loadLogs()
    }

    private fun setupUI() {
        binding.routeName.text = route.name
        binding.routeUrl.text = route.url

        binding.routeParameters.text = """
        Минимум кнопок: ${route.buttonThreshold}
        Интервал проверки: ${route.checkIntervalSec} сек
        Интервал healthcheck: ${route.healthIntervalMin} мин
        Telegram chatId: ${route.chatId}
    """.trimIndent()

        binding.routeCreationDate.text = "Создан: ${route.getCreationDateFormatted()}"

        binding.buttonStart.setOnClickListener {
            startMonitoring(route.id)
            route.isActive = true
            updateRouteStatus()
        }

        binding.buttonStop.setOnClickListener {
            stopMonitoring(route.id)
            route.isActive = false
            updateRouteStatus()
        }

        binding.buttonEdit.setOnClickListener {
            editRoute()
        }

        binding.buttonDelete.setOnClickListener {
            deleteRoute()
        }

        updateRouteStatus()
    }

    private fun updateRouteStatus() {
        if (route.isActive) {
            binding.routeStatus.text = "● Активен"
            binding.routeStatus.setTextColor(getColor(R.color.success))
            binding.routeStatus.setBackgroundResource(R.drawable.bg_status_active)
        } else {
            binding.routeStatus.text = "○ Не активен"
            binding.routeStatus.setTextColor(getColor(R.color.inactive_status))
            binding.routeStatus.setBackgroundResource(R.drawable.bg_status_inactive)
        }
        binding.buttonStart.isEnabled = !route.isActive
        binding.buttonStop.isEnabled = route.isActive
    }

    private fun loadLogs() {
        binding.logTextView.text = route.logs.joinToString("\n")
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun appendLog(message: String) {
        runOnUiThread {
            route.logs.add(0, message) // Добавляем в начало списка
            if (route.logs.size > 100) {
                route.logs.removeLast() // Ограничиваем количество логов
            }

            binding.logTextView.text = route.logs.joinToString("\n")

            // Сохраняем обновленные логи
            val routes = MonitoringPreferenceManager.getRoutes(this).toMutableList()
            routes.replaceAll { if (it.id == route.id) route else it }
            MonitoringPreferenceManager.saveRoutes(this, routes)
        }
    }

    private fun startMonitoring(routeId: Long) {
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_START
            putExtra("route_id", routeId)
        }
        startService(intent)
    }

    private fun stopMonitoring(routeId: Long) {
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_STOP
            putExtra("route_id", routeId)
        }
        startService(intent)
    }

    private fun editRoute() {
        val intent = Intent(this, CreateMonitoringActivity::class.java).apply {
            putExtra("route_id", route.id)
        }
        startActivity(intent)
    }

    private fun deleteRoute() {
        stopMonitoring(route.id)

        val routes = MonitoringPreferenceManager.getRoutes(this)
            .filter { it.id != route.id }
        MonitoringPreferenceManager.saveRoutes(this, routes)

        finish()
    }

    override fun onResume() {
        super.onResume()
        val routes = MonitoringPreferenceManager.getRoutes(this)
        routes.find { it.id == route.id }?.let {
            route = it
            setupUI()
            loadLogs()
        }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(logReceiver, IntentFilter("LOG_UPDATE"))
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(logReceiver)
    }
}