package com.aryan.premdue

import android.app.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.*
import android.widget.*

/** Shared presentation only; domain mutations belong to Repository commands. */
open class UiActivity : Activity() {
    val ink = Color.rgb(249, 248, 245)
    val muted = Color.rgb(166, 167, 180)
    val surface = Color.rgb(17, 18, 22)
    val sans by lazy { resources.getFont(R.font.google_sans) }
    val serif by lazy { resources.getFont(R.font.playfair) }
    lateinit var root: LinearLayout
    lateinit var body: LinearLayout

    fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    fun shape(color: Int = surface, radius: Int = 16) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), Color.rgb(48, 49, 57))
        }

    fun column(parent: LinearLayout? = null) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            parent?.addView(this, LinearLayout.LayoutParams(-1, -2))
        }

    fun row(parent: LinearLayout) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }

    fun text(
        parent: LinearLayout,
        value: String,
        size: Float = 15f,
        color: Int = ink,
        bold: Boolean = false,
    ) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = if (bold) Typeface.create(sans, Typeface.BOLD) else sans
            setLineSpacing(dp(3).toFloat(), 1f)
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

    fun heading(value: String, subtitle: String = "") {
        text(body, value, 28f).typeface = serif
        if (subtitle.isNotBlank()) text(body, subtitle, 13f, muted)
        space(body)
    }

    fun space(parent: LinearLayout = body, n: Int = 10) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(n)))
    }

    fun card(parent: LinearLayout = body) =
        column().apply {
            background = shape()
            setPadding(dp(16), dp(16), dp(16), dp(12))
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }

    fun button(parent: LinearLayout, label: String, primary: Boolean = false, action: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.create(sans, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.BLACK else ink)
            background = shape(if (primary) ink else surface, 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minHeight = dp(48)
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { action() }
            Design.press(this)
            parent.addView(
                this,
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(6)
                },
            )
        }

    fun link(parent: LinearLayout, title: String, subtitle: String = "", action: () -> Unit) {
        val c = card(parent)
        text(c, title, 16f, bold = true)
        if (subtitle.isNotBlank()) text(c, subtitle, 12f, muted)
        c.isClickable = true
        c.isFocusable = true
        c.contentDescription = listOf(title, subtitle).filter { it.isNotBlank() }.joinToString(". ")
        c.setOnClickListener { action() }
    }

    lateinit var footer: LinearLayout
    var scroll: ScrollView? = null

    fun shell(title: String, right: String = "", onRight: () -> Unit = {}) {
        root = column().apply { setBackgroundColor(Color.BLACK) }
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, i ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars =
                    i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = i.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            } else
                v.setPadding(
                    i.systemWindowInsetLeft,
                    i.systemWindowInsetTop,
                    i.systemWindowInsetRight,
                    i.systemWindowInsetBottom,
                )
            i
        }
        setContentView(root)
        root.requestApplyInsets()
        val bar = row(root).apply { setPadding(dp(18), dp(6), dp(12), 0) }
        text(bar, title, 24f).apply {
            typeface = serif
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        if (right.isNotBlank())
            button(bar, right, action = onRight).apply {
                contentDescription = "Menu"
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            }
        val sv =
            ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
            }
        scroll = sv
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        body = column().apply { setPadding(dp(18), dp(12), dp(18), dp(28)) }
        sv.addView(body)
        footer = column(root).apply { setPadding(dp(18), 0, dp(18), dp(8)) }
    }

    fun choose(title: String, items: List<String>, action: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, i -> action(i) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ -> action() }
            .show()
    }

    fun toast(value: String) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    }

    fun tabs(
        parent: LinearLayout,
        labels: List<String>,
        selected: String,
        action: (String) -> Unit,
    ) {
        val horizontal = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        parent.addView(horizontal)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        horizontal.addView(bar)
        labels.forEach { label ->
            button(bar, label, label == selected) { action(label) }.apply {
                isSelected = label == selected // TalkBack announces the current tab
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
            }
        }
        space(parent)
    }

    /** Bottom navigation pinned under [footer]: one icon + label per tab, the current one highlighted. */
    fun navBar(items: List<Pair<String, Int>>, selected: String, action: (String) -> Unit) {
        val bar = row(root).apply {
            setBackgroundColor(surface)
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(View(this).apply { setBackgroundColor(Color.rgb(48, 49, 57)) }, root.indexOfChild(bar), LinearLayout.LayoutParams(-1, dp(1)))
        items.forEach { (label, icon) ->
            val on = label == selected
            val item = column(bar).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                minimumHeight = dp(56)
                isClickable = true
                isFocusable = true
                isSelected = on // TalkBack announces the current tab
                contentDescription = label
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { if (!on) action(label) }
            }
            val pill = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                if (on) background = GradientDrawable().apply { setColor(Color.rgb(48, 49, 57)); cornerRadius = dp(16).toFloat() }
            }
            item.addView(pill, LinearLayout.LayoutParams(dp(56), dp(30)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            pill.addView(ImageView(this).apply {
                setImageResource(icon)
                setColorFilter(if (on) ink else muted)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (on) ink else muted)
                typeface = if (on) Typeface.create(sans, Typeface.BOLD) else sans
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                item.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            }
        }
    }

    /** A titled settings section; add rows to it with [item]. */
    fun group(title: String): LinearLayout {
        text(body, title.uppercase(), 11f, muted, bold = true).apply {
            letterSpacing = 0.08f
            (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(10); marginStart = dp(4) }
        }
        return column(body).apply {
            background = shape()
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(14)
        }
    }

    /** One settings row: title, optional detail underneath, optional value on the right; tappable when [action] is given. */
    fun item(group: LinearLayout, title: String, detail: String = "", value: String = "", color: Int = ink, action: (() -> Unit)? = null) {
        if (group.childCount > 0)
            group.addView(View(this).apply { setBackgroundColor(Color.rgb(48, 49, 57)) }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(16) })
        val r = row(group).apply {
            minimumHeight = dp(56)
            setPadding(dp(16), dp(12), dp(14), dp(12))
        }
        val left = column(r).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        text(left, title, 15f, color).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0 }
        if (detail.isNotBlank()) text(left, detail, 12f, muted).apply { (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(2); bottomMargin = 0 } }
        if (value.isNotBlank()) text(r, value, 13f, muted).apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) } }
        if (action != null) {
            text(r, "›", 20f, muted).apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) } }
            r.isClickable = true
            r.isFocusable = true
            r.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.rgb(48, 49, 57)), null, null)
            r.contentDescription = listOf(title, detail, value).filter { it.isNotBlank() }.joinToString(". ")
            r.setOnClickListener { action() }
        }
    }
}
