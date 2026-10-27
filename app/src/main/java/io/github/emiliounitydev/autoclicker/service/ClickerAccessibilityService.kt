package io.github.emiliounitydev.autoclicker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Servicio de accesibilidad: solo se usa para inyectar gestos (toques y
 * arrastres) y la tecla Atrás. No lee el contenido de otras apps.
 */
class ClickerAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    suspend fun tap(x: Int, y: Int, durationMs: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        dispatch(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1).toLong()))
    }

    suspend fun drag(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int) {
        if (holdMs > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Mantener pulsado y luego desplazarse: dos trazos encadenados.
            val p1 = Path().apply { moveTo(x1.toFloat(), y1.toFloat()) }
            val hold = GestureDescription.StrokeDescription(p1, 0, holdMs.toLong(), true)
            dispatch(hold)
            val p2 = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
            dispatch(hold.continueStroke(p2, 0, durationMs.coerceAtLeast(1).toLong(), false))
        } else {
            val p = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
            dispatch(GestureDescription.StrokeDescription(p, 0, durationMs.coerceAtLeast(1).toLong()))
        }
    }

    suspend fun back() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(150)
    }

    private suspend fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val g = GestureDescription.Builder().addStroke(stroke).build()
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }

    companion object {
        @Volatile
        var instance: ClickerAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null
    }
}

/** Tamaño real de la pantalla (incluye barras del sistema), en vertical. */
object ScreenInfo {
    fun size(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    fun density(ctx: Context): Int = ctx.resources.displayMetrics.densityDpi
}
