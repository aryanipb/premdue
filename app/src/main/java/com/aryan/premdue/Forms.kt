package com.aryan.premdue

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.*
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Labelled input box. Numeric by default; [decimal] allows fractions. */
fun UiActivity.input(parent: LinearLayout, label: String, value: String = "", numeric: Boolean = true, decimal: Boolean = true): EditText {
    val caption = text(parent, label, 12f, muted).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(4) }
    return EditText(this).apply {
        // The id lets TalkBack announce the label; saving stays off as before so generated ids never restore into the wrong box.
        id = View.generateViewId()
        isSaveEnabled = false
        caption.labelFor = id
        minHeight = dp(48)
        setText(value)
        setTextColor(ink)
        setHintTextColor(muted)
        typeface = sans
        textSize = 17f
        inputType =
            if (!numeric) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            else InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
        setSingleLine()
        background = shape()
        setPadding(dp(14), dp(10), dp(14), dp(10))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
}

/**
 * The box's number; null when empty or invalid. Accepts any script's digits (e.g. Devanagari from a Marathi keyboard)
 * and a decimal comma where the device locale uses one; elsewhere commas are grouping and ignored.
 */
fun EditText.num(): Double? {
    val decimalComma = DecimalFormatSymbols.getInstance(textLocale).decimalSeparator == ','
    val s = text.toString().trim().mapNotNull { c ->
        when {
            c == ',' -> if (decimalComma) '.' else null
            else -> c.digitToIntOrNull()?.let { '0' + it } ?: c
        }
    }
    return String(s.toCharArray()).toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** Runs [compute] now and whenever any of [fields] changes. */
fun onChange(fields: List<EditText>, compute: () -> Unit) {
    val w =
        object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = compute()
        }
    fields.forEach { it.addTextChangedListener(w) }
    compute()
}

/** Replaces [out] with a result card: one large headline, then label/value rows. */
fun UiActivity.result(out: LinearLayout, headline: String, rows: List<Pair<String, String>> = emptyList()) {
    out.removeAllViews()
    out.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    val c = card(out)
    text(c, headline, 22f, bold = true)
    rows.forEach { (k, v) -> kv(c, k, v) }
}

/** One label/value line. */
fun UiActivity.kv(parent: LinearLayout, label: String, value: String) {
    val r = row(parent)
    text(r, label, 13f, muted).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    text(r, value, 14f, bold = true).layoutParams = LinearLayout.LayoutParams(-2, -2)
}

/** 0.0825 -> "8.25%". */
fun pct(rate: Double) = String.format(Locale.US, "%.2f%%", rate * 100)
