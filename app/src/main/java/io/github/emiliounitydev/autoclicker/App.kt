package io.github.emiliounitydev.autoclicker

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de diagnóstico persistente (files/diag.log, ~400 KB con rotación).
 *
 * Sirve para averiguar por qué se para tras horas de uso: guarda los cierres
 * inesperados de la app (excepción con su traza), los motivos con los que
 * Android cerró el proceso en ejecuciones anteriores (memoria, ANR, cierre por
 * el sistema, crash nativo… vía ApplicationExitInfo), cuándo se conecta o cae
 * el servicio de accesibilidad, inicio/fin de secuencias con su motivo y un
 * latido periódico con memoria y fotogramas capturados.
 */
object Diag {
    private const val MAX_BYTES = 400_000L
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    @Volatile private var dir: File? = null

    fun file(ctx: Context) = File(ctx.filesDir, "diag.log")

    fun init(ctx: Context) {
        dir = ctx.filesDir
    }

    @Synchronized
    fun log(tag: String, msg: String) {
        val d = dir ?: return
        runCatching {
            val f = File(d, "diag.log")
            if (f.length() > MAX_BYTES) {
                val old = File(d, "diag.old.log")
                old.delete()
                f.renameTo(old)
            }
            f.appendText("${fmt.format(Date())} [$tag] $msg\n")
        }
    }

    fun memory(): String {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
        val max = rt.maxMemory() / 1_048_576
        val nativeMb = Debug.getNativeHeapAllocatedSize() / 1_048_576
        return "memoria Java ${used}/${max} MB, nativa ${nativeMb} MB"
    }

    /**
     * Texto para compartir, LO MÁS RECIENTE PRIMERO (al pegarlo en otro sitio
     * suele cortarse el final): primero un resumen de los eventos importantes
     * (cierres, accesibilidad, gestos, secuencias, errores) y después el
     * registro completo de las últimas 36 h, también del más nuevo al más viejo.
     */
    fun dump(ctx: Context, maxChars: Int = 60_000): String {
        val old = File(ctx.filesDir, "diag.old.log")
        val text = (if (old.exists()) old.readText() else "") + (file(ctx).takeIf { it.exists() }?.readText() ?: "")
        val cutoff = fmt.format(Date(System.currentTimeMillis() - 36L * 3600_000))
        // Entradas (una entrada puede ocupar varias líneas: trazas).
        val entries = mutableListOf<String>()
        for (line in text.lines()) {
            if (line.length > 20 && line[4] == '-' && line[13] == ':') entries.add(line)
            else if (entries.isNotEmpty() && line.isNotEmpty()) entries[entries.size - 1] += "\n" + line
        }
        val recent = entries.filter { it.take(19) >= cutoff }.reversed()
        val important = recent.filter { e ->
            listOf("[cierre]", "[accesibilidad]", "[gestos]", "[secuencia]", "[CRASH]", "[panel]", "[captura]", "[app]")
                .any { e.contains(it) }
        }.take(60)
        val sb = StringBuilder()
        sb.append("=== RESUMEN (más reciente primero) ===\n")
        important.forEach { sb.append(it.take(600)).append('\n') }
        sb.append("\n=== REGISTRO COMPLETO 36 h (más reciente primero) ===\n")
        for (e in recent) {
            if (sb.length > maxChars) { sb.append("…\n"); break }
            sb.append(e).append('\n')
        }
        return sb.toString()
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
        File(ctx.filesDir, "diag.old.log").delete()
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR (la app se colgó)"
        ApplicationExitInfo.REASON_CRASH -> "CRASH (excepción Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH NATIVO"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "murió una dependencia"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "uso EXCESIVO de recursos (batería/CPU)"
        ApplicationExitInfo.REASON_EXIT_SELF -> "la app se cerró a sí misma"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "fallo de inicialización"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "POCA MEMORIA en el sistema"
        ApplicationExitInfo.REASON_OTHER -> "OTRO (cerrada por el sistema)"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "cambio de permisos"
        ApplicationExitInfo.REASON_SIGNALED -> "señal del sistema (matada)"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "cerrada por el usuario (forzar detención/recientes)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "detenida por el usuario"
        ApplicationExitInfo.REASON_FREEZER -> "congelada por el sistema"
        else -> "motivo $r"
    }

    /**
     * Anota los cierres del proceso desde la última vez (Android 11+). Devuelve
     * un resumen del último cierre para mostrarlo en la pantalla principal.
     */
    fun recordExitReasons(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val prefs = ctx.getSharedPreferences("diag", Context.MODE_PRIVATE)
        val seen = prefs.getLong("last_exit_ts", 0L)
        val infos = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10) }.getOrNull() ?: return null
        var newest = seen
        for (i in infos.sortedBy { it.timestamp }) {
            if (i.timestamp <= seen) continue
            newest = maxOf(newest, i.timestamp)
            val trace = if (i.reason == ApplicationExitInfo.REASON_ANR || i.reason == ApplicationExitInfo.REASON_CRASH_NATIVE)
                runCatching { i.traceInputStream?.bufferedReader()?.readText()?.take(4000) }.getOrNull() else null
            log("cierre", "${fmt.format(Date(i.timestamp))}: ${reasonName(i.reason)} · ${i.description ?: ""} · " +
                "proceso ${i.processName} · importancia ${i.importance} · PSS ${i.pss / 1024} MB · RSS ${i.rss / 1024} MB" +
                (trace?.let { "\n$it" } ?: ""))
        }
        prefs.edit().putLong("last_exit_ts", newest).apply()
        val last = infos.maxByOrNull { it.timestamp } ?: return null
        return "Último cierre de la app: ${fmt.format(Date(last.timestamp))} · ${reasonName(last.reason)}" +
            (last.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        if (Build.VERSION.SDK_INT < 28 || Application.getProcessName() == packageName) Diag.log("app", "proceso iniciado · ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            Diag.log("CRASH", "hilo «${t.name}» · ${Diag.memory()}\n$sw")
            previous?.uncaughtException(t, e)
        }
        // Solo en el proceso principal (el de gestos «:gestures» no lo repite).
        val proc = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else packageName
        if (proc == packageName) lastExit = Diag.recordExitReasons(this)
        else Diag.log("app", "proceso $proc iniciado")
    }

    companion object {
        @Volatile var lastExit: String? = null
    }
}
