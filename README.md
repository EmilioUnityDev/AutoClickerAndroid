# AutoClicker Android

Versión Android de [AutoClickerForApp](https://github.com/EmilioUnityDev/AutoClickerForApp):
secuencias de toques configurables (click, arrastre, esperas y condiciones por
color, subsecuencias) que se ejecutan sobre cualquier app — pensada sobre todo
para *Clash of Critters*.

> Solo incluye el motor de **secuencias**. El espejo multi-click, el ranking, el
> OCR y demás utilidades de la versión de PC no forman parte de esta app.

## Cómo funciona

| Necesidad | Implementación |
|---|---|
| Tocar / arrastrar sobre otras apps | `AccessibilityService.dispatchGesture` |
| Ver la pantalla (detectores de color) | `MediaProjection` (o captura de accesibilidad, Android 11+) |
| Controles sobre el juego | Panel flotante (▶ ■, elegir punto/región) |
| Secuencias | JSON compartido con la versión de PC ([formato](docs/sequence-format.md)) |

## Instalación (sin tienda)

Las APK firmadas se publican en **[Releases](../../releases)**.

1. Desde el móvil, abre la última release y descarga `AutoClicker-vX.Y.apk`.
2. Permite «Instalar apps desconocidas» para el navegador cuando lo pida.
3. Android 13+: en *Ajustes → Apps → AutoClicker → ⋮* activa **«Permitir
   ajustes restringidos»**; después activa el servicio en *Ajustes →
   Accesibilidad → AutoClicker*.

Las actualizaciones se instalan encima (misma firma); no hay que desinstalar.

## Hoja de ruta

- [ ] 0. Repositorio, permisos y flujo de trabajo
- [ ] 1. Compilación en GitHub Actions (debug en cada push, release firmada por etiqueta)
- [ ] 2. Publicación de APK en Releases
- [ ] 3. MVP: panel flotante ▶ ■, pasos click / arrastre / pausa, elegir punto, guardar/cargar
- [ ] 4. Visión: captura, condiciones de color, «esperar imagen», «si / si-no»
- [ ] 5. Compatibilidad PC ⇄ Android: origen de la secuencia + mapeo de resolución
- [ ] 6. Freno de pagos, ▶ por paso, duplicar, complementos (p. ej. pesca)

## Desarrollo

Kotlin + Gradle. No hace falta el SDK local: GitHub Actions compila cada push.
