# Assistant - Asistente Agéntico para Android

## Visión General

Assistant es un asistente de voz y texto altamente configurable para Android, diseñado para ser rápido, fluido y compatible con ecosistemas abiertos. Soporta múltiples endpoints de IA (OpenAI, Ollama, LocalAI) con capacidades de streaming en tiempo real y failover automático.

---

## Estado del Proyecto - v0.1.0

### Qué Funciona

| Categoría | Funcionalidad | Estado |
|-----------|---------------|--------|
| **Endpoints** | OpenAI, Ollama (Cloud/Self-hosted), Groq, Together.AI, Mistral, LocalAI | ✅ |
| **Streaming** | SSE en tiempo real con failover automático | ✅ |
| **Tools** | send_sms, make_call, set_alarm, search_contacts, get_location, open_app, get_weather, read_notifications | ✅ |
| **MCP** | Cliente MCP local y remoto (JSON-RPC 2.0) | ✅ |
| **STT Local** | Whisper TFLite (tiny/base/small) | ✅ |
| **LLM Local** | MediaPipe .task, Gemini Nano (AICore), TFLite | ✅ |
| **TTS** | System TTS + Cloud TTS | ✅ |
| **UI** | Chat con Markdown, historial persistente, dark mode | ✅ |

### Limitaciones Conocidas (v0.1.0)

| Issue | Descripción | Workaround |
|-------|-------------|------------|
| **Activación por gesto** | ASSIST/VOICE_COMMAND funciona (abre `ChatActivity`). El gesto de “Circle to Search” es otra función del sistema y no se puede reemplazar por apps de terceros | Usar long‑press del botón Home / gesto estándar de asistente |
| **Android 16 (ZUI) Power‑button** | En Android 16, el gesto de Power (mantener pulsado) puede abrir el asistente; si aparece “Assistant ha dejado de funcionar”, era un crash del overlay por tema Material3 en `VoiceInteractionSession` | Se corrigió inflando el overlay con `Theme.Assistant` (ContextThemeWrapper) |
| **Auto‑silencio STT** | Para STT no‑sistema, se añadió auto‑stop por silencio con calibración rápida de ruido y cue háptico/sonoro | Tap en mic para auto‑silencio; long‑press = PTT |
| **STT remoto** | STT remoto validado y funcionando correctamente | Sin workaround |
| **LlmTokenizer básico** | No implementa SentencePiece BPE/Unigram | Usar MediaPipe .task o GeminiNano |
| **Whisper GPU inestable** | Algunos dispositivos fallan con GPU delegate | Fallback automático a CPU |
| **MediaPipe .task crashes** | Algunos modelos crashean en native | Validar modelo antes de usar |
| **Modelos multimodales** | SmolVLM requiere tokenizer especial | No soportado en v0.1.0 |
| **TTS/STT remoto** | Falta diagnóstico y logs claros | Usar local cuando sea posible |

### Modelos Recomendados para v0.1.0

| Tipo | Modelo | Tamaño | Notas |
|------|--------|--------|-------|
| **STT** | `whisper-tiny-transcribe-translate.tflite` | 42 MB | Multilingüe, rápido |
| **LLM** | Gemini Nano (AICore) | Sistema | Requiere dispositivo compatible |
| **LLM** | `gemma-2b-it-cpu-int4.task` | ~1.3 GB | MediaPipe, tokenizer incluido |

### Requisitos Mínimos

- **Android:** 8.0+ (API 26), recomendado 14+ para AICore
- **RAM:** 4GB mínimo
- **Storage:** 500MB base + modelos descargados
- **Permisos:** INTERNET, RECORD_AUDIO, READ_CONTACTS, SEND_SMS, CALL_PHONE, etc.

---

## Historial de Desarrollo

<details>
<summary><b>Fase 1.0 - Estabilidad y Configuración</b> ✅</summary>

- Estructura VoiceInteractionService para ser Asistente del Sistema
- Cliente OpenAI compatible con Streaming SSE
- Gestión de múltiples endpoints con failover
- ChatActivity con RecyclerView y Markdown
- Persistencia con SharedPreferences + JSON
</details>

<details>
<summary><b>Fase 1.5 - Endpoints y Conectividad</b> ✅</summary>

- Soporte Ollama Cloud (API Key) y Self-Hosted (auto-discovery)
- Templates preconfigurados (OpenAI, Groq, Together.AI, Mistral)
- Health Check con WorkManager y notificaciones
- Caché de modelos disponibles (TTL 1 hora)
</details>

<details>
<summary><b>Fase 2.0 - Capacidades Agénticas</b> ✅</summary>

- Function calling con ToolRegistry y ToolExecutor
- Sistema de Tool Gating (permisos + confirmaciones)
- Cliente MCP (Model Context Protocol) local y remoto
- Whisper on-device con TFLite
- MediaPipe LLM Inference API
- Endpoints locales integrados en flujo normal
</details>

<details>
<summary><b>Fase 2.5 - Optimizaciones</b> ✅ (95%)</summary>

- TtsController, PermissionController, WhisperController
- Tests unitarios e integración
- UI: progress bars, bubble de tools, cancelación, autoscroll
- Descarga de modelos desde HuggingFace
- GPU delegate con fallback a CPU
- **Pendiente:** Eviction real de modelos inactivos
</details>

---

## Roadmap v0.2.0+

### Prioridad Alta - Estabilidad

| Task | Descripción | Complejidad |
|------|-------------|-------------|
| **Activación por gesto/botón** | ✅ Agregados intent-filters ASSIST/VOICE_COMMAND - validar en dispositivo | Completado |
| **Eviction real de modelos** | Liberar instancias inactivas (no solo tracking) | Media |
| **Validar modelos MediaPipe** | Lista de modelos .task compatibles | Baja |
| **Diagnóstico TTS/STT remoto** | Logs claros, latencias, rutas de error | Media |
| **Estabilidad modelos locales** | Trazas detalladas para inconsistencias | Media |

### Prioridad Media - Features

| Task | Descripción | Complejidad |
|------|-------------|-------------|
| **GPU Whisper estable** | Diagnosticar y ajustar delegate por modelo/dispositivo | Alta |
| **Mejor feedback de errores** | Mensajes claros, estados y recuperación | Baja |
| **Telemetría en chat** | Consumo de memoria, estado de modelo | Baja |
| **Curar catálogo de modelos** | Probar y seleccionar los mejores; presets iniciales | Media |

### Prioridad Baja - Futuro

| Task | Descripción | Complejidad |
|------|-------------|-------------|
| **Categorías → Agentes** | Configuraciones de agentes (modelo + tools) | Alta |
| **Tokenizer SentencePiece** | JNI binding o HuggingFace tokenizers | Alta |
| **Vision API** | Captura de pantalla, cámara | Alta |
| **Image Generation** | DALL-E / Stable Diffusion | Media |
| **Routing cloud/local** | Heurísticas automáticas (batería, conexión) | Media |

---

## Fase 3.0 - Procesamiento Multimodal (Planificado)

**Objetivo:** Capacidades de visión, generación de imágenes y audio avanzado.

### 3.1 Vision
- [ ] Captura de pantalla para contexto visual
- [ ] Camera input para "ver" el mundo
- [ ] Integrar con tool `analyze_screen`

### 3.2 Generación de Imágenes
- [ ] Integrar DALL-E / Stable Diffusion
- [ ] Preview y descarga de imágenes generadas

### 3.3 Audio Pro
- [ ] Modelos de voz expresivos (ElevenLabs, OpenAI Voice)
- [ ] Audio directo a Gemma 3n (bypass STT)

---

## Catálogo de Modelos

### STT - Whisper TFLite

| Modelo | Tamaño | Idiomas | Uso |
|--------|--------|---------|-----|
| `whisper-tiny-transcribe-translate.tflite` | 42 MB | 100+ | ⭐ Recomendado - Balance ideal |
| `whisper-tiny.en.tflite` | 41.5 MB | EN | Ultra rápido, solo inglés |
| `whisper-base-transcribe-translate.tflite` | 78.5 MB | 100+ | Mayor precisión |
| `whisper-base.es.tflite` | 78.4 MB | ES | Especializado español |
| `whisper-base.en.tflite` | 78.4 MB | EN | Especializado inglés |
| `whisper-small-transcribe-translate.tflite` | 249 MB | 100+ | Alta precisión |

**Vocabularios requeridos:**
- `filters_vocab_en.bin` (586 KB) - Para modelos .en
- `filters_vocab_multilingual.bin` (572 KB) - Para modelos multilingual

### LLM - MediaPipe .task

| Modelo | Tamaño | Descripción |
|--------|--------|-------------|
| `gemma-2b-it-cpu-int4.task` | ~1.3 GB | ⭐ Recomendado - Tokenizer incluido |
| `gemma-2b-it-gpu-int4.task` | ~1.3 GB | Con aceleración GPU |

### LLM - TFLite (requiere LlmTokenizer)

| Modelo | Tamaño | Notas |
|--------|--------|-------|
| `gemma-3-270m-it.tflite` | ~300 MB | Ultra ligero |
| `qwen2.5-1.5b-instruct.tflite` | ~1.5 GB | Balance calidad/velocidad |
| `phi-4-mini-instruct.tflite` | ~1.8 GB | Razonamiento complejo |
| `gemma2-2b-it.tflite` | ~2 GB | Estable y confiable |

**⚠️ Nota:** Los modelos TFLite LLM usan `LlmTokenizer` básico. Resultados variables con prompts complejos. Preferir MediaPipe .task o GeminiNano.

### LLM - Gemini Nano (AICore)

- Gestionado por ML Kit GenAI
- Requiere dispositivo compatible (Pixel 8+, Samsung Galaxy S24+)
- Tokenizer y modelo optimizados por Google

---

## Arquitectura de IA Local

```
Assistant App
    ↓
┌─────────────────────────────────────────┐
│ LocalLlmService │ MediaPipeLlmService │ GeminiNanoService │
│   (TFLite)      │      (.task)        │    (ML Kit)       │
└─────────────────────────────────────────┘
    ↓                    ↓                      ↓
┌─────────────────────────────────────────┐
│           LiteRT Runtime (TFLite)       │
└─────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────┐
│    Hardware: CPU / GPU / NPU            │
└─────────────────────────────────────────┘
```

### Comparativa de Servicios LLM Local

| Servicio | Tokenizer | GPU | Estabilidad | Recomendado |
|----------|-----------|-----|-------------|-------------|
| `GeminiNanoService` | ✅ ML Kit | ✅ NPU | ✅ Alta | ⭐ Si disponible |
| `MediaPipeLlmService` | ✅ Integrado | ✅ GPU | ⚠️ Algunos crashes | ⭐ Alternativa |
| `LocalLlmService` | ⚠️ Básico | ✅ GPU | ⚠️ Variable | Solo prompts simples |

---

## Limitaciones del LlmTokenizer

El tokenizer actual (`LlmTokenizer.kt`) es una implementación básica:

- Tokenización a nivel de palabra con fallback a caracteres
- NO implementa algoritmos SentencePiece (BPE/Unigram)
- Funciona aceptablemente con prompts simples
- Textos complejos pueden tokenizarse incorrectamente

**Modelos afectados:** Solo `LocalLlmService` con modelos `.tflite` LLM

**Modelos NO afectados:**
- `MediaPipeLlmService` - Tokenizer integrado en .task
- `GeminiNanoService` - Tokenizer gestionado por ML Kit
- `LocalWhisperService` - Usa vocabulario propio

**Opciones futuras:**
1. Usar modelos MediaPipe .task (tokenizer incluido)
2. Implementar JNI binding a SentencePiece nativo
3. Usar tokenizers de HuggingFace (dependencia adicional)

---

## Referencias

### Documentación Oficial
- [LiteRT Documentation](https://ai.google.dev/edge/litert)
- [MediaPipe LLM Inference](https://developers.google.com/mediapipe/solutions/genai/llm_inference)
- [ML Kit GenAI](https://developers.google.com/ml-kit/genai)
- [TensorFlow Lite Guide](https://www.tensorflow.org/lite/guide)

### Repositorios de Modelos
- [LiteRT Community Collection](https://huggingface.co/collections/litert-community/android-models)
- [Whisper TFLite Models](https://huggingface.co/DocWolle/whisper_tflite_models)
- [MediaPipe Models](https://developers.google.com/mediapipe/solutions/genai/llm_inference#models)

### Código de Referencia
- [LiteRT-LM GitHub](https://github.com/google-ai-edge/LiteRT-LM)
- [Gemma Cookbook](https://github.com/google-gemini/gemma-cookbook)
- [whisperIME (Whisper Android)](https://github.com/woheller69/whisperIME)
