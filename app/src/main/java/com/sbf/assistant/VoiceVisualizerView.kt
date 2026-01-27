package com.sbf.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Visualizador de audio de ultra-alta resolución.
 * Muestra un historial denso de amplitudes para un efecto de "onda de energía".
 */
class VoiceVisualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val typedValue = TypedValue()
        if (context.theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)) {
            color = typedValue.data
        } else {
            color = ContextCompat.getColor(context, android.R.color.holo_blue_light)
        }
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }

    // Resolución masiva: 480 barras para un detalle fluido y continuo (doble de muestreo)
    private val amplitudes = FloatArray(480) 
    private var index = 0

    fun addAmplitude(amplitude: Float) {
        // Normalización con altísima sensibilidad
        val normalized = (amplitude * 50f).coerceIn(0.02f, 1.0f)
        
        // Muestreo ultra-denso: 24 pasos por actualización para fluidez extrema
        repeat(24) {
            amplitudes[index] = normalized
            index = (index + 1) % amplitudes.size
        }
        
        invalidate()
    }

    fun clear() {
        amplitudes.fill(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val spacing = w / amplitudes.size
        val barWidth = spacing * 0.95f 
        paint.strokeWidth = barWidth

        for (i in amplitudes.indices) {
            val pos = (index + i) % amplitudes.size
            val amp = amplitudes[pos]
            val x = i * spacing + spacing / 2
            
            // Dibujado centrado verticalmente con amplitud máxima
            val barHeight = h * amp
            val startY = (h - barHeight) / 2
            val stopY = (h + barHeight) / 2
            canvas.drawLine(x, startY, x, stopY, paint)
        }
    }
}
