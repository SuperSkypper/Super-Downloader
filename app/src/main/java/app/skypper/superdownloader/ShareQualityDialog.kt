package app.skypper.superdownloader

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * The gear in the quality row: which quality applies to SHARE.
 *
 * The QUALITY preset on the screen governs the link pasted into the field; a video
 * shared from elsewhere usually has a different need (send on WhatsApp, keep the
 * best possible), so the share quality is chosen here.
 *
 * A single panel: theme background, the toggle row and the chips right on it. Before,
 * there was panel > card > row (three boxes one inside the other) just to show
 * a toggle and three chips — the same space with three borders.
 */
class ShareQualityDialog(
    private val host: Activity,
    private val palette: Palette,
    private val prefs: Prefs,
    private val onChanged: () -> Unit,
) {

    private lateinit var body: LinearLayout

    fun show() {
        body = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }

        val panel = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = palette.roundRect(
                palette.background, dp(16).toFloat(), palette.border, dp(1)
            )
            setPadding(dp(10), dp(10), dp(10), dp(8))
        }
        panel.addView(
            TextView(host).apply {
                text = "Share Quality"
                setTextColor(palette.text)
                textSize = 16f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        )
        panel.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        rebuild()

        val dialog = AlertDialog.Builder(host)
            .setView(panel)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(palette.accent)
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun rebuild() {
        body.removeAllViews()

        // Toggle row: the frame IS the state, and the whole row toggles.
        body.addView(
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(10), 0)
                background = if (prefs.shareQualityEnabled) {
                    palette.roundRect(palette.surface, dp(10).toFloat(), palette.accent, dp(1))
                } else {
                    ContextCompat.getDrawable(host, R.drawable.bg_action)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    prefs.shareQualityEnabled = !prefs.shareQualityEnabled
                    if (prefs.shareQualityEnabled && prefs.shareQualityKey.isBlank()) {
                        // Starts equal to the on-screen preset: turning the toggle on does not
                        // change behavior until the user picks something else.
                        prefs.shareQualityKey = prefs.effectiveQuality().key
                    }
                    onChanged()
                    rebuild()
                }
                addView(
                    ImageView(host).apply {
                        setImageResource(R.drawable.ic_check)
                        imageTintList = ColorStateList.valueOf(
                            if (prefs.shareQualityEnabled) palette.accent else palette.muted
                        )
                    },
                    LinearLayout.LayoutParams(dp(16), dp(16))
                )
                addView(
                    TextView(host).apply {
                        text = "Use A Quality For Share"
                        setTextColor(palette.text)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginStart = dp(5) }
                )
                addView(
                    Switch(host).apply {
                        isChecked = prefs.shareQualityEnabled
                        isClickable = false
                        isFocusable = false
                        minHeight = 0
                        minWidth = 0
                    }
                )
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42))
        )

        if (prefs.shareQualityEnabled) {
            // The same 34dp chips as the main screen.
            val strip = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
            Quality.ALL.forEach { preset ->
                val selected = preset.key == prefs.shareQualityKey
                strip.addView(
                    TextView(host).apply {
                        text = preset.label
                        textSize = 13f
                        gravity = Gravity.CENTER
                        setPadding(dp(12), 0, dp(12), 0)
                        isClickable = true
                        isFocusable = true
                        background = if (selected) {
                            palette.roundRect(palette.accent, dp(12).toFloat())
                        } else {
                            ContextCompat.getDrawable(host, R.drawable.bg_action)
                        }
                        setTextColor(if (selected) palette.onAccent else palette.muted)
                        setOnClickListener {
                            prefs.shareQualityKey = preset.key
                            onChanged()
                            rebuild()
                        }
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)
                    ).apply { marginEnd = dp(10) }
                )
            }
            body.addView(
                HorizontalScrollView(host).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(strip)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            )
        }

        body.addView(
            TextView(host).apply {
                text = if (prefs.shareQualityEnabled) {
                    "Falls back to the largest available when the post has no such height."
                } else {
                    "Off: shared videos use the quality picked on the main screen (${prefs.effectiveQuality().label})."
                }
                setTextColor(palette.muted)
                textSize = 13f
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
    }

    private fun dp(value: Int): Int =
        (value * host.resources.displayMetrics.density).toInt()
}
