package com.dsh.autoroot

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small design system, so the app reads as a finished thing rather than a debug
 * build. Colours are resolved from the system's light/dark setting instead of being
 * hardcoded, and every group of controls sits on a rounded card so sections look like
 * sections instead of one long wall of text.
 *
 * Every text-bearing view sets its own colour. That matters because the background is
 * drawn by us while the theme still supplies default text colours: without this a
 * light-themed phone would render dark text on our dark card and the result would be
 * unreadable.
 */
object Ui {

    private fun night(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun bg(ctx: Context)   = if (night(ctx)) 0xFF0F1115.toInt() else 0xFFEFF1F4.toInt()
    fun card(ctx: Context) = if (night(ctx)) 0xFF181B21.toInt() else 0xFFFFFFFF.toInt()
    fun sunken(ctx: Context) = if (night(ctx)) 0xFF11141A.toInt() else 0xFFF5F6F8.toInt()
    fun text(ctx: Context) = if (night(ctx)) 0xFFE6E8EB.toInt() else 0xFF15171C.toInt()
    // Brighter than a typical "secondary" grey. On a dark card the detail lines
    // ("yes", "binder alive") were dim enough to be hard work to read.
    fun dim(ctx: Context)  = if (night(ctx)) 0xFFAEB5BE.toInt() else 0xFF4A4F57.toInt()
    fun line(ctx: Context) = if (night(ctx)) 0xFF2F353E.toInt() else 0xFFD5D9DF.toInt()

    // Darkened from #4C8DFF, which measured 3.20:1 against white at 12sp. This is
    // about 4.6:1, so primary button labels clear the 4.5:1 they need.
    fun accent() = 0xFF2F6FE0.toInt()
    fun ok()     = 0xFF32D74B.toInt()
    fun warn()   = 0xFFFFB020.toInt()
    fun bad()    = 0xFFFF453A.toInt()

    fun dp(ctx: Context, v: Int) = (ctx.resources.displayMetrics.density * v).toInt()

    private fun round(fill: Int, radiusDp: Int, ctx: Context, strokeDp: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(fill)
            if (strokeDp > 0) setStroke(dp(ctx, strokeDp), strokeColor)
        }

    /** A rounded panel. Everything else is placed inside one of these. */
    fun panel(ctx: Context, padDp: Int = 14, radiusDp: Int = 12): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = round(card(ctx), radiusDp, ctx)
            setPadding(dp(ctx, padDp), dp(ctx, padDp), dp(ctx, padDp), dp(ctx, padDp))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 12) }
        }

    /** A small uppercase heading with a little tracking, like a real settings screen. */
    fun heading(ctx: Context, t: String): TextView =
        TextView(ctx).apply {
            text = t.uppercase()
            textSize = 11f
            letterSpacing = 0.12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent())
            setPadding(0, 0, 0, dp(ctx, 8))
        }

    fun label(ctx: Context, t: String, size: Float = 13f, color: Int? = null): TextView =
        TextView(ctx).apply {
            text = t
            textSize = size
            setTextColor(color ?: this@Ui.text(ctx))
            setLineSpacing(0f, 1.15f)
        }

    fun mono(ctx: Context, size: Float = 12f, color: Int? = null): TextView =
        TextView(ctx).apply {
            textSize = size
            typeface = Typeface.MONOSPACE
            setTextColor(color ?: dim(ctx))
            setTextIsSelectable(true)
        }

    /**
     * The state marker for a row.
     *
     * A glyph, not a bare coloured dot: if colour is the only thing distinguishing two
     * states then it is invisible to anyone who cannot tell them apart. The glyph
     * carries the same information on its own.
     */
    fun statusMark(ctx: Context, color: Int, glyph: String = "\u2713"): View =
        TextView(ctx).apply {
            text = glyph
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 20), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { rightMargin = dp(ctx, 8); gravity = Gravity.CENTER_VERTICAL }
        }

    fun row(ctx: Context): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 5), 0, dp(ctx, 5))
        }

    fun column(ctx: Context): LinearLayout =
        LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun button(ctx: Context, label: String, primary: Boolean = false): Button =
        Button(ctx).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            // Never let a label wrap. A two-line button next to a one-line button is
            // the single ugliest thing this screen did, and it only happened on
            // narrower buttons ("Install Shizuku" broke after "Install").
            isSingleLine = true
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setTypeface(typeface, Typeface.BOLD)
            val fill = if (primary) accent() else card(ctx)
            val stroke = if (primary) 0 else line(ctx)
            setTextColor(if (primary) 0xFFFFFFFF.toInt() else text(ctx))
            val normal = round(fill, 12, ctx, if (primary) 0 else 1, stroke)
            val pressed = round(
                if (primary) 0xFF3B78E0.toInt() else sunken(ctx), 12, ctx,
                if (primary) 0 else 1, stroke
            )
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(), normal)
            }
            stateListAnimator = null      // the default elevation looks wrong on cards
            minHeight = dp(ctx, 48)       // Android's minimum comfortable tap target
            minimumHeight = dp(ctx, 48)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(ctx, 10), dp(ctx, 12), dp(ctx, 10), dp(ctx, 12))
        }

    /** A full-width button, for when the label is long enough to fill the row. */
    fun wideButton(ctx: Context, label: String, primary: Boolean = false): Button =
        button(ctx, label, primary).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
        }

    /**
     * A text field with a visible container. Without this the pairing code box was a
     * bare underline floating between two paragraphs and read as a rendering mistake.
     */
    fun input(ctx: Context, hint: String, numeric: Boolean = false): android.widget.EditText =
        android.widget.EditText(ctx).apply {
            this.hint = hint
            textSize = 13f
            inputType = if (numeric) android.text.InputType.TYPE_CLASS_NUMBER
                        else android.text.InputType.TYPE_CLASS_TEXT
            setTextColor(text(ctx))
            setHintTextColor(dim(ctx))
            background = round(sunken(ctx), 10, ctx, 1, line(ctx))
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
        }

    fun buttonRow(ctx: Context): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 4), 0, 0)
        }

    /** Weighted child for a button row. */
    fun stretch(b: View, weight: Float, ctx: Context, gapDp: Int = 6) {
        b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            .apply { rightMargin = dp(ctx, gapDp) }
    }
}
