package com.sbf.assistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.json.JSONObject

class ModelDownloadManager(private val context: Context) {
    private val client = HttpClientProvider.download

    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    data class ModelInfo(
        val name: String,
        val url: String,
        val filename: String,
        val sizeBytes: Long,
        val type: String,  // "tflite", "litertlm", "task"
        val category: String,  // "STT", "LLM-Multimodal", "LLM-Text", "Function-Calling"
        val description: String,
        val gated: Boolean = false,
        val termsUrl: String? = null
    )

    data class ModelCategory(val name: String, val models: List<ModelInfo>)

    data class RepoScanResult(val models: List<ModelInfo>, val errors: List<String>)

    data class RepoModelDetails(
        val name: String,
        val filename: String,
        val url: String,
        val sizeBytes: Long,
        val type: String,
        val category: String,
        val description: String,
        val compatible: Boolean,
        val missingReason: String?
    )

    data class RepoScanDetails(
        val repoId: String,
        val gated: Boolean,
        val models: List<RepoModelDetails>
    )

    private data class RepoSibling(val filename: String, val sizeBytes: Long)

    private data class RepoInfo(val repoId: String, val gated: Boolean, val siblings: List<RepoSibling>)

    companion object {
        private const val TAG = "ModelDownloadManager"
        
        // ========== WHISPER STT MODELS ==========
        private val WHISPER_MODELS = listOf(
            // Tiny Models (Fastest)
            ModelInfo(
                name = "Whisper Tiny - Multilingual",
                url = "https://huggingface.co/DocWolle/whisper_tflite_models/resolve/main/whisper-tiny-transcribe-translate.tflite",
                filename = "whisper-tiny-transcribe-translate.tflite",
                sizeBytes = 42_100_000,  // 42MB
                type = "tflite",
                category = "STT",
                description = "Ultra rápido, transcribe + traducción, 100+ idiomas"
            ),
            ModelInfo(
                name = "Whisper Tiny - English Only",
                url = "https://huggingface.co/DocWolle/whisper_tflite_models/resolve/main/whisper-tiny.en.tflite",
                filename = "whisper-tiny.en.tflite",
                sizeBytes = 41_500_000,  // 41.5MB
                type = "tflite",
                category = "STT",
                description = "Especializado en inglés, máxima velocidad"
            ),

            // Base Models (Balanced)
            ModelInfo(
                name = "Whisper Base - Multilingual",
                url = "https://huggingface.co/DocWolle/whisper_tflite_models/resolve/main/whisper-base-transcribe-translate.tflite",
                filename = "whisper-base-transcribe-translate.tflite",
                sizeBytes = 78_500_000,  // 78.5MB
                type = "tflite",
                category = "STT",
                description = "Balance ideal, transcribe + traducción, 100+ idiomas"
            ),
            ModelInfo(
                name = "Whisper Base - Spanish",
                url = "https://huggingface.co/DocWolle/whisper_tflite_models/resolve/main/whisper-base.es.tflite",
                filename = "whisper-base.es.tflite",
                sizeBytes = 78_400_000,  // 78.4MB
                type = "tflite",
                category = "STT",
                description = "Especializado en español, mayor precisión"
            ),
            ModelInfo(
                name = "Whisper Base - English",
                url = "https://huggingface.co/DocWolle/whisper_tflite_models/resolve/main/whisper-base.en.tflite",
                filename = "whisper-base.en.tflite",
                sizeBytes = 78_400_000,  // 78.4MB
                type = "tflite",
                category = "STT",
                description = "Especializado en inglés, mayor precisión"
            ),

            // Small Models (Best Quality)
            ModelInfo(
                name = "Whisper Small - Multilingual",
                url = "https://huggingface.co/DocWolle/whisper_tflite_models/resolve/main/whisper-small-transcribe-translate.tflite",
                filename = "whisper-small-transcribe-translate.tflite",
                sizeBytes = 249_000_000,  // 249MB
                type = "tflite",
                category = "STT",
                description = "Alta precisión, transcribe + traducción, 100+ idiomas"
            )
        )

        // ========== MULTIMODAL LLM MODELS ==========
        private val MULTIMODAL_MODELS = listOf(
            ModelInfo(
                name = "Gemma 3n E2B - Multimodal",
                url = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/model.litertlm",
                filename = "gemma-3n-E2B-it-int4.litertlm",
                sizeBytes = 2_000_000_000,  // ~2GB
                type = "litertlm",
                category = "LLM-Multimodal",
                description = "Audio + Image + Video + Text → Text. USM encoder, 5B params, 2GB RAM. 140+ idiomas",
                gated = true,
                termsUrl = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm"
            ),
            ModelInfo(
                name = "Gemma 3n E4B - Multimodal",
                url = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/model.litertlm",
                filename = "gemma-3n-E4B-it-int4.litertlm",
                sizeBytes = 3_000_000_000,  // ~3GB
                type = "litertlm",
                category = "LLM-Multimodal",
                description = "Versión más capaz de E2B, mejor calidad, 3GB RAM",
                gated = true,
                termsUrl = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm"
            ),
            ModelInfo(
                name = "SmolVLM-256M - Vision-Language",
                url = "https://huggingface.co/litert-community/SmolVLM-256M-Instruct/resolve/main/smolvlm-256m-instruct.tflite",
                filename = "smolvlm-256m-instruct.tflite",
                sizeBytes = 300_000_000,  // ~300MB (estimado)
                type = "tflite",
                category = "LLM-Multimodal",
                description = "Ultra ligero, Image + Text → Text, ideal para análisis visual rápido"
            )
        )

        // ========== TEXT-ONLY LLM MODELS ==========
        private val TEXT_LLM_MODELS = listOf(
            ModelInfo(
                name = "Gemma 3-270M Instruct",
                url = "https://huggingface.co/litert-community/gemma-3-270m-it/resolve/main/gemma3-270m-it-q8.litertlm",
                filename = "gemma3-270m-it-q8.litertlm",
                sizeBytes = 1_000_000_000,  // ~1GB
                type = "litertlm",
                category = "LLM-Text",
                description = "Ultra ligero, rápido, bueno para chat simple",
                gated = true,
                termsUrl = "https://huggingface.co/litert-community/gemma-3-270m-it"
            ),
            ModelInfo(
                name = "Qwen 2.5-1.5B Instruct",
                url = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_seq128_q8_ekv1280.tflite",
                filename = "Qwen2.5-1.5B-Instruct_seq128_q8_ekv1280.tflite",
                sizeBytes = 1_500_000_000,  // ~1.5GB
                type = "tflite",
                category = "LLM-Text",
                description = "Excelente balance calidad/velocidad, muy popular"
            ),
            ModelInfo(
                name = "Phi-4 Mini Instruct",
                url = "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_seq128_q8_ekv1280.tflite",
                filename = "Phi-4-mini-instruct_seq128_q8_ekv1280.tflite",
                sizeBytes = 1_800_000_000,  // ~1.8GB (estimado)
                type = "tflite",
                category = "LLM-Text",
                description = "Optimizado para razonamiento complejo"
            ),
            ModelInfo(
                name = "Gemma 2-2B Instruct",
                url = "https://huggingface.co/litert-community/Gemma2-2B-IT/resolve/main/Gemma2-2B-IT_seq128_q8_ekv1280.tflite",
                filename = "Gemma2-2B-IT_seq128_q8_ekv1280.tflite",
                sizeBytes = 2_000_000_000,  // ~2GB
                type = "tflite",
                category = "LLM-Text",
                description = "Versión anterior de Gemma, estable y confiable",
                gated = true,
                termsUrl = "https://huggingface.co/litert-community/Gemma2-2B-IT"
            )
        )

        // ========== MEDIAPIPE TASK MODELS ==========
        private val MEDIAPIPE_TASK_MODELS = listOf(
            ModelInfo(
                name = "Gemma 3-270M Instruct (.task)",
                url = "https://huggingface.co/litert-community/gemma-3-270m-it/resolve/main/gemma3-270m-it-q8.task",
                filename = "gemma3-270m-it-q8.task",
                sizeBytes = 1_000_000_000,  // ~1GB (estimado)
                type = "task",
                category = "LLM-Text",
                description = "MediaPipe task, alternativo al LiteRT LM"
            ),
            ModelInfo(
                name = "Qwen 2.5-1.5B Instruct (.task)",
                url = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_seq128_q8_ekv1280.task",
                filename = "Qwen2.5-1.5B-Instruct_seq128_q8_ekv1280.task",
                sizeBytes = 1_500_000_000,  // ~1.5GB (estimado)
                type = "task",
                category = "LLM-Text",
                description = "MediaPipe task, buena calidad general"
            ),
            ModelInfo(
                name = "Phi-4 Mini Instruct (.task)",
                url = "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/phi4_q8_ekv1280.task",
                filename = "phi4_q8_ekv1280.task",
                sizeBytes = 1_800_000_000,  // ~1.8GB (estimado)
                type = "task",
                category = "LLM-Text",
                description = "MediaPipe task, razonamiento complejo"
            ),
            ModelInfo(
                name = "Gemma 2-2B Instruct (.task)",
                url = "https://huggingface.co/litert-community/Gemma2-2B-IT/resolve/main/Gemma2-2B-IT_multi-prefill-seq_q8_ekv1280.task",
                filename = "Gemma2-2B-IT_multi-prefill-seq_q8_ekv1280.task",
                sizeBytes = 2_000_000_000,  // ~2GB (estimado)
                type = "task",
                category = "LLM-Text",
                description = "MediaPipe task, estable y confiable",
                gated = true,
                termsUrl = "https://huggingface.co/litert-community/Gemma2-2B-IT"
            )
        )

        private val LITERT_LM_MODELS = listOf(
            ModelInfo(
                name = "Qwen 2.5-1.5B Instruct (LiteRT LM)",
                url = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
                filename = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
                sizeBytes = 1_500_000_000,  // ~1.5GB (estimado)
                type = "litertlm",
                category = "LLM-Text",
                description = "LiteRT LM con secuencia extendida"
            ),
            ModelInfo(
                name = "Phi-4 Mini Instruct (LiteRT LM)",
                url = "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
                filename = "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
                sizeBytes = 1_800_000_000,  // ~1.8GB (estimado)
                type = "litertlm",
                category = "LLM-Text",
                description = "LiteRT LM con secuencia extendida"
            ),
            ModelInfo(
                name = "DeepSeek R1 Distill Qwen 1.5B (LiteRT LM)",
                url = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
                filename = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
                sizeBytes = 1_500_000_000,  // ~1.5GB (estimado)
                type = "litertlm",
                category = "LLM-Text",
                description = "LiteRT LM con razonamiento mejorado"
            )
        )

        // Catálogo completo
        val AVAILABLE_MODELS = WHISPER_MODELS + MULTIMODAL_MODELS + TEXT_LLM_MODELS + MEDIAPIPE_TASK_MODELS + LITERT_LM_MODELS

        // Categorías para UI
        val MODEL_CATEGORIES = listOf(
            ModelCategory("STT - Whisper", WHISPER_MODELS),
            ModelCategory("LLM - Multimodal (Audio/Vision)", MULTIMODAL_MODELS),
            ModelCategory("LLM - Text Only", TEXT_LLM_MODELS + MEDIAPIPE_TASK_MODELS + LITERT_LM_MODELS)
        )

        fun modelInfoToJson(model: ModelInfo): JSONObject = JSONObject().apply {
            put("name", model.name)
            put("url", model.url)
            put("filename", model.filename)
            put("sizeBytes", model.sizeBytes)
            put("type", model.type)
            put("category", model.category)
            put("description", model.description)
            put("gated", model.gated)
            if (model.termsUrl != null) {
                put("termsUrl", model.termsUrl)
            }
        }

        fun modelInfoFromJson(obj: JSONObject): ModelInfo? {
            return try {
                ModelInfo(
                    name = obj.optString("name"),
                    url = obj.optString("url"),
                    filename = obj.optString("filename"),
                    sizeBytes = obj.optLong("sizeBytes"),
                    type = obj.optString("type"),
                    category = obj.optString("category"),
                    description = obj.optString("description"),
                    gated = obj.optBoolean("gated", false),
                    termsUrl = obj.optString("termsUrl").ifBlank { null }
                )
            } catch (e: Exception) {
                null
            }
        }

        fun repoScanDetailsToJson(details: RepoScanDetails): JSONObject = JSONObject().apply {
            put("repoId", details.repoId)
            put("gated", details.gated)
            val arr = org.json.JSONArray()
            details.models.forEach { model ->
                arr.put(JSONObject().apply {
                    put("name", model.name)
                    put("filename", model.filename)
                    put("url", model.url)
                    put("sizeBytes", model.sizeBytes)
                    put("type", model.type)
                    put("category", model.category)
                    put("description", model.description)
                    put("compatible", model.compatible)
                    if (model.missingReason != null) {
                        put("missingReason", model.missingReason)
                    }
                })
            }
            put("models", arr)
        }

        fun repoScanDetailsFromJson(obj: JSONObject): RepoScanDetails? {
            return try {
                val repoId = obj.optString("repoId")
                val gated = obj.optBoolean("gated", false)
                val modelsJson = obj.optJSONArray("models") ?: org.json.JSONArray()
                val models = mutableListOf<RepoModelDetails>()
                for (i in 0 until modelsJson.length()) {
                    val m = modelsJson.optJSONObject(i) ?: continue
                    models.add(
                        RepoModelDetails(
                            name = m.optString("name"),
                            filename = m.optString("filename"),
                            url = m.optString("url"),
                            sizeBytes = m.optLong("sizeBytes"),
                            type = m.optString("type"),
                            category = m.optString("category"),
                            description = m.optString("description"),
                            compatible = m.optBoolean("compatible", false),
                            missingReason = m.optString("missingReason").ifBlank { null }
                        )
                    )
                }
                RepoScanDetails(repoId, gated, models)
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun downloadModel(
        modelInfo: ModelInfo,
        apiKey: String?,
        onProgress: (progress: Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val outputFile = File(modelsDir, modelInfo.filename)

            // Si ya existe y tiene el tamaño correcto, retornar
            if (outputFile.exists() && outputFile.length() == modelInfo.sizeBytes) {
                Log.d(TAG, "Model already exists: ${modelInfo.filename}")
                return@withContext Result.success(outputFile)
            }

            Log.d(TAG, "Downloading ${modelInfo.name} from ${modelInfo.url}")
            
            fun execute(url: String): okhttp3.Response {
                val key = apiKey?.trim().orEmpty()
                val httpUrl = url.toHttpUrlOrNull()
                val finalUrl = if (httpUrl != null && isHuggingFace(httpUrl)) {
                    httpUrl.newBuilder()
                        .addQueryParameter("download", "true")
                        .build()
                } else {
                    httpUrl
                }
                val requestBuilder = Request.Builder().url(finalUrl?.toString() ?: url)
                requestBuilder.addHeader("Accept", "application/octet-stream")
                if (key.isNotBlank()) {
                    requestBuilder.addHeader("Authorization", "Bearer $key")
                }
                val hasAuth = key.isNotBlank()
                val safeUrl = finalUrl?.newBuilder()?.query(null)?.build()?.toString() ?: url
                Log.d(TAG, "Downloading ${modelInfo.filename}, auth=${if (hasAuth) "yes" else "no"} url=$safeUrl")
                return client.newCall(requestBuilder.build()).execute()
            }

            var downloadUrl = modelInfo.url
            var response = execute(downloadUrl)

            if (!response.isSuccessful) {
                if (response.code == 404 && modelInfo.url.contains("/resolve/main/")) {
                    response.close()
                    val repoId = parseRepoId(modelInfo.url)
                    if (repoId != null) {
                        val extension = when (modelInfo.type) {
                            "litertlm" -> ".litertlm"
                            "task" -> ".task"
                            else -> ".tflite"
                        }
                        val discovered = fetchModelFileFromApi(repoId, apiKey, extension)
                        if (discovered != null) {
                            downloadUrl = "https://huggingface.co/$repoId/resolve/main/$discovered"
                            Log.w(TAG, "Retrying with discovered file: $downloadUrl")
                            response = execute(downloadUrl)
                        } else {
                            val base = modelInfo.url.substringBeforeLast("/") + "/"
                            val fallback = when (modelInfo.type) {
                                "litertlm" -> "model.litertlm"
                                "task" -> "model.task"
                                else -> "model.tflite"
                            }
                            downloadUrl = base + fallback
                            Log.w(TAG, "Retrying with fallback URL: $downloadUrl")
                            response = execute(downloadUrl)
                        }
                    }
                }
            }

            if (!response.isSuccessful) {
                Log.e(TAG, "Download failed: HTTP ${response.code} url=$downloadUrl")
                val gatedMessage = if (response.code == 404 && modelInfo.gated) {
                    "Modelo gated: acepta terminos y reintenta."
                } else if ((response.code == 401 || response.code == 403) && apiKey.isNullOrBlank()) {
                    "API key de Hugging Face no configurada."
                } else if (response.code == 404) {
                    "Archivo no encontrado en el repositorio."
                } else if (response.code == 401 || response.code == 403) {
                    "Sin permiso para descargar (API key requerida o terminos no aceptados)."
                } else {
                    "Download failed: HTTP ${response.code}"
                }
                response.close()
                return@withContext Result.failure(Exception(gatedMessage))
            }

            val body = response.body 
                ?: return@withContext Result.failure(Exception("Empty response body"))
            
            val totalBytes = body.contentLength()
            Log.d(TAG, "Total size: $totalBytes bytes")

            FileOutputStream(outputFile).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        val progress = if (totalBytes > 0) {
                            ((totalRead * 100) / totalBytes).toInt()
                        } else {
                            0
                        }
                        
                        if (totalRead % (1024 * 1024) == 0L) {  // Log cada MB
                            Log.d(TAG, "Downloaded ${totalRead / (1024 * 1024)} MB ($progress%)")
                        }
                        
                        onProgress(progress)
                    }
                }
            }

            Log.d(TAG, "Download completed: ${modelInfo.filename}")
            if (modelInfo.type == "task") {
                val isZip = isZipFile(outputFile)
                val isHtml = isLikelyHtml(outputFile)
                val isLfs = isLfsPointer(outputFile)
                if (!isZip && (isHtml || isLfs)) {
                    Log.e(
                        TAG,
                        "Downloaded .task invalid: ${modelInfo.filename} html=$isHtml lfs=$isLfs"
                    )
                    outputFile.delete()
                    val message = if (isHtml) {
                        "Descarga devolvió HTML (posible gated o URL incorrecta)."
                    } else {
                        "Descarga devolvió un puntero LFS (archivo incompleto)."
                    }
                    return@withContext Result.failure(Exception(message))
                } else if (!isZip) {
                    Log.w(TAG, "Downloaded .task is not ZIP but accepted: ${modelInfo.filename}")
                }
            }
            val repoId = parseRepoId(downloadUrl)
            if (repoId != null) {
                downloadCompanionFiles(repoId, modelInfo.filename, apiKey)
                if (modelInfo.type == "tflite" && modelInfo.category.startsWith("LLM")) {
                    downloadTokenizerFiles(repoId, modelInfo, apiKey)
                }
            }
            if (modelInfo.category == "STT") {
                downloadWhisperVocabFiles(apiKey)
            }
            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download model: ${modelInfo.name}", e)
            Result.failure(e)
        }
    }

    fun getCandidateModels(settings: SettingsManager?): List<ModelInfo> {
        val scanned = settings?.getHfScannedModels().orEmpty()
        return (AVAILABLE_MODELS + scanned)
            .distinctBy { it.filename }
    }

    fun getAvailableModels(settings: SettingsManager?): List<ModelInfo> {
        val scanned = settings?.getHfScannedModels().orEmpty()
        val repoDetails = settings?.getHfRepoScanDetails().orEmpty()
        val repoModels = repoDetails.flatMap { repo ->
            repo.models.map { model ->
                ModelInfo(
                    name = model.name,
                    url = model.url,
                    filename = model.filename,
                    sizeBytes = model.sizeBytes,
                    type = model.type,
                    category = model.category,
                    description = model.description,
                    gated = repo.gated,
                    termsUrl = "https://huggingface.co/${repo.repoId}"
                )
            }
        }
        return (AVAILABLE_MODELS + scanned + repoModels).distinctBy { it.filename }
    }

    fun getInstalledModels(models: List<ModelInfo> = AVAILABLE_MODELS): List<ModelInfo> {
        return models.filter { model ->
            getModelFile(model.filename) != null
        }
    }

    fun isModelInstalled(modelInfo: ModelInfo): Boolean {
        return getModelFile(modelInfo.filename) != null
    }

    fun deleteModel(modelInfo: ModelInfo): Boolean {
        return try {
            val primary = File(modelsDir, modelInfo.filename)
            var deleted = false
            if (primary.exists()) {
                deleted = primary.delete() || deleted
            }
            val legacyName = legacyFilenameFor(modelInfo.filename)
            if (legacyName != null) {
                val legacy = File(modelsDir, legacyName)
                if (legacy.exists()) {
                    deleted = legacy.delete() || deleted
                }
            }
            if (deleted) {
                Log.d(TAG, "Model deleted: ${modelInfo.filename}")
            }
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete model: ${modelInfo.filename}", e)
            false
        }
    }

    fun getModelFile(filename: String): File? {
        val file = File(modelsDir, filename)
        if (file.exists() && file.length() > 0) return file
        val legacyName = legacyFilenameFor(filename) ?: return null
        val legacy = File(modelsDir, legacyName)
        return if (legacy.exists() && legacy.length() > 0) legacy else null
    }

    private fun legacyFilenameFor(filename: String): String? {
        if (!filename.endsWith(".litertlm", ignoreCase = true)) return null
        return if (filename.contains("-int4")) {
            filename.replace("-int4", "")
        } else {
            null
        }
    }

    private fun isZipFile(file: File): Boolean {
        if (!file.exists() || file.length() < 4L) return false
        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(4)
                val read = input.read(header)
                read == 4 && header[0] == 0x50.toByte() &&
                    header[1] == 0x4B.toByte() &&
                    header[2] == 0x03.toByte() &&
                    header[3] == 0x04.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isLikelyHtml(file: File): Boolean {
        if (!file.exists() || file.length() < 8L) return false
        return try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64)
                val read = input.read(buffer)
                if (read <= 0) return false
                val text = String(buffer, 0, read, Charsets.US_ASCII).trim().lowercase()
                text.startsWith("<!doctype") || text.startsWith("<html") || text.startsWith("<!doctype html")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isLfsPointer(file: File): Boolean {
        if (!file.exists() || file.length() < 32L) return false
        return try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(128)
                val read = input.read(buffer)
                if (read <= 0) return false
                val text = String(buffer, 0, read, Charsets.US_ASCII).trim().lowercase()
                text.startsWith("version https://git-lfs.github.com/spec/v1")
            }
        } catch (_: Exception) {
            false
        }
    }

    fun getTokenizerFile(modelInfo: ModelInfo): File? {
        val base = modelInfo.filename.substringBeforeLast(".")
        val candidates = listOf(
            "${base}_vocab.txt",
            "${base}.vocab",
            "${base}.txt",
            "vocab.txt",
            "tokenizer.txt",
            "tokenizer.model"
        )
        return candidates
            .map { File(modelsDir, it) }
            .firstOrNull { it.exists() && it.length() > 0 }
    }

    fun ensureTokenizerForModel(modelInfo: ModelInfo, apiKey: String?): File? {
        val existing = getTokenizerFile(modelInfo)
        if (existing != null) return existing
        val repoId = parseRepoId(modelInfo.url) ?: return null
        downloadTokenizerFiles(repoId, modelInfo, apiKey)
        return getTokenizerFile(modelInfo)
    }

    fun getTotalUsedSpace(): Long {
        return modelsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun getAvailableSpace(): Long {
        return modelsDir.usableSpace
    }

    suspend fun scanHfRepos(repoIds: List<String>, apiKey: String?): RepoScanResult =
        withContext(Dispatchers.IO) {
            val models = mutableListOf<ModelInfo>()
            val errors = mutableListOf<String>()
            repoIds.map { it.trim() }.filter { it.isNotBlank() }.forEach { repoId ->
                val info = fetchRepoInfo(repoId, apiKey)
                if (info == null) {
                    errors.add("No se pudo leer $repoId")
                    return@forEach
                }
                models.addAll(buildModelsFromRepo(info))
            }
            RepoScanResult(models, errors)
        }

    suspend fun scanHfReposDetailed(
        repoIds: List<String>,
        apiKey: String?
    ): Pair<List<RepoScanDetails>, List<String>> = withContext(Dispatchers.IO) {
        val details = mutableListOf<RepoScanDetails>()
        val errors = mutableListOf<String>()
        repoIds.map { it.trim() }.filter { it.isNotBlank() }.forEach { repoId ->
            val info = fetchRepoInfo(repoId, apiKey)
            if (info == null) {
                errors.add("No se pudo leer $repoId")
                return@forEach
            }
            details.add(buildRepoDetails(info))
        }
        details to errors
    }

    private fun isHuggingFace(url: okhttp3.HttpUrl): Boolean {
        return url.host.endsWith("huggingface.co") || url.host.endsWith("hf.co")
    }

    private fun buildModelsFromRepo(info: RepoInfo): List<ModelInfo> {
        val models = mutableListOf<ModelInfo>()
        val siblings = info.siblings
        val filenames = siblings.map { it.filename }
        val vocabCandidates = listOf(
            "_vocab.txt",
            ".vocab",
            "vocab.txt",
            "tokenizer.txt"
        )
        val hasWhisperVocab = filenames.any { it == "filters_vocab_en.bin" || it == "filters_vocab_multilingual.bin" }

        siblings.forEach { sibling ->
            val name = sibling.filename
            val url = "https://huggingface.co/${info.repoId}/resolve/main/${sibling.filename}"
            val sizeBytes = sibling.sizeBytes
            when {
                name.endsWith(".task", ignoreCase = true) -> {
                    models.add(
                        ModelInfo(
                            name = "${info.repoId}: ${sibling.filename}",
                            url = url,
                            filename = sibling.filename,
                            sizeBytes = sizeBytes,
                            type = "task",
                            category = "LLM-Text",
                            description = "MediaPipe task desde ${info.repoId}",
                            gated = info.gated,
                            termsUrl = "https://huggingface.co/${info.repoId}"
                        )
                    )
                }
                name.endsWith(".litertlm", ignoreCase = true) -> {
                    models.add(
                        ModelInfo(
                            name = "${info.repoId}: ${sibling.filename}",
                            url = url,
                            filename = sibling.filename,
                            sizeBytes = sizeBytes,
                            type = "litertlm",
                            category = "LLM-Text",
                            description = "LiteRT LM desde ${info.repoId}",
                            gated = info.gated,
                            termsUrl = "https://huggingface.co/${info.repoId}"
                        )
                    )
                }
                name.endsWith(".tflite", ignoreCase = true) -> {
                    val isWhisper = name.contains("whisper", ignoreCase = true)
                    if (isWhisper) {
                        if (!hasWhisperVocab) return@forEach
                        models.add(
                            ModelInfo(
                                name = "${info.repoId}: ${sibling.filename}",
                                url = url,
                                filename = sibling.filename,
                                sizeBytes = sizeBytes,
                                type = "tflite",
                                category = "STT",
                                description = "Whisper TFLite desde ${info.repoId}",
                                gated = info.gated,
                                termsUrl = "https://huggingface.co/${info.repoId}"
                            )
                        )
                        return@forEach
                    }
                    val hasTxtVocab = filenames.any { candidate ->
                        vocabCandidates.any { suffix -> candidate.endsWith(suffix, ignoreCase = true) }
                    }
                    if (!hasTxtVocab) return@forEach
                    models.add(
                        ModelInfo(
                            name = "${info.repoId}: ${sibling.filename}",
                            url = url,
                            filename = sibling.filename,
                            sizeBytes = sizeBytes,
                            type = "tflite",
                            category = "LLM-Text",
                            description = "LLM TFLite desde ${info.repoId}",
                            gated = info.gated,
                            termsUrl = "https://huggingface.co/${info.repoId}"
                        )
                    )
                }
            }
        }
        return models
    }

    private fun buildRepoDetails(info: RepoInfo): RepoScanDetails {
        val siblings = info.siblings
        val filenames = siblings.map { it.filename }
        val vocabCandidates = listOf(
            "_vocab.txt",
            ".vocab",
            "vocab.txt",
            "tokenizer.txt"
        )
        val hasWhisperVocab = filenames.any { it == "filters_vocab_en.bin" || it == "filters_vocab_multilingual.bin" }

        val models = siblings.mapNotNull { sibling ->
            val name = sibling.filename
            val url = "https://huggingface.co/${info.repoId}/resolve/main/${sibling.filename}"
            val sizeBytes = sibling.sizeBytes
            when {
                name.endsWith(".task", ignoreCase = true) -> {
                    RepoModelDetails(
                        name = "${info.repoId}: ${sibling.filename}",
                        filename = sibling.filename,
                        url = url,
                        sizeBytes = sizeBytes,
                        type = "task",
                        category = "LLM-Text",
                        description = "MediaPipe task desde ${info.repoId}",
                        compatible = true,
                        missingReason = null
                    )
                }
                name.endsWith(".litertlm", ignoreCase = true) -> {
                    RepoModelDetails(
                        name = "${info.repoId}: ${sibling.filename}",
                        filename = sibling.filename,
                        url = url,
                        sizeBytes = sizeBytes,
                        type = "litertlm",
                        category = "LLM-Text",
                        description = "LiteRT LM desde ${info.repoId}",
                        compatible = true,
                        missingReason = null
                    )
                }
                name.endsWith(".tflite", ignoreCase = true) -> {
                    val isWhisper = name.contains("whisper", ignoreCase = true)
                    if (isWhisper) {
                        RepoModelDetails(
                            name = "${info.repoId}: ${sibling.filename}",
                            filename = sibling.filename,
                            url = url,
                            sizeBytes = sizeBytes,
                            type = "tflite",
                            category = "STT",
                            description = "Whisper TFLite desde ${info.repoId}",
                            compatible = hasWhisperVocab,
                            missingReason = if (hasWhisperVocab) null else "Falta vocab Whisper"
                        )
                    } else {
                        val hasTxtVocab = filenames.any { candidate ->
                            vocabCandidates.any { suffix -> candidate.endsWith(suffix, ignoreCase = true) }
                        }
                        RepoModelDetails(
                            name = "${info.repoId}: ${sibling.filename}",
                            filename = sibling.filename,
                            url = url,
                            sizeBytes = sizeBytes,
                            type = "tflite",
                            category = "LLM-Text",
                            description = "LLM TFLite desde ${info.repoId}",
                            compatible = hasTxtVocab,
                            missingReason = if (hasTxtVocab) null else "Falta vocab"
                        )
                    }
                }
                else -> null
            }
        }

        return RepoScanDetails(
            repoId = info.repoId,
            gated = info.gated,
            models = models
        )
    }

    private fun parseRepoId(url: String): String? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        if (!isHuggingFace(httpUrl)) return null
        val segments = httpUrl.pathSegments
        if (segments.size < 2) return null
        return "${segments[0]}/${segments[1]}"
    }

    private fun fetchRepoInfo(repoId: String, apiKey: String?): RepoInfo? {
        val requestBuilder = Request.Builder()
            .url("https://huggingface.co/api/models/$repoId")
        val key = apiKey?.trim().orEmpty()
        if (key.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $key")
        }
        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Repo list failed: HTTP ${response.code} repo=$repoId")
                    return null
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val gated = json.optBoolean("gated", false)
                val siblingsJson = json.optJSONArray("siblings") ?: return null
                val siblings = mutableListOf<RepoSibling>()
                for (i in 0 until siblingsJson.length()) {
                    val item = siblingsJson.optJSONObject(i) ?: continue
                    val name = item.optString("rfilename")
                    if (name.isBlank()) continue
                    val size = item.optLong("size", 0L)
                    siblings.add(RepoSibling(name, size))
                }
                RepoInfo(repoId, gated, siblings)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Repo list parse failed for $repoId", e)
            null
        }
    }

    private fun fetchModelFileFromApi(
        repoId: String,
        apiKey: String?,
        extension: String
    ): String? {
        val requestBuilder = Request.Builder()
            .url("https://huggingface.co/api/models/$repoId")
        val key = apiKey?.trim().orEmpty()
        if (key.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $key")
        }
        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Repo list failed: HTTP ${response.code} repo=$repoId")
                    return null
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val siblings = json.optJSONArray("siblings") ?: return null
                var candidate: String? = null
                for (i in 0 until siblings.length()) {
                    val item = siblings.optJSONObject(i) ?: continue
                    val name = item.optString("rfilename")
                    if (name.endsWith(extension)) {
                        if (candidate == null || name.contains("model", ignoreCase = true)) {
                            candidate = name
                        }
                    }
                }
                candidate
            }
        } catch (e: Exception) {
            Log.w(TAG, "Repo list parse failed for $repoId", e)
            null
        }
    }

    private fun downloadCompanionFiles(repoId: String, filename: String, apiKey: String?) {
        val base = filename.substringBeforeLast(".")
        val extensions = listOf(".task", ".task.bin", ".bin")
        val siblings = fetchSiblings(repoId, apiKey) ?: return
        extensions.forEach { ext ->
            val candidate = siblings.firstOrNull { it.equals("$base$ext", ignoreCase = false) }
            if (candidate != null) {
                val url = "https://huggingface.co/$repoId/resolve/main/$candidate"
                try {
                    val requestBuilder = Request.Builder().url(url)
                    val key = apiKey?.trim().orEmpty()
                    if (key.isNotBlank()) {
                        requestBuilder.addHeader("Authorization", "Bearer $key")
                    }
                    requestBuilder.addHeader("Accept", "application/octet-stream")
                    client.newCall(requestBuilder.build()).execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.w(TAG, "Companion download failed: HTTP ${response.code} $candidate")
                            return@use
                        }
                        val outputFile = File(modelsDir, candidate)
                        response.body?.byteStream()?.use { input ->
                            FileOutputStream(outputFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        Log.d(TAG, "Companion downloaded: $candidate")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Companion download error: $candidate", e)
                }
            }
        }
    }

    private fun downloadTokenizerFiles(repoId: String, modelInfo: ModelInfo, apiKey: String?) {
        val siblings = fetchSiblings(repoId, apiKey) ?: return
        val base = modelInfo.filename.substringBeforeLast(".")
        val candidates = listOf(
            "${base}_vocab.txt",
            "${base}.vocab",
            "${base}.txt",
            "vocab.txt",
            "tokenizer.txt",
            "tokenizer.model"
        )
        val target = candidates.firstOrNull { candidate ->
            siblings.any { it.equals(candidate, ignoreCase = false) }
        } ?: return

        if (getModelFile(target) != null) return

        val url = "https://huggingface.co/$repoId/resolve/main/$target"
        try {
            val requestBuilder = Request.Builder().url(url)
            val key = apiKey?.trim().orEmpty()
            if (key.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $key")
            }
            requestBuilder.addHeader("Accept", "application/octet-stream")
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Tokenizer download failed: HTTP ${response.code} $target")
                    return@use
                }
                val outputFile = File(modelsDir, target)
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(outputFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Tokenizer downloaded: $target")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tokenizer download error: $target", e)
        }
    }

    private fun fetchSiblings(repoId: String, apiKey: String?): List<String>? {
        val requestBuilder = Request.Builder()
            .url("https://huggingface.co/api/models/$repoId")
        val key = apiKey?.trim().orEmpty()
        if (key.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $key")
        }
        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Repo list failed: HTTP ${response.code} repo=$repoId")
                    return null
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val siblings = json.optJSONArray("siblings") ?: return null
                val list = mutableListOf<String>()
                for (i in 0 until siblings.length()) {
                    val item = siblings.optJSONObject(i) ?: continue
                    val name = item.optString("rfilename")
                    if (name.isNotBlank()) list.add(name)
                }
                list
            }
        } catch (e: Exception) {
            Log.w(TAG, "Repo list parse failed for $repoId", e)
            null
        }
    }

    private fun downloadWhisperVocabFiles(apiKey: String?) {
        val repoId = "DocWolle/whisper_tflite_models"
        val files = listOf("filters_vocab_en.bin", "filters_vocab_multilingual.bin")
        files.forEach { filename ->
            if (getModelFile(filename) != null) return@forEach
            val url = "https://huggingface.co/$repoId/resolve/main/$filename"
            try {
                val requestBuilder = Request.Builder().url(url)
                val key = apiKey?.trim().orEmpty()
                if (key.isNotBlank()) {
                    requestBuilder.addHeader("Authorization", "Bearer $key")
                }
                requestBuilder.addHeader("Accept", "application/octet-stream")
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Vocab download failed: HTTP ${response.code} $filename")
                        return@use
                    }
                    val outputFile = File(modelsDir, filename)
                    response.body?.byteStream()?.use { input ->
                        FileOutputStream(outputFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Vocab downloaded: $filename")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Vocab download error: $filename", e)
            }
        }
    }
}
