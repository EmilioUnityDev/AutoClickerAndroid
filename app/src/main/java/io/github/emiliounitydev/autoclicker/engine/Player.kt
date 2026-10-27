package io.github.emiliounitydev.autoclicker.engine

import io.github.emiliounitydev.autoclicker.model.ClickStep
import io.github.emiliounitydev.autoclicker.model.ColorCondition
import io.github.emiliounitydev.autoclicker.model.DragStep
import io.github.emiliounitydev.autoclicker.model.IfImageStep
import io.github.emiliounitydev.autoclicker.model.Mapping
import io.github.emiliounitydev.autoclicker.model.PluginStep
import io.github.emiliounitydev.autoclicker.model.Screen
import io.github.emiliounitydev.autoclicker.model.Sequence
import io.github.emiliounitydev.autoclicker.model.Step
import io.github.emiliounitydev.autoclicker.model.SubsequenceStep
import io.github.emiliounitydev.autoclicker.model.WaitImageStep
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Lo que el reproductor necesita del dispositivo (servicio real o pruebas). */
interface DeviceIO {
    /** Coordenadas en píxeles de la pantalla real. */
    suspend fun tap(x: Int, y: Int, durationMs: Int)
    suspend fun drag(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int)
    suspend fun back()
    /** Último fotograma (null si no hay captura activa). */
    fun frame(): Frame?
    val screenWidth: Int
    val screenHeight: Int
}

interface PlayerListener {
    fun status(text: String) {}
    fun step(step: Step) {}
    fun tapped(x: Int, y: Int) {}
}

class StopSequence(msg: String) : Exception(msg)
private class SkipIteration : Exception()
private class BreakLoop : Exception()

/**
 * Intérprete de secuencias, con la misma semántica que `player.py` de PC:
 * subsecuencias con repeticiones (null = ∞), esperas/condiciones de color con
 * `also`/`unless`, `on_timeout` continue/skip/break/stop y freno de pagos.
 */
class Player(
    private val seq: Sequence,
    private val io: DeviceIO,
    private val listener: PlayerListener,
    mode: Mapping.Mode? = null,
    /** Reloj en ms (inyectable para pruebas con tiempo virtual). */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val src = Mapping.sourceScreen(seq)
    private val dst = Screen(io.screenWidth, io.screenHeight)
    private val mapper = Mapping.Mapper(src, dst, mode ?: Mapping.defaultMode(src, dst))

    suspend fun run(steps: List<Step> = seq.steps) {
        if (!Mapping.sameFormat(src, dst)) {
            listener.status("Aviso: secuencia de ${src.width}×${src.height} en pantalla " +
                "${io.screenWidth}×${io.screenHeight}; posiciones aproximadas (mapea la secuencia).")
        }
        try {
            runSteps(steps)
        } catch (_: SkipIteration) {
        } catch (_: BreakLoop) {
        }
    }

    private suspend fun runSteps(steps: List<Step>) {
        for (s in steps) {
            coroutineContext.ensureActive()
            if (s.delayBeforeMs > 0) delay(s.delayBeforeMs.toLong())
            listener.step(s)
            when (s) {
                is ClickStep -> doClick(s)
                is DragStep -> doDrag(s)
                is SubsequenceStep -> doSub(s)
                is WaitImageStep -> doWait(s)
                is IfImageStep -> doIf(s)
                is PluginStep -> listener.status("Saltado: complemento «${s.pluginId}» no disponible.")
            }
        }
    }

    // ------------------------------------------------------------ acciones
    private suspend fun doClick(s: ClickStep) {
        if (s.button == "right") {
            checkGuards(null)
            io.back()
            return
        }
        val x = mapper.x(s.x)
        val y = mapper.y(s.y)
        checkGuards(x to y)
        listener.tapped(x, y)
        io.tap(x, y, s.durationMs)
    }

    private suspend fun doDrag(s: DragStep) {
        val x1 = mapper.x(s.x1); val y1 = mapper.y(s.y1)
        checkGuards(x1 to y1)
        listener.tapped(x1, y1)
        io.drag(x1, y1, mapper.x(s.x2), mapper.y(s.y2), s.durationMs, s.holdMs)
    }

    private suspend fun doSub(s: SubsequenceStep) {
        var i = 0
        while (s.repeats == null || i < s.repeats!!) {
            coroutineContext.ensureActive()
            i++
            try {
                runSteps(s.steps)
            } catch (_: SkipIteration) {
                continue
            } catch (_: BreakLoop) {
                break
            }
        }
    }

    private suspend fun doWait(s: WaitImageStep) {
        val deadline = clock() + s.timeoutMs
        var lastStatus = 0L
        while (true) {
            coroutineContext.ensureActive()
            val (ok, fr) = evaluate(s.condition, s.also, s.unless)
            if (ok) return
            val now = clock()
            if (now - lastStatus > 1000) {
                listener.status("Esperando «${s.name}»… (${(deadline - now).coerceAtLeast(0) / 1000}s, f=${"%.2f".format(fr)})")
                lastStatus = now
            }
            if (now >= deadline) {
                when (s.onTimeout) {
                    "continue" -> return
                    "skip" -> throw SkipIteration()
                    "break" -> throw BreakLoop()
                    else -> {
                        listener.status("Tiempo agotado esperando «${s.name}».")
                        throw StopSequence("Tiempo agotado esperando «${s.name}»")
                    }
                }
            }
            delay(s.pollMs.toLong().coerceAtLeast(20))
        }
    }

    private suspend fun doIf(s: IfImageStep) {
        val (ok, fr) = evaluate(s.condition, s.also, s.unless)
        listener.status("Condición «${s.name}»: ${if (ok) "sí" else "no"} (f=${"%.2f".format(fr)})")
        runSteps(if (ok) s.thenSteps else s.elseSteps)
    }

    // -------------------------------------------------------------- visión
    private suspend fun frameOrWait(): Frame {
        repeat(50) {
            io.frame()?.let { return it }
            delay(100)
        }
        throw StopSequence("Sin captura de pantalla: concede el permiso de captura.")
    }

    private suspend fun evaluate(c: ColorCondition, also: List<ColorCondition>, unless: List<ColorCondition>): Pair<Boolean, Double> {
        val f = frameOrWait()
        val sx = f.width.toDouble() / io.screenWidth
        val sy = f.height.toDouble() / io.screenHeight
        return Vision.evalStep(f, mapper.cond(c), also.map { mapper.cond(it) }, unless.map { mapper.cond(it) }, sx, sy)
    }

    /** Freno de emergencia: si hay una pantalla prohibida, parar sin tocar. */
    private fun checkGuards(point: Pair<Int, Int>?) {
        if (seq.guards.isEmpty()) return
        val f = io.frame() ?: return
        val sx = f.width.toDouble() / io.screenWidth
        val sy = f.height.toDouble() / io.screenHeight
        for (g in seq.guards) {
            if (g.conditions.isEmpty()) continue
            if (g.conditions.all { Vision.eval(f, mapper.cond(it), sx, sy).first }) {
                val a = g.allow
                if (a != null && point != null) {
                    val ax1 = mapper.x(a[0]); val ay1 = mapper.y(a[1])
                    val ax2 = mapper.x(a[2]); val ay2 = mapper.y(a[3])
                    if (point.first in ax1..ax2 && point.second in ay1..ay2) continue
                }
                listener.status("⛔ FRENO: «${g.name}» en pantalla — secuencia detenida sin tocar.")
                throw StopSequence("Freno: ${g.name}")
            }
        }
    }
}
