package com.dronecamera.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
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

/** Segment listesini sirayla oynatir; ilerleme ve bitis geri bildirimi verir. */
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

    fun start() = playNext()

    fun cancel() {
        cancelled = true
        animator?.cancel()
        animator = null
    }

    private fun playNext() {
        if (cancelled) return
        if (index >= segments.size) {
            onEnd()
            return
        }
        val segment = segments[index++]
        if (segment is ZoomSegment.Hold) setZoom(segment.zoom)

        val anim = ValueAnimator.ofFloat(0f, 1f).setDuration(segment.durationMs)
        anim.interpolator = LinearInterpolator()
        anim.addUpdateListener { a ->
            val t = a.animatedValue as Float
            if (segment is ZoomSegment.Ramp) {
                val eased = segment.interpolator.getInterpolation(t)
                val logFrom = ln(segment.from.toDouble())
                val logTo = ln(segment.to.toDouble())
                setZoom(exp(logFrom + (logTo - logFrom) * eased).toFloat())
            }
            val elapsed = elapsedBefore + (segment.durationMs * t).toLong()
            onProgress(elapsed / totalMs.toFloat(), (totalMs - elapsed) / 1000f)
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (cancelled) return
                elapsedBefore += segment.durationMs
                playNext()
            }
        })
        animator = anim
        anim.start()
    }
}
