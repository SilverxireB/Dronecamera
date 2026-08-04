package com.dronecamera.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Deklansorun cevresinde donen ince ilerleme halkasi. Ayri bir ilerleme
 * cubugu yerine cekimin durumunu deklansorun kendisinde gosterir.
 */
class ProgressRing @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bounds = RectF()

    /** 0..1 arasi ilerleme. */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var ringColor: Int = 0xFF9BE8FF.toInt()
        set(value) {
            field = value
            invalidate()
        }

    var ringWidth: Float = 6f
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress <= 0f) return
        val inset = ringWidth / 2f
        bounds.set(inset, inset, width - inset, height - inset)
        paint.color = ringColor
        paint.strokeWidth = ringWidth
        canvas.drawArc(bounds, START_ANGLE, SWEEP * progress, false, paint)
    }

    private companion object {
        /** Saat 12 yonunden baslar. */
        const val START_ANGLE = -90f
        const val SWEEP = 360f
    }
}
