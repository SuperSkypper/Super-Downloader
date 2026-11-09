package app.skypper.superdownloader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * A barra de progresso do bloco de status, desenhada a mao.
 *
 * O `ProgressBar` do Android nao serviu: ele respeita `maxHeight` e o tamanho
 * intrinseco do drawable, entao o preenchimento nao cobria a altura nem a
 * largura do bloco (o usuario via "a barra nao pega a barra inteira"). Desenhar
 * dois retangulos arredondados aqui elimina a duvida: trilho do tamanho da View,
 * preenchimento proporcional ao progresso, mesmo raio do cartao.
 */
class ProgressStrip(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val radius = 12f * density

    // Trilho no tom da borda e preenchimento no accent com alfa: sem isso a
    // barra em 0% ficava invisivel (surface_variant era quase igual ao cartao) e
    // o bloco parecia um retangulo vazio e sem funcao.
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.border)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        alpha = 130
    }

    private val rect = RectF()

    /** 0f..1f. */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val r = radius.coerceAtMost(height / 2f)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, trackPaint)

        val filled = width * progress
        if (filled > 0.5f) {
            rect.set(0f, 0f, filled, height.toFloat())
            canvas.drawRoundRect(rect, r, r, fillPaint)
        }
    }
}
