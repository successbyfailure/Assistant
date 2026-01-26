package com.sbf.assistant

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

class EndpointAdapter(
    private var endpoints: List<Endpoint>,
    private val onDelete: (Endpoint) -> Unit
) : RecyclerView.Adapter<EndpointAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_name)
        val tvUrl: TextView = view.findViewById(R.id.tv_url)
        val btnDelete: MaterialButton = view.findViewById(R.id.btn_delete)
        val statusIndicator: View = view.findViewById(R.id.view_status_indicator)
        val tvLatency: TextView = view.findViewById(R.id.tv_latency)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_endpoint, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val endpoint = endpoints[position]
        holder.tvName.text = endpoint.name
        holder.tvUrl.text = endpoint.baseUrl
        holder.btnDelete.setOnClickListener { onDelete(endpoint) }

        // Load health status
        val prefs = holder.itemView.context.getSharedPreferences("health_check", Context.MODE_PRIVATE)
        val statusJson = prefs.getString(endpoint.id, null)
        
        if (statusJson != null) {
            try {
                val json = JSONObject(statusJson)
                val online = json.getBoolean("online")
                val latency = json.getLong("latency")
                
                holder.statusIndicator.setBackgroundColor(if (online) Color.GREEN else Color.RED)
                holder.tvLatency.text = if (latency >= 0) "${latency}ms" else "Offline"
                holder.tvLatency.visibility = View.VISIBLE
            } catch (e: Exception) {
                holder.statusIndicator.setBackgroundColor(Color.GRAY)
                holder.tvLatency.visibility = View.GONE
            }
        } else {
            holder.statusIndicator.setBackgroundColor(Color.GRAY)
            holder.tvLatency.visibility = View.GONE
        }
    }

    override fun getItemCount() = endpoints.size

    fun updateData(newEndpoints: List<Endpoint>) {
        endpoints = newEndpoints
        notifyDataSetChanged()
    }
}

class CategoryAdapter(
    private val categories: List<Category>,
    private val settingsManager: SettingsManager,
    private val onEdit: (Category) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvCategoryName: TextView = view.findViewById(R.id.tv_category_name)
        val tvPrimary: TextView = view.findViewById(R.id.tv_primary)
        val tvBackup: TextView = view.findViewById(R.id.tv_backup)
        val btnEdit: Button = view.findViewById(R.id.btn_edit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val category = categories[position]
        val config = settingsManager.getCategoryConfig(category)
        
        holder.tvCategoryName.text = category.name
        
        val primaryText = config.primary?.let { 
            val endpoint = settingsManager.getEndpoint(it.endpointId)
            "${it.modelName} (${endpoint?.name ?: "Unknown"})"
        } ?: "Not configured"
        holder.tvPrimary.text = primaryText
        
        val backupText = config.backup?.let { 
            val endpoint = settingsManager.getEndpoint(it.endpointId)
            "${it.modelName} (${endpoint?.name ?: "Unknown"})"
        } ?: "Not configured"
        holder.tvBackup.text = backupText
        
        holder.btnEdit.setOnClickListener { onEdit(category) }
    }

    override fun getItemCount() = categories.size
}
