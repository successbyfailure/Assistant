package com.sbf.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HealthCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val settingsManager = SettingsManager(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val endpoints = settingsManager.getEndpoints()
        val statusMap = mutableMapOf<String, JSONObject>()

        for (endpoint in endpoints) {
            val startTime = System.currentTimeMillis()
            val status = JSONObject()
            try {
                val url = if (endpoint.baseUrl.endsWith("/")) "${endpoint.baseUrl}models"
                          else "${endpoint.baseUrl}/models"
                
                val requestBuilder = Request.Builder().url(url).get()
                if (endpoint.apiKey.isNotBlank()) {
                    requestBuilder.header("Authorization", "Bearer ${endpoint.apiKey}")
                }

                val response = client.newCall(requestBuilder.build()).execute()
                val latency = System.currentTimeMillis() - startTime
                
                status.put("online", response.isSuccessful)
                status.put("latency", latency)
                status.put("lastCheck", System.currentTimeMillis())
                status.put("errorCode", if (response.isSuccessful) 0 else response.code)

                if (!response.isSuccessful && endpoint.id == getActiveEndpointId()) {
                    showNotification(endpoint.name, "Endpoint is down (HTTP ${response.code})")
                }
            } catch (e: Exception) {
                status.put("online", false)
                status.put("latency", -1)
                status.put("lastCheck", System.currentTimeMillis())
                status.put("error", e.message)
                
                if (endpoint.id == getActiveEndpointId()) {
                    showNotification(endpoint.name, "Endpoint unreachable: ${e.message}")
                }
            }
            statusMap[endpoint.id] = status
        }

        saveStatus(statusMap)
        return Result.success()
    }

    private fun getActiveEndpointId(): String? {
        // Simple logic: get the endpoint ID used for AGENT category
        return settingsManager.getCategoryConfig(Category.AGENT).primary?.endpointId
    }

    private fun saveStatus(statusMap: Map<String, JSONObject>) {
        val prefs = applicationContext.getSharedPreferences("health_check", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        statusMap.forEach { (id, json) ->
            editor.putString(id, json.toString())
        }
        editor.apply()
    }

    private fun showNotification(name: String, message: String) {
        val channelId = "health_check"
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(channelId, "Health Check", NotificationManager.IMPORTANCE_DEFAULT)
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Endpoint Issue: $name")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(name.hashCode(), notification)
    }
}
