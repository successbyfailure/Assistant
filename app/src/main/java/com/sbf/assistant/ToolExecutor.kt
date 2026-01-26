package com.sbf.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.content.ActivityNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.provider.Settings
import android.content.ComponentName

class ToolExecutor(
    private val context: Context,
    private val mcpClient: McpClient?
) {
    fun execute(call: ToolCall): ToolResult {
        val args = parseArgs(call)
        Log.d(TAG, "Tool call: ${call.name} args=$args id=${call.id}")

        if (call.name.startsWith("mcp.")) {
            return executeMcp(call, args)
        }

        return try {
            when (call.name) {
                "send_sms" -> sendSms(call, args)
                "make_call" -> makeCall(call, args)
                "set_alarm" -> setAlarm(call, args)
                "search_contacts" -> searchContacts(call, args)
                "get_location" -> getLocation(call)
                "open_app" -> openApp(call, args)
                "get_weather" -> getWeather(call, args)
                "read_notifications" -> readNotifications(call, args)
                else -> ToolResult(call.id, call.name, "Tool no reconocida: ${call.name}", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool error: ${call.name}", e)
            ToolResult(call.id, call.name, "Error ejecutando ${call.name}: ${e.message}", true)
        }
    }

    private fun parseArgs(call: ToolCall): JSONObject {
        return try {
            if (call.arguments.isBlank()) JSONObject() else JSONObject(call.arguments)
        } catch (e: Exception) {
            Log.e(TAG, "Argumentos invalidos para ${call.name}", e)
            JSONObject()
        }
    }

    private fun sendSms(call: ToolCall, args: JSONObject): ToolResult {
        val number = args.optString("number")
        val message = args.optString("message")
        if (number.isBlank() || message.isBlank()) {
            return ToolResult(call.id, call.name, "Faltan campos 'number' o 'message'", true)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return ToolResult(call.id, call.name, "Permiso SEND_SMS no otorgado.", true)
        }
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$number")
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (startActivity(intent)) {
            ToolResult(call.id, call.name, "SMS abierto en la app de mensajes.")
        } else {
            ToolResult(call.id, call.name, "No se pudo abrir la app de SMS.", true)
        }
    }

    private fun makeCall(call: ToolCall, args: JSONObject): ToolResult {
        val number = args.optString("number")
        if (number.isBlank()) {
            return ToolResult(call.id, call.name, "Falta campo 'number'", true)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return ToolResult(call.id, call.name, "Permiso CALL_PHONE no otorgado.", true)
        }
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$number")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (startActivity(intent)) {
            ToolResult(call.id, call.name, "Marcador abierto.")
        } else {
            ToolResult(call.id, call.name, "No se pudo abrir el marcador.", true)
        }
    }

    private fun setAlarm(call: ToolCall, args: JSONObject): ToolResult {
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", -1)
        if (hour < 0 || minute < 0) {
            return ToolResult(call.id, call.name, "Faltan campos 'hour' o 'minute'", true)
        }
        val label = args.optString("label")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (label.isNotBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (startActivity(intent)) {
            ToolResult(call.id, call.name, "Intent de alarma lanzado.")
        } else {
            ToolResult(call.id, call.name, "No se pudo abrir la app de reloj.", true)
        }
    }

    private fun searchContacts(call: ToolCall, args: JSONObject): ToolResult {
        val query = args.optString("query")
        if (query.isBlank()) {
            return ToolResult(call.id, call.name, "Falta campo 'query'", true)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return ToolResult(call.id, call.name, "Permiso READ_CONTACTS no otorgado.", true)
        }
        val results = mutableListOf<String>()
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        val argsSelection = arrayOf("%$query%", "%$query%")
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            selection,
            argsSelection,
            null
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (nameIdx == -1 || numberIdx == -1) {
                Log.e(TAG, "Invalid cursor columns: nameIdx=$nameIdx numberIdx=$numberIdx")
                return@use
            }
            while (cursor.moveToNext() && results.size < 5) {
                val name = cursor.getString(nameIdx)
                val number = cursor.getString(numberIdx)
                results.add("$name: $number")
            }
        }
        return if (results.isEmpty()) {
            ToolResult(call.id, call.name, "No se encontraron contactos.")
        } else {
            ToolResult(call.id, call.name, "Resultados: ${results.joinToString("; ")}")
        }
    }

    private fun getLocation(call: ToolCall): ToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return ToolResult(call.id, call.name, "Permiso ACCESS_FINE_LOCATION no otorgado.", true)
        }
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = locationManager.getProviders(true)
        val location = providers.asSequence()
            .mapNotNull { locationManager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
        return if (location == null) {
            ToolResult(call.id, call.name, "Ubicacion no disponible.", true)
        } else {
            ToolResult(call.id, call.name, "Lat ${location.latitude}, Lng ${location.longitude}")
        }
    }

    private fun openApp(call: ToolCall, args: JSONObject): ToolResult {
        val packageName = args.optString("package")
        val query = args.optString("query")
        val resolvedPackage = when {
            packageName.isNotBlank() -> packageName
            query.isNotBlank() -> findAppPackage(query)
            else -> ""
        }
        if (resolvedPackage.isBlank()) {
            return ToolResult(call.id, call.name, "No se encontro la app solicitada.", true)
        }
        val intent = context.packageManager.getLaunchIntentForPackage(resolvedPackage)
            ?: return ToolResult(call.id, call.name, "App no encontrada: $resolvedPackage", true)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (startActivity(intent)) {
            ToolResult(call.id, call.name, "App abierta: $resolvedPackage")
        } else {
            ToolResult(call.id, call.name, "No se pudo abrir la app: $resolvedPackage", true)
        }
    }

    private fun getWeather(call: ToolCall, args: JSONObject): ToolResult {
        val location = args.optString("location")
        val query = if (location.isBlank()) "clima" else "clima $location"
        val url = "https://www.google.com/search?q=" + Uri.encode(query)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (startActivity(intent)) {
            val label = if (location.isBlank()) "ubicacion actual" else location
            ToolResult(call.id, call.name, "Abriendo clima para $label.")
        } else {
            ToolResult(call.id, call.name, "No se pudo abrir el navegador.", true)
        }
    }

    private fun readNotifications(call: ToolCall, args: JSONObject): ToolResult {
        val limit = args.optInt("limit", 5)
        if (!hasNotificationAccess()) {
            openNotificationAccessSettings()
            return ToolResult(
                call.id,
                call.name,
                "Habilita el acceso a notificaciones para Assistant en Ajustes.",
                true
            )
        }
        val entries = NotificationStore.getRecent(limit)
        if (entries.isEmpty()) {
            return ToolResult(call.id, call.name, "No hay notificaciones recientes.")
        }
        val formatted = entries.joinToString("\n") { entry ->
            val title = if (entry.title.isNotBlank()) entry.title else entry.packageName
            val text = if (entry.text.isNotBlank()) " - ${entry.text}" else ""
            "$title$text"
        }
        return ToolResult(call.id, call.name, formatted)
    }

    private fun executeMcp(call: ToolCall, args: JSONObject): ToolResult {
        val mcpClient = mcpClient ?: return ToolResult(call.id, call.name, "MCP no configurado.", true)
        val parsed = McpToolAdapter.parseToolName(call.name)
            ?: return ToolResult(call.id, call.name, "Formato MCP invalido.", true)
        val result = mcpClient.callTool(parsed.serverName, parsed.toolName, args)
        return ToolResult(call.id, call.name, result.content, result.isError)
    }

    private fun startActivity(intent: Intent): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try {
                context.startActivity(intent)
                true
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "Activity not found for intent", e)
                false
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception for intent", e)
                false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start activity", e)
                false
            }
        }

        val latch = CountDownLatch(1)
        val success = AtomicBoolean(false)
        Handler(Looper.getMainLooper()).post {
            try {
                context.startActivity(intent)
                success.set(true)
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "Activity not found for intent", e)
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception for intent", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start activity", e)
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
        return success.get()
    }

    private fun findAppPackage(query: String): String {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val lowerQuery = query.trim().lowercase()
        var bestMatch: String? = null
        var bestScore = 0

        for (activity in activities) {
            val label = activity.loadLabel(pm)?.toString()?.lowercase().orEmpty()
            if (label.isBlank()) continue
            val score = when {
                label == lowerQuery -> 3
                label.startsWith(lowerQuery) -> 2
                label.contains(lowerQuery) -> 1
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                bestMatch = activity.activityInfo.packageName
            }
        }
        return bestMatch.orEmpty()
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val component = ComponentName(context, NotificationStore::class.java).flattenToString()
        return enabled.contains(component)
    }

    private fun openNotificationAccessSettings() {
        val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    companion object {
        private const val TAG = "ToolExecutor"
    }
}
