# Guía de Configuración del Asistente

## Cambios Realizados

### 1. ✅ Chat como Pantalla Principal
La app ahora abre directamente en la **ChatActivity** para una interacción inmediata:
- Interfaz de chat completa con historial de mensajes
- Soporte para entrada de voz y texto
- Respuestas en streaming en tiempo real
- Text-to-Speech automático
- Sistema de failover (modelo primario + backup)
- Menú de tres puntos (⋮) en la esquina superior derecha para:
  - **Settings**: Acceder a la configuración de endpoints y modelos
  - **Clear Chat**: Limpiar el historial de conversación

**Cómo acceder a la configuración:**
- Presiona el menú de tres puntos (⋮) en la esquina superior derecha
- Selecciona "Settings"

### 2. ✅ Corrección de UI para Pixel 8 y Dispositivos Modernos
Se han corregido los problemas de interfaz en dispositivos modernos con navegación gestual:
- **WindowInsets correctamente manejados**: Los controles de entrada ahora se posicionan correctamente sobre las barras de navegación del sistema
- **Soporte para teclado**: Cuando el teclado aparece, los controles se ajustan automáticamente para quedar visibles
- **Edge-to-edge display**: La app utiliza toda la pantalla de forma correcta
- **androidWindowSoftInputMode="adjustResize"**: El layout se redimensiona automáticamente con el teclado

### 3. ✅ Configuración del Asistente del Sistema
Se ha corregido la configuración del VoiceInteractionService para que funcione correctamente como asistente predeterminado del sistema Android.

**Archivos añadidos/modificados:**
- `ChatActivity.kt` - Activity principal de chat con manejo de WindowInsets
- `activity_chat.xml` - Layout con contenedor para insets
- `chat_menu.xml` - Menú de opciones (Settings, Clear Chat)
- `AssistantSettingsActivity.kt` - Activity de configuración requerida por Android
- `MainActivity.kt` - Ahora es la pantalla de configuración (no principal)
- `assistant_service.xml` - Configuración del VoiceInteractionService
- `AndroidManifest.xml` - ChatActivity como launcher, windowSoftInputMode configurado

## Cómo Configurar el Asistente del Sistema

### Paso 1: Instalar la App
```bash
./gradlew installDebug
```
O desde Android Studio: **Run > Run 'app'**

### Paso 2: Acceder a la Configuración
1. Abre la app **Assistant** (se abrirá la pantalla de chat)
2. Presiona el **menú de tres puntos (⋮)** en la esquina superior derecha
3. Selecciona **"Settings"**

### Paso 3: Configurar Endpoints
1. En la pantalla de Settings, presiona **"+ Add Endpoint"**
2. Añade tu endpoint (ej. Ollama):
   - **Name:** Ollama Local
   - **URL:** http://tu-ip:11434/v1
   - **API Key:** (opcional, déjalo vacío para Ollama)
3. Presiona **"Add"**

### Paso 4: Configurar Modelo AGENT
1. En la sección **"Categories"**, presiona sobre **AGENT**
2. Selecciona tu endpoint en **"Primary Endpoint"**
3. Selecciona el modelo que quieres usar (ej. llama3.2:1b)
4. (Opcional) Configura un modelo de backup
5. Presiona **"Save"**
6. Vuelve atrás al chat (botón de navegación o Home)

### Paso 5: Establecer como Asistente Predeterminado (Opcional)

**Opción A - Desde Settings:**
1. En la pantalla de Settings, presiona **"Set as Default Assistant"**
2. Te llevará a los ajustes de Android
3. Busca **"Assist app"** o **"Digital assistant app"**
4. Selecciona **"Assistant"** de la lista

**Opción B - Manualmente:**
1. Ve a **Configuración de Android**
2. Busca **"Apps"** → **"Apps predeterminadas"** o **"Default apps"**
3. Selecciona **"Asistente digital y voz"** o **"Digital assistant app"**
4. Selecciona **"App de asistencia"** o **"Assist app"**
5. Elige **"Assistant"**

### Paso 6: Usar el Asistente

**Método 1 - Chat en la App (Recomendado):**
- Abre la app **Assistant**
- Escribe tu pregunta o presiona el botón de **micrófono** para hablar
- El asistente responderá en tiempo real con streaming
- Las respuestas se leerán automáticamente en voz alta

**Método 2 - Pulsación Larga (si configuraste como asistente predeterminado):**
- Mantén presionado el botón de **Inicio/Home** o desliza desde la esquina inferior
- Debería aparecer el overlay del asistente
- Habla o escribe tu pregunta

## Solución de Problemas

### Problema: Los controles de texto quedan detrás de los botones de Android

**Solución:**
Este problema ya está **corregido** en la última versión. La app ahora maneja correctamente los WindowInsets del sistema:
- Los controles se posicionan automáticamente sobre las barras de navegación
- Cuando se abre el teclado, los controles se ajustan para quedar visibles
- Compatible con navegación gestual y navegación por botones

Si sigues viendo el problema:
1. Asegúrate de tener la última versión compilada
2. Desinstala la app anterior
3. Vuelve a instalar con `./gradlew installDebug`
4. Reinicia el dispositivo si es necesario

### Problema: Al mantener presionado el botón Home se abre Google Assistant

**Solución:**
1. Ve a **Configuración** → **Apps** → **Apps predeterminadas**
2. Selecciona **"Asistente digital y voz"**
3. Cambia de **"Google"** a **"Assistant"**
4. Si no aparece la opción, reinicia el dispositivo después de instalar la app

**Nota para Android 12+:**
En algunos dispositivos con Android 12 o superior, Google Assistant está muy integrado en el sistema. Puede que necesites:
1. Desactivar Google Assistant:
   - Abre Google app → **Más** → **Configuración** → **Asistente de Google**
   - Ve a **"General"**
   - Desactiva **"Asistente de Google"**
2. Luego configura Assistant como predeterminado

### Problema: Con pulsación larga de Power aparece “Assistant ha dejado de funcionar”

**Causa:**
En Android 16 (por ejemplo ZUI), el gesto de Power sí invoca el asistente, pero el overlay del `VoiceInteractionSession` podía crashear por falta de tema Material3.

**Solución:**
Actualiza a la versión con el fix que infla el overlay con `Theme.Assistant` (ContextThemeWrapper). Tras eso el overlay abre correctamente.

### Nota sobre “Circle to Search” (gesto del círculo)

En Android 16 el gesto de “círculo” dispara **Contextual Search** (Google/Lens) y **no** pasa por el canal de asistente.  
Aunque la app sea el asistente predeterminado, ese gesto puede seguir abriendo Google y no es reemplazable por apps de terceros.

### Problema: La app no aparece en la lista de asistentes

**Solución:**
1. Verifica que la app esté instalada correctamente
2. Ve a **Configuración** → **Apps** → **Assistant**
3. Verifica que tenga los permisos necesarios:
   - Micrófono
   - Aparecer sobre otras apps (si aparece)
4. Reinicia el dispositivo
5. Vuelve a intentar configurarla como asistente predeterminado

### Problema: No funciona el reconocimiento de voz

**Solución:**
1. Ve a **Configuración** → **Apps** → **Assistant** → **Permisos**
2. Asegúrate de que el permiso de **"Micrófono"** esté activado
3. Si usas un emulador, verifica que tenga acceso al micrófono de tu PC

### Problema: Error "Configure a primary model first"

**Solución:**
1. Abre la app Assistant
2. Configura al menos un endpoint
3. En la sección Categories, configura el modelo AGENT
4. Asegúrate de seleccionar tanto el endpoint como el modelo
5. Presiona "Save"

### Problema: No puedo conectar a Ollama Self-Hosted (servidor local)

**Solución:**

**Síntoma:** Error "Connection refused" o "Timeout"

**Causas comunes:**

1. **Usar localhost o 127.0.0.1:**
   - ❌ **NUNCA funciona:** `http://localhost:11434/v1` o `http://127.0.0.1:11434/v1`
   - ✅ **Usa la IP local:** `http://192.168.1.100:11434/v1`

   **Cómo encontrar tu IP local:**
   - Windows: Abre CMD → `ipconfig` → busca "IPv4 Address" (ej. 192.168.1.100)
   - Mac: Terminal → `ifconfig en0 | grep inet` → primera línea
   - Linux: Terminal → `ip addr show` → busca inet 192.168.x.x

2. **Ollama no está corriendo en el PC:**
   ```bash
   # Verifica que Ollama esté corriendo:
   ollama serve

   # En otra terminal, verifica que responde:
   curl http://localhost:11434/api/tags
   # Debe retornar una lista de modelos en JSON
   ```

3. **Firewall bloqueando el puerto 11434:**
   - Windows: Agregar excepción para puerto 11434 en Windows Defender
   - Mac: System Preferences → Security → Firewall → Allow Ollama
   - Linux: `sudo ufw allow 11434`

4. **PC y móvil en redes WiFi diferentes:**
   - Ambos deben estar en la **misma red WiFi**
   - Si tu PC usa Ethernet y el móvil WiFi, asegúrate que estén en la misma subnet

5. **Ollama configurado para escuchar solo localhost:**
   ```bash
   # Por defecto Ollama escucha en todas las interfaces
   # Si no funciona, configura explícitamente:

   # Windows (PowerShell):
   $env:OLLAMA_HOST="0.0.0.0:11434"
   ollama serve

   # Mac/Linux:
   OLLAMA_HOST=0.0.0.0:11434 ollama serve
   ```

**Prueba de diagnóstico:**
```bash
# Desde tu PC, verifica que Ollama responde:
curl http://localhost:11434/api/tags

# Desde tu móvil, verifica que puede acceder a tu PC:
# Instala "Network Tools" de Play Store
# Ping a la IP de tu PC (ej. 192.168.1.100)
# Port scan del puerto 11434
```

**Configuración correcta:**
```
Tipo: Ollama Self-Hosted
Nombre: Ollama Local
URL: http://192.168.1.100:11434/v1
API Key: [Dejar vacío]
```

### Problema: Ollama Cloud retorna error 401 Unauthorized

**Solución:**

**Causas:**
1. **API Key inválida o incorrecta:**
   - Verifica que copiaste la key completa sin espacios
   - Asegúrate de que la key no haya expirado
   - Genera una nueva key en https://ollama.com/settings/keys

2. **Formato incorrecto de la key:**
   - La key debe estar en el campo "API Key", no en la URL
   - No incluyas "Bearer" ni prefijos, solo la key

3. **Cuenta sin acceso a Ollama Cloud:**
   - Verifica que tu cuenta esté activada
   - Comprueba que aceptaste los términos de servicio

**Configuración correcta:**
```
Tipo: Ollama Cloud
Nombre: Ollama Cloud
URL: https://api.ollama.com/v1
API Key: ollama-abc123xyz789... (tu key completa)
```

### Problema: Ollama Cloud retorna error 429 Rate Limit

**Solución:**
Has excedido el límite de solicitudes del plan gratuito.

**Opciones:**
1. Espera unos minutos (los límites se resetean)
2. Actualiza a un plan de pago en https://ollama.com/pricing
3. Usa Ollama Self-Hosted como alternativa (gratis e ilimitado)
4. Configura un endpoint de backup (OpenAI, LocalAI, etc.)

### Problema: Los modelos tardan mucho en responder

**Solución:**

**Para Ollama Cloud:**
- Normal: 200-500ms primer token
- Si tarda > 2s: Verifica tu conexión a internet
- Prueba con un modelo más pequeño (llama3.2:1b en vez de llama3.2:3b)

**Para Ollama Self-Hosted:**
- Depende del hardware de tu PC
- **GPU recomendada:** NVIDIA con al menos 4GB VRAM
- **Sin GPU:** Usa modelos pequeños (llama3.2:1b, phi3:mini)
- Verifica uso de CPU/RAM con `htop` o Task Manager
- Si tu PC es lento, considera Ollama Cloud

**Optimización:**
```bash
# Ver modelos instalados y su tamaño:
ollama list

# Eliminar modelos grandes no usados:
ollama rm llama3:70b

# Descargar modelos optimizados:
ollama pull llama3.2:1b  # 1.3GB, muy rápido
ollama pull phi3:mini    # 2.3GB, excelente calidad/velocidad
```

### Problema: Error "No models available" al configurar endpoint

**Solución:**

**Para Ollama Self-Hosted:**
```bash
# Primero descarga al menos un modelo:
ollama pull llama3.2:1b

# Verifica que esté instalado:
ollama list

# Reinicia Ollama:
# Ctrl+C en la terminal donde corre "ollama serve"
# Luego: ollama serve
```

**Para Ollama Cloud:**
- Los modelos deberían estar disponibles automáticamente
- Si no aparecen, verifica tu API key
- Intenta presionar "Refresh" en el spinner de modelos

## Características Adicionales

### Sistema de Failover
Si el modelo primario falla, el asistente automáticamente intentará usar el modelo de backup configurado.

### Streaming en Tiempo Real
Las respuestas se muestran token por token mientras el modelo está generando, proporcionando una experiencia más fluida.

### Text-to-Speech
Las respuestas del asistente se leen automáticamente en voz alta después de completarse.

## Próximos Pasos

### Fase 1.5 (Inmediata) - Mejoras de Endpoints
Las próximas funcionalidades a implementar para mejorar la conectividad:
- **Soporte oficial para Ollama.com Cloud** - Integración con API key
- **Auto-descubrimiento de servidores Ollama** - Scan de red local automático
- **Templates de endpoints** - Configuración rápida con plantillas predefinidas
- **Health check automático** - Monitoreo de estado de endpoints
- **Dashboard de latencias** - Ver rendimiento en tiempo real

### Fase 2 (Siguiente) - Capacidades Agénticas
Funcionalidades agénticas según roadmap en GEMINI.md:
- **Tools / Function Calling** - Permitir al asistente llamar funciones (SMS, llamadas, alarmas)
- **MCP Support** - Model Context Protocol para herramientas extensibles
- **Gemma Mobile Actions** - Procesamiento local on-device para privacidad y velocidad

## Soporte

Si encuentras algún problema, verifica:
1. Los logs en Android Studio (Logcat)
2. Que el endpoint esté accesible desde tu dispositivo
3. Que el modelo configurado exista en el endpoint
4. Que tengas conexión de red (para endpoints remotos)
