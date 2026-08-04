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
 * Bir cekim boyunca zoom'un nasil hareket edecegini tanimlayan parcalar.
 * Rampalar logaritmik uzayda oynatilir: buyutme orani sabit hizda degisir,
 * boylece gecis goze dengeli gorunur.
 */
sealed class ZoomSegment {
    abstract val durationMs: Long

    data class Ramp(
        val from: Float,
        val to: Float,
        override val durationMs: Long,
        val interpolator: TimeInterpolator
    ) : ZoomSegment()

    data class Hold(val zoom: Float, override val durationMs: Long) : ZoomSegment()
}

/**
 * Sekansin belirli bir anindaki zoom degerini hesaplar. Oynaticinin
 * seyreltilmis adimlarindan bagimsizdir; her karede cagrilarak tam degerin
 * elde edilmesini saglar (yazilim kirpmasi bunu kullanir).
 */
fun List<ZoomSegment>.zoomAt(elapsedMs: Long): Float {
    var remaining = elapsedMs.coerceAtLeast(0L)
    for (segment in this) {
        if (remaining < segment.durationMs) {
            return when (segment) {
                is ZoomSegment.Hold -> segment.zoom
                is ZoomSegment.Ramp -> {
                    val eased = segment.interpolator.getInterpolation(
                        remaining.toFloat() / segment.durationMs
                    )
                    val logFrom = ln(segment.from.toDouble())
                    val logTo = ln(segment.to.toDouble())
                    exp(logFrom + (logTo - logFrom) * eased).toFloat()
                }
            }
        }
        remaining -= segment.durationMs
    }
    return when (val last = lastOrNull()) {
        is ZoomSegment.Hold -> last.zoom
        is ZoomSegment.Ramp -> last.to
        else -> 1f
    }
}

/**
 * Segment listesini sirayla oynatir.
 *
 * Titreme onlemi: ValueAnimator ekran tazeleme hizinda (120 Hz'e kadar)
 * tetiklenir; her tetiklemede kameraya yeni bir zoom istegi gondermek HAL'i
 * bogar ve goruntude sicramaya yol acar. Bu yuzden istekler ~30 Hz'e
 * seyreltilir ve anlamsiz kucuk degisimler atlanir.
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
