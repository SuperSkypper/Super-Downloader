package app.skypper.superdownloader

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat

/**
 * The palette in one place, same as Super Capture.
 *
 * The values live in `res/values/colors.xml`; this class resolves once per
 * activity and adds what layouts built in code need (alpha tint
 * and rounded rectangle). An `Int` constant in a companion sees neither the theme nor can it
 * be overridden by a resource, which was the reason the old app had the same color
 * repeated on every screen.
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
     * The same color with reduced opacity.
     *
     * Multiplying the RGB would darken it toward black, which on a nearly black page
     * disappears; alpha over the page is what reads as tint.
     */
    fun alpha(base: Int, fraction: Float): Int = Color.argb(
        (255 * fraction).toInt().coerceIn(0, 255),
        Color.red(base),
        Color.green(base),
        Color.blue(base),
    )

    /** Rounded rectangle in [fill], with an optional outline in [stroke]. */
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
