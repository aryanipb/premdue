package com.aryan.premdue

import android.widget.EditText
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/** Whole years in 0..120, else null. */
private fun EditText.years(): Int? = num()?.takeIf { it in 0.0..120.0 && it % 1.0 == 0.0 }?.toInt()

/** Rupee amount ≥ 0 and sane, else null. */
private fun EditText.money(): Double? = num()?.takeIf { it in 0.0..1e15 }

/** Percent in 0..100 as a decimal, else null. */
private fun EditText.rate(): Double? = num()?.takeIf { it in 0.0..100.0 }?.div(100)

/** Income-replacement cover: PV of future surplus income, plus loans, less existing cover. */
fun ToolsActivity.hlv() {
    val age = input(body, "Age", "30", decimal = false)
    val retire = input(body, "Retirement age", "60", decimal = false)
    val income = input(body, "Yearly income ₹", "600000")
    val spend = input(body, "Yearly personal expenses ₹", "150000")
    val growth = input(body, "Income growth %", "5")
    val ret = input(body, "Expected return %", "8")
    val cover = input(body, "Existing life cover ₹", "0")
    val loans = input(body, "Loans outstanding ₹", "0")
    onChange(listOf(age, retire, income, spend, growth, ret, cover, loans)) {
        val a = age.years(); val ra = retire.years(); val inc = income.money(); val sp = spend.money()
        val g = growth.rate(); val r = ret.rate(); val c = cover.money(); val l = loans.money()
        if (a == null || ra == null || inc == null || sp == null || g == null || r == null || c == null || l == null)
            return@onChange result(footer, "Fill in all fields")
        if (ra <= a) return@onChange result(footer, "Retirement age must be more than age")
        if (inc <= sp) return@onChange result(footer, "Expenses must be less than income")
        val n = ra - a
        val x = inc - sp
        val value = if (abs(r - g) < 1e-9) n * x / (1 + r) else x / (r - g) * (1 - ((1 + g) / (1 + r)).pow(n))
        val need = max(0.0, value + l - c)
        if (!value.isFinite() || !need.isFinite()) return@onChange result(footer, "Fill in all fields")
        result(footer, "Cover needed ${rupees(need)}", listOf(
            "Human life value" to rupees(value),
            "Years of income" to "$n",
            "Existing cover" to rupees(c),
        ))
    }
}

/** Corpus for inflation-linked withdrawals until life expectancy, and the monthly SIP to reach it. */
fun ToolsActivity.retirement() {
    val age = input(body, "Age", "30", decimal = false)
    val retire = input(body, "Retirement age", "60", decimal = false)
    val life = input(body, "Life expectancy", "85", decimal = false)
    val monthly = input(body, "Monthly expenses today ₹", "30000")
    val inflation = input(body, "Inflation %", "6")
    val before = input(body, "Return before retirement %", "10")
    val after = input(body, "Return after retirement %", "7")
    val saved = input(body, "Savings already ₹", "0")
    onChange(listOf(age, retire, life, monthly, inflation, before, after, saved)) {
        val a = age.years(); val ra = retire.years(); val l = life.years(); val e = monthly.money()
        val i = inflation.rate(); val r = before.rate(); val p = after.rate(); val s = saved.money()
        if (a == null || ra == null || l == null || e == null || i == null || r == null || p == null || s == null)
            return@onChange result(footer, "Fill in all fields")
        if (ra <= a) return@onChange result(footer, "Retirement age must be more than age")
        if (l <= ra) return@onChange result(footer, "Life expectancy must be more than retirement age")
        val n = ra - a
        val m = l - ra
        val atRetire = e * (1 + i).pow(n)
        val y = 12 * atRetire
        val corpus = if (abs(p - i) < 1e-9) m * y else y * (1 + p) / (p - i) * (1 - ((1 + i) / (1 + p)).pow(m))
        val grown = s * (1 + r).pow(n)
        val gap = max(0.0, corpus - grown)
        val q = (1 + r).pow(1.0 / 12) - 1
        val months = 12 * n
        val sip = if (gap == 0.0) 0.0 else if (q < 1e-12) gap / months else gap / (((1 + q).pow(months) - 1) / q * (1 + q))
        if (!listOf(atRetire, corpus, grown, sip).all { it.isFinite() }) return@onChange result(footer, "Fill in all fields")
        result(footer, "Save ${rupees(sip)} / month", listOf(
            "Corpus needed" to rupees(corpus),
            "Monthly expense at retirement" to rupees(atRetire),
            "Savings will grow to" to rupees(grown),
        ))
    }
}
