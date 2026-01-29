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
        Log.d(TAG, "listToolDefinitions() called with ${servers.size} servers")
        val allTools = mutableListOf<ToolDefinition>()
        for (server in servers) {
            Log.d(TAG, "Fetching tools from server: ${server.name}")
            try {
                val tools = server.listTools()
                Log.d(TAG, "Server ${server.name} returned ${tools.size} tools")
                tools.forEach { tool ->
                    allTools.add(
                        ToolDefinition(
                            name = McpToolAdapter.composeToolName(server.name, tool.name),
                            description = tool.description,
                            parameters = tool.inputSchema
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching tools from ${server.name}", e)
            }
        }
        Log.d(TAG, "listToolDefinitions() returning ${allTools.size} total tools")
        return allTools
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

/**
 * Wrapper that filters out disabled tools from an MCP server
 */
class FilteredMcpServer(
    private val delegate: McpServer,
    private val disabledTools: Set<String>
) : McpServer {
    override val name: String = delegate.name

    override fun listTools(): List<McpTool> {
        return delegate.listTools().filter { it.name !in disabledTools }
    }

    override fun callTool(name: String, arguments: JSONObject): McpToolResult {
        if (name in disabledTools) {
            return McpToolResult("Tool '$name' is disabled", isError = true)
        }
        return delegate.callTool(name, arguments)
    }
}

object McpServerFactory {
    fun createClient(context: Context, settings: SettingsManager): McpClient {
        val servers = mutableListOf<McpServer>()
        val configs = settings.getMcpServers()
        Log.d(TAG, "createClient: ${configs.size} MCP configs found, ${configs.count { it.enabled }} enabled")

        configs.filter { it.enabled }.forEach { config ->
            Log.d(TAG, "Loading MCP: ${config.name} type=${config.type} url=${config.baseUrl} authType=${config.authType}")
            val baseServer: McpServer? = when (config.type) {
                "local_filesystem" -> FileSystemMcpServer(context, config.serverName)
                "local_calendar" -> CalendarMcpServer(context, config.serverName)
                "local_notes" -> NotesMcpServer(context, config.serverName)
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
                    Log.d(TAG, "Added remote MCP: ${config.name}")
                    RemoteMcpServer(config, onConfigUpdate)
                }
                else -> {
                    Log.w(TAG, "MCP type not supported: ${config.type} (${config.name})")
                    null
                }
            }

            if (baseServer != null) {
                // Wrap with filtering if there are disabled tools
                val server = if (config.disabledTools.isNotEmpty()) {
                    Log.d(TAG, "MCP ${config.name}: ${config.disabledTools.size} tools disabled")
                    FilteredMcpServer(baseServer, config.disabledTools)
                } else {
                    baseServer
                }
                servers.add(server)
            }
        }
        Log.d(TAG, "McpClient created with ${servers.size} servers")
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

    // Executor for network operations to avoid NetworkOnMainThreadException
    private val networkExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    @Volatile
    private var cachedAccessToken: String? = null
    private var tokenExpiry: Long = 0

    // MCP Streamable HTTP session management
    @Volatile
    private var mcpSessionId: String? = null
    private var requestId = 1

    /**
     * Run a network operation on a background thread and return the result.
     * This avoids NetworkOnMainThreadException.
     */
    private fun <T> runOnNetwork(operation: () -> T): T {
        return try {
            networkExecutor.submit(operation).get(30, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * Initialize MCP session for Streamable HTTP transport.
     * Returns the session ID if successful, null otherwise.
     */
    private fun initializeSession(): String? {
        if (mcpSessionId != null) return mcpSessionId

        Log.d(tag, "Initializing MCP session for ${config.name}")
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", requestId++)
            put("method", "initialize")
            put("params", JSONObject().apply {
                put("protocolVersion", "2024-11-05")
                put("clientInfo", JSONObject().apply {
                    put("name", "AndroidAssistant")
                    put("version", "1.0")
                })
                put("capabilities", JSONObject())
            })
        }

        val request = Request.Builder()
            .url(config.baseUrl)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json, text/event-stream")
            .applyAuth()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                Log.d(tag, "MCP initialize response: code=${response.code}")

                if (!response.isSuccessful) {
                    Log.w(tag, "MCP initialize failed: ${response.code} - ${body.take(200)}")
                    return null
                }

                // Get session ID from header
                val sessionId = response.header("Mcp-Session-Id")
                if (sessionId != null) {
                    Log.d(tag, "MCP session established: $sessionId")
                    mcpSessionId = sessionId

                    // Send initialized notification
                    sendNotification("notifications/initialized", JSONObject())
                }

                sessionId
            }
        } catch (e: Exception) {
            Log.e(tag, "MCP initialize error", e)
            null
        }
    }

    private fun sendNotification(method: String, params: JSONObject) {
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }

        val requestBuilder = Request.Builder()
            .url(config.baseUrl)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json, text/event-stream")
            .applyAuth()

        mcpSessionId?.let { requestBuilder.header("Mcp-Session-Id", it) }

        try {
            client.newCall(requestBuilder.build()).execute().close()
        } catch (e: Exception) {
            Log.w(tag, "MCP notification failed: ${e.message}")
        }
    }

    override fun listTools(): List<McpTool> {
        Log.d(tag, "listTools() called for server: ${config.name} (${config.serverName}) url=${config.baseUrl} authType=${config.authType}")

        return try {
            runOnNetwork { listToolsInternal() }
        } catch (e: Exception) {
            Log.e(tag, "Remote MCP listTools failed: ${config.baseUrl}", e)
            emptyList()
        }
    }

    private fun listToolsInternal(): List<McpTool> {
        // Try with session initialization for Streamable HTTP servers
        val sessionId = initializeSession()
        val rpcResult = callJsonRpc("tools/list", JSONObject(), sessionId)
        if (rpcResult != null) {
            val tools = rpcResult.optJSONArray("tools") ?: JSONArray()
            Log.d(tag, "JSON-RPC tools/list returned ${tools.length()} tools for ${config.name}")
            return parseTools(tools)
        }

        // If session-based call failed, try without session (for simple HTTP servers)
        if (sessionId == null) {
            val directResult = callJsonRpc("tools/list", JSONObject(), null)
            if (directResult != null) {
                val tools = directResult.optJSONArray("tools") ?: JSONArray()
                Log.d(tag, "Direct JSON-RPC tools/list returned ${tools.length()} tools for ${config.name}")
                return parseTools(tools)
            }
        }

        Log.d(tag, "JSON-RPC failed for ${config.name}, trying REST fallback")

        val httpUrl = "${config.baseUrl.trimEnd('/')}/tools"
        val request = Request.Builder().url(httpUrl).get().applyAuth().build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                Log.d(tag, "REST /tools response: code=${response.code} body=${body.take(500)}")
                if (!response.isSuccessful) {
                    Log.w(tag, "REST /tools failed for ${config.name}: ${response.code}")
                    return emptyList()
                }
                val json = JSONObject(body)
                val tools = json.optJSONArray("tools") ?: JSONArray()
                Log.d(tag, "REST returned ${tools.length()} tools for ${config.name}")
                parseTools(tools)
            }
        } catch (e: Exception) {
            Log.e(tag, "Remote MCP listTools REST fallback failed: ${config.baseUrl}", e)
            emptyList()
        }
    }

    override fun callTool(name: String, arguments: JSONObject): McpToolResult {
        return try {
            runOnNetwork { callToolInternal(name, arguments) }
        } catch (e: Exception) {
            Log.e(tag, "Remote MCP callTool failed: ${config.baseUrl}", e)
            McpToolResult("Fallo de conexion MCP remoto: ${e.message}", true)
        }
    }

    private fun callToolInternal(name: String, arguments: JSONObject): McpToolResult {
        val rpcParams = JSONObject().apply {
            put("name", name)
            put("arguments", arguments)
        }

        // Try with existing session or initialize new one
        val sessionId = mcpSessionId ?: initializeSession()
        val rpcResult = callJsonRpc("tools/call", rpcParams, sessionId)
        if (rpcResult != null) {
            // Handle content array format
            val contentArray = rpcResult.optJSONArray("content")
            val content = if (contentArray != null && contentArray.length() > 0) {
                val firstContent = contentArray.getJSONObject(0)
                firstContent.optString("text", firstContent.toString())
            } else {
                rpcResult.optString("content", rpcResult.optString("text", ""))
            }
            val isError = rpcResult.optBoolean("isError", rpcResult.optBoolean("is_error", false))
            return McpToolResult(content = content, isError = isError)
        }

        // REST fallback
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
            Log.e(tag, "Remote MCP callTool REST fallback failed: ${config.baseUrl}", e)
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

    private fun callJsonRpc(method: String, params: JSONObject, sessionId: String? = null): JSONObject? {
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", requestId++)
            put("method", method)
            put("params", params)
        }
        Log.d(tag, "JSON-RPC request: ${config.baseUrl} method=$method sessionId=${sessionId?.take(8)}... authType=${config.authType}")
        if (config.authType == McpAuthType.CUSTOM_HEADERS) {
            Log.d(tag, "Custom headers: ${config.customHeaders.keys.joinToString()}")
        }

        val requestBuilder = Request.Builder()
            .url(config.baseUrl)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json, text/event-stream")
            .applyAuth()

        // Add session ID header for Streamable HTTP transport
        sessionId?.let { requestBuilder.header("Mcp-Session-Id", it) }

        val request = requestBuilder.build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                Log.d(tag, "JSON-RPC response: code=${response.code} contentType=${response.header("Content-Type")}")
                if (!response.isSuccessful) {
                    Log.w(tag, "JSON-RPC failed: ${response.code} for ${config.name}")
                    return null
                }

                // Parse SSE format if response is text/event-stream
                val jsonBody = if (body.contains("event:") || body.contains("data:")) {
                    parseSseResponse(body)
                } else {
                    body
                }

                Log.d(tag, "Parsed JSON body: ${jsonBody.take(300)}")
                val json = JSONObject(jsonBody)
                if (!json.has("result")) {
                    Log.w(tag, "JSON-RPC response missing 'result' field for ${config.name}")
                    return null
                }
                json.getJSONObject("result")
            }
        } catch (e: Exception) {
            Log.e(tag, "Remote MCP JSON-RPC failed: ${config.baseUrl}", e)
            null
        }
    }

    /**
     * Parse Server-Sent Events (SSE) format response and extract JSON data.
     * SSE format:
     *   id:session-id
     *   event:message
     *   data:{"jsonrpc":"2.0",...}
     */
    private fun parseSseResponse(sseBody: String): String {
        val dataLines = sseBody.lines()
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:") }

        // Concatenate all data lines (for multi-line JSON)
        return dataLines.joinToString("")
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
