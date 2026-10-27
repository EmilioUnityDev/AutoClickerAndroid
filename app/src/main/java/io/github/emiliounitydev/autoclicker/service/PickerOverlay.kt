package io.github.emiliounitydev.autoclicker.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Capa transparente a pantalla completa para elegir un punto (toque) o una
 * región (arrastre) sobre el juego. Devuelve coordenadas de pantalla real.
 */
object PickerOverlay {
    private var view: View? = null

    fun isShowing() = view != null

    /**
     * @param region true = arrastrar para marcar un rectángulo; false = un toque.
     * @param done (x1, y1, x2, y2) en píxeles de pantalla; para un punto x2=x1, y2=y1.
     *        null si se cancela (toque largo de 2 dedos o botón Atrás).
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show(ctx: Context, region: Boolean, hint: String, done: (IntArray?) -> Unit) {
        hide(ctx)
        val app = ctx.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = object : View(app) {
            var sx = -1f; var sy = -1f; var ex = -1f; var ey = -1f
            val dim = Paint().apply { color = 0x33000000 }
            val box = Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 4f }
            val txt = Paint().apply { color = Color.WHITE; textSize = 42f; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
            override fun onDraw(c: Canvas) {
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
                c.drawText(hint, 40f, height * 0.12f, txt)
                if (sx >= 0) c.drawRect(minOf(sx, ex), minOf(sy, ey), maxOf(sx, ex), maxOf(sy, ey), box)
            }
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.sx = e.rawX; v.sy = e.rawY; v.ex = e.rawX; v.ey = e.rawY; v.invalidate() }
                MotionEvent.ACTION_MOVE -> { v.ex = e.rawX; v.ey = e.rawY; v.invalidate() }
                MotionEvent.ACTION_POINTER_DOWN -> { hide(app); done(null) }
                MotionEvent.ACTION_UP -> {
                    val r = if (region) intArrayOf(
                        minOf(v.sx, v.ex).toInt(), minOf(v.sy, v.ey).toInt(),
                        maxOf(v.sx, v.ex).toInt(), maxOf(v.sy, v.ey).toInt())
                    else intArrayOf(e.rawX.toInt(), e.rawY.toInt(), e.rawX.toInt(), e.rawY.toInt())
                    hide(app)
                    done(r)
                }
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(v, lp)
        view = v
    }

    fun hide(ctx: Context) {
        val v = view ?: return
        val wm = ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(v) }
        view = null
    }
}
