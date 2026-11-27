package io.github.emiliounitydev.autoclicker.service

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import io.github.emiliounitydev.autoclicker.Diag
import java.io.File

/**
 * Permiso de captura sin intervención del usuario.
 *
 * Android corta la captura (MediaProjection) cuando la app compartida se
 * cierra, p. ej. al reiniciar el juego, y para volver a capturar hace falta
 * aceptar otra vez el diálogo del sistema. Durante una secuencia la app lo
 * pide sola ([ConsentActivity]) y el servicio de accesibilidad pulsa
 * «Compartir pantalla» — SOLO durante unos segundos tras pedirlo nosotros
 * (marca en un fichero: el servicio vive en otro proceso).
 */
object Consent {
    private fun file(ctx: Context) = File(ctx.filesDir, "consent_until")

    fun arm(ctx: Context, ms: Long = 20_000L) {
        runCatching { file(ctx).writeText((System.currentTimeMillis() + ms).toString()) }
    }

    fun disarm(ctx: Context) {
        runCatching { file(ctx).delete() }
    }

    fun armed(ctx: Context): Boolean =
        runCatching { file(ctx).readText().trim().toLong() > System.currentTimeMillis() }.getOrDefault(false)

    /** Intent del diálogo: en Android 14+ solo «toda la pantalla» (una captura
     * de una sola app se corta al cerrar esa app). */
    fun captureIntent(ctx: Context): Intent {
        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else mpm.createScreenCaptureIntent()
    }

    /** Pide de nuevo el permiso de captura (desde el servicio). */
    fun request(ctx: Context) {
        Diag.log("captura", "pidiendo de nuevo el permiso de captura (automático)")
        arm(ctx)
        val i = Intent(ctx, io.github.emiliounitydev.autoclicker.ui.ConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        runCatching { ctx.startActivity(i) }.onFailure { Diag.log("captura", "no se pudo pedir: $it") }
    }

    /** Textos del botón que acepta el diálogo del sistema (ES/EN). */
    val ACCEPT = listOf("Compartir pantalla", "Compartir toda la pantalla", "Empezar ahora", "Iniciar ahora",
        "Empezar", "Iniciar", "Comenzar", "Aceptar", "Permitir",
        "Share screen", "Share entire screen", "Start now", "Start", "Allow")
}
