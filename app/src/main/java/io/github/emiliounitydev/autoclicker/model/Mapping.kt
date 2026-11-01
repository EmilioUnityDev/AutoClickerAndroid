package io.github.emiliounitydev.autoclicker.model

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Conversión de coordenadas entre la pantalla donde se creó una secuencia y la
 * pantalla donde se ejecuta.
 *
 * - [Mode.STRETCH]: escala X e Y por separado. Exacto si ambas pantallas tienen
 *   el mismo formato (p. ej. 900×1600 → 1080×1920).
 * - [Mode.ANCHOR]: escala por el ancho (como hacen los juegos) y reparte el
 *   alto sobrante según el anclaje de cada elemento (`anchor_y`: 0 arriba,
 *   0.5 centrado, 1 abajo). Sin anclaje: la mitad superior arriba y la
 *   inferior abajo (aproximación).
 *
 *   Medido en Clash of Critters (900×1600 → 1280×2772): chat centrado (0.5),
 *   mapa y overview abajo (1), ventanas de resumen ≈0.66.
 */
object Mapping {
    enum class Mode(val label: String) {
        STRETCH("Estirar (mismo formato)"),
        ANCHOR("Ancho + anclajes (formato distinto)"),
    }

    /** Las secuencias de PC antiguas (sin origen) se hicieron sobre MuMu 900×1600. */
    val PC_DEFAULT = Screen(900, 1600)

    fun sourceScreen(seq: Sequence): Screen = seq.origin?.screen ?: PC_DEFAULT

    fun sameFormat(a: Screen, b: Screen) = abs(a.aspect - b.aspect) / a.aspect < 0.02

    fun defaultMode(src: Screen, dst: Screen) = if (sameFormat(src, dst)) Mode.STRETCH else Mode.ANCHOR

    class Mapper(val src: Screen, val dst: Screen, val mode: Mode) {
        private val sx = dst.width.toDouble() / src.width
        private val sy = dst.height.toDouble() / src.height
        val identity = src.width == dst.width && src.height == dst.height

        fun x(x: Int): Int = (x * sx).roundToInt()

        /** Alto sobrante al escalar por el ancho (negativo si la pantalla es más ancha). */
        private val extra = dst.height - src.height * sx

        fun y(y: Int, anchor: Double? = null): Int = when (mode) {
            Mode.STRETCH -> (y * sy).roundToInt()
            Mode.ANCHOR -> when {
                anchor != null -> (y * sx + anchor * extra).roundToInt()
                y < src.height / 2 -> (y * sx).roundToInt()
                else -> (dst.height - (src.height - y) * sx).roundToInt()
            }
        }

        fun cond(c: ColorCondition) = ColorCondition(x(c.x1), y(c.y1, c.anchorY), x(c.x2), y(c.y2, c.anchorY),
            c.color, c.tolerance, c.minFraction, c.anchorY)

        /** Inversa (de la pantalla real a la de la secuencia), p. ej. para el selector de punto. */
        fun invX(x: Int): Int = (x / sx).roundToInt()
        fun invY(y: Int, anchor: Double? = null): Int = when (mode) {
            Mode.STRETCH -> (y / sy).roundToInt()
            Mode.ANCHOR -> when {
                anchor != null -> ((y - anchor * extra) / sx).roundToInt()
                y < dst.height / 2 -> (y / sx).roundToInt()
                else -> (src.height - (dst.height - y) / sx).roundToInt()
            }
        }
    }

    class Report(val changedSteps: Int, val warnings: List<String>)

    /** Reescribe la secuencia para `dst` y actualiza su origen. */
    fun remap(seq: Sequence, dst: Screen, mode: Mode, platform: String, app: String): Report {
        val src = sourceScreen(seq)
        val m = Mapper(src, dst, mode)
        val warnings = mutableListOf<String>()
        if (!sameFormat(src, dst)) {
            warnings += "Formato distinto (${src.width}×${src.height} → ${dst.width}×${dst.height}): " +
                "posiciones aproximadas, revisa los pasos importantes."
        }
        var n = 0
        Sequence.walk(seq.steps) { s ->
            when (s) {
                is ClickStep -> { s.x = m.x(s.x); s.y = m.y(s.y, s.anchorY); n++ }
                is DragStep -> {
                    s.x1 = m.x(s.x1); s.y1 = m.y(s.y1, s.anchorY); s.x2 = m.x(s.x2); s.y2 = m.y(s.y2, s.anchorY); n++
                }
                is WaitImageStep -> { remapConds(m, s.condition, s.also, s.unless); n++ }
                is IfImageStep -> { remapConds(m, s.condition, s.also, s.unless); n++ }
                is PluginStep -> warnings += "Paso de complemento «${s.pluginId}» sin mapear."
                is SubsequenceStep -> Unit
            }
        }
        for (g in seq.guards) {
            g.conditions.replaceAll { m.cond(it) }
            g.allow = g.allow?.let { a -> intArrayOf(m.x(a[0]), m.y(a[1]), m.x(a[2]), m.y(a[3])) }
        }
        seq.origin = Origin(platform, app, Screen(dst.width, dst.height, dst.density))
        return Report(n, warnings.distinct())
    }

    private fun remapConds(m: Mapper, c: ColorCondition, also: MutableList<ColorCondition>, unless: MutableList<ColorCondition>) {
        val mc = m.cond(c)
        c.x1 = mc.x1; c.y1 = mc.y1; c.x2 = mc.x2; c.y2 = mc.y2
        also.replaceAll { m.cond(it) }
        unless.replaceAll { m.cond(it) }
    }
}
