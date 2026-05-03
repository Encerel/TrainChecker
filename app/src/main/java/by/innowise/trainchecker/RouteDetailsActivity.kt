package by.innowise.trainchecker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import by.innowise.trainchecker.databinding.ActivityRouteDetailsBinding
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter


class RouteDetailsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRouteDetailsBinding
    private lateinit var route: MonitoringRoute
    private lateinit var logRepository: MonitoringLogRepository
    private var savedLogEntries: List<MonitoringLogEntry> = emptyList()
    private var fallbackLogLines: List<String> = emptyList()
    private val liveLogEntries = mutableListOf<MonitoringLogEntry>()
    private var logQuery: String = ""
    private var logsNewestFirst: Boolean = true
    private val logTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(
                this,
                "Разрешите уведомления, чтобы видеть активный мониторинг",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val routeId = intent?.getLongExtra("route_id", -1) ?: return
            if (routeId == route.id) {
                val isImportant = intent.getBooleanExtra("important", false)
                if (isImportant) {
                    loadLogs()
                } else {
                    val msg = intent.getStringExtra("log_message") ?: return
                    val rawMessage = intent.getStringExtra("raw_message") ?: msg
                    val timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())
                    val level = intent.getStringExtra("level").toLogLevel()
                    val category = intent.getStringExtra("category").toLogCategory()
                    appendLiveLog(
                        MonitoringLogEntry(
                            routeId = route.id,
                            timestamp = timestamp,
                            level = level,
                            category = category,
                            message = rawMessage,
                            important = false
                        )
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        binding = ActivityRouteDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        logRepository = MonitoringLogRepository(this)

        val routeId = intent.getLongExtra("route_id", -1)
        route = MonitoringPreferenceManager.getRoutes(this)
            .find { it.id == routeId } ?: run {
            finish()
            return
        }

        setupUI()
        setupLogSearch()
        setupLogSort()
        setupLogFilters()
        setupLogScroll()
        loadLogs()
    }

    private fun setupUI() {
        binding.routeName.text = route.name
        binding.routeUrl.text = route.url

        val autoPurchaseInfo = if (route.autoPurchaseEnabled) {
            val trains = route.trainNumbersFormatted.ifBlank { route.trainNumber }
            val dryRun = if (route.autoPurchaseDryRun) ", dry-run" else ""
            "\n🛒 Авторезерв: ВКЛ (поезда $trains, классы ${route.serviceClassesFormatted}$dryRun)"
        } else {
            "\n🛒 Авторезерв: ВЫКЛ"
        }

        binding.routeParameters.text = """
        Интервал проверки: ${route.checkIntervalSec} сек
        Интервал healthcheck: ${route.healthIntervalMin} мин
        Telegram chatId: ${route.chatId}$autoPurchaseInfo
    """.trimIndent()

        binding.routeCreationDate.text = "Создан: ${route.getCreationDateFormatted()}"

        binding.buttonStart.setOnClickListener {
            if (startMonitoring(route.id)) {
                route.isActive = true
                updateRouteStatus()
            }
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

        binding.buttonCopyLogs.setOnClickListener {
            copyVisibleLogs()
        }

        updateRouteStatus()
    }

    private fun copyVisibleLogs() {
        val text = binding.logTextView.text?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "Логи пустые", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TrainChecker logs", text))
        Toast.makeText(this, "Логи скопированы", Toast.LENGTH_SHORT).show()
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
        lifecycleScope.launch {
            val logs = logRepository.getRecentLogs(
                routeId = route.id,
                query = logQuery,
                levels = selectedLogLevels(),
                categories = selectedLogCategories()
            )
            savedLogEntries = logs
            fallbackLogLines = if (
                logs.isEmpty() &&
                logQuery.isBlank() &&
                allLogFiltersSelected() &&
                route.logs.isNotEmpty()
            ) {
                route.logs
            } else {
                emptyList()
            }
            renderLogs()
        }
    }

    private fun setupLogSearch() {
        binding.editLogSearch.doAfterTextChanged { text ->
            logQuery = text?.toString().orEmpty().trim()
            loadLogs()
        }
    }

    private fun setupLogSort() {
        binding.radioLogSortGroup.setOnCheckedChangeListener { _, checkedId ->
            logsNewestFirst = checkedId == binding.radioLogsNewestFirst.id
            renderLogs(resetScroll = true)
        }
    }

    private fun setupLogFilters() {
        val checkBoxes = listOf(
            binding.checkLogInfo,
            binding.checkLogSuccess,
            binding.checkLogWarning,
            binding.checkLogError,
            binding.checkLogMonitoring,
            binding.checkLogAvailability,
            binding.checkLogAutoPurchase,
            binding.checkLogTelegram,
            binding.checkLogSystem
        )

        checkBoxes.forEach { checkBox ->
            checkBox.setOnCheckedChangeListener { _, _ ->
                loadLogs()
            }
        }
    }

    private fun setupLogScroll() {
        binding.logScrollView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> view.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun appendLiveLog(log: MonitoringLogEntry) {
        runOnUiThread {
            liveLogEntries.add(0, log)
            while (liveLogEntries.size > 200) {
                liveLogEntries.removeLast()
            }
            renderLogs()
        }
    }

    private fun renderLogs(resetScroll: Boolean = false) {
        val levels = selectedLogLevels()
        val categories = selectedLogCategories()
        val matchingLiveEntries = liveLogEntries
            .filter { log ->
                log.level in levels &&
                    log.category in categories &&
                    (logQuery.isBlank() || log.message.contains(logQuery, ignoreCase = true))
            }

        val sortedEntries = (matchingLiveEntries + savedLogEntries)
            .let { logs ->
                if (logsNewestFirst) {
                    logs.sortedByDescending { it.timestamp }
                } else {
                    logs.sortedBy { it.timestamp }
                }
            }

        val sortedFallbackLines = if (logsNewestFirst) {
            fallbackLogLines
        } else {
            fallbackLogLines.asReversed()
        }

        val lines = sortedEntries.map { it.toDisplayLine() } + sortedFallbackLines
        binding.logTextView.text = if (lines.isEmpty()) {
            "За последние 24 часа сохраненных логов нет"
        } else {
            lines.joinToString("\n")
        }

        if (resetScroll) {
            binding.logScrollView.post {
                binding.logScrollView.scrollTo(0, 0)
            }
        }
    }

    private fun MonitoringLogEntry.toDisplayLine(): String {
        val time = Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
            .format(logTimeFormatter)
        return "$time [${level.title}] [${category.title}]: $message"
    }

    private fun selectedLogLevels(): Set<MonitoringLogLevel> {
        return buildSet {
            if (binding.checkLogInfo.isChecked) add(MonitoringLogLevel.INFO)
            if (binding.checkLogSuccess.isChecked) add(MonitoringLogLevel.SUCCESS)
            if (binding.checkLogWarning.isChecked) add(MonitoringLogLevel.WARNING)
            if (binding.checkLogError.isChecked) add(MonitoringLogLevel.ERROR)
        }
    }

    private fun selectedLogCategories(): Set<MonitoringLogCategory> {
        return buildSet {
            if (binding.checkLogMonitoring.isChecked) add(MonitoringLogCategory.MONITORING)
            if (binding.checkLogAvailability.isChecked) add(MonitoringLogCategory.AVAILABILITY)
            if (binding.checkLogAutoPurchase.isChecked) add(MonitoringLogCategory.AUTO_PURCHASE)
            if (binding.checkLogTelegram.isChecked) add(MonitoringLogCategory.TELEGRAM)
            if (binding.checkLogSystem.isChecked) add(MonitoringLogCategory.SYSTEM)
        }
    }

    private fun allLogFiltersSelected(): Boolean {
        return selectedLogLevels().size == MonitoringLogLevel.entries.size &&
            selectedLogCategories().size == MonitoringLogCategory.entries.size
    }

    private fun String?.toLogLevel(): MonitoringLogLevel {
        return runCatching {
            MonitoringLogLevel.valueOf(this ?: MonitoringLogLevel.INFO.name)
        }.getOrDefault(MonitoringLogLevel.INFO)
    }

    private fun String?.toLogCategory(): MonitoringLogCategory {
        return runCatching {
            MonitoringLogCategory.valueOf(this ?: MonitoringLogCategory.SYSTEM.name)
        }.getOrDefault(MonitoringLogCategory.SYSTEM)
    }

    private fun startMonitoring(routeId: Long): Boolean {
        if (!hasNotificationPermission()) {
            requestNotificationPermissionIfNeeded()
            return false
        }

        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_START
            putExtra("route_id", routeId)
        }
        startService(intent)
        return true
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
        RwPasswordManager.deletePassword(this, route.id)

        val routes = MonitoringPreferenceManager.getRoutes(this)
            .filter { it.id != route.id }
        MonitoringPreferenceManager.saveRoutes(this, routes)
        lifecycleScope.launch {
            logRepository.deleteByRouteId(route.id)
        }

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
