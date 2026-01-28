package com.sbf.assistant

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject
)

data class McpToolResult(
    val content: String,
    val isError: Boolean = false
)

interface McpServer {
    val name: String
    fun listTools(): List<McpTool>
    fun callTool(name: String, arguments: JSONObject): McpToolResult
}

class McpClient(private val servers: List<McpServer>) {
    fun listToolDefinitions(): List<ToolDefinition> {
        return servers.flatMap { server ->
            server.listTools().map { tool ->
                ToolDefinition(
                    name = McpToolAdapter.composeToolName(server.name, tool.name),
                    description = tool.description,
                    parameters = tool.inputSchema
                )
            }
        }
    }

    fun callTool(serverName: String, toolName: String, args: JSONObject): McpToolResult {
        val server = servers.firstOrNull { it.name == serverName }
            ?: return McpToolResult("Servidor MCP no encontrado: $serverName", true)
        Log.d(TAG, "MCP call: $serverName.$toolName args=$args")
        return server.callTool(toolName, args)
    }

    companion object {
        private const val TAG = "McpClient"
    }
}

object McpToolAdapter {
    data class ParsedName(val serverName: String, val toolName: String)

    fun composeToolName(serverName: String, toolName: String): String {
        return "mcp.$serverName.$toolName"
    }

    fun parseToolName(toolName: String): ParsedName? {
        val parts = toolName.split(".")
        if (parts.size < 3 || parts[0] != "mcp") return null
        return ParsedName(parts[1], parts.drop(2).joinToString("."))
    }
}

class FileSystemMcpServer(
    private val context: Context,
    override val name: String = "filesystem"
) : McpServer {

    override fun listTools(): List<McpTool> {
        return listOf(
            McpTool(
                name = "read_file",
                description = "Leer un archivo dentro del almacenamiento interno de la app.",
                inputSchema = fileSchema("Ruta relativa del archivo.")
            ),
            McpTool(
                name = "write_file",
                description = "Escribir un archivo en el almacenamiento interno de la app.",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", stringProp("Ruta relativa del archivo."))
                        put("content", stringProp("Contenido del archivo."))
                    })
                    put("required", org.json.JSONArray(listOf("path", "content")))
                }
            ),
            McpTool(
                name = "list_files",
                description = "Listar archivos dentro del almacenamiento interno.",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", stringProp("Ruta relativa del directorio. Opcional."))
                    })
                }
            )
        )
    }

    override fun callTool(name: String, arguments: JSONObject): McpToolResult {
        return when (name) {
            "read_file" -> readFile(arguments)
            "write_file" -> writeFile(arguments)
            "list_files" -> listFiles(arguments)
            else -> McpToolResult("Tool MCP desconocida: $name", true)
        }
    }

    private fun readFile(args: JSONObject): McpToolResult {
        val path = args.optString("path")
        if (path.isBlank()) return McpToolResult("Falta 'path'", true)
        val file = safeFile(path) ?: return McpToolResult("Ruta invalida: $path", true)
        if (!file.exists()) return McpToolResult("Archivo no encontrado: $path", true)
        return McpToolResult(file.readText())
    }

    private fun writeFile(args: JSONObject): McpToolResult {
        val path = args.optString("path")
        val content = args.optString("content")
        if (path.isBlank()) return McpToolResult("Falta 'path'", true)
        val file = safeFile(path) ?: return McpToolResult("Ruta invalida: $path", true)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return McpToolResult("Archivo escrito: ${file.name}")
    }

    private fun listFiles(args: JSONObject): McpToolResult {
        val path = args.optString("path")
        val dir = if (path.isBlank()) context.filesDir else safeFile(path)
            ?: return McpToolResult("Ruta invalida: $path", true)
        if (!dir.exists() || !dir.isDirectory) return McpToolResult("Directorio no encontrado: $path", true)
        val names = dir.listFiles()?.map { it.name }.orEmpty()
        return McpToolResult(names.joinToString(", "))
    }

    private fun safeFile(relativePath: String): File? {
        val base = context.filesDir
        val file = File(base, relativePath)
        val canonicalBase = base.canonicalPath
        val canonicalFile = file.canonicalPath
        return if (canonicalFile.startsWith(canonicalBase)) file else null
    }

    private fun fileSchema(pathDescription: String): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("path", stringProp(pathDescription))
            })
            put("required", listOf("path"))
        }
    }

    private fun stringProp(description: String): JSONObject = JSONObject().apply {
        put("type", "string")
        put("description", description)
    }
}

class CalendarMcpServer(
    private val context: Context,
    override val name: String = "calendar"
) : McpServer {

    override fun listTools(): List<McpTool> {
        return listOf(
            McpTool(
                name = "create_event",
                description = "Crear un evento de calendario.",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("title", stringProp("Titulo del evento."))
                        put("start", stringProp("Inicio ISO-8601 (ej: 2025-01-25T10:00:00)."))
                        put("end", stringProp("Fin ISO-8601 (opcional)."))
                        put("timezone", stringProp("Zona horaria (opcional, ej: Europe/Madrid)."))
                        put("description", stringProp("Descripcion opcional."))
                    })
                    put("required", JSONArray(listOf("title", "start")))
                }
            ),
            McpTool(
                name = "list_events",
                description = "Listar eventos entre fechas.",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("start", stringProp("Inicio ISO-8601."))
                        put("end", stringProp("Fin ISO-8601."))
                        put("limit", JSONObject().apply {
                            put("type", "integer")
                            put("description", "Maximo de eventos.")
                        })
                    })
                    put("required", JSONArray(listOf("start", "end")))
                }
            )
        )
    }

    override fun callTool(name: String, arguments: JSONObject): McpToolResult {
        return when (name) {
            "create_event" -> createEvent(arguments)
            "list_events" -> listEvents(arguments)
            else -> McpToolResult("Tool MCP desconocida: $name", true)
        }
    }

    private fun stringProp(description: String): JSONObject = JSONObject().apply {
        put("type", "string")
        put("description", description)
    }

    private fun createEvent(args: JSONObject): McpToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return McpToolResult("Permiso WRITE_CALENDAR no otorgado.", true)
        }

        val title = args.optString("title")
        val start = args.optString("start")
        val end = args.optString("end")
        val description = args.optString("description")
        val timezone = args.optString("timezone")

        if (title.isBlank() || start.isBlank()) {
            return McpToolResult("Faltan campos 'title' o 'start'.", true)
        }

        val startMillis = parseIsoToMillis(start, timezone) ?: return McpToolResult("Fecha 'start' invalida.", true)
        val endMillis = if (end.isBlank()) startMillis + 60 * 60 * 1000 else parseIsoToMillis(end, timezone)
        if (endMillis == null) {
            return McpToolResult("Fecha 'end' invalida.", true)
        }

        val calendarId = getPrimaryCalendarId() ?: return McpToolResult("No hay calendario disponible.", true)
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (timezone.isBlank()) TimeZone.getDefault().id else timezone)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        return if (uri != null) {
            McpToolResult("Evento creado: $title")
        } else {
            McpToolResult("No se pudo crear el evento.", true)
        }
    }

    private fun listEvents(args: JSONObject): McpToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return McpToolResult("Permiso READ_CALENDAR no otorgado.", true)
        }

        val start = args.optString("start")
        val end = args.optString("end")
        val limit = args.optInt("limit", 5)
        if (start.isBlank() || end.isBlank()) {
            return McpToolResult("Faltan campos 'start' o 'end'.", true)
        }
        val startMillis = parseIsoToMillis(start, "") ?: return McpToolResult("Fecha 'start' invalida.", true)
        val endMillis = parseIsoToMillis(end, "") ?: return McpToolResult("Fecha 'end' invalida.", true)

        val selection = "(${CalendarContract.Events.DTSTART} >= ?) AND (${CalendarContract.Events.DTSTART} <= ?)"
        val selectionArgs = arrayOf(startMillis.toString(), endMillis.toString())
        val results = mutableListOf<String>()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART),
            selection,
            selectionArgs,
            "${CalendarContract.Events.DTSTART} ASC"
        )?.use { cursor ->
            val titleIdx = cursor.getColumnIndex(CalendarContract.Events.TITLE)
            val startIdx = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
            if (titleIdx == -1 || startIdx == -1) {
                Log.e("CalendarMcpServer", "Invalid cursor columns: titleIdx=$titleIdx startIdx=$startIdx")
                return@use
            }
            while (cursor.moveToNext() && results.size < limit) {
                val title = cursor.getString(titleIdx)
                val startTime = cursor.getLong(startIdx)
                results.add("$title (${startTime})")
            }
        }
        return if (results.isEmpty()) {
            McpToolResult("No se encontraron eventos.")
        } else {
            McpToolResult(results.joinToString("\n"))
        }
    }

    private fun parseIsoToMillis(value: String, timezone: String): Long? {
        return try {
            val tz = if (timezone.isBlank()) TimeZone.getDefault() else TimeZone.getTimeZone(timezone)
            val formats = listOf(
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm"
            )
            for (pattern in formats) {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply { timeZone = tz }
                val date = sdf.parse(value)
                if (date != null) return date.time
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun getPrimaryCalendarId(): Long? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1"
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            null,
            null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(CalendarContract.Calendars._ID)
            if (idIdx == -1) {
                Log.e("CalendarMcpServer", "Invalid cursor column: idIdx=-1")
                return null
            }
            if (cursor.moveToFirst()) {
                return cursor.getLong(idIdx)
            }
        }
        return null
    }
}

class NotesMcpServer(
    private val context: Context,
    override val name: String = "notes"
) : McpServer {
    private val notesFile = File(context.filesDir, "notes.txt")

    override fun listTools(): List<McpTool> {
        return listOf(
            McpTool(
                name = "add_note",
                description = "Guardar una nota local simple.",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("text", stringProp("Contenido de la nota."))
                    })
                    put("required", org.json.JSONArray(listOf("text")))
                }
            ),
            McpTool(
                name = "list_notes",
                description = "Listar notas guardadas.",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                }
            )
        )
    }

    override fun callTool(name: String, arguments: JSONObject): McpToolResult {
        return when (name) {
            "add_note" -> addNote(arguments)
            "list_notes" -> listNotes()
            else -> McpToolResult("Tool MCP desconocida: $name", true)
        }
    }

    private fun addNote(args: JSONObject): McpToolResult {
        val text = args.optString("text")
        if (text.isBlank()) return McpToolResult("Falta 'text'", true)
        notesFile.appendText(text.trim() + "\n")
        return McpToolResult("Nota guardada.")
    }

    private fun listNotes(): McpToolResult {
        if (!notesFile.exists()) return McpToolResult("Sin notas.")
        return McpToolResult(notesFile.readText().trim())
    }

    private fun stringProp(description: String): JSONObject = JSONObject().apply {
        put("type", "string")
        put("description", description)
    }
}

object McpServerFactory {
    fun createClient(context: Context, settings: SettingsManager): McpClient {
        val servers = mutableListOf<McpServer>()
        val configs = settings.getMcpServers()
        configs.filter { it.enabled }.forEach { config ->
            when (config.type) {
                "local_filesystem" -> servers.add(FileSystemMcpServer(context, config.serverName))
                "local_calendar" -> servers.add(CalendarMcpServer(context, config.serverName))
                "local_notes" -> servers.add(NotesMcpServer(context, config.serverName))
                "remote_http" -> {
                    val onConfigUpdate: (McpServerConfig) -> Unit = { updatedConfig ->
                        // Persist updated OAuth tokens
                        val allConfigs = settings.getMcpServers().toMutableList()
                        val index = allConfigs.indexOfFirst { it.id == updatedConfig.id }
                        if (index >= 0) {
                            allConfigs[index] = updatedConfig
                            settings.saveMcpServers(allConfigs)
                        }
                    }
                    servers.add(RemoteMcpServer(config, onConfigUpdate))
                }
                else -> Log.w(TAG, "MCP type not supported: ${config.type} (${config.name})")
            }
        }
        return McpClient(servers)
    }

    private const val TAG = "McpServerFactory"
}

class RemoteMcpServer(
    private var config: McpServerConfig,
    private val onConfigUpdate: ((McpServerConfig) -> Unit)? = null
) : McpServer {
    override val name: String = config.serverName
    private val client = HttpClientProvider.default
    private val tag = "RemoteMcpServer"

    @Volatile
    private var cachedAccessToken: String? = null
    private var tokenExpiry: Long = 0

    override fun listTools(): List<McpTool> {
        val rpcResult = callJsonRpc("tools/list", JSONObject())
        if (rpcResult != null) {
            val tools = rpcResult.optJSONArray("tools") ?: JSONArray()
            return parseTools(tools)
        }
        val httpUrl = "${config.baseUrl.trimEnd('/')}/tools"
        val request = Request.Builder().url(httpUrl).get().applyAuth().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return emptyList()
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val tools = json.optJSONArray("tools") ?: JSONArray()
                parseTools(tools)
            }
        } catch (e: Exception) {
            Log.e(tag, "Remote MCP listTools failed: ${config.baseUrl}", e)
            emptyList()
        }
    }

    override fun callTool(name: String, arguments: JSONObject): McpToolResult {
        val rpcParams = JSONObject().apply {
            put("name", name)
            put("arguments", arguments)
        }
        val rpcResult = callJsonRpc("tools/call", rpcParams)
        if (rpcResult != null) {
            val content = rpcResult.optString("content", rpcResult.optString("text", ""))
            val isError = rpcResult.optBoolean("is_error", false)
            return McpToolResult(content = content, isError = isError)
        }
        val httpUrl = "${config.baseUrl.trimEnd('/')}/tool"
        val json = JSONObject().apply {
            put("name", name)
            put("arguments", arguments)
        }
        val request = Request.Builder()
            .url(httpUrl)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .applyAuth()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return McpToolResult("MCP error ${response.code}: $body", true)
                }
                val result = JSONObject(body)
                McpToolResult(
                    content = result.optString("content", ""),
                    isError = result.optBoolean("is_error", false)
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Remote MCP callTool failed: ${config.baseUrl}", e)
            McpToolResult("Fallo de conexion MCP remoto.", true)
        }
    }

    private fun parseTools(tools: JSONArray): List<McpTool> {
        val list = mutableListOf<McpTool>()
        for (i in 0 until tools.length()) {
            val tool = tools.getJSONObject(i)
            list.add(
                McpTool(
                    name = tool.getString("name"),
                    description = tool.optString("description", ""),
                    inputSchema = tool.optJSONObject("input_schema") ?: JSONObject().put("type", "object")
                )
            )
        }
        return list
    }

    private fun callJsonRpc(method: String, params: JSONObject): JSONObject? {
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", method.replace("/", "_"))
            put("method", method)
            put("params", params)
        }
        val request = Request.Builder()
            .url(config.baseUrl)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .applyAuth()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return null
                val json = JSONObject(body)
                if (!json.has("result")) return null
                json.getJSONObject("result")
            }
        } catch (e: Exception) {
            Log.e(tag, "Remote MCP JSON-RPC failed: ${config.baseUrl}", e)
            null
        }
    }

    private fun Request.Builder.applyAuth(): Request.Builder {
        when (config.authType) {
            McpAuthType.NONE -> {
                // No authentication
            }
            McpAuthType.BEARER -> {
                if (config.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${config.apiKey}")
                }
            }
            McpAuthType.CUSTOM_HEADERS -> {
                config.customHeaders.forEach { (key, value) ->
                    header(key, value)
                }
            }
            McpAuthType.OAUTH -> {
                val token = getOAuthAccessToken()
                if (token != null) {
                    header("Authorization", "Bearer $token")
                }
            }
        }
        return this
    }

    private fun getOAuthAccessToken(): String? {
        // Check if we have a valid cached token
        if (cachedAccessToken != null && System.currentTimeMillis() < tokenExpiry - 60_000) {
            return cachedAccessToken
        }

        // Check if stored token is still valid
        if (config.oauthAccessToken.isNotBlank() && System.currentTimeMillis() < config.oauthTokenExpiry - 60_000) {
            cachedAccessToken = config.oauthAccessToken
            tokenExpiry = config.oauthTokenExpiry
            return cachedAccessToken
        }

        // Try to refresh token
        if (config.oauthRefreshToken.isNotBlank()) {
            return refreshOAuthToken()
        }

        // Get new token with client credentials
        return fetchNewOAuthToken()
    }

    private fun refreshOAuthToken(): String? {
        if (config.oauthTokenUrl.isBlank()) return null

        val formBody = okhttp3.FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", config.oauthRefreshToken)
            .add("client_id", config.oauthClientId)
            .add("client_secret", config.oauthClientSecret)
            .build()

        return executeOAuthRequest(formBody)
    }

    private fun fetchNewOAuthToken(): String? {
        if (config.oauthTokenUrl.isBlank() || config.oauthClientId.isBlank()) return null

        val formBody = okhttp3.FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", config.oauthClientId)
            .add("client_secret", config.oauthClientSecret)
            .apply {
                if (config.oauthScope.isNotBlank()) {
                    add("scope", config.oauthScope)
                }
            }
            .build()

        return executeOAuthRequest(formBody)
    }

    private fun executeOAuthRequest(formBody: okhttp3.FormBody): String? {
        val request = Request.Builder()
            .url(config.oauthTokenUrl)
            .post(formBody)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(tag, "OAuth token request failed: ${response.code}")
                    return null
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val accessToken = json.optString("access_token")
                val expiresIn = json.optLong("expires_in", 3600)
                val refreshToken = json.optString("refresh_token", config.oauthRefreshToken)

                if (accessToken.isBlank()) return null

                cachedAccessToken = accessToken
                tokenExpiry = System.currentTimeMillis() + (expiresIn * 1000)

                // Update config with new tokens
                val updatedConfig = config.copy(
                    oauthAccessToken = accessToken,
                    oauthRefreshToken = refreshToken,
                    oauthTokenExpiry = tokenExpiry
                )
                config = updatedConfig
                onConfigUpdate?.invoke(updatedConfig)

                accessToken
            }
        } catch (e: Exception) {
            Log.e(tag, "OAuth token request error", e)
            null
        }
    }
}
