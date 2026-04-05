# Project Assistant - Plan y Features

## Objetivo
Definir un Project Assistant como proyecto independiente que orquesta y documenta proyectos en GitHub/Coder: estado actual, siguientes pasos, tareas y ejecución controlada de acciones en workspaces.
La app Android Assistant es uno de los clientes para interactuar con este Project Assistant.

## Alcance (MVP)
- Consultar estado del proyecto (git + issues/PRs).
- Gestionar tareas (crear issues o tareas locales).
- Ejecutar comandos controlados en Coder (tests, build, herramientas CLI).
- Proponer siguientes pasos basados en actividad reciente y pendientes.

## Fuentes de verdad
- Repositorio Git (estado y actividad).
- GitHub (issues, PRs, milestones).
- Archivo local de tareas opcional (ej. TASKS.md).

## Features clave
### Estado del proyecto
- Resumen: branch, dirty, último commit, cambios recientes.
- Actividad: commits recientes y archivos tocados.
- Salud: tests/lint/build (si existen scripts).

### Tareas y planificación
- Añadir tareas a GitHub Issues o TASKS.md.
- Sugerir siguientes pasos según issues/commits.
- Crear “research tasks” (investigaciones puntuales).

### Ejecución en Coder
- Ejecutar comandos con allowlist (git, gradle, scripts).
- Lanzar herramientas CLI (codex-cli, claude-cli, opencode).
- Confirmación previa para acciones destructivas o de escritura.

### GitHub integration
- Crear issues desde voz/chat.
- Listar issues/PRs relevantes.
- Adjuntar logs o salidas de comandos a issues (opcional).

## Arquitectura propuesta
- Project Assistant como servicio independiente.
- Android Assistant como cliente (UI/voz) del Project Assistant.
- Interfaz principal: MCP sobre HTTPS.
- MCP Client en el Project Assistant.
- MCP Server en Coder (remoto HTTP o local por túnel).
- Tools MCP expuestas por el servidor (estado, tareas, ejecución).

## Tools MCP sugeridas (borrador)
- `project.status`: git status/log/diff y resumen.
- `project.activity`: commits recientes.
- `project.tasks.list`: issues + tareas locales.
- `project.tasks.add`: crear issue o escribir en TASKS.md.
- `project.run`: ejecutar comandos con allowlist.
- `project.commit`: git add/commit con confirmación.
- `project.issue.create`: crear issue en GitHub.

## Fases
### Fase 1 (MVP)
- project.status + project.tasks.add + project.run
- Reporte de estado mínimo en UI.

### Fase 2
- project.commit + integración GH issues/PRs.
- Sugerencias de siguientes pasos.

### Fase 3
- Sub-agentes especializados (investigación, QA, docs).
- Automatización de PRs y cambios asistidos.

## Seguridad y control
- Allowlist de comandos.
- Confirmación explícita para commits, pushes y cambios de archivos.
- Auditoría básica: log de acciones ejecutadas.

## Dependencias a validar
- MCP remoto de Coder (experiments oauth2 + mcp-server-http).
- Acceso al CLI de Coder desde el workspace.
- Disponibilidad de codex/claude/opencode CLI.

## Integración con Android Assistant
- El Assistant actúa como cliente del Project Assistant.
- La UI de Android presenta estado, tareas y acciones sugeridas.
- Las ejecuciones reales se delegan al Project Assistant vía MCP (HTTPS).
