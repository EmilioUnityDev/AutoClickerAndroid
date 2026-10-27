package io.github.emiliounitydev.autoclicker.engine

import io.github.emiliounitydev.autoclicker.model.ColorCondition
import kotlin.math.abs

/** Fotograma capturado: píxeles ARGB y su tamaño. */
class Frame(val width: Int, val height: Int, val pixels: IntArray) {
    fun pixel(x: Int, y: Int): Int = pixels[y * width + x]
}

/**
 * Misma métrica que `vision.region_fraction` de la versión de PC: fracción de
 * píxeles muestreados (cada 3 px en el espacio del frame) cuya distancia por
 * canal a alguno de los colores es ≤ tolerancia.
 *
 * `scaleX/scaleY` pasan de coordenadas de pantalla (las que llegan ya mapeadas)
 * a coordenadas del frame (la captura puede ir a menor resolución).
 */
object Vision {
    fun fraction(f: Frame, c: ColorCondition, scaleX: Double, scaleY: Double, step: Int = 3): Double {
        val x1 = (c.x1 * scaleX).toInt().coerceIn(0, f.width - 1)
        val y1 = (c.y1 * scaleY).toInt().coerceIn(0, f.height - 1)
        val x2 = (c.x2 * scaleX).toInt().coerceIn(x1 + 1, f.width)
        val y2 = (c.y2 * scaleY).toInt().coerceIn(y1 + 1, f.height)
        val cols = c.rgbs
        if (cols.isEmpty()) return 0.0
        val tol = c.tolerance
        var hits = 0
        var total = 0
        var y = y1
        while (y < y2) {
            var x = x1
            val row = y * f.width
            while (x < x2) {
                val p = f.pixels[row + x]
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                for (rgb in cols) {
                    if (abs(r - ((rgb shr 16) and 0xff)) <= tol &&
                        abs(g - ((rgb shr 8) and 0xff)) <= tol &&
                        abs(b - (rgb and 0xff)) <= tol
                    ) { hits++; break }
                }
                total++
                x += step
            }
            y += step
        }
        return if (total == 0) 0.0 else hits.toDouble() / total
    }

    fun eval(f: Frame, c: ColorCondition, sx: Double, sy: Double): Pair<Boolean, Double> {
        val fr = fraction(f, c, sx, sy)
        return (fr >= c.minFraction) to fr
    }

    /** Condición principal + `also` (todas) + `unless` (ninguna). */
    fun evalStep(f: Frame, main: ColorCondition, also: List<ColorCondition>, unless: List<ColorCondition>,
                 sx: Double, sy: Double): Pair<Boolean, Double> {
        val (ok, fr) = eval(f, main, sx, sy)
        if (!ok) return false to fr
        if (also.any { !eval(f, it, sx, sy).first }) return false to fr
        if (unless.any { eval(f, it, sx, sy).first }) return false to fr
        return true to fr
    }
}
