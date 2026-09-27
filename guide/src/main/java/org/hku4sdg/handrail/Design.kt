package org.hku4sdg.handrail

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.TextView

internal object Design {
    val background = 0xFFF6F4EE.toInt()
    val surface = 0xFFFFFFFF.toInt()
    val ink = 0xFF173B35.toInt()
    val secondary = 0xFF66736D.toInt()
    val forest = 0xFF17483E.toInt()
    val paleGreen = 0xFFE8F1EB.toInt()
    val orange = 0xFFE8783D.toInt()
    val paleOrange = 0xFFFFEFE5.toInt()
    val line = 0xFFE4E8E1.toInt()

    fun shape(fill: Int, radius: Float, stroke: Int? = null, strokeWidth: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            if (stroke != null) setStroke(strokeWidth, stroke)
        }
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

internal fun Context.text(
    value: String,
    size: Float,
    color: Int = Design.ink,
    bold: Boolean = false,
    gravity: Int = Gravity.START
): TextView = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(color)
    this.gravity = gravity
    includeFontPadding = false
    if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

/** A small ascending rail with two touch points, used as the product mark. */
internal class HandrailMark(context: Context) : View(context) {
    private val rail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Design.surface
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val point = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFB178.toInt() }

    init {
        background = Design.shape(Design.forest, context.dp(15).toFloat())
        contentDescription = "数字扶手标志"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val path = Path().apply {
            moveTo(w * 0.24f, h * 0.69f)
            lineTo(w * 0.43f, h * 0.51f)
            lineTo(w * 0.55f, h * 0.51f)
            lineTo(w * 0.76f, h * 0.30f)
        }
        canvas.drawPath(path, rail)
        canvas.drawCircle(w * 0.24f, h * 0.69f, context.dp(3).toFloat(), point)
        canvas.drawCircle(w * 0.76f, h * 0.30f, context.dp(3).toFloat(), point)
    }
}
