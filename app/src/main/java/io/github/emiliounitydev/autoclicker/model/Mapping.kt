package io.github.emiliounitydev.autoclicker.model

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Conversión de coordenadas entre la pantalla donde se creó una secuencia y la
 * pantalla donde se ejecuta.
 *
 * - [Mode.STRETCH]: escala X e Y por separado. Exacto si ambas pantallas tienen
 *   el mismo formato (p. ej. 900×1600 → 1080×1920).
 * - [Mode.ANCHOR]: escala por el ancho y ancla la mitad superior arriba y la
 *   inferior abajo. Aproximación para formatos distintos (9:16 → 20:9), donde
 *   los juegos suelen pegar los menús a los bordes y estirar el centro.
 */
object Mapping {
    enum class Mode(val label: String) {
        STRETCH("Estirar (mismo formato)"),
        ANCHOR("Ancho + anclar arriba/abajo (formato distinto)"),
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

        fun y(y: Int): Int = when (mode) {
            Mode.STRETCH -> (y * sy).roundToInt()
            Mode.ANCHOR -> if (y < src.height / 2) (y * sx).roundToInt()
            else (dst.height - (src.height - y) * sx).roundToInt()
        }

        fun cond(c: ColorCondition) = ColorCondition(x(c.x1), y(c.y1), x(c.x2), y(c.y2), c.color, c.tolerance, c.minFraction)

        /** Inversa (de la pantalla real a la de la secuencia), p. ej. para el selector de punto. */
        fun invX(x: Int): Int = (x / sx).roundToInt()
        fun invY(y: Int): Int = when (mode) {
            Mode.STRETCH -> (y / sy).roundToInt()
            Mode.ANCHOR -> if (y < dst.height / 2) (y / sx).roundToInt()
            else (src.height - (dst.height - y) / sx).roundToInt()
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
                is ClickStep -> { s.x = m.x(s.x); s.y = m.y(s.y); n++ }
                is DragStep -> { s.x1 = m.x(s.x1); s.y1 = m.y(s.y1); s.x2 = m.x(s.x2); s.y2 = m.y(s.y2); n++ }
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
