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

- [x] 0. Repositorio, permisos y flujo de trabajo
- [x] 1. Compilación en GitHub Actions (tests + APK de desarrollo en cada push)
- [ ] 2. Publicación de APK en Releases (firma definitiva)
- [x] 3. MVP: panel flotante ▶ ■, toque / mantener / arrastre / pausa / Atrás, elegir punto, guardar/cargar
- [x] 4. Visión: captura, condiciones de color (+ `also`/`unless`), «esperar imagen», «si / si-no», tomar color
- [x] 5. Compatibilidad PC ⇄ Android: origen de la secuencia + mapeo de resolución (en Android)
- [x] 6. Freno de pagos, ▶ por paso, duplicar, complementos (aviso y conservación)
- [ ] 7. Mapeo y origen también en la versión de PC
- [ ] 8. Editor de condiciones extra (`also`/`unless`) y de pantallas prohibidas en Android
- [ ] 9. Complementos implementados (p. ej. pesca)

## Probado

- Tests unitarios (CI): lectura/escritura del JSON de PC, complementos desconocidos,
  mapeo, métrica de color idéntica a la de PC, **un ciclo completo de `raid_join`**
  sobre capturas reales (enmascaradas), resumen de raid, «sin raids = sin toques»
  y freno de pagos.
- En un Android 15 (MuMu, 1220×2712): permisos, panel flotante, captura de
  pantalla, espera/condición por color + toque por accesibilidad, importar la
  secuencia de PC, mapearla a 1220×2712, editor y selector de punto.

## Desarrollo

Kotlin + Gradle, interfaz construida por código (sin Compose). No hace falta el
SDK local: GitHub Actions ejecuta los tests y compila una APK de desarrollo en
cada push (pestaña *Actions → último run → Artifacts*; requiere sesión de GitHub).

- `model/` — formato de secuencias, mapeo de resolución, complementos.
- `engine/` — reproductor (misma semántica que `player.py` de PC) y visión.
- `service/` — accesibilidad (gestos), captura + panel flotante, selector en pantalla.
- `ui/` — pantalla principal y editor.
- `tools/mask_test_images.py` — prepara capturas de prueba sin datos de jugadores.

Las APK de desarrollo (`…autoclicker.dev`) conviven con la versión de release.
