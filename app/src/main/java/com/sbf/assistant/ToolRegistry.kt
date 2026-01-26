package com.sbf.assistant

import org.json.JSONObject

class ToolRegistry(
    private val settings: SettingsManager,
    private val mcpClient: McpClient?
) {
    fun getTools(): List<ToolDefinition> {
        if (!settings.toolsEnabled) return emptyList()
        val tools = mutableListOf<ToolDefinition>()
        if (settings.toolAllowSms) {
            tools.add(basicTool(
                name = "send_sms",
                description = "Enviar un SMS a un numero de telefono.",
                properties = mapOf(
                    "number" to stringProp("Numero de telefono de destino."),
                    "message" to stringProp("Contenido del mensaje.")
                ),
                required = listOf("number", "message")
            ))
        }
        if (settings.toolAllowCall) {
            tools.add(basicTool(
                name = "make_call",
                description = "Realizar una llamada telefonica.",
                properties = mapOf(
                    "number" to stringProp("Numero de telefono a llamar.")
                ),
                required = listOf("number")
            ))
        }
        if (settings.toolAllowAlarm) {
            tools.add(basicTool(
                name = "set_alarm",
                description = "Configurar una alarma del sistema.",
                properties = mapOf(
                    "hour" to intProp("Hora en formato 24h."),
                    "minute" to intProp("Minutos."),
                    "label" to stringProp("Etiqueta opcional.")
                ),
                required = listOf("hour", "minute")
            ))
        }
        if (settings.toolAllowContacts) {
            tools.add(basicTool(
                name = "search_contacts",
                description = "Buscar contactos por nombre o telefono.",
                properties = mapOf(
                    "query" to stringProp("Texto para buscar en contactos.")
                ),
                required = listOf("query")
            ))
        }
        if (settings.toolAllowLocation) {
            tools.add(basicTool(
                name = "get_location",
                description = "Obtener la ubicacion aproximada actual.",
                properties = emptyMap(),
                required = emptyList()
            ))
        }
        if (settings.toolAllowOpenApp) {
            tools.add(basicTool(
                name = "open_app",
                description = "Abrir una app por su package name.",
                properties = mapOf(
                    "package" to stringProp("Package name de la app a abrir."),
                    "query" to stringProp("Nombre de la app a buscar (ej: WhatsApp).")
                ),
                required = emptyList()
            ))
        }
        if (settings.toolAllowWeather) {
            tools.add(basicTool(
                name = "get_weather",
                description = "Consultar el clima actual para una ubicacion.",
                properties = mapOf(
                    "location" to stringProp("Ciudad o ubicacion, si aplica.")
                ),
                required = emptyList()
            ))
        }

        if (settings.toolAllowNotifications) {
            tools.add(basicTool(
                name = "read_notifications",
                description = "Leer notificaciones recientes del dispositivo.",
                properties = mapOf(
                    "limit" to intProp("Cantidad maxima de notificaciones a devolver.")
                ),
                required = emptyList()
            ))
        }

        if (settings.mcpEnabled) {
            val mcpTools = mcpClient?.listToolDefinitions().orEmpty()
            tools.addAll(mcpTools)
        }
        return tools
    }

    private fun basicTool(
        name: String,
        description: String,
        properties: Map<String, JSONObject>,
        required: List<String>
    ): ToolDefinition {
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                properties.forEach { (key, value) -> put(key, value) }
            })
            if (required.isNotEmpty()) {
                put("required", org.json.JSONArray(required))
            }
        }
        return ToolDefinition(name, description, schema)
    }

    private fun stringProp(description: String): JSONObject = JSONObject().apply {
        put("type", "string")
        put("description", description)
    }

    private fun intProp(description: String): JSONObject = JSONObject().apply {
        put("type", "integer")
        put("description", description)
    }
}
