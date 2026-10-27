package io.github.emiliounitydev.autoclicker.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.emiliounitydev.autoclicker.engine.Frame
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
import io.github.emiliounitydev.autoclicker.service.PickerOverlay
import io.github.emiliounitydev.autoclicker.service.RunnerService
import io.github.emiliounitydev.autoclicker.service.ScreenInfo
import io.github.emiliounitydev.autoclicker.ui.Ui.dbl
import io.github.emiliounitydev.autoclicker.ui.Ui.int
import java.util.IdentityHashMap

/**
 * Editor de secuencias en árbol: toca una fila para editarla (o
 * expandir/contraer un bloque), mantén pulsado para más acciones.
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var store: SequenceStore
    private lateinit var name: String
    private lateinit var seq: Sequence
    private val expanded = IdentityHashMap<Step, Boolean>()
    private val rows = mutableListOf<Row>()
    private lateinit var adapter: RowAdapter

    /** Fila visible: un paso, o la cabecera de una rama («sí» / «si no»). */
    private class Row(
        val depth: Int,
        val list: MutableList<Step>,
        val index: Int,          // -1 = cabecera de rama
        val path: IntArray,
        val header: String? = null,
    ) {
        val step: Step? get() = if (index >= 0) list[index] else null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SequenceStore(this)
        name = intent.getStringExtra("name") ?: run { finish(); return }
        seq = store.load(name)
        title = name

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = Ui.hbox(this)
        bar.addView(Ui.button(this, "＋ Paso") { addDialog(seq.steps, seq.steps.size) })
        bar.addView(Ui.button(this, "▶ Probar") { playAll() })
        bar.addView(Ui.button(this, "Info") { infoDialog() })
        root.addView(bar)
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@EditorActivity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        adapter = RowAdapter()
        rv.adapter = adapter
        root.addView(rv)
        setContentView(root)
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        pendingAfterPick?.let { it(); pendingAfterPick = null }
    }

    private fun save() {
        store.save(name, seq)
    }

    // -------------------------------------------------------------- filas
    private fun rebuild() {
        rows.clear()
        flatten(seq.steps, 0, intArrayOf())
        adapter.notifyDataSetChanged()
    }

    private fun flatten(list: MutableList<Step>, depth: Int, prefix: IntArray) {
        list.forEachIndexed { i, s ->
            val path = prefix + i
            rows += Row(depth, list, i, path)
            if (expanded[s] == true) {
                val branches = s.childLists()
                branches.forEachIndexed { b, (label, child) ->
                    val bp = path + b
                    if (branches.size > 1) rows += Row(depth + 1, child, -1, bp, label)
                    flatten(child, depth + if (branches.size > 1) 2 else 1, bp)
                }
            }
        }
    }

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val tv = TextView(parent.context).apply {
                textSize = 14f
                setPadding(Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            return object : RecyclerView.ViewHolder(tv) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val r = rows[pos]
            val tv = h.itemView as TextView
            tv.setPadding(Ui.dp(tv.context, 8 + 18 * r.depth), tv.paddingTop, tv.paddingRight, tv.paddingBottom)
            val s = r.step
            if (s == null) {
                tv.text = "↳ ${r.header}:  (toca para añadir)"
                tv.setTextColor(Color.GRAY)
                tv.setOnClickListener { addDialog(r.list, r.list.size) }
                tv.setOnLongClickListener(null)
                return
            }
            val arrow = if (s.childLists().isNotEmpty()) (if (expanded[s] == true) "▾ " else "▸ ") else ""
            val delay = if (s.delayBeforeMs > 0 && !(s is SubsequenceStep && s.steps.isEmpty())) "  ⏱${s.delayBeforeMs}ms" else ""
            tv.text = "${r.index + 1}. $arrow${s.label()}$delay"
            tv.setTextColor(if (s is PluginStep) Ui.WARN else Color.BLACK)
            tv.setOnClickListener {
                if (s.childLists().isNotEmpty()) { expanded[s] = expanded[s] != true; rebuild() } else editDialog(s)
            }
            tv.setOnLongClickListener { actionsDialog(r); true }
        }
    }

    // ------------------------------------------------------------ acciones
    private fun actionsDialog(r: Row) {
        val s = r.step ?: return
        val opts = mutableListOf("Editar", "▶ Ejecutar solo este paso", "Duplicar", "Subir", "Bajar",
            "Añadir paso después", "Eliminar")
        if (s is SubsequenceStep) opts.add(5, "Añadir dentro")
        if (s is IfImageStep) { opts.add(5, "Añadir en «sí»"); opts.add(6, "Añadir en «si no»") }
        AlertDialog.Builder(this).setTitle(s.label()).setItems(opts.toTypedArray()) { _, w ->
            when (opts[w]) {
                "Editar" -> editDialog(s)
                "▶ Ejecutar solo este paso" -> playStep(r.path)
                "Duplicar" -> { r.list.add(r.index + 1, s.deepCopy()); save(); rebuild() }
                "Subir" -> if (r.index > 0) { r.list.add(r.index - 1, r.list.removeAt(r.index)); save(); rebuild() }
                "Bajar" -> if (r.index < r.list.size - 1) { r.list.add(r.index + 1, r.list.removeAt(r.index)); save(); rebuild() }
                "Añadir paso después" -> addDialog(r.list, r.index + 1)
                "Añadir dentro" -> { expanded[s] = true; addDialog((s as SubsequenceStep).steps, s.steps.size) }
                "Añadir en «sí»" -> { expanded[s] = true; addDialog((s as IfImageStep).thenSteps, s.thenSteps.size) }
                "Añadir en «si no»" -> { expanded[s] = true; addDialog((s as IfImageStep).elseSteps, s.elseSteps.size) }
                "Eliminar" -> AlertDialog.Builder(this).setMessage("¿Eliminar «${s.label()}»?")
                    .setPositiveButton("Eliminar") { _, _ -> r.list.removeAt(r.index); save(); rebuild() }
                    .setNegativeButton("Cancelar", null).show()
            }
        }.show()
    }

    private fun addDialog(list: MutableList<Step>, at: Int) {
        val kinds = arrayOf("👆 Toque", "👆 Mantener pulsado", "↔ Arrastre", "⏸ Pausa", "◀ Atrás (Android)",
            "⏳ Esperar imagen", "❓ Si se ve… / si no…", "🔁 Subsecuencia (repetir)")
        AlertDialog.Builder(this).setTitle("Añadir paso").setItems(kinds) { _, w ->
            val c = Mapping.sourceScreen(seq).let { it.width / 2 to it.height / 2 }
            val s: Step = when (w) {
                0 -> ClickStep(c.first, c.second, 60)
                1 -> ClickStep(c.first, c.second, 1000)
                2 -> DragStep(c.first, c.second, c.first, c.second - 300, 500)
                3 -> SubsequenceStep("pausa", 1).also { it.delayBeforeMs = 1000 }
                4 -> ClickStep(0, 0, 60, "right")
                5 -> WaitImageStep(condition = ColorCondition(c.first - 50, c.second - 50, c.first + 50, c.second + 50))
                6 -> IfImageStep(condition = ColorCondition(c.first - 50, c.second - 50, c.first + 50, c.second + 50))
                else -> SubsequenceStep("Subsecuencia", 1)
            }
            list.add(at.coerceIn(0, list.size), s)
            save(); rebuild()
            if (w != 4) editDialog(s)
        }.show()
    }

    // --------------------------------------------------------- reproducir
    private fun ensureRunner(): Boolean {
        if (RunnerService.instance == null) {
            Toast.makeText(this, "Inicia antes el panel flotante desde la pantalla principal.", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun playAll() {
        if (!ensureRunner()) return
        save()
        RunnerService.select(this, name, null, autoplay = true)
        moveTaskToBack(true)
    }

    private fun playStep(path: IntArray) {
        if (!ensureRunner()) return
        save()
        RunnerService.select(this, name, path, autoplay = true)
        moveTaskToBack(true)
    }

    // ------------------------------------------------------ seleccionar en pantalla
    private fun mapper(): Mapping.Mapper {
        val src = Mapping.sourceScreen(seq)
        val (w, h) = ScreenInfo.size(this)
        val dst = Screen(w, h)
        return Mapping.Mapper(src, dst, Mapping.defaultMode(src, dst))
    }

    /** Oculta el editor, deja elegir en pantalla y vuelve. */
    private fun pickOnScreen(region: Boolean, hint: String, onPicked: (IntArray) -> Unit) {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Falta el permiso de mostrar sobre otras apps.", Toast.LENGTH_LONG).show(); return
        }
        moveTaskToBack(true)
        window.decorView.postDelayed({
            PickerOverlay.show(this, region, hint) { r ->
                val back = Intent(this, EditorActivity::class.java).putExtra("name", name)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                if (r != null) {
                    val m = mapper()
                    val conv = intArrayOf(m.invX(r[0]), m.invY(r[1]), m.invX(r[2]), m.invY(r[3]))
                    pendingAfterPick = { onPicked(conv) }
                }
                startActivity(back)
            }
        }, 500)
    }

    // -------------------------------------------------------------- editar
    private fun editDialog(s: Step) {
        val box = Ui.vbox(this)
        val fDelay = Ui.field(this, box, "Espera antes (ms)", s.delayBeforeMs.toString())
        var apply: () -> Unit = {}
        when (s) {
            is ClickStep -> if (s.button == "right") {
                box.addView(Ui.text(this, "Pulsa el botón Atrás de Android."))
                apply = {}
            } else {
                val fx = Ui.field(this, box, "X", s.x.toString())
                val fy = Ui.field(this, box, "Y", s.y.toString())
                val fd = Ui.field(this, box, "Duración (ms)", s.durationMs.toString())
                box.addView(Ui.button(this, "📍 Elegir punto en pantalla") {
                    pickOnScreen(false, "Toca el punto (2 dedos = cancelar)") { p ->
                        s.x = p[0]; s.y = p[1]; save(); rebuild(); editDialog(s)
                    }
                })
                apply = { s.x = fx.edit.int(s.x); s.y = fy.edit.int(s.y); s.durationMs = fd.edit.int(60).coerceAtLeast(1) }
            }
            is DragStep -> {
                val f = listOf(
                    Ui.field(this, box, "X inicio", s.x1.toString()), Ui.field(this, box, "Y inicio", s.y1.toString()),
                    Ui.field(this, box, "X fin", s.x2.toString()), Ui.field(this, box, "Y fin", s.y2.toString()))
                val fd = Ui.field(this, box, "Duración (ms)", s.durationMs.toString())
                val fh = Ui.field(this, box, "Mantener antes (ms)", s.holdMs.toString())
                // Dos toques (inicio y fin): el selector de región normaliza el
                // rectángulo y perdería la dirección del arrastre.
                box.addView(Ui.button(this, "📍 Elegir inicio y fin en pantalla") { pickDrag(s) })
                apply = {
                    s.x1 = f[0].edit.int(s.x1); s.y1 = f[1].edit.int(s.y1); s.x2 = f[2].edit.int(s.x2); s.y2 = f[3].edit.int(s.y2)
                    s.durationMs = fd.edit.int(400).coerceAtLeast(1); s.holdMs = fh.edit.int(0).coerceAtLeast(0)
                }
            }
            is SubsequenceStep -> {
                val fn = Ui.field(this, box, "Nombre", s.name, numeric = false)
                val fr = Ui.field(this, box, "Repeticiones (0 = ∞)", (s.repeats ?: 0).toString())
                apply = { s.name = fn.edit.text.toString(); s.repeats = fr.edit.int(1).let { if (it <= 0) null else it } }
            }
            is WaitImageStep -> {
                val fn = Ui.field(this, box, "Nombre", s.name, numeric = false)
                val condApply = conditionForm(box, s.condition, s)
                val ft = Ui.field(this, box, "Tiempo máx. (ms)", s.timeoutMs.toString())
                val fp = Ui.field(this, box, "Comprobar cada (ms)", s.pollMs.toString())
                val opts = arrayOf("continue", "skip", "break", "stop")
                val labels = arrayOf("Continuar igualmente", "Saltar el resto de la vuelta", "Salir de la subsecuencia", "Detener la secuencia")
                box.addView(Ui.text(this, "Si se agota el tiempo:", 13f))
                val sp = Spinner(this).apply {
                    adapter = ArrayAdapter(this@EditorActivity, android.R.layout.simple_spinner_dropdown_item, labels)
                    setSelection(opts.indexOf(s.onTimeout).coerceAtLeast(0))
                }
                box.addView(sp)
                extrasNote(box, s.also.size, s.unless.size)
                apply = {
                    s.name = fn.edit.text.toString(); condApply()
                    s.timeoutMs = ft.edit.int(15000); s.pollMs = fp.edit.int(80); s.onTimeout = opts[sp.selectedItemPosition]
                }
            }
            is IfImageStep -> {
                val fn = Ui.field(this, box, "Nombre", s.name, numeric = false)
                val condApply = conditionForm(box, s.condition, s)
                extrasNote(box, s.also.size, s.unless.size)
                apply = { s.name = fn.edit.text.toString(); condApply() }
            }
            is PluginStep -> {
                box.addView(Ui.text(this, "Paso del complemento «${s.pluginId}», no disponible en esta versión. " +
                    "Se conserva al guardar y se salta al ejecutar.\n\n${s.raw.toString(1)}", 12f))
            }
        }
        AlertDialog.Builder(this).setTitle(s.label())
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Guardar") { _, _ ->
                s.delayBeforeMs = fDelay.edit.int(0).coerceAtLeast(0)
                apply(); save(); rebuild()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun pickDrag(s: DragStep) {
        pickOnScreen(false, "1/2 · Toca el INICIO del arrastre") { a ->
            s.x1 = a[0]; s.y1 = a[1]; save()
            window.decorView.postDelayed({
                pickOnScreen(false, "2/2 · Toca el FINAL del arrastre") { b ->
                    s.x2 = b[0]; s.y2 = b[1]; save(); rebuild(); editDialog(s)
                }
            }, 300)
        }
    }

    private fun extrasNote(box: LinearLayout, also: Int, unless: Int) {
        if (also + unless > 0) box.addView(Ui.text(this,
            "Además: $also condición(es) que también deben cumplirse y $unless que no deben verse " +
                "(se editan en la versión de PC; aquí se conservan).", 12f, Ui.WARN))
    }

    /** Formulario de condición de color; devuelve la función que aplica los cambios. */
    private fun conditionForm(box: LinearLayout, c: ColorCondition, owner: Step): () -> Unit {
        box.addView(Ui.text(this, "Condición de color", 15f))
        val f = listOf(Ui.field(this, box, "X1", c.x1.toString()), Ui.field(this, box, "Y1", c.y1.toString()),
            Ui.field(this, box, "X2", c.x2.toString()), Ui.field(this, box, "Y2", c.y2.toString()))
        val fc = Ui.field(this, box, "Color(es) #rrggbb", c.color, numeric = false)
        val ft = Ui.field(this, box, "Tolerancia", c.tolerance.toString())
        val fm = Ui.field(this, box, "Fracción mínima %", (c.minFraction * 100).toInt().toString(), decimal = true)
        val row = Ui.hbox(this)
        row.addView(Ui.button(this, "▭ Elegir región") {
            pickOnScreen(true, "Arrastra para marcar la región") { r ->
                c.x1 = r[0]; c.y1 = r[1]; c.x2 = maxOf(r[2], r[0] + 2); c.y2 = maxOf(r[3], r[1] + 2)
                save(); rebuild(); editDialog(owner)
            }
        })
        row.addView(Ui.button(this, "🎨 Tomar color") { sampleColor(c, owner) })
        box.addView(row)
        return {
            c.x1 = f[0].edit.int(c.x1); c.y1 = f[1].edit.int(c.y1); c.x2 = f[2].edit.int(c.x2); c.y2 = f[3].edit.int(c.y2)
            c.color = fc.edit.text.toString().trim(); c.tolerance = ft.edit.int(45)
            c.minFraction = (fm.edit.dbl(15.0) / 100.0).coerceIn(0.0, 1.0)
        }
    }

    /**
     * Toma los colores dominantes de la región en la pantalla actual (requiere
     * el panel flotante con captura activa) y ajusta la fracción mínima.
     */
    private fun sampleColor(c: ColorCondition, owner: Step) {
        val svc = RunnerService.instance
        val frame: Frame? = svc?.currentFrame()
        if (frame == null) {
            Toast.makeText(this, "Necesito la captura activa: inicia el panel flotante.", Toast.LENGTH_LONG).show(); return
        }
        pickOnScreen(true, "Arrastra sobre la zona cuyo color quieres detectar") { r ->
            c.x1 = r[0]; c.y1 = r[1]; c.x2 = maxOf(r[2], r[0] + 2); c.y2 = maxOf(r[3], r[1] + 2)
            val f = RunnerService.instance?.currentFrame()
            if (f != null) {
                val m = mapper()
                val cond = m.cond(c)
                val sx = f.width.toDouble() / ScreenInfo.size(this).first
                val sy = f.height.toDouble() / ScreenInfo.size(this).second
                val counts = HashMap<Int, Int>()
                var total = 0
                val x1 = (cond.x1 * sx).toInt().coerceIn(0, f.width - 1); val x2 = (cond.x2 * sx).toInt().coerceIn(x1 + 1, f.width)
                val y1 = (cond.y1 * sy).toInt().coerceIn(0, f.height - 1); val y2 = (cond.y2 * sy).toInt().coerceIn(y1 + 1, f.height)
                for (y in y1 until y2) for (x in x1 until x2) {
                    val p = f.pixel(x, y)
                    // cuantizar a pasos de 16 (como el análisis de PC)
                    val qr = ((p shr 16) and 0xf0) + 8
                    val qg = ((p shr 8) and 0xf0) + 8
                    val qb = (p and 0xf0) + 8
                    val q = (qr shl 16) or (qg shl 8) or qb
                    counts[q] = (counts[q] ?: 0) + 1; total++
                }
                val top = counts.entries.sortedByDescending { it.value }.take(2)
                c.color = top.joinToString(",") { "#%06x".format(it.key) }
                c.tolerance = 20
                val share = top.sumOf { it.value }.toDouble() / total.coerceAtLeast(1)
                c.minFraction = (share * 0.75).coerceIn(0.05, 0.95)
                Toast.makeText(this, "Color ${c.color} (${(share * 100).toInt()}% de la región)", Toast.LENGTH_LONG).show()
            }
            save(); rebuild(); editDialog(owner)
        }
    }

    private fun infoDialog() {
        val src = Mapping.sourceScreen(seq)
        val (w, h) = ScreenInfo.size(this)
        val miss = seq.missingPlugins()
        AlertDialog.Builder(this).setTitle(name).setMessage(
            "Creada en: ${seq.origin?.platform ?: "?"} ${seq.origin?.app ?: ""}\n" +
                "Pantalla de la secuencia: ${src.width}×${src.height}\n" +
                "Esta pantalla: ${w}×${h}\n" +
                "Freno de pagos: ${seq.guards.size} pantalla(s) prohibida(s)\n" +
                (if (miss.isNotEmpty()) "Complementos no disponibles: ${miss.joinToString()}\n" else "") +
                "\nLas coordenadas se guardan en la pantalla de la secuencia; al elegir en pantalla se convierten.")
            .setPositiveButton("OK", null).show()
    }

    companion object {
        /** Acción pendiente tras volver del selector en pantalla. */
        private var pendingAfterPick: (() -> Unit)? = null
    }
}
