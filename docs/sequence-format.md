# Formato de secuencias (compartido PC ⇄ Android)

Borrador de la **versión 3** del JSON de AutoClickerForApp. Es compatible hacia
atrás: un JSON v2 de PC se lee como v3 con `origin` desconocido.

```jsonc
{
  "version": 3,
  "name": "raid_join",
  "origin": {                       // dónde se creó (nuevo en v3)
    "platform": "pc",               // "pc" | "android"
    "app": "AutoClickerForApp 1.x",
    "screen": { "width": 900, "height": 1600, "density": 320 },
    "rotation": 0
  },
  "requires": ["fishing"],          // complementos usados (nuevo en v3)
  "target":  { "exe": "MuMuNxDevice.exe", "title": ".Main" },   // solo PC
  "method":  "adb",                                            // solo PC
  "guards":  [ /* pantallas prohibidas (freno de pagos) */ ],
  "steps":   [ /* pasos */ ]
}
```

## Coordenadas

Los pasos con `"space": "device"` usan píxeles de la pantalla del dispositivo
definida en `origin.screen`. Al ejecutar en una pantalla distinta, la app
**mapea** (escala) puntos y regiones:

- Mismo formato de pantalla (p. ej. 9:16 → 9:16): escalado proporcional exacto.
- Formato distinto (p. ej. 9:16 → 9:20,5): el juego reparte la interfaz de otra
  forma; el mapeo hace una aproximación (ajuste por ancho + anclaje arriba/abajo)
  y **avisa**. Conviene revisar los pasos marcados con el selector de punto.

La opción **«Mapear a esta pantalla»** (en ambas versiones) reescribe la
secuencia para la pantalla actual y actualiza `origin`.

## Complementos

Los pasos específicos de un juego (p. ej. `fishing_pull`) son **complementos**
con un identificador. Si una secuencia usa uno que la app no tiene:

- Al importar se muestra un aviso con la lista de complementos que faltan.
- Esos pasos se conservan (no se borran) pero quedan marcados como
  *no disponibles* y se saltan al ejecutar.

## Tipos de paso (núcleo)

| `type` | Descripción |
|---|---|
| `click` | Toque (o pulsación larga con `duration_ms`) |
| `drag` | Arrastre de (x1,y1) a (x2,y2) |
| `subsequence` | Bloque de pasos con `repeats` (`null` = infinito) |
| `wait_image` | Espera a una condición de color (`on_timeout`: continue / skip / break / stop) |
| `if_image` | Si / si-no según condición de color |

Las condiciones de color admiten `also` (también deben cumplirse) y `unless`
(ninguna debe cumplirse).
