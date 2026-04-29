package by.innowise.trainchecker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import by.innowise.trainchecker.databinding.ActivityMainBinding
import kotlinx.coroutines.launch


class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MonitoringRoutesAdapter
    private val logRepository by lazy { MonitoringLogRepository(this) }
    private val routes = mutableListOf<MonitoringRoute>()
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

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val routeId = intent?.getLongExtra("route_id", -1) ?: return
            val isActive = intent.getBooleanExtra("is_active", false)

            routes.find { it.id == routeId }?.isActive = isActive
            adapter.notifyDataSetChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        loadRoutes()
        handleOpenFromNotification()

        binding.buttonInstructions.setOnClickListener {
            startActivity(Intent(this, InstructionsActivity::class.java))
        }
        binding.buttonPassengerProfiles.setOnClickListener {
            startActivity(Intent(this, PassengerProfilesActivity::class.java))
        }
        binding.buttonAdd.setOnClickListener {
            startActivity(Intent(this, CreateMonitoringActivity::class.java))
        }

        binding.buttonStartAll.setOnClickListener {
            routes.forEach { route ->
                startMonitoring(route.id)
            }
            adapter.notifyDataSetChanged()
        }

        binding.buttonStopAll.setOnClickListener {
            routes.forEach { route ->
                stopMonitoring(route.id)
            }
            adapter.notifyDataSetChanged()
        }

        binding.buttonOpenWebsite.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://pass.rw.by/ru/"))
            startActivity(intent)
        }
    }

    private fun setupRecyclerView() {
        adapter = MonitoringRoutesAdapter(routes,
            onItemClick = { route ->
                showRouteDetails(route)
            },
            onStartClick = { routeId ->
                startMonitoring(routeId)
                adapter.notifyDataSetChanged()
            },
            onStopClick = { routeId ->
                stopMonitoring(routeId)
                adapter.notifyDataSetChanged()
            },
            onCopyClick = { routeId ->
                copyRoute(routeId)
            },
            onDeleteClick = { routeId ->
                deleteRoute(routeId)
            }
        )
        binding.routesRecyclerView.adapter = adapter
        binding.routesRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun loadRoutes() {
        routes.clear()
        routes.addAll(MonitoringPreferenceManager.getRoutes(this))
        adapter.notifyDataSetChanged()
    }

    private fun showRouteDetails(route: MonitoringRoute) {
        val intent = Intent(this, RouteDetailsActivity::class.java).apply {
            putExtra("route_id", route.id)
        }
        startActivity(intent)
    }

    private fun handleOpenFromNotification() {
        if (intent?.action != "OPEN_FROM_NOTIFICATION") return

        val activeRouteIds = MonitorService.getActiveRoutes(this)
        val activeRoute = routes.find { it.id == activeRouteIds.firstOrNull() }
        activeRoute?.let { showRouteDetails(it) }
    }

    private fun startMonitoring(routeId: Long) {
        if (!hasNotificationPermission()) {
            requestNotificationPermissionIfNeeded()
            return
        }

        val route = routes.find { it.id == routeId } ?: return
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_START
            putExtra("route_id", route.id)
        }
        startService(intent)
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

    private fun copyRoute(routeId: Long) {
        val sourceRoute = routes.find { it.id == routeId } ?: return
        val newRouteId = generateRouteId()
        val copiedRoute = sourceRoute.copy(
            id = newRouteId,
            name = "${sourceRoute.name} (копия)",
            isActive = false,
            createdAt = System.currentTimeMillis(),
            logs = mutableListOf()
        )

        if (RwPasswordManager.hasPassword(this, sourceRoute.id)) {
            RwPasswordManager.getPassword(this, sourceRoute.id)?.let { password ->
                RwPasswordManager.savePassword(this, newRouteId, password)
            }
        }

        val updatedRoutes = MonitoringPreferenceManager.getRoutes(this).toMutableList()
        updatedRoutes.add(copiedRoute)
        MonitoringPreferenceManager.saveRoutes(this, updatedRoutes)
        loadRoutes()
        Toast.makeText(this, "Маршрут скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun generateRouteId(): Long {
        val existingIds = MonitoringPreferenceManager.getRoutes(this).map { it.id }.toSet()
        var candidate = System.currentTimeMillis()
        while (candidate in existingIds) {
            candidate += 1
        }
        return candidate
    }

    private fun deleteRoute(routeId: Long) {
        stopMonitoring(routeId)
        RwPasswordManager.deletePassword(this, routeId)
        val updatedRoutes = routes.filter { it.id != routeId }
        MonitoringPreferenceManager.saveRoutes(this, updatedRoutes)
        lifecycleScope.launch {
            logRepository.deleteByRouteId(routeId)
        }
        loadRoutes()
        if (updatedRoutes.none { it.isActive }) {
            val stopIntent = Intent(this, MonitorService::class.java).apply {
                action = MonitorService.ACTION_STOP_ALL
            }
            startService(stopIntent)
        }
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(statusReceiver, IntentFilter("ROUTE_STATUS_UPDATE"))

        loadRoutes()
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(statusReceiver)
    }
}
