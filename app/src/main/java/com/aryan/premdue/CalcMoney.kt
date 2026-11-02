package com.aryan.premdue

import android.widget.EditText
import kotlin.math.pow

/** Whole years in 1..100, else null. */
private fun EditText.wholeYears(): Int? = num()?.takeIf { it >= 1 && it <= 100 && it % 1 == 0.0 }?.toInt()

/** Amount or rate that is finite and not negative, else null. */
private fun EditText.nonNegative(): Double? = num()?.takeIf { it.isFinite() && it >= 0 }

/** Signed rupees: -500 -> "−₹500". Rounds first so a tiny float error never shows as "−₹0" or "₹-0". */
private fun signedRupees(v: Double): String {
    val r = Math.rint(v) + 0.0
    return if (r < 0) "−" + rupees(-r) else rupees(r)
}

/** Monthly instalment, totals and a year-wise principal/interest split. */
fun ToolsActivity.emi() {
    val p = input(body, "Loan amount ₹", "1000000")
    val rate = input(body, "Interest % per year", "9")
    val yrs = input(body, "Years", "10", decimal = false)
    val detail = column(body)
    onChange(listOf(p, rate, yrs)) {
        detail.removeAllViews()
        val principal = p.nonNegative()?.takeIf { it > 0 }
        val r = rate.nonNegative()?.let { it / 12 / 100 }
        val y = yrs.wholeYears()
        if (principal == null || r == null || y == null) return@onChange result(footer, "Fill in all fields")
        val n = 12 * y
        val emi = if (r == 0.0) principal / n else principal * r * (1 + r).pow(n) / ((1 + r).pow(n) - 1)
        val total = emi * n
        if (!total.isFinite()) return@onChange result(footer, "Fill in all fields")
        // At 0% total can land a hair below principal (e.g. ₹3,33,333 over 25 years), which would print "₹-0".
        val totalInterest = (total - principal).coerceAtLeast(0.0)
        result(footer, "EMI ${rupees(emi)} / month", listOf("Total interest" to rupees(totalInterest), "Total paid" to rupees(total)))
        space(detail)
        text(detail, "Each year: principal + interest repaid", 12f, muted)
        var bal = principal
        for (year in 1..minOf(y, 30)) {
            var prin = 0.0
            var interest = 0.0
            repeat(12) {
                val i = bal * r
                interest += i
                prin += emi - i
                bal -= emi - i
            }
            kv(detail, "Year $year", "${rupees(prin)} + ${rupees(interest)}")
        }
        if (y > 30) text(detail, "First 30 years shown", 12f, muted)
    }
}

/** Future value of a one-time amount plus yearly additions made at the start of each year. */
fun ToolsActivity.growth() {
    val lump = input(body, "One-time amount ₹", "100000")
    val add = input(body, "Add at the start of every year ₹", "0")
    val rate = input(body, "Return %", "8")
    val yrs = input(body, "Years", "15", decimal = false)
    onChange(listOf(lump, add, rate, yrs)) {
        val l = lump.nonNegative()
        val a = add.nonNegative()
        val r = rate.nonNegative()?.let { it / 100 }
        val n = yrs.wholeYears()
        if (l == null || a == null || r == null || n == null || l + a <= 0) return@onChange result(footer, "Fill in all fields")
        val g = (1 + r).pow(n)
        val fv = if (r == 0.0) l + a * n else l * g + a * (g - 1) / r * (1 + r)
        val invested = l + a * n
        if (!fv.isFinite()) return@onChange result(footer, "Fill in all fields")
        result(footer, "Grows to ${rupees(fv)}", listOf("Invested" to rupees(invested), "Gain" to signedRupees(fv - invested)))
    }
}

/** Effective yearly return of a policy: premiums at the start of each paying year, maturity at the end of the term. */
fun ToolsActivity.yieldCalc() {
    val prem = input(body, "Premium per year ₹", "50000")
    val pay = input(body, "Paying years", "15", decimal = false)
    val term = input(body, "Policy term years", "20", decimal = false)
    val mat = input(body, "Maturity amount ₹", "1500000")
    text(body, "Assumes premiums at the start of each year and maturity at the end of the term.", 12f, muted)
    onChange(listOf(prem, pay, term, mat)) {
        val premium = prem.nonNegative()?.takeIf { it > 0 }
        val py = pay.wholeYears()
        val t = term.wholeYears()
        val m = mat.nonNegative()
        if (premium == null || py == null || t == null || m == null || !(premium * py).isFinite()) return@onChange result(footer, "Fill in all fields")
        if (py > t) return@onChange result(footer, "Paying years cannot be more than the term")
        val plan = PlanInput("", 0.0, premium, py, t, "", listOf(t to m))
        val y = plan.yield()
        val headline =
            when {
                // Fin.irr only searches up to 1000% a year, so null with a gain means a return above that.
                y == null -> if (plan.received > plan.paid) "Returns over 1000% a year" else "No positive return"
                y < -0.00005 -> "Loses ${pct(-y)} a year"
                y < 0.00005 -> "Returns 0.00% a year"
                else -> "Returns ${pct(y)} a year"
            }
        result(footer, headline, listOf("Total paid" to rupees(plan.paid), "Maturity" to rupees(plan.received), "Gain" to signedRupees(plan.received - plan.paid)))
    }
}

/** "13 y 2 m" from a month count. */
private fun span(m: Int) = if (m % 12 == 0) "${m / 12} y" else "${m / 12} y ${m % 12} m"

/**
 * One way of repaying the same loan: a [years] loan, with whatever is left of the monthly [outgo] after its EMI
 * invested every month. EMI is paid at the end of each month (as banks quote it); the SIP goes in at the start of each
 * month and grows at [ret] a year, compounded monthly at the equivalent rate (as SIP calculators quote it). [tax] is
 * charged on the gain above the ₹1.25 lakh equity exemption, as if the whole investment were withdrawn that month.
 */
class LoanPlan(private val principal: Double, loanRate: Double, val years: Int, outgo: Double, ret: Double, private val tax: Double) {
    private val i = loanRate / 12
    private val j = (1 + ret).pow(1.0 / 12) - 1
    val emi = emi(principal, i, years * 12)
    val sip = outgo - emi

    /** Loan left after [t] EMIs. */
    fun owed(t: Int) = owed(principal, i, emi, t)

    /** Investment after [t] months, after tax on the gain. */
    fun corpus(t: Int): Double {
        val fv = if (j == 0.0) sip * t else sip * ((1 + j).pow(t) - 1) / j * (1 + j)
        return fv - (fv - sip * t - 125_000).coerceAtLeast(0.0) * tax
    }

    /** First month the investment can repay everything still owed; the loan's own end at the latest. */
    val freeAt = (1..years * 12).first { corpus(it) >= owed(it) - 0.5 }

    companion object {
        fun emi(p: Double, i: Double, n: Int) = if (i == 0.0) p / n else p * i * (1 + i).pow(n) / ((1 + i).pow(n) - 1)

        /** Loan left after [t] monthly payments of [pay]. */
        fun owed(p: Double, i: Double, pay: Double, t: Int) =
            (if (i == 0.0) p - pay * t else p * (1 + i).pow(t) - pay * ((1 + i).pow(t) - 1) / i).coerceAtLeast(0.0)
    }
}

/**
 * Same monthly outgo two ways: pay it all into the planned loan (the planned EMI plus any extra as prepayment), or take
 * a longer loan, invest everything above its EMI, and repay the loan from the investment once it is large enough.
 */
fun ToolsActivity.loanSip() {
    val p = input(body, "Loan amount ₹", "10000000")
    val rate = input(body, "Loan interest % per year", "8")
    val base = input(body, "Tenure you planned (years)", "15", decimal = false)
    val max = input(body, "Longest tenure the bank allows (years)", "30", decimal = false)
    val extra = input(body, "Extra you can add every month ₹", "0")
    val ret = input(body, "Investment return % per year", "12")
    val tax = input(body, "Tax on investment gain %", "13")
    text(body, "The extra is compared fairly: either prepaid into the planned loan, or invested. Equity funds: 12.5% + 4% cess = 13% on gains above ₹1.25 lakh; enter 0 to see it before tax. For reducing-balance loans at a fixed rate. Returns are not guaranteed.", 12f, muted)
    val detail = column(body)
    onChange(listOf(p, rate, base, max, extra, ret, tax)) {
        detail.removeAllViews()
        val principal = p.nonNegative()?.takeIf { it > 0 }
        val r = rate.nonNegative()?.takeIf { it <= 50 }?.let { it / 100 }
        val b = base.wholeYears()?.takeIf { it <= 40 }
        val m = max.wholeYears()?.takeIf { it <= 40 }
        val x = extra.nonNegative()
        val g = ret.nonNegative()?.takeIf { it <= 100 }?.let { it / 100 }
        val t = tax.nonNegative()?.takeIf { it <= 100 }?.let { it / 100 }
        if (principal == null || r == null || b == null || m == null || x == null || g == null || t == null) return@onChange result(footer, "Fill in all fields")
        if (m <= b) return@onChange result(footer, "Longest tenure must be more than $b years")
        val i = r / 12
        val n0 = b * 12
        val baseEmi = LoanPlan.emi(principal, i, n0)
        val outgo = baseEmi + x
        if (!(outgo * n0).isFinite()) return@onChange result(footer, "Fill in all fields")
        // Paying everything into the planned loan: when it ends.
        val prepaidAt = (1..n0).first { LoanPlan.owed(principal, i, outgo, it) <= 0.5 }
        // With an extra, the planned loan itself plus a SIP of the extra is a way too.
        val plans = ((if (x > 0) b else b + 1)..m).map { LoanPlan(principal, r, it, outgo, g, t) }
        // Earliest loan-free month wins; on a tie the shorter loan, since it bets less on the market.
        val best = plans.minBy { it.freeAt }
        // The return at which the best way only just repays the loan when paying into the loan would have.
        fun gap(y: Double) = LoanPlan(principal, r, best.years, outgo, y, t).let { it.corpus(prepaidAt) - it.owed(prepaidAt) }
        val breakEven =
            if (gap(1.0) < 0) null
            else {
                var lo = 0.0
                var hi = 1.0
                repeat(60) { val mid = (lo + hi) / 2; if (gap(mid) < 0) lo = mid else hi = mid }
                hi
            }
        val needs = breakEven?.let { pct(it) } ?: "100%"
        val instead = if (x > 0) "$b years, ${rupees(x)} prepaid monthly" else "$b years"
        if (best.freeAt >= prepaidAt) {
            result(
                footer,
                if (x > 0) "Prepay ${rupees(x)} a month · loan-free in ${span(prepaidAt)}" else "Keep the $b-year loan",
                listOf("Your EMI" to rupees(baseEmi), "Investing wins only above" to "$needs return"),
            )
        } else {
            result(
                footer,
                "${if (best.years == b) "Keep" else "Take"} ${best.years} years · loan-free in ${span(best.freeAt)}",
                listOfNotNull(
                    "Instead of" to instead,
                    "EMI" to rupees(best.emi),
                    "Invest every month" to rupees(best.sip),
                    if (x > 0) "of which extra from pocket" to rupees(x) else null,
                    "Ahead at ${span(prepaidAt)}" to rupees(best.corpus(prepaidAt) - best.owed(prepaidAt)),
                    "Investing wins only above" to "$needs return",
                ),
            )
        }
        space(detail)
        text(detail, "Each way · you pay ${rupees(outgo)} a month in all", 12f, muted)
        kv(detail, if (x > 0) "All into $b-yr loan" else "$b years", "loan-free in ${span(prepaidAt)}")
        plans.forEach { kv(detail, "${it.years} years", "invest ${rupees(it.sip)} · free in ${span(it.freeAt)}") }
        space(detail)
        text(detail, "Loan left each year: all into the $b-year loan → ${best.years}-year loan minus investment", 12f, muted)
        for (y in 1..(prepaidAt + 11) / 12) {
            val net = best.owed(y * 12) - best.corpus(y * 12)
            kv(detail, "Year $y", "${rupees(LoanPlan.owed(principal, i, outgo, y * 12))} → " + if (net > 0) rupees(net) else "cleared, ${rupees(-net)} spare")
        }
    }
}
