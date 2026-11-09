package dev.superskypper.superdownloader

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat

/**
 * A paleta em um lugar so, igual ao Super Capture.
 *
 * Os valores moram em `res/values/colors.xml`; esta classe resolve uma vez por
 * activity e adiciona o que os layouts montados em codigo precisam (tinta alpha
 * e retangulo arredondado). Constante `Int` em companion nao ve o tema nem pode
 * ser sobrescrita por recurso, que era o motivo de o app antigo ter cor
 * repetida em cada tela.
 */
class Palette(context: Context) {

    private val ctx: Context = context.applicationContext ?: context

    private fun color(res: Int): Int = ContextCompat.getColor(ctx, res)

    val background: Int get() = color(R.color.bg)
    val surface: Int get() = color(R.color.surface)
    val border: Int get() = color(R.color.border)

    val text: Int get() = color(R.color.text)
    val muted: Int get() = color(R.color.text_muted)

    val accent: Int get() = color(R.color.accent)
    val accentDim: Int get() = color(R.color.accent_dim)
    val danger: Int get() = color(R.color.danger)
    val onAccent: Int get() = color(R.color.on_accent)

    /**
     * A mesma cor com opacidade reduzida.
     *
     * Multiplicar o RGB escureceria para preto, que numa pagina quase preta
     * desaparece; alpha sobre a pagina e o que le como tinta.
     */
    fun alpha(base: Int, fraction: Float): Int = Color.argb(
        (255 * fraction).toInt().coerceIn(0, 255),
        Color.red(base),
        Color.green(base),
        Color.blue(base),
    )

    /** Retangulo arredondado em [fill], com contorno opcional em [stroke]. */
    fun roundRect(
        fill: Int,
        radiusPx: Float,
        stroke: Int? = null,
        strokePx: Int = 0,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(fill)
        stroke?.let { setStroke(strokePx.coerceAtLeast(1), it) }
    }
}
