package io.github.emiliounitydev.autoclicker.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import io.github.emiliounitydev.autoclicker.BuildConfig
import io.github.emiliounitydev.autoclicker.model.Mapping
import io.github.emiliounitydev.autoclicker.model.Origin
import io.github.emiliounitydev.autoclicker.model.Screen
import io.github.emiliounitydev.autoclicker.model.Sequence
import io.github.emiliounitydev.autoclicker.service.ClickerAccessibilityService
import io.github.emiliounitydev.autoclicker.service.RunnerService
import io.github.emiliounitydev.autoclicker.service.ScreenInfo

class MainActivity : AppCompatActivity() {

    private lateinit var store: SequenceStore
    private lateinit var root: LinearLayout
    private var exportName: String? = null
    private var pendingSequence: String? = null

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK && r.data != null) {
            val i = Intent(this, RunnerService::class.java).setAction(RunnerService.ACTION_START)
                .putExtra(RunnerService.EXTRA_RESULT_CODE, r.resultCode)
                .putExtra(RunnerService.EXTRA_RESULT_DATA, r.data)
                .putExtra(RunnerService.EXTRA_SEQUENCE, pendingSequence ?: prefs().getString("active", null))
            startForegroundService(i)
            Toast.makeText(this, "Panel flotante activo. Abre el juego.", Toast.LENGTH_SHORT).show()
            render()
        } else {
            Toast.makeText(this, "Sin permiso de captura no se pueden usar las condiciones de color.", Toast.LENGTH_LONG).show()
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importFrom(it) }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val n = exportName
        if (uri != null && n != null) {
            contentResolver.openOutputStream(uri)?.use { it.write(store.load(n).toJson().toString(1).toByteArray()) }
            Toast.makeText(this, "Exportada", Toast.LENGTH_SHORT).show()
        }
    }

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SequenceStore(this)
        root = Ui.vbox(this, 16)
        setContentView(ScrollView(this).apply { addView(root) })
        title = "AutoClicker"
        if (intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_SEND) handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun prefs() = getSharedPreferences("main", MODE_PRIVATE)

    private fun screen(): Screen {
        val (w, h) = ScreenInfo.size(this)
        return Screen(w, h, ScreenInfo.density(this))
    }

    // ------------------------------------------------------------- permisos
    private fun accessibilityEnabled(): Boolean {
        val want = ComponentName(this, ClickerAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }.any { it.equals(want, true) }
    }

    private fun overlayAllowed() = Settings.canDrawOverlays(this)

    private fun notifAllowed() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------- pantalla
    private fun render() {
        root.removeAllViews()
        val s = screen()
        root.addView(Ui.text(this, "AutoClicker ${BuildConfig.VERSION_NAME} · pantalla ${s.width}×${s.height}", 12f))

        root.addView(Ui.title(this, "Permisos"))
        permRow("Accesibilidad (tocar en otras apps)", accessibilityEnabled()) {
            AlertDialog.Builder(this).setTitle("Activar accesibilidad")
                .setMessage("Ajustes → Accesibilidad → Apps instaladas → AutoClicker → activar.\n\n" +
                    "Si aparece como «ajuste restringido»: Ajustes → Apps → AutoClicker → ⋮ (arriba a la " +
                    "derecha) → «Permitir ajustes restringidos», y vuelve a intentarlo.\n\n" +
                    "La app solo usa este permiso para hacer toques y arrastres; no lee el contenido de otras apps.")
                .setPositiveButton("Abrir ajustes") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .setNeutralButton("Info de la app") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }.show()
        }
        permRow("Mostrar sobre otras apps (panel flotante)", overlayAllowed()) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= 33) permRow("Notificaciones (servicio en marcha)", notifAllowed()) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        root.addView(Ui.title(this, "Panel flotante"))
        val running = RunnerService.instance != null
        val active = prefs().getString("active", null)
        root.addView(Ui.text(this, "Secuencia activa: ${active ?: "(ninguna)"}"))
        if (!running) {
            root.addView(Ui.button(this, "▶ Iniciar panel flotante") { startRunner(active) })
            root.addView(Ui.text(this, "Pedirá permiso para capturar la pantalla: elige «Pantalla completa».", 12f))
        } else {
            root.addView(Ui.button(this, "■ Cerrar panel flotante") {
                startService(Intent(this, RunnerService::class.java).setAction(RunnerService.ACTION_STOP_ALL))
                root.postDelayed({ render() }, 400)
            })
        }

        root.addView(Ui.title(this, "Secuencias"))
        val bar = Ui.hbox(this)
        bar.addView(Ui.button(this, "＋ Nueva") { newSequence() })
        bar.addView(Ui.button(this, "Importar JSON") { importLauncher.launch(arrayOf("*/*")) })
        root.addView(bar)
        val names = store.names()
        if (names.isEmpty()) root.addView(Ui.text(this, "Aún no hay secuencias. Crea una o importa un JSON (p. ej. de la versión de PC).", 13f))
        for (n in names) {
            val seq = runCatching { store.load(n) }.getOrNull()
            val info = seq?.let { describe(it, s) } ?: ("No se pudo leer" to Ui.BAD)
            val b = Ui.button(this, (if (n == active) "● " else "") + n) { sequenceMenu(n) }
            root.addView(b)
            root.addView(Ui.text(this, info.first, 12f, info.second))
        }
    }

    private fun permRow(label: String, ok: Boolean, fix: () -> Unit) {
        val row = Ui.hbox(this)
        row.addView(Ui.text(this, (if (ok) "✅ " else "❌ ") + label).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (!ok) row.addView(Ui.button(this, "Conceder") { fix() })
        root.addView(row)
    }

    /** Texto de origen + avisos (complementos y resolución). */
    private fun describe(seq: Sequence, s: Screen): Pair<String, Int> {
        val src = Mapping.sourceScreen(seq)
        val o = seq.origin
        val parts = mutableListOf("Creada en ${o?.platform ?: "?"} · ${src.width}×${src.height}" +
            (if (o?.screen == null) " (supuesto)" else ""))
        var color = Ui.OK
        if (src.width != s.width || src.height != s.height) {
            parts += if (Mapping.sameFormat(src, s)) "se escalará a esta pantalla"
            else "formato distinto: conviene «Mapear a esta pantalla»"
            color = Ui.WARN
        }
        val miss = seq.missingPlugins()
        if (miss.isNotEmpty()) {
            parts += "⚠ complementos no disponibles: ${miss.joinToString()}"
            color = Ui.WARN
        }
        return parts.joinToString(" · ") to color
    }

    // ------------------------------------------------------------ acciones
    private fun startRunner(name: String?) {
        if (!accessibilityEnabled() || !overlayAllowed()) {
            Toast.makeText(this, "Faltan permisos (arriba).", Toast.LENGTH_LONG).show(); return
        }
        pendingSequence = name
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun setActive(name: String) {
        prefs().edit().putString("active", name).apply()
        if (RunnerService.instance != null) RunnerService.select(this, name)
    }

    private fun sequenceMenu(n: String) {
        val opts = arrayOf("Usar en el panel flotante", "Editar", "Mapear a esta pantalla", "Duplicar",
            "Renombrar", "Exportar JSON", "Eliminar")
        AlertDialog.Builder(this).setTitle(n).setItems(opts) { _, which ->
            when (which) {
                0 -> { setActive(n); if (RunnerService.instance == null) startRunner(n) else render() }
                1 -> startActivity(Intent(this, EditorActivity::class.java).putExtra("name", n))
                2 -> mapDialog(n)
                3 -> { val c = store.freeName("$n copia"); store.save(c, store.load(n)); render() }
                4 -> ask("Nuevo nombre", n) { nn -> store.rename(n, nn); if (prefs().getString("active", null) == n) setActive(SequenceStore.safe(nn)); render() }
                5 -> { exportName = n; exportLauncher.launch("$n.json") }
                6 -> AlertDialog.Builder(this).setMessage("¿Eliminar «$n»?")
                    .setPositiveButton("Eliminar") { _, _ -> store.delete(n); render() }
                    .setNegativeButton("Cancelar", null).show()
            }
        }.show()
    }

    private fun mapDialog(n: String) {
        val seq = store.load(n)
        val src = Mapping.sourceScreen(seq)
        val dst = screen()
        val modes = Mapping.Mode.values()
        val def = Mapping.defaultMode(src, dst)
        var chosen = def.ordinal
        AlertDialog.Builder(this)
            .setTitle("Mapear ${src.width}×${src.height} → ${dst.width}×${dst.height}")
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), chosen) { _, w -> chosen = w }
            .setPositiveButton("Crear copia mapeada") { _, _ ->
                val rep = Mapping.remap(seq, dst, modes[chosen], "android", "AutoClicker Android ${BuildConfig.VERSION_NAME}")
                val nn = store.freeName("$n (${dst.width}x${dst.height})")
                store.save(nn, seq)
                AlertDialog.Builder(this).setTitle("Mapeada: $nn")
                    .setMessage("${rep.changedSteps} pasos convertidos.\n\n" + rep.warnings.joinToString("\n\n").ifEmpty { "Sin avisos." })
                    .setPositiveButton("OK", null).show()
                render()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun newSequence() = ask("Nombre de la secuencia", "Nueva secuencia") { nn ->
        val name = store.freeName(nn)
        val seq = Sequence(name, Origin("android", "AutoClicker Android ${BuildConfig.VERSION_NAME}", screen()))
        store.save(name, seq)
        startActivity(Intent(this, EditorActivity::class.java).putExtra("name", name))
    }

    private fun ask(title: String, value: String, done: (String) -> Unit) {
        val e = EditText(this).apply { setText(value); selectAll() }
        AlertDialog.Builder(this).setTitle(title).setView(e)
            .setPositiveButton("OK") { _, _ -> e.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done) }
            .setNegativeButton("Cancelar", null).show()
    }

    // ------------------------------------------------------------ importar
    @Suppress("DEPRECATION")
    private fun handleIncoming(i: Intent) {
        val uri: Uri? = i.data ?: (i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        uri?.let { importFrom(it) }
    }

    private fun importFrom(uri: Uri) {
        val text = runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        val seq = text?.let { runCatching { Sequence.parse(it) }.getOrNull() }
        if (seq == null) {
            Toast.makeText(this, "No es una secuencia válida", Toast.LENGTH_LONG).show(); return
        }
        val name = store.freeName(seq.name.ifBlank { "importada" })
        store.save(name, seq)
        val msgs = mutableListOf<String>()
        val miss = seq.missingPlugins()
        if (miss.isNotEmpty()) msgs += "Usa complementos que esta app no tiene: ${miss.joinToString()}. " +
            "Esos pasos se conservan pero se saltarán al ejecutar."
        val src = Mapping.sourceScreen(seq)
        val dst = screen()
        if (src.width != dst.width || src.height != dst.height) {
            msgs += "Se creó para ${src.width}×${src.height}" + (if (seq.origin?.screen == null) " (supuesto: PC/MuMu)" else "") +
                "; esta pantalla es ${dst.width}×${dst.height}. Usa «Mapear a esta pantalla»."
        }
        render()
        AlertDialog.Builder(this).setTitle("Importada: $name")
            .setMessage(msgs.joinToString("\n\n").ifEmpty { "Lista para usar." })
            .setPositiveButton("OK", null)
            .apply { if (src.width != dst.width || src.height != dst.height) setNeutralButton("Mapear ahora") { _, _ -> mapDialog(name) } }
            .show()
    }
}
