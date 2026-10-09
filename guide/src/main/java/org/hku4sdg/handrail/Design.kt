package org.hku4sdg.handrail

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.TextView

internal object Design {
    val background = 0xFFFFFFFF.toInt()
    val surface = 0xFFFFFFFF.toInt()
    val ink = 0xFF173B35.toInt()
    val secondary = 0xFF173B35.toInt()
    val forest = 0xFF17483E.toInt()
    val paleGreen = 0xFFFFFFFF.toInt()
    val orange = 0xFF173B35.toInt()
    val paleOrange = 0xFFFFFFFF.toInt()
    val line = 0xFF173B35.toInt()
    var appScale = 1.35f
    var displayScript = DisplayScript.HANS

    fun shape(fill: Int, radius: Float, stroke: Int? = null, strokeWidth: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            if (stroke != null) setStroke(strokeWidth, stroke)
        }

    fun ripple(fill: Int, radius: Float, stroke: Int? = null, strokeWidth: Int = 1): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(0x2417483E),
            shape(fill, radius, stroke, strokeWidth),
            shape(0xFFFFFFFF.toInt(), radius)
        )
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

internal fun Context.text(
    value: String,
    size: Float,
    color: Int = Design.ink,
    bold: Boolean = false,
    gravity: Int = Gravity.START,
    convert: Boolean = true
): TextView = TextView(this).apply {
    text = if (convert && Design.displayScript == DisplayScript.HANT) Han.convert(value) else value
    textSize = (size * Design.appScale).coerceAtLeast(18f)
    setTextColor(color)
    this.gravity = gravity
    includeFontPadding = true
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

/** Official Material round vectors, decorative when paired with a spoken label. */
internal fun Context.icon(resource: Int, size: Int = 24, color: Int = Design.forest) =
    android.widget.ImageView(this).apply {
        setImageResource(resource)
        imageTintList = ColorStateList.valueOf(color)
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        minimumWidth = dp(size)
        minimumHeight = dp(size)
    }

internal fun TextView.heading(): TextView = apply {
    if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
}
