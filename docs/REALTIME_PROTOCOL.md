# Protocolo Realtime — Guia de implementacion para Android Assistant

## Resumen

El endpoint `/v1/realtime` de oCabra proporciona una sesion WebSocket bidireccional que orquesta STT → LLM → TTS en tiempo real. El cliente envía audio PCM16 y recibe texto + audio sintetizado de vuelta.

## Diferencia con OpenAI Realtime

En OpenAI, `gpt-4o-realtime` integra STT + LLM + TTS en un solo modelo. En oCabra, son **3 modelos separados** orquestados por el servidor:

- **LLM**: modelo pasado en `?model=` (ej: `qwen3-8b`)
- **STT**: modelo Whisper, configurable via `session.update` → `input_audio_transcription.model` o default del servidor
- **TTS**: modelo de voz, configurable via `session.update` → `tts_model` (extension oCabra) o default del servidor

Si los modelos STT/TTS estan configurados como defaults en el servidor (Settings → General → Realtime API), no hace falta especificarlos en `session.update`.

## Formatos de audio

| Direccion | Formato | Sample Rate | Canales | Bits |
|-----------|---------|-------------|---------|------|
| Cliente → Servidor | PCM16 LE | **16000 Hz** | Mono | 16-bit |
| Servidor → Cliente | PCM16 LE | **24000 Hz** | Mono | 16-bit |

**IMPORTANTE**: El audio de salida llega a 24kHz (sample rate del TTS). El AudioTrack debe configurarse a 24000 Hz, no 16000.

## Flujo de conexion

```
1. Cliente abre WebSocket: ws://<host>/v1/realtime?model=<profile_id>
   Header: Authorization: Bearer <api_key>

2. Servidor: session.created { session: { id, model, voice, ... } }

3. Cliente: session.update { session: { voice, instructions, turn_detection, ... } }

4. Servidor: session.updated { session: { ... } }

5. Cliente empieza a enviar audio chunks
```

## Flujo de conversacion (VAD del servidor)

```
[LISTENING] Cliente envia chunks de audio continuamente
     |
     |-- Servidor detecta habla → input_audio_buffer.speech_started
     |   [TRANSCRIBING]
     |
     |-- Servidor detecta silencio → input_audio_buffer.speech_stopped
     |   El servidor auto-commits y procesa:
     |     1. input_audio_buffer.committed { item_id }
     |     2. conversation.item.created { item: { role: "user", content: [{ transcript }] } }
     |
     |   [RESPONDING] Pipeline STT → LLM → TTS
     |     3. response.created { response: { id, status: "in_progress" } }
     |     4. response.audio_transcript.delta { delta: "texto parcial..." }  (multiples)
     |     5. response.audio.delta { delta: "<base64 PCM16 24kHz>" }        (multiples)
     |     6. response.audio_transcript.done { transcript: "texto completo" }
     |     7. response.audio.done
     |     8. response.done { response: { status: "completed" } }
     |
     |   [SPEAKING] Cliente reproduce audio
     |   Cuando AudioTrack termina → volver a [LISTENING]
```

## Eventos cliente → servidor

| Evento | Cuando | Payload |
|--------|--------|---------|
| `session.update` | Tras session.created | `{ session: { voice, instructions, turn_detection, input_audio_transcription, tts_model } }` |
| `input_audio_buffer.append` | Continuamente mientras graba | `{ audio: "<base64 PCM16 16kHz>" }` |
| `input_audio_buffer.commit` | Manual (si VAD desactivado) | `{}` |
| `input_audio_buffer.clear` | Al interrumpir | `{}` |
| `response.create` | Manual (si VAD desactivado) | `{}` |
| `response.cancel` | Para interrumpir respuesta | `{}` |

## Eventos servidor → cliente

| Evento | Significado |
|--------|-------------|
| `session.created` | Sesion lista |
| `session.updated` | Configuracion aplicada |
| `input_audio_buffer.speech_started` | VAD detecto inicio de habla |
| `input_audio_buffer.speech_stopped` | VAD detecto fin de habla → auto-commit |
| `input_audio_buffer.committed` | Audio procesado |
| `conversation.item.created` | Transcripcion del usuario anadida a la conversacion |
| `response.created` | Respuesta iniciada |
| `response.audio_transcript.delta` | Texto parcial de la respuesta del LLM |
| `response.audio.delta` | Chunk de audio TTS en base64 (PCM16 24kHz) |
| `response.audio_transcript.done` | Texto completo de la respuesta |
| `response.audio.done` | Todo el audio TTS enviado |
| `response.done` | Respuesta completada (incluye usage) |
| `error` | Error en la sesion |

## Maquina de estados en Android

```
IDLE → CONNECTING → LISTENING ←→ TRANSCRIBING → RESPONDING → SPEAKING → LISTENING
                                                       ↑
                                         (interrupt) ──┘
```

### Transiciones

| Desde | Evento | Hacia |
|-------|--------|-------|
| IDLE | startSession() | CONNECTING |
| CONNECTING | onConnected + session.created | LISTENING |
| LISTENING | speech_started | TRANSCRIBING |
| TRANSCRIBING | speech_stopped | RESPONDING |
| RESPONDING | response.audio.delta (1er chunk) | SPEAKING |
| SPEAKING | AudioTrack termina de reproducir | LISTENING |
| SPEAKING | interrupt() | LISTENING |
| Cualquiera | error / disconnect | IDLE |

### Regla critica: no grabar mientras habla

**No procesar audio del microfono mientras el AudioTrack esta reproduciendo.** Si el microfono captura la salida del speaker, el VAD detectara "habla" y creara un bucle de retroalimentacion. Opciones:

1. **Pausar envio de audio** durante SPEAKING (implementacion actual)
2. Enviar audio pero ignorar speech_started/speech_stopped durante SPEAKING
3. Implementar AEC (echo cancellation) - mas complejo

La opcion 1 es la mas simple y robusta.

## Interrupcion (barge-in)

Cuando el usuario quiere interrumpir al asistente:

```kotlin
fun interrupt() {
    realtimeClient.cancelResponse()   // Cancela generacion LLM+TTS
    realtimeClient.clearAudio()       // Limpia buffer del servidor
    audioPlayer.stop()                // Para reproduccion inmediatamente
    setState(LISTENING)               // Vuelve a escuchar
}
```

## Configuracion de sesion

```json
{
  "type": "session.update",
  "session": {
    "voice": "alloy",
    "instructions": "Eres un asistente de voz. Responde de forma concisa.",
    "modalities": ["text", "audio"],
    "turn_detection": {
      "type": "server_vad",
      "threshold": 0.5,
      "silence_duration_ms": 800,
      "prefix_padding_ms": 300
    },
    "input_audio_transcription": {
      "model": "whisper/Systran/faster-whisper-base"
    },
    "tts_model": "tts/mlx-community/Kokoro-82M-bf16"
  }
}
```

### Parametros de VAD

- `threshold` (0.0-1.0): sensibilidad de deteccion de habla. 0.5 es buen default.
- `silence_duration_ms`: silencio necesario para considerar que el usuario termino. 800ms para conversacion natural, 500ms para respuestas rapidas.
- `prefix_padding_ms`: audio antes del inicio de habla que se incluye. 300ms evita cortar el inicio.

### Voces disponibles

Los nombres OpenAI (alloy, echo, fable, nova, onyx, shimmer) se mapean automaticamente a las voces nativas de cada modelo TTS:

| OpenAI | Kokoro | Bark | Qwen CustomVoice |
|--------|--------|------|-------------------|
| alloy | af_alloy | v2/en_speaker_0 | ryan |
| echo | am_echo | v2/en_speaker_1 | aiden |
| fable | bm_fable | v2/en_speaker_2 | serena |
| onyx | am_onyx | v2/en_speaker_3 | uncle_fu |
| nova | af_nova | v2/en_speaker_4 | vivian |
| shimmer | af_sky | v2/en_speaker_5 | sohee |

## Configuracion de audio en Android

### Grabacion (AudioRecord)

```kotlin
val sampleRate = 16000
val channelConfig = AudioFormat.CHANNEL_IN_MONO
val audioFormat = AudioFormat.ENCODING_PCM_16BIT
val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

// Enviar chunks cada 100ms (~3200 bytes)
val chunkSizeBytes = sampleRate * 2 * 100 / 1000  // 3200
```

### Reproduccion (AudioTrack)

```kotlin
val sampleRate = 24000  // ← TTS output rate
val channelConfig = AudioFormat.CHANNEL_OUT_MONO
val audioFormat = AudioFormat.ENCODING_PCM_16BIT
val mode = AudioTrack.MODE_STREAM
```

## Errores comunes

| Problema | Causa | Solucion |
|----------|-------|----------|
| Audio distorsionado/acelerado | AudioTrack a 16kHz, audio llega a 24kHz | Configurar AudioTrack a 24000 Hz |
| Asistente se escucha a si mismo | Mic activo durante reproduccion | Pausar envio de audio durante SPEAKING |
| Sin respuesta de audio | STT/TTS no configurados en servidor | Especificar en session.update o configurar defaults en Settings de oCabra |
| WebSocket close 1008 | API key invalida o revocada | Verificar key en Settings → API Keys |
| Primera respuesta lenta | Modelos cargandose on-demand | El servidor carga STT+TTS la primera vez; las siguientes son rapidas |
| Eco / bucle de feedback | VAD detecta la salida del speaker | No enviar audio durante SPEAKING |

## Estructura del codigo Android

```
realtime/
├── RealtimeClient.kt               # WebSocket, envio/recepcion de eventos JSON
├── RealtimeEvents.kt                # SessionConfig, Usage, State, Listener interface
├── RealtimeAudioPlayer.kt           # AudioTrack wrapper, streaming, deteccion de fin
└── RealtimeConversationManager.kt   # Orquestador: mic + client + player + estados
```

## Ejemplo de uso desde la app

```kotlin
val manager = RealtimeConversationManager(
    context = this,
    settingsManager = settingsManager,
    onTranscript = { text -> showUserMessage(text) },
    onResponse = { text -> showAssistantMessage(text) },
    onStateChange = { state -> updateUI(state) },
    onError = { msg -> showError(msg) }
)

// Iniciar sesion de voz
manager.startSession()

// Interrumpir (barge-in)
manager.interrupt()

// Terminar sesion
manager.stopSession()
```
