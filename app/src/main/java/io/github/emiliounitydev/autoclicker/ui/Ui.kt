package io.github.emiliounitydev.autoclicker.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Pequeñas utilidades para construir la interfaz por código. */
object Ui {
    fun dp(ctx: Context, v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun vbox(ctx: Context, pad: Int = 12) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, pad)
        setPadding(p, p, p, p)
    }

    fun hbox(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

    fun title(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(ctx, 12), 0, dp(ctx, 4))
    }

    fun text(ctx: Context, t: String, size: Float = 14f, color: Int? = null) = TextView(ctx).apply {
        text = t; textSize = size; color?.let { setTextColor(it) }
    }

    fun button(ctx: Context, t: String, onClick: () -> Unit) = Button(ctx).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
    }

    val WARN = Color.rgb(0xd0, 0x80, 0x00)
    val OK = Color.rgb(0x2e, 0x9d, 0x3a)
    val BAD = Color.rgb(0xc6, 0x28, 0x28)

    /** Campo de formulario con etiqueta. */
    class Field(val label: String, val edit: EditText)

    fun field(ctx: Context, parent: ViewGroup, label: String, value: String, numeric: Boolean = true,
              decimal: Boolean = false): Field {
        val row = hbox(ctx)
        val l = TextView(ctx).apply { text = label; minWidth = dp(ctx, 120) }
        val e = EditText(ctx).apply {
            setText(value)
            inputType = when {
                decimal -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                else -> InputType.TYPE_CLASS_TEXT
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(l); row.addView(e)
        parent.addView(row)
        return Field(label, e)
    }

    fun EditText.int(def: Int = 0) = text.toString().trim().toIntOrNull() ?: def
    fun EditText.dbl(def: Double = 0.0) = text.toString().trim().replace(',', '.').toDoubleOrNull() ?: def

    fun spacer(ctx: Context, h: Int = 8) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, h))
    }
}
