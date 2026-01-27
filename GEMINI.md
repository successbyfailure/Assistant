# Assistant - Asistente Agéntico para Android

## Visión General

Assistant es un asistente de voz y texto altamente configurable para Android, diseñado para ser rápido, fluido y compatible con ecosistemas abiertos. Soporta múltiples endpoints de IA (OpenAI, Ollama, LocalAI) con capacidades de streaming en tiempo real y failover automático.

---

## Estado del Proyecto - v0.2.0 (Desarrollo)

### Qué Funciona

| Categoría | Funcionalidad | Estado |
|-----------|---------------|--------|
| **Endpoints** | OpenAI, Ollama (Cloud/Self-hosted), Groq, Together.AI, Mistral, LocalAI | ✅ |
| **Streaming** | SSE en tiempo real con failover automático | ✅ |
| **Tools** | send_sms, make_call, set_alarm, search_contacts, get_location, open_app, get_weather, read_notifications | ✅ |
| **MCP** | Cliente MCP local y remoto (JSON-RPC 2.0) | ✅ |
| **STT Local** | Whisper TFLite (tiny/base/small) con VOICE_RECOGNITION | ✅ |
| **LLM Local** | MediaPipe .task, Gemini Nano (AICore), TFLite | ✅ |
| **UI** | Overlay de voz optimizado, PTT con gestos, Visualizador Ultra-HD | ✅ |
| **Eviction** | Liberación automática de modelos por LRU e inactividad | ✅ |

### Limitaciones Conocidas (v0.2.0)

| Issue | Descripción | Workaround |
|-------|-------------|------------|
| **LlmTokenizer básico** | No implementa SentencePiece BPE/Unigram | Usar MediaPipe .task o GeminiNano |
| **Whisper GPU inestable** | Algunos dispositivos fallan con GPU delegate | Fallback automático a CPU |
| **Modelos multimodales** | SmolVLM requiere tokenizer especial | No soportado aún |

---

## Roadmap v0.3.0+

### Fase 4.0 - Ecosistema y Feedback (Prioridad Actual)

#### 🔄 Sistema de Agentes y Sub-agentes
- **Agentes Especializados**: Diferenciar el agente del **Overlay** (rápido, orientado a sistema) del agente del **Chat** (razonamiento profundo, herramientas complejas).
- **Orquestador**: Implementar un sistema donde un agente "Padre" analiza la intención y delega a sub-agentes específicos (Domótica, Comunicación, MCP).

#### 🛠 Feedback y Desarrollo Comunitario
- **GitHub Integration**:
    - Investigar e implementar login con GitHub (OAuth/Webview).
    - Tool `create_github_issue`: Permitir al usuario crear issues directamente desde la voz/chat.
    - Exportación de logs de diagnóstico adjuntos al issue.

### Fase 5.0 - Multimodalidad y Routing Pro

- **Vision API**: Captura de pantalla y cámara como contexto para el agente.
- **Dynamic Routing**: Seleccionar automáticamente entre Local LLM o Cloud LLM basado en latencia, batería y complejidad de la tarea.
- **Audio Nativo**: Pasar de STT -> LLM a modelos de audio-a-audio (Gemma 3n).

---

## Arquitectura de Audio (Optimizado para STT)

El sistema utiliza la fuente `VOICE_RECOGNITION` para obtener audio crudo del hardware, evitando el post-procesamiento de Android (AGC/NS) que suele degradar la precisión de Whisper.

**Configuración de Detección de Silencio:**
- **Calibración:** 300ms de ruido ambiental inicial.
- **Detección:** Basada en RMS dinámico con umbral auto-ajustable.
- **Margen:** 2000ms de silencio sostenido antes de cerrar la captura.

---

## Catálogo de Modelos Recomendados

| Tipo | Modelo | Tamaño | Notas |
|------|--------|--------|-------|
| **STT** | `whisper-tiny-transcribe-translate.tflite` | 42 MB | ⭐ Ideal para uso diario |
| **LLM** | Gemini Nano (AICore) | Sistema | Máxima velocidad en dispositivos soportados |
| **LLM** | `gemma-2b-it-cpu-int4.task` | ~1.3 GB | MediaPipe, razonamiento sólido |

---

## Referencias y Créditos
- [MediaPipe LLM Inference](https://developers.google.com/mediapipe/solutions/genai/llm_inference)
- [Whisper TFLite Models](https://huggingface.co/DocWolle/whisper_tflite_models)
