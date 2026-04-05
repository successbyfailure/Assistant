# Plan: Voice Pipeline — Android Assistant

**Fecha:** 2026-04-05  
**Estado:** Activo  
**Objetivo:** Que la app funcione correctamente con oCabra como backend de voz, en dos fases:
- Fase 1 — El pipeline de tres llamadas (STT → LLM → TTS) funciona con baja latencia y robustez.
- Fase 2 — Implementar cliente de la [OpenAI Realtime API](https://platform.openai.com/docs/guides/realtime) para conversación continua vía WebSocket.

**Backend de referencia:** oCabra (self-hosted, compatible OpenAI API).  
**Plan del backend:** ver `docs/tasks/voice-pipeline-plan.md` en el repo oCabra.

---

## Fase 1 — Pipeline de tres llamadas

### F1-1: TTS — corregir formato del archivo de audio

**Bug actual:** `generateSpeech()` en `OpenAiClient.kt` guarda siempre la respuesta en un archivo `.mp3` (`tts_*.mp3`), pero oCabra puede devolver WAV. Android `MediaPlayer` falla o reproduce audio corrupto si el contenido no coincide con la extensión.

**Archivo:** `app/src/main/java/com/sbf/assistant/OpenAiClient.kt`

**Cambio:**
1. Enviar `response_format` en el body del request (empezar con `"mp3"` o `"wav"` según lo que soporte el backend configurado):

```kotlin
// En generateSpeech():
val json = JSONObject().apply {
    put("model", modelName)
    put("input", text)
    put("voice", voice)          // ver F1-2
    put("response_format", "mp3") // explicitar siempre
}
```

2. Leer el `Content-Type` de la respuesta para determinar la extensión real del archivo:

```kotlin
val contentType = response.header("Content-Type", "audio/mpeg") ?: "audio/mpeg"
val ext = when {
    contentType.contains("wav") -> "wav"
    contentType.contains("ogg") || contentType.contains("opus") -> "ogg"
    contentType.contains("flac") -> "flac"
    else -> "mp3"
}
val tempFile = File.createTempFile("tts_", ".$ext")
```

---

### F1-2: TTS — voz configurable (no hardcodeada)

**Bug actual:** La voz está hardcodeada a `"alloy"` en `OpenAiClient.kt:133`. El usuario no puede cambiarla desde la UI.

**Archivos:** `OpenAiClient.kt`, `SettingsManager.kt`, `AssistantSettingsActivity.kt` (o la pantalla de settings que corresponda)

**Cambios:**

1. En `SettingsManager.kt`, añadir preferencia:
```kotlin
var ttsVoice: String
    get() = prefs.getString("tts_voice", "alloy") ?: "alloy"
    set(value) = prefs.edit().putString("tts_voice", value).apply()
```

2. En `TtsController.kt`, leer la voz al llamar a `generateSpeech`:
```kotlin
OpenAiClient(endpoint).generateSpeech(
    text = text,
    modelName = ttsPref.modelName,
    voice = settingsManager.ttsVoice,
    onResult = { file, error -> ... }
)
```

3. En `OpenAiClient.kt`, añadir parámetro `voice` a `generateSpeech()`.

4. En la pantalla de Settings, añadir selector de voz (alloy, echo, fable, onyx, nova, shimmer).

---

### F1-3: TTS — streaming por frases para baja latencia

**Problema actual:** El flujo actual es: LLM termina de generar TODA la respuesta → se llama a TTS con el texto completo → se espera TODO el audio → se reproduce. Latencia total: 3-10 segundos para respuestas largas.

**Solución:** Iniciar TTS en cuanto el LLM completa una frase, sin esperar el resto de la respuesta.

**Archivos:** `ChatController.kt`, `TtsController.kt`

**Diseño:**

En `ChatController.kt`, el callback `onToken` acumula tokens. Detectar fin de frase y disparar TTS inmediatamente:

```kotlin
// En executeChatRequest() / onToken callback:
private val sentenceBuffer = StringBuilder()
private val ttsQueue = ArrayDeque<String>()
private var isTtsPlaying = false

fun onToken(token: String) {
    fullResponse.append(token)
    sentenceBuffer.append(token)
    onTokenUpdate(token)

    // Detectar fin de frase
    if (isSentenceEnd(sentenceBuffer.toString())) {
        val sentence = sentenceBuffer.trim().toString()
        sentenceBuffer.clear()
        if (sentence.isNotBlank()) {
            enqueueTts(sentence)
        }
    }
}

fun onComplete() {
    // Flushar lo que quede en el buffer
    val remainder = sentenceBuffer.trim().toString()
    if (remainder.isNotBlank()) enqueueTts(remainder)
    sentenceBuffer.clear()
    onCompleteCallback()
}

private fun isSentenceEnd(text: String): Boolean {
    val trimmed = text.trimEnd()
    return trimmed.endsWith('.') || trimmed.endsWith('!') ||
           trimmed.endsWith('?') || trimmed.endsWith('\n')
}
```

`TtsController.speak()` recibe frases cortas y las encola. Reproducir secuencialmente con `onComplete` para pasar a la siguiente frase:

```kotlin
class TtsController(...) {
    private val queue = ArrayDeque<String>()
    private var isPlaying = false

    fun enqueue(text: String) {
        queue.addLast(text)
        if (!isPlaying) playNext()
    }

    private fun playNext() {
        if (queue.isEmpty()) { isPlaying = false; onAllComplete?.invoke(); return }
        isPlaying = true
        val text = queue.removeFirst()
        speak(text, onComplete = { playNext() })
    }

    fun clearQueue() {
        queue.clear()
        stop()
        isPlaying = false
    }
}
```

**Resultado esperado:** Primer audio en ~0.5-1.5 segundos después de que el LLM empieza a generar.

---

### F1-4: STT — manejo robusto de errores y fallback

**Problema actual:** Si el endpoint STT falla (modelo no cargado, red, etc.), el error se loguea pero no hay feedback claro al usuario ni lógica de reintento.

**Archivo:** `WhisperController.kt`, `AssistantSession.kt`, `ChatActivity.kt`

**Cambios:**
1. En `stopAndTranscribe()`, distinguir tipos de error:
   - `HTTP 503` (modelo no cargado) → mensaje "Modelo STT no disponible, intenta de nuevo"
   - `HTTP 401/403` → mensaje "Error de autenticación — verifica la API key"
   - Timeout → mensaje "Tiempo de espera agotado"
   - Error de red → mostrar mensaje + opción de reintento

2. Si hay modelo local configurado como backup, intentar fallback automático:
```kotlin
fun stopAndTranscribe(config: ModelConfig, onResult: ...) {
    transcribeRemote(config, file) { text, error ->
        if (error != null && backupConfig != null) {
            transcribeLocal(file) { localText, localError ->
                onResult(localText, localError)
            }
        } else {
            onResult(text, error)
        }
    }
}
```

---

### F1-5: STT — verificar formato M4A para oCabra

El `AudioRecorder` usa `MediaRecorder` con `MPEG_4` + `AAC` para el STT remoto. Verificar que:

1. oCabra/Whisper acepta M4A sin conversión previa
2. Si hay problemas, añadir opción en Settings para usar WAV siempre (`useWavForRemote: Boolean`)

**Archivo:** `AudioRecorder.kt` — añadir flag `forceWav` configurable desde `SettingsManager`.

---

### F1-6: Barge-in durante TTS por frases

Con el nuevo modelo de cola de TTS (F1-3), el barge-in necesita limpiar tanto la reproducción actual como las frases pendientes en la cola:

**Archivo:** `AssistantSession.kt`, `ChatActivity.kt`

```kotlin
fun cancelCurrentResponse() {
    currentLlmHandle?.cancel()         // Cancela el stream LLM
    ttsController.clearQueue()         // Limpia cola de frases pendientes
    ttsController.stop()               // Para la reproducción actual
    chatController.clearSentenceBuffer() // Limpia el buffer de frases
}
```

---

### Tabla de archivos — Fase 1

| Archivo | Cambio | Prioridad |
|---------|--------|-----------|
| `OpenAiClient.kt` | TTS: enviar `response_format`, leer Content-Type, parámetro `voice` | **Alta** |
| `TtsController.kt` | Cola de frases, `enqueue()`, `clearQueue()` | **Alta** |
| `ChatController.kt` | Streaming por frases: `onToken` → detectar fin frase → `ttsController.enqueue()` | **Alta** |
| `SettingsManager.kt` | `ttsVoice`, `ttsResponseFormat`, `forceWavForRemote` | Media |
| `WhisperController.kt` | Errores tipados, fallback a local | Media |
| `AssistantSession.kt` | Barge-in con `clearQueue()` | Media |
| `ChatActivity.kt` | Mismo barge-in | Media |

---

## Fase 2 — Cliente OpenAI Realtime API

### Visión general

La Realtime API de OpenAI usa WebSocket (`wss://.../v1/realtime?model=<model>`). El servidor (oCabra) gestiona internamente STT, LLM y TTS. El cliente solo envía audio PCM y recibe audio PCM.

**Ventajas sobre Fase 1:**
- Una sola conexión para todo el pipeline
- VAD en el servidor (el cliente no necesita detectar silencio)
- Latencia menor (~200-500ms de voz a primera respuesta en audio)
- Transcripción en tiempo real visible mientras el usuario habla

### F2-1: `RealtimeClient.kt` — conexión WebSocket

**Archivo nuevo:** `app/src/main/java/com/sbf/assistant/realtime/RealtimeClient.kt`

```kotlin
class RealtimeClient(
    private val endpoint: Endpoint,
    private val model: String,
    private val listener: RealtimeListener
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout en WS
        .build()

    private var webSocket: WebSocket? = null

    fun connect(sessionConfig: RealtimeSessionConfig) {
        val request = Request.Builder()
            .url("${endpoint.baseUrl}/realtime?model=$model")
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .header("OpenAI-Beta", "realtime=v1")
            .build()
        webSocket = client.newWebSocket(request, RealtimeWebSocketListener())
    }

    fun sendAudioChunk(pcm16Bytes: ByteArray) {
        val b64 = Base64.encodeToString(pcm16Bytes, Base64.NO_WRAP)
        sendEvent(mapOf(
            "type" to "input_audio_buffer.append",
            "audio" to b64
        ))
    }

    fun commitAudio() = sendEvent(mapOf("type" to "input_audio_buffer.commit"))
    fun clearAudio() = sendEvent(mapOf("type" to "input_audio_buffer.clear"))
    fun requestResponse() = sendEvent(mapOf("type" to "response.create"))
    fun cancelResponse() = sendEvent(mapOf("type" to "response.cancel"))

    fun updateSession(config: RealtimeSessionConfig) {
        sendEvent(mapOf("type" to "session.update", "session" to config.toMap()))
    }

    fun disconnect() = webSocket?.close(1000, "done")
}
```

### F2-2: Protocolo de eventos — tipos de datos

**Archivo nuevo:** `app/src/main/java/com/sbf/assistant/realtime/RealtimeEvents.kt`

```kotlin
data class RealtimeSessionConfig(
    val voice: String = "alloy",
    val instructions: String = "",
    val inputAudioTranscriptionModel: String? = null, // STT model en oCabra
    val vadType: String = "server_vad",
    val vadThreshold: Float = 0.5f,
    val silenceDurationMs: Int = 800,
    val tools: List<ToolDefinition> = emptyList()
)

interface RealtimeListener {
    fun onSessionCreated(sessionId: String)
    fun onSpeechStarted()
    fun onSpeechStopped()
    fun onTranscriptDelta(text: String)       // STT parcial mientras el usuario habla
    fun onTranscriptDone(text: String)        // Texto completo transcrito
    fun onAudioDelta(pcm16Bytes: ByteArray)   // Chunk de audio de respuesta
    fun onAudioDone()
    fun onResponseDone(usage: RealtimeUsage?)
    fun onError(type: String, message: String)
}
```

### F2-3: Reproducción de audio en streaming

El servidor envía audio PCM16 (16kHz, mono) en chunks base64. Reproducir con `AudioTrack` (no `MediaPlayer` — este requiere archivo completo):

**Archivo nuevo:** `app/src/main/java/com/sbf/assistant/realtime/RealtimeAudioPlayer.kt`

```kotlin
class RealtimeAudioPlayer {
    private var audioTrack: AudioTrack? = null

    fun start() {
        val bufferSize = AudioTrack.getMinBufferSize(
            16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(bufferSize * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    fun write(pcm16Bytes: ByteArray) {
        audioTrack?.write(pcm16Bytes, 0, pcm16Bytes.size)
    }

    fun stop() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}
```

### F2-4: Grabación continua de audio (PCM streaming)

El `AudioRecorder` actual graba a archivo. Para Realtime necesitamos callbacks de chunks mientras se graba:

**Archivo:** `AudioRecorder.kt` — añadir modo streaming:

```kotlin
fun startStreamingRecording(
    onChunk: (ByteArray) -> Unit,
    chunkSizeMs: Int = 100  // chunks de 100ms
) {
    val sampleRate = 16000
    val chunkSamples = sampleRate * chunkSizeMs / 1000
    val chunkBytes = chunkSamples * 2  // 16-bit = 2 bytes/sample
    
    audioRecord = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        sampleRate, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        AudioRecord.getMinBufferSize(...) * 2
    )
    audioRecord.startRecording()
    
    scope.launch(Dispatchers.IO) {
        val buffer = ByteArray(chunkBytes)
        while (isRecording) {
            val read = audioRecord.read(buffer, 0, chunkBytes)
            if (read > 0) onChunk(buffer.copyOf(read))
        }
    }
}
```

### F2-5: `RealtimeConversationManager.kt` — orquestación

**Archivo nuevo:** `app/src/main/java/com/sbf/assistant/realtime/RealtimeConversationManager.kt`

Une `RealtimeClient` + `RealtimeAudioPlayer` + streaming de `AudioRecorder` + UI:

```kotlin
class RealtimeConversationManager(
    private val context: Context,
    private val settingsManager: SettingsManager,
    private val onTranscript: (String) -> Unit,
    private val onResponse: (String) -> Unit,
    private val onStateChange: (RealtimeState) -> Unit
) {
    // Estados: IDLE, LISTENING, TRANSCRIBING, RESPONDING, SPEAKING
    enum class RealtimeState { IDLE, CONNECTING, LISTENING, TRANSCRIBING, RESPONDING }

    fun startSession() { ... }   // Conectar WS, configurar sesión, iniciar grabación
    fun stopSession() { ... }    // Cerrar WS, detener grabación y reproducción
    fun interrupt() { ... }      // Barge-in: cancelResponse + clearAudio + reiniciar escucha
}
```

### F2-6: Integración en ChatActivity y AssistantSession

Añadir un **modo Realtime** como alternativa al modo de 3 llamadas:

**`ChatActivity.kt`:**
```kotlin
// Botón de mic existente: si está configurado Realtime y hay endpoint compatible,
// ofrecer opción de usar Realtime vs PTT clásico
private fun startVoiceInput() {
    if (settingsManager.realtimeEnabled && hasRealtimeEndpoint()) {
        startRealtimeSession()
    } else {
        startClassicPtt()
    }
}
```

**`SettingsManager.kt`:**
```kotlin
var realtimeEnabled: Boolean
    get() = prefs.getBoolean("realtime_enabled", false)
    set(value) = prefs.edit().putBoolean("realtime_enabled", value).apply()
```

### Tabla de archivos — Fase 2

| Archivo | Cambio |
|---------|--------|
| `realtime/RealtimeClient.kt` | **Nuevo** — WebSocket client, envío de eventos |
| `realtime/RealtimeEvents.kt` | **Nuevo** — tipos de datos, `RealtimeListener` interface |
| `realtime/RealtimeAudioPlayer.kt` | **Nuevo** — `AudioTrack` streaming playback |
| `realtime/RealtimeConversationManager.kt` | **Nuevo** — orquestación de sesión |
| `AudioRecorder.kt` | Añadir `startStreamingRecording(onChunk)` |
| `SettingsManager.kt` | `realtimeEnabled`, `realtimeModel` |
| `ChatActivity.kt` | Modo Realtime vs PTT según settings |
| `AssistantSession.kt` | Integrar `RealtimeConversationManager` en modo VAD |

---

## Orden de ejecución recomendado

```
Fase 1:
  [1] F1-1 TTS formato correcto — BLOQUEANTE (audio corrupto actualmente)
  [2] F1-2 TTS voz configurable — mejora UX inmediata
  [3] F1-3 Streaming por frases — mayor impacto en latencia percibida
  [4] F1-4 Errores STT robustos — robustez
  [5] F1-5 Verificar M4A — puede ser solo prueba manual
  [6] F1-6 Barge-in con cola — necesario con F1-3

Fase 2:
  [7] F2-1 + F2-2 RealtimeClient + eventos — protocolo base
  [8] F2-4 AudioRecorder streaming — entrada de audio
  [9] F2-3 RealtimeAudioPlayer — salida de audio
  [10] F2-5 RealtimeConversationManager — orquestación
  [11] F2-6 Integración en UI
```

---

## Dependencias del backend

Antes de empezar Fase 1, el backend oCabra necesita:
- **F1-1 oCabra:** TTS worker devuelve MP3 cuando se pide (actualmente siempre WAV)

Antes de empezar Fase 2, el backend oCabra necesita:
- **Fase 2 oCabra:** endpoint WebSocket `/v1/realtime` implementado

Ver `docs/tasks/voice-pipeline-plan.md` en el repo oCabra para el estado de cada tarea.

---

## Notas de audio

- **PCM16 a 16kHz mono** es el formato de intercambio con el servidor Realtime
- **MediaPlayer** soporta MP3, AAC, WAV, OGG — pero requiere archivo completo, no sirve para streaming real-time
- **AudioTrack** es la API correcta para reproducción de streaming PCM en Android
- El volumen del `AudioTrack` debe respetar el stream `STREAM_MUSIC` del sistema para integrarse con los controles de volumen del usuario
