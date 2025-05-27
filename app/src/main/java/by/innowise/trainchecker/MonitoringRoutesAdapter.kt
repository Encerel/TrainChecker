package by.innowise.trainchecker

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import by.innowise.trainchecker.databinding.ItemRouteBinding


class MonitoringRoutesAdapter(
    private val routes: List<MonitoringRoute>,
    private val onItemClick: (MonitoringRoute) -> Unit,
    private val onStartClick: (Long) -> Unit,
    private val onStopClick: (Long) -> Unit,
    private val onDeleteClick: (Long) -> Unit
) : RecyclerView.Adapter<MonitoringRoutesAdapter.RouteViewHolder>() {

    inner class RouteViewHolder(val binding: ItemRouteBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteViewHolder {
        val binding = ItemRouteBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RouteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
        val route = routes[position]



        holder.binding.apply {
            val parts = route.name.split(", ")
            if (parts.size == 2) {
                routeName.text = parts[0] // Направление
                routeDate.text = parts[1] // Дата
            } else {
                routeName.text = route.name
                routeDate.text = ""
            }

            routeName.text = route.name
            routeStatus.text = if (route.isActive) "Активен" else "Не активен"

            buttonStart.isEnabled = !route.isActive
            buttonStop.isEnabled = route.isActive

            buttonStart.setOnClickListener { onStartClick(route.id) }
            buttonStop.setOnClickListener { onStopClick(route.id) }
            buttonDelete.setOnClickListener { onDeleteClick(route.id) }

            root.setOnClickListener { onItemClick(route) }
        }
    }

    override fun getItemCount() = routes.size
}