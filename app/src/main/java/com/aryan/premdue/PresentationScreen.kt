package com.aryan.premdue

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.*

/** Typed values of one plan card; survives re-renders. Benefit rows are [from, to, amount]. */
private class PlanState {
    var name = ""
    var sumAssured = ""
    var premium = ""
    var payYears = ""
    var term = ""
    var maturity = ""
    var death = ""
    val benefits = mutableListOf<Array<String>>()
}

private fun String.amount(): Double? = replace(",", "").trim().toDoubleOrNull()?.takeIf { it.isFinite() }

private fun String.whole(): Int? = trim().toIntOrNull()

/** Validates [s] (plan number [n]) into a PlanInput; throws with a plain message on the first problem. */
private fun parsePlan(n: Int, s: PlanState): PlanInput {
    fun fail(m: String): Nothing = throw IllegalArgumentException("Plan $n: $m")
    fun money(v: String, what: String) = v.amount()?.also { if (it > 1e15) fail("$what is too large") }
    val name = s.name.trim().ifBlank { fail("enter the plan name") }
    val sa = money(s.sumAssured, "sum assured") ?: fail("enter the sum assured")
    if (sa < 0) fail("sum assured cannot be negative")
    val premium = money(s.premium, "premium per year") ?: fail("enter the premium per year")
    if (premium <= 0) fail("premium per year must be more than 0")
    val pay = s.payYears.whole() ?: fail("enter paying years")
    val term = s.term.whole() ?: fail("enter the policy term")
    if (term !in 1..100) fail("policy term must be 1 to 100 years")
    if (pay < 1) fail("paying years must be at least 1")
    if (pay > term) fail("paying years must be ≤ policy term")
    val maturity = if (s.maturity.isBlank()) 0.0 else money(s.maturity, "maturity amount") ?: fail("maturity amount is not a number")
    if (maturity < 0) fail("maturity amount cannot be negative")
    val out = mutableListOf<Pair<Int, Double>>()
    s.benefits.forEachIndexed { i, (f, t, a) ->
        if (f.isBlank() && t.isBlank() && a.isBlank()) return@forEachIndexed
        val b = "benefit ${i + 1}"
        val from = f.whole() ?: fail("$b needs a From year")
        val to = if (t.isBlank()) from else t.whole() ?: fail("$b To year is not a whole number")
        val amt = money(a, "$b amount") ?: fail("$b needs an amount")
        if (amt < 0) fail("$b amount cannot be negative")
        if (from !in 1..term || to !in 1..term) fail("$b years must be between 1 and $term")
        if (to < from) fail("$b To year must be ≥ From year")
        for (y in from..to) out += y to amt
    }
    if (maturity > 0) out += term to maturity
    return PlanInput(name, sa, premium, pay, term, s.death.trim(), out)
}

/** LIC illustration figures for 1–3 plans → a shareable PDF presentation. */
fun ToolsActivity.presentation() {
    val prefs = getSharedPreferences("presentation", Context.MODE_PRIVATE)
    val plans = mutableListOf(PlanState().apply { benefits += arrayOf("", "", "") })

    fun saved(parent: LinearLayout, label: String, key: String, phone: Boolean = false) =
        input(parent, label, prefs.getString(key, "") ?: "", numeric = false).apply {
            if (phone) inputType = InputType.TYPE_CLASS_PHONE
            onChange(listOf(this)) { prefs.edit().putString(key, text.toString().trim()).apply() }
        }

    val me = card()
    text(me, "Your details", 16f, bold = true)
    val agent = saved(me, "Your name", "name")
    val phone = saved(me, "Mobile", "phone", phone = true)
    val line = saved(me, "Line under your name (e.g. LIC Agent · Branch 91J)", "line")

    val who = card()
    text(who, "Customer", 16f, bold = true)
    val customer = input(who, "Customer name", numeric = false)
    val age = input(who, "Age", decimal = false)

    val box = column(body)
    lateinit var addPlan: TextView
    lateinit var render: () -> Unit

    /** A text box bound to one field of the plan state; [set] receives every edit. */
    fun field(parent: LinearLayout, label: String, value: String, numeric: Boolean, money: Boolean = false, set: (String) -> Unit) =
        input(parent, label, value, numeric, decimal = money).apply { onChange(listOf(this)) { set(text.toString()) } }

    fun planCard(n: Int, s: PlanState) {
        val c = card(box)
        val head = row(c)
        text(head, "Plan $n", 16f, bold = true).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        if (plans.size > 1)
            button(head, "Remove") { plans.remove(s); render() }.apply {
                contentDescription = "Remove plan $n"
                layoutParams = LinearLayout.LayoutParams(-2, -2)
            }
        val fields = mutableListOf<EditText>()
        fields += field(c, "Plan name / no. (e.g. Jeevan Labh 936)", s.name, false) { s.name = it }
        fields += field(c, "Sum assured ₹", s.sumAssured, true, money = true) { s.sumAssured = it }
        fields += field(c, "Premium per year ₹ (total of the year's instalments)", s.premium, true, money = true) { s.premium = it }
        fields += field(c, "Paying years", s.payYears, true) { s.payYears = it }
        fields += field(c, "Policy term (years)", s.term, true) { s.term = it }
        fields += field(c, "Maturity amount ₹ (at end of term; 0 if none)", s.maturity, true, money = true) { s.maturity = it }
        fields += field(c, "Death cover (optional, e.g. ₹10,00,000 + bonus)", s.death, false) { s.death = it }
        text(c, "Other benefits (from the illustration)", 13f, bold = true)
        s.benefits.forEach { b ->
            val r = row(c)
            listOf("From year", "To year", "₹ per year").forEachIndexed { i, label ->
                val col = column(r)
                col.layoutParams = LinearLayout.LayoutParams(0, -2, if (i == 2) 1.4f else 1f).apply { marginEnd = dp(6) }
                fields += field(col, label, b[i], true, money = i == 2) { b[i] = it }
            }
            button(r, "✕") { s.benefits.remove(b); render() }.apply {
                contentDescription = "Remove benefit"
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            }
        }
        button(c, "+ Add benefit") { s.benefits += arrayOf("", "", ""); render() }
        val summary = text(c, "", 13f, muted)
        onChange(fields) {
            summary.text =
                runCatching { parsePlan(n, s) }.fold(
                    { p -> "Paid ${rupees(p.paid)} · Received ${rupees(p.received)} · Return ${p.`yield`()?.let(::pct) ?: "—"} a year" },
                    { e ->
                        if (listOf(s.name, s.sumAssured, s.premium, s.payYears, s.term).all { it.isBlank() }) "Fill in the plan details"
                        else e.message?.substringAfter(": ") ?: "Please check the details"
                    },
                )
        }
    }

    render = {
        box.removeAllViews()
        plans.forEachIndexed { i, s -> planCard(i + 1, s) }
        addPlan.visibility = if (plans.size < 3) View.VISIBLE else View.GONE
    }
    addPlan = button(body, "+ Add plan to compare") { if (plans.size < 3) { plans += PlanState().apply { benefits += arrayOf("", "", "") }; render() } }
    render()

    lateinit var create: TextView
    create = button(footer, "Create PDF & share", primary = true) {
        if (!create.isEnabled) return@button
        val pr =
            try {
                val name = customer.text.toString().trim().ifBlank { throw IllegalArgumentException("Enter the customer name") }
                val years = age.text.toString().whole()?.takeIf { it in 0..100 } ?: throw IllegalArgumentException("Enter the customer's age (0 to 100)")
                Presentation(name, years, plans.mapIndexed { i, s -> parsePlan(i + 1, s) })
            } catch (e: IllegalArgumentException) {
                return@button toast(e.message ?: "Please check the details")
            }
        val lh = Letterhead(agent.text.toString().trim(), phone.text.toString().trim(), line.text.toString().trim())
        create.isEnabled = false
        create.text = "Creating PDF…"
        val app = applicationContext
        Thread {
            val r = runCatching { PresentationPdf.build(app, pr, lh) }
            runOnUiThread {
                create.isEnabled = true
                create.text = "Create PDF & share"
                if (isFinishing || isDestroyed) return@runOnUiThread
                r.onSuccess { f -> runCatching { PresentationPdf.share(this, f) }.onFailure { toast("Could not create PDF: ${it.message}") } }
                    .onFailure { toast("Could not create PDF: ${it.message}") }
            }
        }.start()
    }
}
