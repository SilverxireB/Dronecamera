package com.dronecamera.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Bir cekim boyunca KADRAJIN nasil hareket edecegini tanimlayan parcalar.
 *
 * Kadraj iki bilesenden olusur: buyutme (zoom) ve kirpma penceresinin merkezi
 * (cx, cy — 0..1, GL doku uzayinda). Zoom logaritmik uzayda, merkez dogrusal
 * olarak yorumlanir; boylece buyume algisal olarak sabit hizda kalirken
 * kaydirma da duzgun ilerler.
 *
 * Merkez varsayilanlari 0.5'tir; merkez belirtilmeyen cagrilar eskisi gibi
 * yalnizca zoom hareketi uretir.
 */
sealed class ZoomSegment {
    abstract val durationMs: Long

    data class Ramp(
        val from: Float,
        val to: Float,
        override val durationMs: Long,
        val interpolator: TimeInterpolator,
        val fromCx: Float = CENTER,
        val fromCy: Float = CENTER,
        val toCx: Float = CENTER,
        val toCy: Float = CENTER
    ) : ZoomSegment()

    data class Hold(
        val zoom: Float,
        override val durationMs: Long,
        val cx: Float = CENTER,
        val cy: Float = CENTER
    ) : ZoomSegment()

    companion object {
        const val CENTER = 0.5f
    }
}

/**
 * Sekansin belirli bir anindaki kadraji [out] dizisine yazar:
 * `out[0] = zoom`, `out[1] = cx`, `out[2] = cy`.
 *
 * Oynaticinin seyreltilmis adimlarindan bagimsizdir; her karede cagrilarak
 * tam deger elde edilir (yazilim kirpmasi bunu kullanir). Dizi disaridan
 * verilir ki kare basina yeni nesne uretilmesin.
 */
fun List<ZoomSegment>.frameAt(elapsedMs: Long, out: FloatArray) {
    var remaining = elapsedMs.coerceAtLeast(0L)
    for (segment in this) {
        if (remaining < segment.durationMs) {
            when (segment) {
                is ZoomSegment.Hold -> {
                    out[0] = segment.zoom
                    out[1] = segment.cx
                    out[2] = segment.cy
                }
                is ZoomSegment.Ramp -> {
                    val eased = segment.interpolator.getInterpolation(
                        remaining.toFloat() / segment.durationMs
                    )
                    val logFrom = ln(segment.from.toDouble())
                    val logTo = ln(segment.to.toDouble())
                    out[0] = exp(logFrom + (logTo - logFrom) * eased).toFloat()
                    out[1] = segment.fromCx + (segment.toCx - segment.fromCx) * eased
                    out[2] = segment.fromCy + (segment.toCy - segment.fromCy) * eased
                }
            }
            return
        }
        remaining -= segment.durationMs
    }
    when (val last = lastOrNull()) {
        is ZoomSegment.Hold -> {
            out[0] = last.zoom
            out[1] = last.cx
            out[2] = last.cy
        }
        is ZoomSegment.Ramp -> {
            out[0] = last.to
            out[1] = last.toCx
            out[2] = last.toCy
        }
        else -> {
            out[0] = 1f
            out[1] = ZoomSegment.CENTER
            out[2] = ZoomSegment.CENTER
        }
    }
}

/**
 * Segment listesini sirayla oynatir.
 *
 * Titreme onlemi: ValueAnimator ekran tazeleme hizinda (120 Hz'e kadar)
 * tetiklenir; her tetiklemede kameraya yeni bir zoom istegi gondermek HAL'i
 * bogar ve goruntude sicramaya yol acar. Bu yuzden OPTIK istekler ~30 Hz'e
 * seyreltilir. Yazilim kirpmasi bu oynaticiyi beklemez; her karede
 * [frameAt] ile tam degeri hesaplar.
 */
class ZoomSequencePlayer(
    private val segments: List<ZoomSegment>,
    private val setZoom: (Float) -> Unit,
    private val onProgress: (fraction: Float, remainingSec: Float) -> Unit,
    private val onEnd: () -> Unit
) {
    private val totalMs = segments.sumOf { it.durationMs }.coerceAtLeast(1)
    private var elapsedBefore = 0L
    private var index = 0
    private var animator: ValueAnimator? = null
    private var cancelled = false
    private var lastEmitTime = 0L
    private var lastZoom = Float.NaN

    fun start() = playNext()

    fun cancel() {
        cancelled = true
        animator?.cancel()
        animator = null
    }

    /** Seyreltilmis ve gereksiz tekrarlardan arindirilmis zoom gonderimi. */
    private fun emitZoom(zoom: Float, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force) {
            if (now - lastEmitTime < MIN_INTERVAL_MS) return
            if (!lastZoom.isNaN() && abs(zoom - lastZoom) < MIN_ZOOM_STEP) return
        }
        lastEmitTime = now
        lastZoom = zoom
        setZoom(zoom)
    }

    private fun playNext() {
        if (cancelled) return
        if (index >= segments.size) {
            onEnd()
            return
        }
        val segment = segments[index++]
        if (segment is ZoomSegment.Hold) emitZoom(segment.zoom, force = true)

        val anim = ValueAnimator.ofFloat(0f, 1f).setDuration(segment.durationMs)
        anim.interpolator = LinearInterpolator()
        anim.addUpdateListener { a ->
            val t = a.animatedValue as Float
            if (segment is ZoomSegment.Ramp) {
                val eased = segment.interpolator.getInterpolation(t)
                val logFrom = ln(segment.from.toDouble())
                val logTo = ln(segment.to.toDouble())
                emitZoom(exp(logFrom + (logTo - logFrom) * eased).toFloat(), force = false)
            }
            val elapsed = elapsedBefore + (segment.durationMs * t).toLong()
            onProgress(elapsed / totalMs.toFloat(), (totalMs - elapsed) / 1000f)
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (cancelled) return
                if (segment is ZoomSegment.Ramp) emitZoom(segment.to, force = true)
                elapsedBefore += segment.durationMs
                playNext()
            }
        })
        animator = anim
        anim.start()
    }

    private companion object {
        /** ~30 Hz. Daha sik gonderim goruntude sicramaya yol aciyor. */
        const val MIN_INTERVAL_MS = 33L
        const val MIN_ZOOM_STEP = 0.004f
    }
}
