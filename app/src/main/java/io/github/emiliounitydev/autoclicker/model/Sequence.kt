package io.github.emiliounitydev.autoclicker.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Modelo de secuencias compartido con AutoClickerForApp (PC).
 * Formato: docs/sequence-format.md (v3, lee v2).
 *
 * Coordenadas: píxeles de la pantalla de `origin.screen`. El reproductor las
 * escala a la pantalla real; «Mapear» las reescribe (ver [Mapping]).
 */

class ColorCondition(
    var x1: Int = 0, var y1: Int = 0, var x2: Int = 100, var y2: Int = 100,
    var color: String = "#ffffff",
    var tolerance: Int = 45,
    var minFraction: Double = 0.15,
) {
    val rgbs: List<Int>
        get() = color.split(",").map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { hexToRgb(it) }

    fun toJson() = JSONObject()
        .put("region", JSONArray(listOf(x1, y1, x2, y2)))
        .put("color", color).put("tolerance", tolerance).put("min_fraction", minFraction)

    fun copy() = ColorCondition(x1, y1, x2, y2, color, tolerance, minFraction)

    fun describe() = "$color en ($x1,$y1)-($x2,$y2) ≥${(minFraction * 100).toInt()}%"

    companion object {
        fun fromJson(o: JSONObject?): ColorCondition {
            if (o == null) return ColorCondition()
            val r = o.optJSONArray("region")
            return ColorCondition(
                r?.optInt(0) ?: 0, r?.optInt(1) ?: 0, r?.optInt(2) ?: 100, r?.optInt(3) ?: 100,
                o.optString("color", "#ffffff"), o.optInt("tolerance", 45),
                o.optDouble("min_fraction", 0.15),
            )
        }

        fun hexToRgb(h: String): Int? {
            val s = h.removePrefix("#")
            if (s.length != 6) return null
            return s.toIntOrNull(16)
        }
    }
}

private fun conds(arr: JSONArray?): MutableList<ColorCondition> {
    val out = mutableListOf<ColorCondition>()
    if (arr != null) for (i in 0 until arr.length()) out += ColorCondition.fromJson(arr.optJSONObject(i))
    return out
}

private fun condsJson(l: List<ColorCondition>) = JSONArray().apply { l.forEach { put(it.toJson()) } }

sealed class Step {
    var delayBeforeMs: Int = 0
    abstract val type: String
    abstract fun toJson(): JSONObject
    abstract fun deepCopy(): Step
    abstract fun label(): String

    /** Hijos editables (subsecuencia: steps; condición: then/else). */
    open fun childLists(): List<Pair<String, MutableList<Step>>> = emptyList()

    protected fun base(o: JSONObject): JSONObject = o.put("type", type).put("delay_before_ms", delayBeforeMs)
}

class ClickStep(
    var x: Int = 0, var y: Int = 0,
    var durationMs: Int = 60,
    /** "left" = toque; "right" = botón Atrás de Android. */
    var button: String = "left",
    var space: String = "device",
) : Step() {
    override val type = "click"
    override fun toJson() = base(JSONObject()).put("x", x).put("y", y).put("button", button)
        .put("duration_ms", durationMs).put("space", space)
    override fun deepCopy() = ClickStep(x, y, durationMs, button, space).also { it.delayBeforeMs = delayBeforeMs }
    override fun label() = if (button == "right") "◀ Atrás (Android)"
    else if (durationMs >= 400) "👆 Mantener ($x, $y) · $durationMs ms" else "👆 Toque ($x, $y)"
}

class DragStep(
    var x1: Int = 0, var y1: Int = 0, var x2: Int = 0, var y2: Int = 0,
    var durationMs: Int = 400, var holdMs: Int = 0,
    var space: String = "device",
) : Step() {
    override val type = "drag"
    override fun toJson() = base(JSONObject()).put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2)
        .put("duration_ms", durationMs).put("hold_ms", holdMs).put("button", "left").put("space", space)
    override fun deepCopy() = DragStep(x1, y1, x2, y2, durationMs, holdMs, space).also { it.delayBeforeMs = delayBeforeMs }
    override fun label() = "↔ Arrastre ($x1, $y1) → ($x2, $y2) · $durationMs ms"
}

class SubsequenceStep(
    var name: String = "Subsecuencia",
    /** null = infinito */
    var repeats: Int? = 1,
    val steps: MutableList<Step> = mutableListOf(),
) : Step() {
    override val type = "subsequence"
    override fun toJson() = base(JSONObject()).put("name", name)
        .put("repeats", repeats ?: JSONObject.NULL)
        .put("steps", stepsJson(steps))
    override fun deepCopy() = SubsequenceStep(name, repeats, steps.map { it.deepCopy() }.toMutableList())
        .also { it.delayBeforeMs = delayBeforeMs }
    override fun childLists() = listOf("" to steps)
    override fun label(): String {
        if (steps.isEmpty() && repeats == 1) return "⏸ Pausa ${delayBeforeMs} ms"
        val t = repeats?.let { "$it×" } ?: "∞"
        return "🔁 $name ($t, ${steps.size} pasos)"
    }
}

class WaitImageStep(
    var name: String = "Esperar imagen",
    var condition: ColorCondition = ColorCondition(),
    var timeoutMs: Int = 15000,
    var pollMs: Int = 80,
    /** continue | skip | break | stop */
    var onTimeout: String = "skip",
    val also: MutableList<ColorCondition> = mutableListOf(),
    val unless: MutableList<ColorCondition> = mutableListOf(),
) : Step() {
    override val type = "wait_image"
    override fun toJson() = base(JSONObject()).put("name", name).put("condition", condition.toJson())
        .put("timeout_ms", timeoutMs).put("poll_ms", pollMs).put("on_timeout", onTimeout)
        .also { if (also.isNotEmpty()) it.put("also", condsJson(also)) }
        .also { if (unless.isNotEmpty()) it.put("unless", condsJson(unless)) }
    override fun deepCopy() = WaitImageStep(name, condition.copy(), timeoutMs, pollMs, onTimeout,
        also.map { it.copy() }.toMutableList(), unless.map { it.copy() }.toMutableList())
        .also { it.delayBeforeMs = delayBeforeMs }
    override fun label() = "⏳ Esperar: $name (≤${timeoutMs / 1000.0}s)"
}

class IfImageStep(
    var name: String = "Condición",
    var condition: ColorCondition = ColorCondition(),
    val thenSteps: MutableList<Step> = mutableListOf(),
    val elseSteps: MutableList<Step> = mutableListOf(),
    val also: MutableList<ColorCondition> = mutableListOf(),
    val unless: MutableList<ColorCondition> = mutableListOf(),
) : Step() {
    override val type = "if_image"
    override fun toJson() = base(JSONObject()).put("name", name).put("condition", condition.toJson())
        .put("then_steps", stepsJson(thenSteps)).put("else_steps", stepsJson(elseSteps))
        .also { if (also.isNotEmpty()) it.put("also", condsJson(also)) }
        .also { if (unless.isNotEmpty()) it.put("unless", condsJson(unless)) }
    override fun deepCopy() = IfImageStep(name, condition.copy(),
        thenSteps.map { it.deepCopy() }.toMutableList(), elseSteps.map { it.deepCopy() }.toMutableList(),
        also.map { it.copy() }.toMutableList(), unless.map { it.copy() }.toMutableList())
        .also { it.delayBeforeMs = delayBeforeMs }
    override fun childLists() = listOf("sí" to thenSteps, "si no" to elseSteps)
    override fun label() = "❓ Si se ve: $name"
}

/**
 * Paso de un complemento que esta app no tiene (p. ej. `fishing_pull` de PC).
 * Se conserva tal cual (para no perderlo al guardar) y se salta al ejecutar.
 */
class PluginStep(val raw: JSONObject) : Step() {
    override val type: String = raw.optString("type", "?")
    val pluginId: String get() = Plugins.pluginFor(type) ?: type
    init { delayBeforeMs = raw.optInt("delay_before_ms", 0) }
    override fun toJson() = JSONObject(raw.toString()).put("delay_before_ms", delayBeforeMs)
    override fun deepCopy() = PluginStep(JSONObject(raw.toString())).also { it.delayBeforeMs = delayBeforeMs }
    override fun label() = "🧩 [${pluginId}] ${raw.optString("name", type)} — complemento no disponible"
}

object Plugins {
    /** Tipos de paso del núcleo (los implementa esta app). */
    val CORE = setOf("click", "drag", "subsequence", "wait_image", "if_image")

    /** type → complemento conocido. */
    private val KNOWN = mapOf("fishing_pull" to "fishing")

    /** Complementos instalados en esta app (de momento ninguno). */
    val INSTALLED: Set<String> = emptySet()

    fun pluginFor(type: String): String? = KNOWN[type]
}

class Screen(var width: Int, var height: Int, var density: Int = 0) {
    fun toJson() = JSONObject().put("width", width).put("height", height).put("density", density)
    val aspect: Double get() = height.toDouble() / width
    companion object {
        fun fromJson(o: JSONObject?) = o?.let { Screen(it.optInt("width"), it.optInt("height"), it.optInt("density")) }
    }
}

class Origin(var platform: String, var app: String, var screen: Screen?) {
    fun toJson() = JSONObject().put("platform", platform).put("app", app)
        .also { o -> screen?.let { o.put("screen", it.toJson()) } }
    companion object {
        fun fromJson(o: JSONObject?) = o?.let {
            Origin(it.optString("platform", "?"), it.optString("app", ""), Screen.fromJson(it.optJSONObject("screen")))
        }
    }
}

class Guard(var name: String, val conditions: MutableList<ColorCondition>, var allow: IntArray?) {
    fun toJson() = JSONObject().put("name", name).put("conditions", condsJson(conditions))
        .also { o -> allow?.let { o.put("allow", JSONArray(it.toList())) } }
    companion object {
        fun fromJson(o: JSONObject) = Guard(
            o.optString("name", "pantalla prohibida"), conds(o.optJSONArray("conditions")),
            o.optJSONArray("allow")?.let { a -> IntArray(4) { a.optInt(it) } },
        )
    }
}

class Sequence(
    var name: String = "Nueva secuencia",
    var origin: Origin? = null,
    val steps: MutableList<Step> = mutableListOf(),
    val guards: MutableList<Guard> = mutableListOf(),
    /** Campos de PC que no usamos (target, method, adb_serial…): se conservan. */
    private val extra: JSONObject = JSONObject(),
) {
    /** Complementos usados por la secuencia (calculado). */
    fun requires(): Set<String> {
        val out = linkedSetOf<String>()
        walk(steps) { if (it is PluginStep) out += it.pluginId }
        return out
    }

    fun missingPlugins(): Set<String> = requires() - Plugins.INSTALLED

    fun toJson(): JSONObject {
        val o = JSONObject(extra.toString())
        o.put("version", 3).put("name", name)
        origin?.let { o.put("origin", it.toJson()) }
        val req = requires()
        if (req.isNotEmpty()) o.put("requires", JSONArray(req.toList())) else o.remove("requires")
        if (guards.isNotEmpty()) o.put("guards", JSONArray().apply { guards.forEach { put(it.toJson()) } })
        else o.remove("guards")
        o.put("steps", stepsJson(steps))
        return o
    }

    companion object {
        fun parse(text: String): Sequence = fromJson(JSONObject(text))

        fun fromJson(o: JSONObject): Sequence {
            val extra = JSONObject(o.toString())
            for (k in listOf("version", "name", "origin", "requires", "guards", "steps")) extra.remove(k)
            var origin = Origin.fromJson(o.optJSONObject("origin"))
            if (origin == null && o.has("target")) {
                // v2 de PC: sin origen. Las secuencias de PC para MuMu usan 900×1600.
                origin = Origin("pc", "AutoClickerForApp (v2)", null)
            }
            val guards = mutableListOf<Guard>()
            o.optJSONArray("guards")?.let { a -> for (i in 0 until a.length()) guards += Guard.fromJson(a.getJSONObject(i)) }
            return Sequence(o.optString("name", "Secuencia"), origin, stepsFrom(o.optJSONArray("steps")), guards, extra)
        }

        fun walk(steps: List<Step>, f: (Step) -> Unit) {
            for (s in steps) {
                f(s)
                s.childLists().forEach { walk(it.second, f) }
            }
        }
    }
}

fun stepsJson(l: List<Step>) = JSONArray().apply { l.forEach { put(it.toJson()) } }

fun stepsFrom(arr: JSONArray?): MutableList<Step> {
    val out = mutableListOf<Step>()
    if (arr != null) for (i in 0 until arr.length()) out += stepFrom(arr.getJSONObject(i))
    return out
}

fun stepFrom(o: JSONObject): Step {
    val s: Step = when (o.optString("type", "click")) {
        "click" -> ClickStep(o.optInt("x"), o.optInt("y"), o.optInt("duration_ms", 60),
            o.optString("button", "left"), o.optString("space", "device"))
        "drag" -> DragStep(o.optInt("x1"), o.optInt("y1"), o.optInt("x2"), o.optInt("y2"),
            o.optInt("duration_ms", 400), o.optInt("hold_ms", 0), o.optString("space", "device"))
        "subsequence" -> SubsequenceStep(o.optString("name", "Subsecuencia"),
            if (o.isNull("repeats")) null else o.optInt("repeats", 1), stepsFrom(o.optJSONArray("steps")))
        "wait_image" -> WaitImageStep(o.optString("name", "Esperar imagen"),
            ColorCondition.fromJson(o.optJSONObject("condition")), o.optInt("timeout_ms", 15000),
            o.optInt("poll_ms", 80), o.optString("on_timeout", "skip"),
            conds(o.optJSONArray("also")), conds(o.optJSONArray("unless")))
        "if_image" -> IfImageStep(o.optString("name", "Condición"),
            ColorCondition.fromJson(o.optJSONObject("condition")),
            stepsFrom(o.optJSONArray("then_steps")), stepsFrom(o.optJSONArray("else_steps")),
            conds(o.optJSONArray("also")), conds(o.optJSONArray("unless")))
        else -> return PluginStep(o)
    }
    s.delayBeforeMs = o.optInt("delay_before_ms", 0)
    return s
}
