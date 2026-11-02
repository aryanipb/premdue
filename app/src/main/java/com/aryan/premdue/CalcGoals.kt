package com.aryan.premdue

import kotlin.math.max
import kotlin.math.pow

/** Rupee amount in a sane range, else null. */
private fun amountOk(v: Double?) = v?.takeIf { it.isFinite() && it >= 0 && it <= 1e12 }

/** Percent typed as 6 -> 0.06; null outside 0..100. */
private fun rateOk(v: Double?) = v?.takeIf { it.isFinite() && it in 0.0..100.0 }?.div(100)

/** Whole years in [min]..100, else null. */
private fun yearsOk(v: Double?, min: Int) = v?.takeIf { it.isFinite() && it >= min && it <= 100 && it % 1 == 0.0 }?.toInt()

/** Monthly saving (paid at the start of each month) and lump sum for a child's goal. */
fun ToolsActivity.goal() {
    val cost = input(body, "Cost today ₹", "1000000")
    val yrs = input(body, "Years to goal", "15", decimal = false)
    val infl = input(body, "Inflation %", "8")
    val ret = input(body, "Expected return %", "10")
    val saved = input(body, "Saved for this already ₹", "0")
    onChange(listOf(cost, yrs, infl, ret, saved)) {
        val c = amountOk(cost.num())?.takeIf { it > 0 }
        val n = yearsOk(yrs.num(), 1)
        val i = rateOk(infl.num())
        val r = rateOk(ret.num())
        val s = if (saved.text.isBlank()) 0.0 else amountOk(saved.num())
        if (c == null || n == null || i == null || r == null || s == null) return@onChange result(footer, "Fill in all fields")
        val future = c * (1 + i).pow(n)
        val grown = s * (1 + r).pow(n)
        val gap = max(0.0, future - grown)
        val months = 12 * n
        val q = (1 + r).pow(1.0 / 12) - 1
        val monthly = if (q == 0.0) gap / months else gap / (((1 + q).pow(months) - 1) / q * (1 + q))
        val lump = gap / (1 + r).pow(n)
        if (!future.isFinite() || !monthly.isFinite()) return@onChange result(footer, "Fill in all fields")
        result(
            footer,
            "Save ${rupees(monthly)} / month",
            listOf("Future cost" to rupees(future), "Or one-time today" to rupees(lump), "Savings will grow to" to rupees(grown)),
        )
    }
}

/** What an amount costs, and is worth, after years of inflation. */
fun ToolsActivity.inflation() {
    val amt = input(body, "Amount ₹", "100000")
    val infl = input(body, "Inflation %", "6")
    val yrs = input(body, "Years", "20", decimal = false)
    val table = column(body)
    onChange(listOf(amt, infl, yrs)) {
        table.removeAllViews()
        val a = amountOk(amt.num())?.takeIf { it > 0 }
        val i = rateOk(infl.num())
        val n = yearsOk(yrs.num(), 0)
        if (a == null || i == null || n == null) return@onChange result(footer, "Fill in all fields")
        val factor = (1 + i).pow(n)
        result(
            footer,
            "${rupees(a)} today = ${rupees(a * factor)} in $n ${if (n == 1) "year" else "years"}",
            listOf("Same money will be worth" to rupees(a / factor), "Price rise" to pct(factor - 1)),
        )
        space(table)
        text(table, "Future cost of the same things", 12f, muted)
        listOf(5, 10, 15, 20, 25, 30).forEach { y -> kv(table, "In $y years", rupees(a * (1 + i).pow(y))) }
    }
}
