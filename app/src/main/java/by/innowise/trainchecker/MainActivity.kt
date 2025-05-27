package by.innowise.trainchecker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import by.innowise.trainchecker.databinding.ActivityMainBinding


class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MonitoringRoutesAdapter
    private val routes = mutableListOf<MonitoringRoute>()

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

        if (intent?.action == "OPEN_FROM_NOTIFICATION") {
            val activeRouteIds = MonitorService.getActiveRoutes(this)
            if (activeRouteIds.isNotEmpty()) {
                val activeRoute = routes.find { it.id == activeRouteIds.first() }
                activeRoute?.let {
                    showRouteDetails(it)
                }
            }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        loadRoutes()

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

    private fun startMonitoring(routeId: Long) {
        val route = routes.find { it.id == routeId } ?: return
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_START
            putExtra("route_id", route.id)
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

    private fun deleteRoute(routeId: Long) {
        stopMonitoring(routeId)
        val updatedRoutes = routes.filter { it.id != routeId }
        MonitoringPreferenceManager.saveRoutes(this, updatedRoutes)
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