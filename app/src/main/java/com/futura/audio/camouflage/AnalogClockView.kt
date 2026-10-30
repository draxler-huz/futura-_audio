package com.futura.audio.camouflage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View

class AnalogClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }

    private var hours = 0
    private var minutes = 0
    private var seconds = 0

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = Runnable { invalidate() }

    init {
        updateTime()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tickRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(tickRunnable)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val centerX = width / 2
        val centerY = height / 2
        val radius = minOf(width, height) / 2 * 0.9f

        canvas.drawCircle(centerX, centerY, radius, paint)

        for (i in 0..11) {
            val angle = (i * 30 - 90) * Math.PI / 180
            val innerRadius = radius * 0.85f
            val outerRadius = radius * 0.95f
            val x1 = centerX + innerRadius * Math.cos(angle).toFloat()
            val y1 = centerY + innerRadius * Math.sin(angle).toFloat()
            val x2 = centerX + outerRadius * Math.cos(angle).toFloat()
            val y2 = centerY + outerRadius * Math.sin(angle).toFloat()
            canvas.drawLine(x1, y1, x2, y2, paint)
        }

        val hourAngle = ((hours % 12) * 30 + minutes * 0.5 - 90) * Math.PI / 180
        val minuteAngle = (minutes * 6 + seconds * 0.1 - 90) * Math.PI / 180
        val secondAngle = (seconds * 6 - 90) * Math.PI / 180

        drawHand(canvas, centerX, centerY, radius * 0.5f, hourAngle, 8f)
        drawHand(canvas, centerX, centerY, radius * 0.75f, minuteAngle, 5f)
        drawHand(canvas, centerX, centerY, radius * 0.85f, secondAngle, 2f)

        canvas.drawCircle(centerX, centerY, 12f, centerPaint)

        val timeText = String.format("%02d:%02d", hours, minutes)
        canvas.drawText(timeText, centerX, centerY + radius + 60f, textPaint)
    }

    private fun drawHand(
        canvas: Canvas,
        cx: Float, cy: Float,
        length: Float,
        angle: Double,
        width: Float
    ) {
        val endX = cx + length * Math.cos(angle).toFloat()
        val endY = cy + length * Math.sin(angle).toFloat()
        paint.strokeWidth = width
        canvas.drawLine(cx, cy, endX, endY, paint)
    }

    private fun updateTime() {
        val cal = java.util.Calendar.getInstance()
        hours = cal.get(java.util.Calendar.HOUR_OF_DAY)
        minutes = cal.get(java.util.Calendar.MINUTE)
        seconds = cal.get(java.util.Calendar.SECOND)
        handler.postDelayed(tickRunnable, 1000)
    }
}