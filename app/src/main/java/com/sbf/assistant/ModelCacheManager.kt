package com.sbf.assistant

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class ModelCacheManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("model_cache", Context.MODE_PRIVATE)

    fun getModels(endpointId: String): List<String>? {
        val lastUpdate = prefs.getLong("${endpointId}_time", 0)
        val currentTime = System.currentTimeMillis()
        
        // TTL de 1 hora
        if (currentTime - lastUpdate > TimeUnit.HOURS.toMillis(1)) {
            return null
        }

        val json = prefs.getString("${endpointId}_list", null) ?: return null
        return try {
            val array = JSONArray(json)
            List(array.length()) { array.getString(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun saveModels(endpointId: String, models: List<String>) {
        val array = JSONArray(models)
        prefs.edit()
            .putString("${endpointId}_list", array.toString())
            .putLong("${endpointId}_time", System.currentTimeMillis())
            .apply()
    }

    fun clearCache(endpointId: String) {
        prefs.edit()
            .remove("${endpointId}_list")
            .remove("${endpointId}_time")
            .apply()
    }
}
