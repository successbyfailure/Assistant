# Assistant - Asistente Agéntico para Android

## Visión General
Assistant es un asistente de voz y texto altamente configurable para Android, diseñado para ser rápido, fluido y compatible con ecosistemas abiertos. Soporta múltiples endpoints de IA (OpenAI, Ollama, LocalAI) con capacidades de streaming en tiempo real y failover automático.

## Estado del Proyecto - Fase 1.5 (✅ COMPLETADA)

### Infraestructura Base
- [x] Estructura de `VoiceInteractionService` para ser Asistente Predeterminado del Sistema
- [x] `AssistantSettingsActivity` configurada como punto de entrada de configuración
- [x] Overlay de interacción rápida para activación del sistema
- [x] Cliente compatible con OpenAI con soporte de **Streaming (SSE)**
- [x] Gestión de múltiples Endpoints (OpenAI, Ollama, LocalAI, APIs compatibles)
- [x] Configuración por categorías (**Agent, STT, TTS, ImageGen, OCR**)
- [x] Sistema de **Failover** automático (Modelo Principal y de Respaldo)
- [x] Carga dinámica de modelos desde el servidor

### Interfaz de Usuario
- [x] **ChatActivity** como pantalla principal con interfaz conversacional
- [x] Menú de tres puntos (⋮) para acceso a configuración y opciones
- [x] RecyclerView con adaptador de chat para historial de mensajes
- [x] Manejo correcto de **WindowInsets** para edge-to-edge display
- [x] Soporte para navegación gestural (Pixel 8, dispositivos modernos)
- [x] Ajuste automático del layout con el teclado (`adjustResize`)

### Capacidades de IA
- [x] Integración de **Text-to-Speech (TTS)** con reproducción automática
- [x] Integración de **Speech-to-Text (STT)** con permisos en runtime
- [x] Streaming de respuestas token por token en tiempo real
- [x] Persistencia de configuración con SharedPreferences + JSON

### Experiencia de Usuario
- [x] Mensajes de bienvenida y retroalimentación de estado
- [x] Indicadores visuales de estado (Listening, Processing, Thinking, Ready)
- [x] Manejo de errores con mensajes descriptivos
- [x] Opción de limpiar historial de chat

## Plan de Desarrollo

### ✅ Fase 1: Estabilidad y Configuración (COMPLETADA)
Base sólida con interfaz conversacional, streaming, failover y soporte multi-endpoint.

---

### ✅ Fase 1.5: Mejoras de Endpoints y Conectividad (COMPLETADA)
**Objetivo:** Soporte completo y optimizado para todos los tipos de endpoints de Ollama y servicios cloud.

#### 1.5.1 Soporte para Ollama.com (Cloud con API Key)
- [x] **Detectar tipo de endpoint Ollama** en el diálogo de configuración
- [x] **Selector de tipo de endpoint:** Ollama Cloud, Self-Hosted, OpenAI, etc.
- [x] **Configuración específica para Ollama Cloud:** URL predefinida, API Key obligatoria y link de registro.
- [x] **Implementar caché de modelos disponibles** (TTL de 1 hora) con `ModelCacheManager`.
- [x] **Manejo de errores HTTP específicos** (401, 402, 429, 404) con mensajes claros.

#### 1.5.2 Soporte Mejorado para Ollama Self-Hosted
- [x] **Auto-descubrimiento de servidores** mediante escaneo de subred en puerto 11434.
- [x] **Botón "Scan"** integrado en el flujo de añadir endpoint.
- [x] **Validación de conectividad** y formateo automático de URL local.

#### 1.5.3 Template de Endpoints Preconfigurados
- [x] **Botón "Templates"** en MainActivity.
- [x] **Plantillas para:** OpenAI, Ollama Cloud, Groq, Together.AI, Mistral AI y LocalAI.
- [x] **Configuración automática:** URLs, ayuda contextual y requerimientos de API Key pre-cargados.

#### 1.5.4 Sistema de Health Check
- [x] **Background worker** (WorkManager) para monitoreo periódico.
- [x] **Indicadores visuales en tiempo real:** Punto de estado (Verde/Rojo) y latencia en ms en la lista de endpoints.
- [x] **Notificaciones push** si el endpoint principal queda fuera de línea.
- [x] **Modo Opcional:** Switch para activar/desactivar la monitorización constante.

---

### ✅ Fase 2: Capacidades Agénticas (COMPLETADA)
**Objetivo:** Transformar el asistente en un agente capaz de ejecutar acciones mediante function calling.

#### 2.1 Implementación de Tools (Function Calling)
**Descripción:** Permitir que el modelo LLM invoque funciones nativas de Android para realizar acciones.

**Tareas:**
- [x] **Definir esquema de tools en JSON** según especificación OpenAI
- [x] **Crear clase `ToolRegistry`** para registrar y gestionar funciones disponibles
- [x] **Implementar `ToolExecutor`** que mapee llamadas del modelo a código Kotlin
- [x] **Modificar `OpenAiClient`** para enviar tools en el request y procesar `tool_calls` en la respuesta
- [x] **Crear tools básicos iniciales:** `send_sms`, `make_call`, `set_alarm`, `search_contacts`, `get_location`, `open_app`, `get_weather`, `read_notifications`.
- [x] **Sistema de Tool Gating:** Control de permisos y confirmaciones de usuario por herramienta
- [x] **UI de configuración de Tools:** Switches para habilitar/deshabilitar y solicitar confirmación

#### 2.2 Soporte para MCP (Model Context Protocol)
- [x] **Implementar cliente MCP** siguiendo la especificación de Anthropic
- [x] **Crear adaptador MCP-to-Tools** que convierta servidores MCP en tools ejecutables
- [x] **Desarrollar MCPs básicos:** Sistema de Archivos, Calendario, Notas.
- [x] **Soporte para MCP remoto:** Cliente HTTP con JSON-RPC 2.0
- [x] **UI de configuración MCP:** Gestión de servidores MCP locales y remotos

#### 2.3 Procesamiento Local Avanzado
- [x] **STT Híbrido:** Opción de STT nativo Android (Local) vs Cloud Whisper.
- [x] **Whisper On-Device (C++ / JNI):** Integrar `whisper.cpp` para transcripción local de alta precisión.
- [x] **Gemma 2B Mobile Actions:** Integrar MediaPipe LLM Inference API para razonamiento local.
- [x] **Sistema de Endpoints Locales:** Modelos locales como endpoints configurables (local_gemma, local_whisper)
- [x] **Arquitectura unificada:** Sin routing especial, modelos locales integrados en el flujo normal

#### 2.4 Refactorizaciones y Optimizaciones
- [x] **ChatController:** Lógica compartida de chat extraída (~250 líneas de código duplicado eliminadas)
- [x] **Correcciones de seguridad:** 32 bugs críticos corregidos (memory leaks, race conditions, null pointers)
- [x] **Sistema de permisos robusto:** Validaciones en todas las operaciones sensibles
- [x] **Cleanup de recursos:** Gestión automática de archivos temporales (audio, TTS)

---

### 🔧 Fase 2.5: Optimizaciones de Arquitectura (EN PROGRESO)
**Objetivo:** Mejorar la mantenibilidad, testabilidad y organización del código.

#### 2.5.1 Extracción de Controladores
- [x] **TtsController:** Centralizar lógica de TTS (System TTS + Cloud TTS con MediaPlayer)
- [x] **PermissionController:** Unificar manejo de permisos runtime en una clase reutilizable
- [x] **WhisperController:** Consolidar lógica de grabación y transcripción (local + cloud)

#### 2.5.2 Testing
- [x] **Tests unitarios para ChatController:** Verificar lógica de streaming y failover
- [x] **Tests de integración para ToolExecutor:** Verificar ejecución correcta de herramientas
- [x] **Tests de MCP:** Verificar comunicación con servidores locales y remotos

#### 2.5.3 Mejoras de UI/UX
- [x] **Indicadores de progreso:** Barra indeterminada durante tools y "Thinking"
- [x] **Historial persistente:** Guardar conversaciones en almacenamiento local
- [x] **Temas personalizables:** Dark mode, light mode, y temas custom
- [x] **Shortcuts de voz:** Activacion mediante palabras clave personalizadas
- [x] **Soporte Markdown:** Renderizado de respuestas del LLM
- [x] **Bubble de tools:** Nombres, cancelacion, icono de estadisticas y tokens
- [x] **Cancelar solicitud:** Boton de enviar alterna a cancelar
- [x] **Timeout configurable de tools**
- [x] **Autoscroll inteligente** al top de la respuesta
- [x] **Aprovechar ancho de chat** y reducir margenes
- [x] **Barra de estado con tokens/tiempo** por solicitud

#### 2.5.4 Opciones de Modelos Locales (PENDIENTE)
- [x] **UI de seleccion local:** Exponer opciones para modelos locales en ajustes
- [x] **Visibilidad de modelos descargados:** Mostrar estado y selector activo
- [x] **Preferencias por categoria:** STT/LLM/TTS locales con prioridad clara
- [x] **AICore status:** Mostrar disponibilidad del SDK/servicio de IA
- [x] **Descargas LiteRT/TFLite:** UI para descargar modelos locales
- [x] **Secciones locales:** AICore/LiteRT LM, TFLite, MediaPipe con cabeceras claras
- [x] **Botones de test por modelo:** STT (grabar + transcribir) y LLM (ping)
- [x] **Indicador de modelo en memoria:** Tamaño y estado por tarjeta
- [ ] **Carga bajo demanda con eviction real:** Aun falta liberar instancias de modelos inactivos (solo tracking)

#### Notas recientes (estado actual)
- ✅ **GPU delegate para Whisper:** Se incluye LiteRT GPU y se intenta cargar con GPU; si falla, hace fallback automatico a CPU.
- ✅ **Crash por GpuDelegateFactory$Options:** Resuelto con dependencias LiteRT GPU (ya no reinicia al cargar).
- ⚠️ **Whisper GPU inestable:** En algunos dispositivos el delegate falla con "Error applying delegate" y se usa CPU (mas lento).
- ✅ **LLM local TFLite y MediaPipe:** Tests implementados (ping) con inicializacion de modelos descargados.
- ⚠️ **LLM multimodal TFLite:** No soportado por tokenizer actual (SmolVLM requiere otro formato).
- ⚠️ **Modelos grandes y memoria:** Se removio el pre-check de memoria; el sistema decide y puede fallar en runtime si no hay RAM.
- ⚠️ **MediaPipe .task:** Algunos modelos pueden crashear en native (`libllm_inference_engine_jni.so`), requiere validar compatibilidad por modelo.
- ⚠️ **Alineamiento 16KB:** `libassistant.so` se ajusta con flags; libs externas siguen con warning si no estan alineadas.
- ⚠️ **STT whisper local sin GPU:** el modelo de whisper de tflite no esta corriendo en GPU; hay que diagnosticarlo y valorar alternativas para tener Stt local acelerado en tiempo real.
- ⚠️ **TTS/STT remoto sin diagnostico:** El TTS/STT remoto no esta bien probado ni instrumentado (faltan logs/errores claros).
- ⚠️ **Resultados inconsistentes en modelos locales:** Respuestas variables y comportamiento inestable; requiere diagnostico y trazas.

#### Pendientes inmediatos (modelos locales)
- [ ] **Eviction real de modelos:** Descargar instancias inactivas (liberar memoria, no solo tracking).
- [ ] **Validar modelos MediaPipe .task** con lista de modelos compatibles para evitar crashes.
- [ ] **Resolver tokenizer multimodal** (SmolVLM requiere tokenizer/modelos extra).
- [ ] **Confirmar soporte GPU Whisper por modelo/dispositivo** y ajustar delegate (o forzar CPU si el modelo no soporta GPU).
- [ ] **Diagnostico completo de TTS:** Verificar TTS remoto/local, latencias y rutas de error.
- [ ] **Estabilidad modelos locales:** Logs detallados y repros para entender inconsistencias.

#### Planes futuros (UX/arquitectura)
- [ ] **Categorias → Agentes:** Reemplazar categorias por configuraciones de agentes con modelo + tools.
- [ ] **Curar catalogo de modelos:** Probar y seleccionar los que mejor funcionan; definir presets iniciales.
- [ ] **Telemetria en chat:** Mostrar consumo de memoria y estado de carga del modelo.
- [ ] **Mejor feedback de errores:** Mensajes claros, estados y recuperacion.
- [ ] **Limpieza y reordenamiento:** Eliminar codigo de pruebas y reorganizar para max eficiencia.

---

### 🎨 Fase 3: Procesamiento Multimodal (FUTURO)
**Objetivo:** Capacidades de visión, generación de imágenes y procesamiento de audio avanzado.
- [ ] **Vision API:** Permitir que el asistente "vea" la pantalla o la cámara.
- [ ] **Image Generation:** Integrar DALL-E / Stable Diffusion.
- [ ] **Audio Pro:** Soporte para modelos de voz expresivos (ElevenLabs, OpenAI Voice).

---

## 🧠 Nueva Arquitectura de IA Local - LiteRT + AICore

### Estado Actual (Legacy - A Migrar)
- ⚠️ **Whisper.cpp integrado:** Funcional pero requiere JNI complejo
- ⚠️ **Gemma 2B con MediaPipe:** Limitado, sin multimodal
- ✅ **Arquitectura unificada:** Modelos locales como endpoints normales (mantener)

### Nueva Estrategia: LiteRT + AICore (Google Official Stack)

### Arquitectura LiteRT + AICore

**Stack Completo:**
```
Assistant App
    ↓
ML Kit GenAI APIs / LiteRT-LM C++ (preview)
    ↓
AICore (Android System Service)
    ↓
LiteRT Runtime (ex-TFLite)
    ↓
Hardware Acceleration (CPU/GPU/NPU)
```

**Beneficios:**
- ✅ **Stack oficial de Google** - Soporte a largo plazo garantizado
- ✅ **Aceleración automática** - NPU en Tensor, Snapdragon, Dimensity (5x más rápido)
- ✅ **Sin JNI complejo** - Todo en Kotlin/Java con APIs high-level
- ✅ **Actualizaciones vía Play Services** - Sin recompilar la app
- ✅ **Modelos pre-optimizados** - Colección oficial en [HuggingFace LiteRT Community](https://huggingface.co/collections/litert-community/android-models)

### Modelos Seleccionados

**Fuente principal de modelos (HF):**
- https://huggingface.co/collections/litert-community/android-models

#### 1. LLM Multimodal: Gemma 3n
**Modelo:** [google/gemma-3n-E2B-it-litert-lm](https://huggingface.co/google/gemma-3n-E2B-it-litert-lm)

**Características:**
- 📊 **Tamaño:** ~2GB RAM (5B params con arquitectura eficiente)
- 🎯 **Multimodal nativo:** Audio + Imagen + Video + Texto → Texto
- 🎤 **Audio encoder:** USM (Universal Speech Model)
- ⚡ **NPU acceleration:** 5x más rápido en Pixel 8 (Google Tensor G3)
- 🔧 **Capacidades:** Transcripción, traducción, razonamiento, visión

**Alternativa ligera:**
- [google/gemma-3n-E4B-it-litert-lm](https://huggingface.co/google/gemma-3n-E4B-it-litert-lm) - 3GB RAM, más capaz

**Otros modelos disponibles:**
- **Qwen2.5-1.5B-Instruct** (1.5GB) - Excelente para chat general
- **Phi-4-mini-instruct** - Optimizado para razonamiento
- **SmolVLM-256M-Instruct** (256MB) - Vision-Language ultra-ligero
- **DeepSeek-R1-Distill-Qwen-1.5B** - Razonamiento mejorado

#### 2. STT: Whisper TFLite (DocWolle Collection)
**Repositorio:** [DocWolle/whisper_tflite_models](https://huggingface.co/DocWolle/whisper_tflite_models)
**Total:** 18 modelos optimizados (2.26GB en total)
**Licencia:** MIT
**Descargas:** 5,095/mes

##### Modelos por Tamaño

| Tamaño | Modelo | Peso | Idiomas | Recomendación |
|--------|--------|------|---------|---------------|
| **Tiny** | `whisper-tiny.en.tflite` | 41.5 MB | Solo inglés | ⚡ Ultra rápido |
| **Tiny** | `whisper-tiny-transcribe-translate.tflite` | 42.1 MB | Multilingual | ⭐ **Balance ideal** |
| **Base** | `whisper-base-transcribe-translate.tflite` | 78.5 MB | Multilingual | ⭐ **Recomendado** |
| **Base** | `whisper-base.{es,de,fr,it,pt,ru,zh,hi,ur}.tflite` | 78.4 MB | 1 idioma | Especializado |
| **Base** | `whisper-base.EUROPEAN_UNION.tflite` | 94.8 MB | EU + Noruego | Para Europa |
| **Base** | `whisper-base.TOP_WORLD.tflite` | 108 MB | Top idiomas | Universal |
| **Small** | `whisper-small-transcribe-translate.tflite` | 249 MB | Multilingual | Alta precisión |
| **Small** | `whisper-small.TOP_WORLD.tflite` | 307 MB | Top idiomas | Mejor calidad |
| **Small** | `whisper-small.tflite` | 388 MB | Full multilingual | Máxima capacidad |

##### Características Técnicas

**Input:**
- Shape: `(1, 80, 3000)` - Mel-spectrogram float32
- 80 frequency bins × 3000 time steps
- ~30 segundos de audio por inferencia

**Output:**
- Tokens generados (max 450 configurable)
- Dual signatures: `serving_transcribe` + `serving_translate`

**Modos de Operación (Forced Decoder IDs):**
```kotlin
// Transcripción (cualquier idioma)
val transcribeIDs = arrayOf(
    intArrayOf(2, 50359),  // Modo transcribe
    intArrayOf(3, 50363)   // Sin timestamps
)

// Traducción al inglés
val translateIDs = arrayOf(
    intArrayOf(2, 50358),  // Modo translate
    intArrayOf(3, 50363)   // Sin timestamps
)

// Transcripción específica (ej: Español)
val spanishTranscribeIDs = arrayOf(
    intArrayOf(1, 50262),  // Español
    intArrayOf(2, 50359),  // Transcribe
    intArrayOf(3, 50363)   // Sin timestamps
)
```

**Decoder IDs Importantes:**
- `50358` - Traducir a inglés
- `50359` - Transcribir (mantener idioma original)
- `50363` - Sin timestamps (más rápido)
- `50261` - Alemán
- `50262` - Español
- Ver [lista completa de idiomas](https://github.com/woheller69/whisperIME/blob/master/app/src/main/java/com/whispertflite/utils/InputLang.java)

**Archivos Adicionales:**
- `filters_vocab_en.bin` (586 KB) - Vocabulario inglés
- `filters_vocab_multilingual.bin` (572 KB) - Vocabulario multilingual

##### Ventajas vs Whisper.cpp

| Característica | Whisper TFLite | Whisper.cpp (actual) |
|----------------|----------------|----------------------|
| Integración | ✅ Nativo Kotlin/Java | ❌ Requiere JNI |
| NPU Support | ✅ Via LiteRT delegation | ❌ Solo CPU |
| Tamaño APK | ✅ Sin libs nativas | ❌ +20MB por ABI |
| Mantenimiento | ✅ Oficial Google | ⚠️ Comunidad |
| Dual mode | ✅ Transcribe + Translate | ❌ Solo transcribe |
| Latencia (Pixel 8) | ✅ ~0.5s (tiny, NPU) | ~1s (tiny, CPU) |
| Idiomas específicos | ✅ 9 modelos optimizados | ❌ Solo multilingual |

##### Recomendaciones para Assistant

**Configuración por defecto sugerida:**
1. **Primary:** `whisper-tiny-transcribe-translate.tflite` (42MB)
   - Razón: Ultra rápido, soporta transcribe + translate, multilingual
   - Latencia esperada: <1s en Pixel 8 con NPU

2. **Opción avanzada:** `whisper-base-transcribe-translate.tflite` (78MB)
   - Para usuarios que prefieren mayor precisión
   - Latencia esperada: ~1.5s en Pixel 8 con NPU

3. **Especializado español:** `whisper-base.es.tflite` (78MB)
   - Si detectamos que el usuario habla principalmente español
   - Mejor precisión para un idioma específico

---

## Fase Actual: Implementación Base LiteRT + TFLite

### Objetivo
Implementar infraestructura básica para cargar y ejecutar modelos TFLite/LiteRT desde HuggingFace, permitiendo probar diferentes modelos y evaluar opciones.

### Alcance de esta Fase

**Implementaciones Básicas:**
1. ✅ Setup de LiteRT + TFLite runtime
2. ✅ Model downloader desde HuggingFace
3. ✅ Carga y ejecución de modelos TFLite (.tflite) y LiteRT (.litertlm)
4. ✅ UI para seleccionar y probar diferentes modelos
5. ✅ Integración con arquitectura existente (endpoints locales)

**NO incluido en esta fase:**
- ❌ Optimizaciones avanzadas (NPU delegation, KV-cache, etc.)
- ❌ Features multimodales (audio/vision processing)
- ❌ Function calling (synthetic o dedicado)
- ❌ Routing inteligente cloud/local
- ❌ Fine-tuning de modelos

### Catálogo de Modelos Descargables

**Total: 17 modelos disponibles** (3.3MB - 3GB cada uno)

#### 📱 STT - Whisper (7 modelos)

| Modelo | Tamaño | Idiomas | Especialización |
|--------|--------|---------|-----------------|
| Whisper Tiny - Multilingual | 42 MB | 100+ | Transcribe + Translate ⭐ Recomendado |
| Whisper Tiny - English Only | 41.5 MB | Solo EN | Máxima velocidad |
| Whisper Base - Multilingual | 78.5 MB | 100+ | Balance ideal |
| **Whisper Base - Spanish** | 78.4 MB | **Solo ES** | **Especializado español** |
| **Whisper Base - English** | 78.4 MB | **Solo EN** | **Especializado inglés** |
| Whisper Small - Multilingual | 249 MB | 100+ | Alta precisión |

#### 🎨 LLM - Multimodal (3 modelos)

| Modelo | Tamaño | Capacidades | Descripción |
|--------|--------|-------------|-------------|
| **Gemma 3n E2B** | **~2 GB** | **Audio + Image + Video + Text → Text** | **⭐ Recomendado - USM encoder, NPU, 140+ idiomas** |
| Gemma 3n E4B | ~3 GB | Audio + Image + Video + Text → Text | Más capaz que E2B |
| SmolVLM-256M | ~300 MB | Image + Text → Text | Ultra ligero para visión |

**Nota:** Gemma 3n confirmado con soporte multimodal nativo según [documentación oficial de Google](https://developers.googleblog.com/en/introducing-gemma-3n-developer-guide/).

#### 💬 LLM - Text Only (4 modelos)

| Modelo | Tamaño | Especialización |
|--------|--------|-----------------|
| Gemma 3-270M | ~300 MB | Ultra ligero, chat simple |
| Qwen 2.5-1.5B | ~1.5 GB | Balance calidad/velocidad ⭐ Popular |
| Phi-4 Mini | ~1.8 GB | Razonamiento complejo |
| Gemma 2-2B | ~2 GB | Estable y confiable |

#### 🔧 Function Calling (1 modelo)

| Modelo | Tamaño | Descripción |
|--------|--------|-------------|
| FunctionGemma-270M | ~288 MB | Especializado en function calling (requiere fine-tuning) |

**Uso total estimado si se descarga todo:** ~14.5 GB

### Plan de Implementación

#### Paso 1: Setup de Dependencias

**Añadir en `app/build.gradle`:**
```gradle
dependencies {
    // TensorFlow Lite (para modelos .tflite)
    implementation 'org.tensorflow:tensorflow-lite:2.15.0'
    implementation 'org.tensorflow:tensorflow-lite-gpu:2.15.0'
    implementation 'org.tensorflow:tensorflow-lite-support:0.4.4'

    // Google AI Edge LiteRT (para modelos .litertlm - opcional)
    implementation 'com.google.ai.edge.litert:litert-api:1.0.0-beta01'
    implementation 'com.google.ai.edge.litert:litert-support:1.0.0-beta01'

    // OkHttp para descargar modelos desde HuggingFace
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'

    // WorkManager para descargas en background
    implementation 'androidx.work:work-runtime-ktx:2.9.0'
}
```

**Añadir en `AndroidManifest.xml`:**
```xml
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
```

**Crear directorio de modelos:**
```
app/src/main/assets/models/   (para modelos bundled - opcional)
```

#### Paso 2: Model Downloader Service

**Crear `ModelDownloadManager.kt`:**
```kotlin
package com.sbf.assistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class ModelDownloadManager(private val context: Context) {
    private val client = OkHttpClient()
    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    data class ModelInfo(
        val name: String,
        val url: String,
        val filename: String,
        val sizeBytes: Long,
        val type: String,  // "tflite" or "litertlm"
        val category: String,  // "STT", "LLM-Multimodal", "LLM-Text", "Function-Calling"
        val description: String  // User-friendly description
    )

    data class ModelCategory(val name: String, val models: List<ModelInfo>)

    companion object {
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
                filename = "gemma-3n-E2B-it.litertlm",
                sizeBytes = 2_000_000_000,  // ~2GB
                type = "litertlm",
                category = "LLM-Multimodal",
                description = "Audio + Image + Video + Text → Text. USM encoder, 5B params, 2GB RAM. 140+ idiomas"
            ),
            ModelInfo(
                name = "Gemma 3n E4B - Multimodal",
                url = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/model.litertlm",
                filename = "gemma-3n-E4B-it.litertlm",
                sizeBytes = 3_000_000_000,  // ~3GB
                type = "litertlm",
                category = "LLM-Multimodal",
                description = "Versión más capaz de E2B, mejor calidad, 3GB RAM"
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
                url = "https://huggingface.co/litert-community/gemma-3-270m-it/resolve/main/gemma-3-270m-it.tflite",
                filename = "gemma-3-270m-it.tflite",
                sizeBytes = 300_000_000,  // ~300MB
                type = "tflite",
                category = "LLM-Text",
                description = "Ultra ligero, rápido, bueno para chat simple"
            ),
            ModelInfo(
                name = "Qwen 2.5-1.5B Instruct",
                url = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/qwen2.5-1.5b-instruct.tflite",
                filename = "qwen2.5-1.5b-instruct.tflite",
                sizeBytes = 1_500_000_000,  // ~1.5GB
                type = "tflite",
                category = "LLM-Text",
                description = "Excelente balance calidad/velocidad, muy popular"
            ),
            ModelInfo(
                name = "Phi-4 Mini Instruct",
                url = "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/phi-4-mini-instruct.tflite",
                filename = "phi-4-mini-instruct.tflite",
                sizeBytes = 1_800_000_000,  // ~1.8GB (estimado)
                type = "tflite",
                category = "LLM-Text",
                description = "Optimizado para razonamiento complejo"
            ),
            ModelInfo(
                name = "Gemma 2-2B Instruct",
                url = "https://huggingface.co/litert-community/Gemma2-2B-IT/resolve/main/gemma2-2b-it.tflite",
                filename = "gemma2-2b-it.tflite",
                sizeBytes = 2_000_000_000,  // ~2GB
                type = "tflite",
                category = "LLM-Text",
                description = "Versión anterior de Gemma, estable y confiable"
            )
        )

        // ========== FUNCTION CALLING MODELS ==========
        private val FUNCTION_CALLING_MODELS = listOf(
            ModelInfo(
                name = "FunctionGemma-270M",
                url = "https://huggingface.co/google/functiongemma-270m-it/resolve/main/functiongemma-270m-it.tflite",
                filename = "functiongemma-270m-it.tflite",
                sizeBytes = 288_000_000,  // ~288MB
                type = "tflite",
                category = "Function-Calling",
                description = "Especializado en function calling, requiere fine-tuning con tu dataset"
            )
        )

        // Catálogo completo
        val AVAILABLE_MODELS = WHISPER_MODELS + MULTIMODAL_MODELS + TEXT_LLM_MODELS + FUNCTION_CALLING_MODELS

        // Categorías para UI
        val MODEL_CATEGORIES = listOf(
            ModelCategory("STT - Whisper", WHISPER_MODELS),
            ModelCategory("LLM - Multimodal (Audio/Vision)", MULTIMODAL_MODELS),
            ModelCategory("LLM - Text Only", TEXT_LLM_MODELS),
            ModelCategory("Function Calling", FUNCTION_CALLING_MODELS)
        )
    }

    suspend fun downloadModel(
        modelInfo: ModelInfo,
        onProgress: (progress: Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val outputFile = File(modelsDir, modelInfo.filename)

            // Si ya existe, retornar
            if (outputFile.exists() && outputFile.length() == modelInfo.sizeBytes) {
                return@withContext Result.success(outputFile)
            }

            val request = Request.Builder().url(modelInfo.url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Download failed: ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty response"))
            val totalBytes = body.contentLength()

            FileOutputStream(outputFile).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        val progress = ((totalRead * 100) / totalBytes).toInt()
                        onProgress(progress)
                    }
                }
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download model", e)
            Result.failure(e)
        }
    }

    fun getInstalledModels(): List<ModelInfo> {
        return AVAILABLE_MODELS.filter { model ->
            val file = File(modelsDir, model.filename)
            file.exists()
        }
    }

    fun deleteModel(modelInfo: ModelInfo): Boolean {
        val file = File(modelsDir, modelInfo.filename)
        return file.delete()
    }

    fun getModelFile(filename: String): File? {
        val file = File(modelsDir, filename)
        return if (file.exists()) file else null
    }

    companion object {
        private const val TAG = "ModelDownloadManager"
    }
}
```

#### Paso 3: TFLite Model Service

**Crear `TFLiteModelService.kt`:**
```kotlin
package com.sbf.assistant

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.nio.ByteBuffer

class TFLiteModelService(private val context: Context) {
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var currentModel: String? = null

    fun loadModel(modelFile: File, useGpu: Boolean = true): Boolean {
        return try {
            release()

            val options = Interpreter.Options().apply {
                setNumThreads(4)
                if (useGpu) {
                    val delegate = GpuDelegate()
                    gpuDelegate = delegate
                    addDelegate(delegate)
                    Log.d(TAG, "GPU delegation enabled")
                }
            }

            interpreter = Interpreter(modelFile, options)
            currentModel = modelFile.name
            Log.d(TAG, "Model loaded: ${modelFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            // Retry without GPU if GPU failed
            if (useGpu) {
                Log.d(TAG, "Retrying without GPU...")
                return loadModel(modelFile, useGpu = false)
            }
            false
        }
    }

    fun runInference(input: Any): Any? {
        val interp = interpreter ?: return null

        return try {
            // Placeholder - implementar según tipo de modelo
            // Para texto: input = tokenized text, output = tokens
            // Para audio: input = mel-spectrogram, output = tokens
            val output = Array(1) { IntArray(512) }  // Placeholder
            interp.run(input, output)
            output
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            null
        }
    }

    fun getInputShape(): IntArray? {
        return interpreter?.getInputTensor(0)?.shape()
    }

    fun getOutputShape(): IntArray? {
        return interpreter?.getOutputTensor(0)?.shape()
    }

    fun release() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
        currentModel = null
    }

    companion object {
        private const val TAG = "TFLiteModelService"
    }
}
```

#### Paso 4: UI para Gestión de Modelos

**Añadir en `AssistantSettingsActivity.kt` o crear nueva `ModelManagementActivity.kt`:**

```kotlin
// Añadir sección en Settings para modelos locales
class ModelManagementFragment : Fragment() {
    private lateinit var downloadManager: ModelDownloadManager
    private lateinit var adapter: ModelListAdapter

    override fun onCreateView(...): View {
        downloadManager = ModelDownloadManager(requireContext())

        // RecyclerView con lista de modelos disponibles
        val availableModels = ModelDownloadManager.AVAILABLE_MODELS
        val installedModels = downloadManager.getInstalledModels()

        adapter = ModelListAdapter(
            models = availableModels,
            installed = installedModels.map { it.filename }.toSet(),
            onDownload = { model ->
                lifecycleScope.launch {
                    downloadManager.downloadModel(model) { progress ->
                        // Update progress bar
                    }
                }
            },
            onDelete = { model ->
                downloadManager.deleteModel(model)
                adapter.notifyDataSetChanged()
            }
        )

        return view
    }
}
```

**Layout sugerido:**
```
Settings → Local AI Models

  📱 STT - Whisper (7 available):
  ☑ Whisper Tiny - Multilingual (42MB) [Delete] ⭐
  ☐ Whisper Tiny - English Only (41.5MB) [Download]
  ☐ Whisper Base - Multilingual (78.5MB) [Download]
  ☐ Whisper Base - Spanish (78.4MB) [Download]
  ☐ Whisper Base - English (78.4MB) [Download]
  ☐ Whisper Small - Multilingual (249MB) [Download]

  🎨 LLM - Multimodal (3 available):
  ☐ Gemma 3n E2B - Multimodal (2GB) [Download] ⭐
      Audio + Image + Video + Text → Text
  ☐ Gemma 3n E4B - Multimodal (3GB) [Download]
  ☐ SmolVLM-256M - Vision (300MB) [Download]

  💬 LLM - Text Only (4 available):
  ☐ Gemma 3-270M (300MB) [Download]
  ☐ Qwen 2.5-1.5B (1.5GB) [Download] ⭐
  ☐ Phi-4 Mini (1.8GB) [Download]
  ☐ Gemma 2-2B (2GB) [Download]

  🔧 Function Calling (1 available):
  ☐ FunctionGemma-270M (288MB) [Download]

  [Total: 42MB used / 8GB available]
  [17 models available for download]
```

#### Paso 5: Integración con Arquitectura Existente

**Modificar `SettingsManager.kt`:**
```kotlin
// Añadir preferencias para modelos locales
fun getLocalSttModel(): String {
    return prefs.getString("local_stt_model", "") ?: ""
}

fun setLocalSttModel(filename: String) {
    prefs.edit().putString("local_stt_model", filename).apply()
}

fun getLocalLlmModel(): String {
    return prefs.getString("local_llm_model", "") ?: ""
}

fun setLocalLlmModel(filename: String) {
    prefs.edit().putString("local_llm_model", filename).apply()
}
```

**Actualizar `LocalGemmaService.kt` para usar TFLite:**
```kotlin
class LocalGemmaService(private val context: Context) {
    private val tfliteService = TFLiteModelService(context)
    private val downloadManager = ModelDownloadManager(context)
    private val settingsManager = SettingsManager(context)

    fun loadModel(): Boolean {
        val modelFilename = settingsManager.getLocalLlmModel()
        if (modelFilename.isBlank()) return false

        val modelFile = downloadManager.getModelFile(modelFilename)
        return if (modelFile != null) {
            tfliteService.loadModel(modelFile)
        } else {
            Log.e(TAG, "Model file not found: $modelFilename")
            false
        }
    }

    fun generateResponse(query: String): String? {
        if (tfliteService.interpreter == null) {
            if (!loadModel()) return null
        }

        // TODO: Implementar tokenization + inference + detokenization
        // Por ahora retornar placeholder
        return "Local model response (TFLite) - TODO: implement tokenization"
    }

    companion object {
        private const val TAG = "LocalGemmaService"
    }
}
```

**Crear `LocalWhisperTFLiteService.kt`:**
```kotlin
class LocalWhisperTFLiteService(private val context: Context) {
    private val tfliteService = TFLiteModelService(context)
    private val downloadManager = ModelDownloadManager(context)
    private val settingsManager = SettingsManager(context)

    fun transcribe(audioFile: File): String? {
        val modelFilename = settingsManager.getLocalSttModel()
        if (modelFilename.isBlank()) return null

        val modelFile = downloadManager.getModelFile(modelFilename)
        if (modelFile == null || !tfliteService.loadModel(modelFile)) {
            return null
        }

        // TODO: Implementar audio → mel-spectrogram → inference → detokenization
        return "Transcription - TODO: implement mel-spectrogram conversion"
    }
}
```

### Cronograma Fase Actual

**Total: 1-2 días**

**Día 1 (4-6 horas):**
- ✅ Añadir dependencias en build.gradle (30 min)
- ✅ Implementar ModelDownloadManager (2 horas)
- ✅ Implementar TFLiteModelService (1.5 horas)
- ✅ Testing básico: descargar y cargar modelo (1 hora)

**Día 2 (3-4 horas):**
- ✅ Crear UI para gestión de modelos (2 horas)
- ✅ Integrar con LocalGemmaService (1 hora)
- ✅ Testing E2E: UI → Download → Load → Inference placeholder (1 hora)

**Resultado esperado:**
- App puede descargar modelos desde HuggingFace
- App puede cargar modelos TFLite/LiteRT en memoria
- UI permite seleccionar modelo activo
- Placeholder de inferencia funciona (sin tokenization real todavía)

### Checklist de Implementación

- [ ] Añadir dependencias TFLite + OkHttp + WorkManager
- [ ] Crear ModelDownloadManager con catálogo de modelos
- [ ] Implementar download con progress tracking
- [ ] Crear TFLiteModelService para cargar modelos
- [ ] Añadir GPU delegation con fallback a CPU
- [ ] Crear UI de gestión de modelos (RecyclerView)
- [ ] Integrar con SettingsManager (preferencias de modelo)
- [ ] Actualizar LocalGemmaService para usar TFLite
- [ ] Crear LocalWhisperTFLiteService (placeholder)
- [ ] Testing: Download → Load → Basic inference
- [ ] Compilar y probar en dispositivo físico

---

## Sugerencias para Fases Futuras

### Fase Futura 1: Tokenization y Detokenization

**Objetivos:**
- Implementar conversión audio → mel-spectrogram para Whisper
- Implementar tokenizer/detokenizer para modelos de texto
- Cargar vocabularios (.bin files)

**Componentes:**
- Audio processing pipeline (TarsosDSP o custom FFT)
- Vocabulary parser (filters_vocab_multilingual.bin)
- BPE tokenizer para Gemma

### Fase Futura 2: Optimizaciones de Performance

**NPU Acceleration:**
- Implementar LiteRT delegation para NPU (Tensor, Snapdragon, Dimensity)
- Benchmark latencia: CPU vs GPU vs NPU
- Auto-selection basado en hardware disponible

**KV-Cache:**
- Implementar KV-cache para reducir latencia en conversaciones
- Memory pooling para evitar allocations

**Model Quantization:**
- Evaluar modelos 8-bit vs 4-bit vs float16
- Trade-off precisión vs velocidad vs tamaño

### Fase Futura 3: Function Calling

**HALLAZGO:** Gemma 3n NO soporta function calling nativo. Tres opciones:

#### Opción A: Synthetic Function Calling
- Implementar sobre Gemma 3n usando system prompt + JSON parsing
- **Pros:** Un solo modelo, sin fine-tuning
- **Contras:** Menor precisión, propenso a errores de formato

#### Opción B: FunctionGemma 270M Dedicado
- Usar [google/functiongemma-270m-it](https://huggingface.co/google/functiongemma-270m-it)
- Fine-tuning con dataset Mobile Actions + herramientas custom
- **Pros:** Function calling nativo y optimizado, solo 288MB
- **Contras:** Requiere fine-tuning, modelo adicional

#### Opción C: Arquitectura Dual (Recomendada)
- FunctionGemma 270M → Detección de tool calls
- Gemma 3n → Razonamiento multimodal + respuesta final
- **Pros:** Mejor de ambos mundos
- **Contras:** Más complejo, ~2.3GB RAM total

```kotlin
class LocalModelRouter(context: Context) {
    private val functionGemma = FunctionGemmaService(context)  // 288MB
    private val gemma3n = Gemma3nService(context)              // ~2GB

    suspend fun processQuery(query: String, tools: List<Tool>): Response {
        // 1. FunctionGemma detecta tool calls
        val functionCall = functionGemma.detectFunctionCall(query, tools)

        if (functionCall != null) {
            // 2. Ejecutar tool
            val toolResult = executeTools(functionCall)

            // 3. Gemma 3n genera respuesta final
            return gemma3n.generate(context = toolResult, query = query)
        } else {
            // 4. Respuesta directa (multimodal)
            return gemma3n.generate(query)
        }
    }
}
```

### Fase Futura 4: Multimodal

**Audio Directo a Gemma 3n:**
- Bypass STT: enviar audio raw directamente a Gemma 3n
- Usar USM (Universal Speech Model) encoder
- Comparar latencia vs STT → Texto → LLM

**Vision con Gemma 3n:**
- Captura de pantalla para contexto visual
- Camera input para "ver" el mundo
- Integrar con tool "analyze_screen"

**Video Input:**
- Procesar frames de video
- Análisis temporal de secuencias

### Fase Futura 5: Routing Inteligente Cloud/Local

**Heurísticas:**
```kotlin
fun shouldUseLocal(query: String, battery: Int, connection: Boolean): Boolean {
    return when {
        !connection -> true // Sin internet → forzar local
        battery < 20 -> query.length < 100 // Batería baja → solo queries simples
        else -> query.length < 200 // Default: queries cortas locales
    }
}
```

**Features:**
- UI: Switch manual "Preferir local" / "Preferir cloud" / "Automático"
- Métricas: Loggear latencia, precisión, satisfacción
- Fallback automático: Local falla → Cloud

### Fase Futura 6: Model Marketplace

**Características:**
- Browse de modelos desde HuggingFace Collections
- Filtros: Tamaño, task, language, rating
- Reviews y benchmarks comunitarios
- One-click install + warm-up automático

### Fase Futura 7: Fine-tuning On-Device

**Características:**
- LoRA adapters para personalización
- Dataset collection desde conversaciones del usuario
- Privacy-preserving fine-tuning
- A/B testing de adapters

### Recursos y Referencias

**Documentación Oficial:**
- [LiteRT Documentation](https://ai.google.dev/edge/litert)
- [Gemma Mobile Actions](https://ai.google.dev/gemma/docs/mobile-actions)
- [FunctionGemma Guide](https://ai.google.dev/gemma/docs/functiongemma)
- [TensorFlow Lite Guide](https://www.tensorflow.org/lite/guide)

**Modelos:**
- [LiteRT Community Collection](https://huggingface.co/collections/litert-community/android-models)
- [Whisper TFLite Models](https://huggingface.co/DocWolle/whisper_tflite_models)
- [FunctionGemma](https://huggingface.co/google/functiongemma-270m-it)

**Código de Referencia:**
- [LiteRT-LM GitHub](https://github.com/google-ai-edge/LiteRT-LM)
- [Gemma Cookbook](https://github.com/google-gemini/gemma-cookbook)
### Requisitos Mínimos

**Hardware:**
- Android 14+ (para AICore API completa)
- 4GB RAM mínimo (para E2B)
- 8GB storage libre (modelos + cache)
- Recomendado: Tensor G3, Snapdragon 8 Gen 3, Dimensity 9300 (NPU support)

**Software:**
- Google Play Services actualizado
- Permisos: INTERNET, WRITE_EXTERNAL_STORAGE, RECORD_AUDIO
