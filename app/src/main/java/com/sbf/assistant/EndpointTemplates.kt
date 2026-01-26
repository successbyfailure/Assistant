package com.sbf.assistant

data class EndpointTemplate(
    val name: String,
    val baseUrl: String,
    val type: String,
    val requiresApiKey: Boolean = true,
    val helpText: String? = null,
    val helpUrl: String? = null
)

object EndpointTemplates {
    val list = listOf(
        EndpointTemplate(
            name = "Ollama Cloud",
            baseUrl = "https://api.ollama.com/v1",
            type = "ollama_cloud",
            helpText = "Get API Key at ollama.com/signup",
            helpUrl = "https://ollama.com/signup"
        ),
        EndpointTemplate(
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            type = "openai",
            helpText = "Get API Key at platform.openai.com",
            helpUrl = "https://platform.openai.com/api-keys"
        ),
        EndpointTemplate(
            name = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            type = "openai",
            helpText = "Get API Key at console.groq.com",
            helpUrl = "https://console.groq.com/keys"
        ),
        EndpointTemplate(
            name = "Together.AI",
            baseUrl = "https://api.together.xyz/v1",
            type = "openai",
            helpText = "Get API Key at api.together.xyz",
            helpUrl = "https://api.together.xyz/settings/api-keys"
        ),
        EndpointTemplate(
            name = "Mistral AI",
            baseUrl = "https://api.mistral.ai/v1",
            type = "openai",
            helpText = "Get API Key at console.mistral.ai",
            helpUrl = "https://console.mistral.ai/api-keys/"
        ),
        EndpointTemplate(
            name = "LocalAI",
            baseUrl = "http://localhost:8080/v1",
            type = "openai",
            requiresApiKey = false,
            helpText = "LocalAI installation required"
        )
    )
}
