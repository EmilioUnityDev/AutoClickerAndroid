package io.github.emiliounitydev.autoclicker.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.github.emiliounitydev.autoclicker.Diag
import kotlinx.coroutines.delay

/**
 * Reactivar la accesibilidad sin pasar por Ajustes.
 *
 * En Xiaomi/HyperOS, cuando el sistema cierra la app por falta de memoria, el
 * servicio de accesibilidad queda «activado pero detenido» y Android no lo
 * vuelve a arrancar. Quitarlo y volver a ponerlo en la lista de servicios
 * activos (lo mismo que desactivar/activar a mano) obliga al sistema a
 * arrancarlo de nuevo. Requiere WRITE_SECURE_SETTINGS, que se concede una vez
 * desde el PC:  adb shell pm grant <paquete> android.permission.WRITE_SECURE_SETTINGS
 */
object AccRepair {
    fun allowed(ctx: Context) =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    private fun lists(ctx: Context): Pair<String, List<String>> {
        val want = ComponentName(ctx, ClickerAccessibilityService::class.java).flattenToString()
        val cur = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return want to cur.split(':').filter { it.isNotBlank() && !it.equals(want, true) }
    }

    private fun off(ctx: Context, others: List<String>) {
        Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            others.joinToString(":"))
    }

    private fun on(ctx: Context, want: String, others: List<String>) {
        val cr = ctx.contentResolver
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + want).joinToString(":"))
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    /** Desde la interfaz (hilo principal). `done` se llama tras comprobarlo. */
    fun repair(ctx: Context, done: (Boolean) -> Unit) {
        if (!allowed(ctx)) { done(false); return }
        val (want, others) = lists(ctx)
        Diag.log("reparar", "accesibilidad detenida: desactivar/activar automático")
        off(ctx, others)
        val h = Handler(Looper.getMainLooper())
        h.postDelayed({
            on(ctx, want, others)
            h.postDelayed({
                GestureClient.ping(ctx) { ok ->
                    Diag.log("reparar", "resultado: ${if (ok) "accesibilidad OK" else "sigue detenida"}")
                    done(ok)
                }
            }, 1500)
        }, 700)
    }

    /** Desde una corrutina (secuencia en marcha). true si vuelve a responder. */
    suspend fun repairAndWait(ctx: Context): Boolean {
        if (!allowed(ctx)) return false
        val (want, others) = lists(ctx)
        Diag.log("reparar", "accesibilidad caída durante la secuencia: desactivar/activar automático")
        off(ctx, others)
        delay(700)
        on(ctx, want, others)
        repeat(10) {
            delay(500)
            val r = GestureClient.request(ctx, GestureBridge.PING, null, 1500)
            if (r == GestureBridge.OK) {
                Diag.log("reparar", "resultado: accesibilidad OK, la secuencia sigue")
                return true
            }
        }
        Diag.log("reparar", "resultado: sigue detenida")
        return false
    }
}
