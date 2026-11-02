package com.aryan.premdue

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Print-friendly A4 plan presentation that the agent shares with a customer. */
object PresentationPdf {
    private const val W = 595f
    private const val H = 842f
    private const val M = 36f
    private const val CW = W - 2 * M
    private val INK = Color.rgb(0x11, 0x11, 0x11)
    private val GREY = Color.rgb(0x6B, 0x6B, 0x6B)
    private val RULE = Color.rgb(0xDD, 0xDD, 0xDD)
    private const val DISCLAIMER = "Figures are entered by the agent from LIC's official benefit illustration. " +
        "This is not an LIC document. Bonuses and other non-guaranteed benefits may vary. " +
        "Return % assumes yearly premiums at the start of each year and benefits at the end of the year."
    /** Year-table column widths: Year, Age, Premium, Benefit, Net so far (sum = content width). */
    private val COLS = floatArrayOf(50f, 50f, 141f, 141f, 141f)
    private const val ROW = 15f

    /** Pages laid out as draw lists first, so the footer can print the known page total. */
    private class Doc(c: Context) {
        val reg: Typeface = c.resources.getFont(R.font.google_sans)
        val bold: Typeface = c.resources.getFont(R.font.google_sans_600)
        val serif: Typeface = c.resources.getFont(R.font.playfair)
        val footP = pt(reg, 7f, GREY)
        val foot = wrap(DISCLAIMER, footP, CW)
        val footTop = H - M - foot.size * 9f - 14f
        val top = M + 56f
        val bottom = footTop - 10f
        val hair = Paint().apply { color = RULE; strokeWidth = 0.5f }
        val pages = mutableListOf<MutableList<(Canvas) -> Unit>>()
        var y = top
        fun page() { pages += mutableListOf<(Canvas) -> Unit>(); y = top }
        fun fits(h: Float) = y + h <= bottom
        fun op(f: (Canvas) -> Unit) { pages.last() += f }
        fun text(s: String, x: Float, base: Float, w: Float, p: Paint, right: Boolean = false) = op { put(it, s, x, base, w, p, right) }
        fun rule(at: Float, p: Paint = hair) = op { it.drawLine(M, at, W - M, at, p) }
        fun box(l: Float, t: Float, r: Float, b: Float, col: Int) { val p = Paint().apply { color = col }; op { it.drawRect(l, t, r, b, p) } }
    }

    /** Writes the presentation as an A4 PDF and returns the file. */
    fun build(c: Context, pr: Presentation, lh: Letterhead): File {
        val d = Doc(c)
        d.page()
        cover(d, pr)
        pr.plans.forEach { plan(d, pr.age, it) }
        // Letters/marks/digits of any script (Devanagari names stay readable); capped well under the 255-byte name limit.
        val safe = pr.customer.trim().replace(Regex("""[^\p{L}\p{M}\p{N}_-]+"""), "_").trim('_').take(60).trimEnd('_').ifEmpty { "customer" }
        val dir = File(c.cacheDir, "presentations").apply { mkdirs() }
        val f = File(dir, "$safe-plans.pdf")
        // Customer PDFs are personal data: drop ones older than a day (recent ones may still be mid-share).
        val stale = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        dir.listFiles()?.forEach { if (it != f && it.lastModified() < stale) it.delete() }
        val pdf = PdfDocument()
        try {
            d.pages.forEachIndexed { i, ops ->
                val pg = pdf.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), i + 1).create())
                try {
                    val cv = pg.canvas
                    cv.drawColor(Color.WHITE)
                    chrome(d, cv, lh, i + 1, d.pages.size)
                    ops.forEach { it(cv) }
                } finally { pdf.finishPage(pg) } // close() throws if a page is left open, hiding the real error
            }
            try {
                f.outputStream().use { pdf.writeTo(it) }
            } catch (e: Throwable) {
                f.delete() // never leave a truncated PDF behind
                throw e
            }
        } finally { pdf.close() }
        return f
    }

    /** Opens the share sheet (WhatsApp, email…) for [f]. */
    fun share(a: Activity, f: File) {
        val uri = FileProvider.getUriForFile(a, "com.aryan.premdue.files", f)
        val i = Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("", uri)
        a.startActivity(Intent.createChooser(i, "Share presentation").putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(a, MainActivity::class.java))))
    }

    /** Letterhead band and disclaimer footer, drawn on every page. */
    private fun chrome(d: Doc, cv: Canvas, lh: Letterhead, n: Int, total: Int) {
        put(cv, lh.name, M, M + 14f, CW * 0.62f, pt(d.bold, 14f, INK))
        put(cv, lh.phone, M + CW * 0.62f, M + 14f, CW * 0.38f, pt(d.reg, 10f, INK), right = true)
        put(cv, lh.line, M, M + 30f, CW, pt(d.reg, 9f, GREY))
        cv.drawLine(M, M + 38f, W - M, M + 38f, Paint().apply { color = INK; strokeWidth = 0.75f })
        cv.drawLine(M, d.footTop, W - M, d.footTop, d.hair)
        d.foot.forEachIndexed { k, s -> put(cv, s, M, d.footTop + 11f + k * 9f, CW, d.footP) }
        put(cv, "Page $n of $total", M, H - M, CW, pt(d.reg, 7.5f, GREY), right = true)
    }

    /** Title, customer line and the summary of all plans. */
    private fun cover(d: Doc, pr: Presentation) {
        d.text("Plan presentation", M, d.y + 26f, CW, pt(d.serif, 26f, INK))
        d.y += 46f
        val date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH))
        d.text("For ${pr.customer.trim()}, age ${pr.age}", M, d.y, CW * 0.7f, pt(d.reg, 11f, GREY))
        d.text(date, M + CW * 0.7f, d.y, CW * 0.3f, pt(d.reg, 11f, GREY), right = true)
        d.y += 28f
        summary(d, pr.plans)
    }

    /** One plan: label/value block; 2–3 plans: comparison table, one column per plan. */
    private fun summary(d: Doc, plans: List<PlanInput>) {
        if (plans.isEmpty()) return
        val rows = listOf(
            "Premium per year" to plans.map { rupees(it.premium) },
            "Paying years" to plans.map { years(it.payYears) },
            "Policy term" to plans.map { years(it.term) },
            "Sum assured" to plans.map { rupees(it.sumAssured) },
            "Death cover" to plans.map { it.deathCover.trim().ifEmpty { "—" } },
            "Total paid" to plans.map { rupees(it.paid) },
            "Total received" to plans.map { rupees(it.received) },
            "Net gain" to plans.map { money(it.received - it.paid) },
            "Return per year" to plans.map { p -> p.yield()?.let { pct(it) } ?: "—" },
        )
        val one = plans.size == 1
        val lw = if (one) 170f else 130f
        val cw = (CW - lw) / plans.size
        val lab = pt(d.reg, 9.5f, GREY)
        val v = pt(d.reg, 10f, INK)
        val vb = pt(d.bold, 10f, INK)
        d.text("Summary", M, d.y + 12f, CW, pt(d.bold, 12f, INK))
        d.y += 20f
        // Column heads: plan names (one plan: a single bold line).
        val heads = plans.map { lines(it.name, vb, cw - 8f) }
        val hh = heads.maxOf { it.size } * 13f + 8f
        heads.forEachIndexed { i, l ->
            l.forEachIndexed { k, t -> d.text(t, M + lw + i * cw + 8f, d.y + 13f + k * 13f, cw - 8f, vb, right = !one) }
        }
        d.y += hh
        d.rule(d.y, Paint().apply { color = INK; strokeWidth = 0.75f })
        rows.forEach { (label, vals) ->
            val strong = label == "Net gain" || label == "Return per year"
            val p = if (strong) vb else v
            val ls = vals.map { lines(it, p, cw - 8f) }
            val h = ls.maxOf { it.size } * 13f + 8f
            if (!d.fits(h)) d.page()
            d.text(label, M, d.y + 14f, lw - 8f, if (strong) pt(d.bold, 9.5f, INK) else lab)
            ls.forEachIndexed { i, l ->
                l.forEachIndexed { k, t -> d.text(t, M + lw + i * cw + 8f, d.y + 14f + k * 13f, cw - 8f, p, right = !one) }
            }
            d.y += h
            d.rule(d.y)
        }
        d.y += 24f
    }

    /** Heading, cash-flow chart and year-by-year table for one plan. */
    private fun plan(d: Doc, age: Int, p: PlanInput) {
        val last = maxOf(p.term, p.payYears, p.benefits.maxOfOrNull { it.first } ?: 0)
        val prem = DoubleArray(last + 1) { y -> if (y in 1..p.payYears) p.premium else 0.0 }
        val ben = DoubleArray(last + 1).also { b -> p.benefits.forEach { (y, a) -> if (y in 1..last) b[y] += a } }
        if (!d.fits(44f + 170f + ROW * 4)) d.page()
        d.text(p.name, M, d.y + 15f, CW, pt(d.bold, 15f, INK))
        d.text("${rupees(p.premium)} a year for ${years(p.payYears)} · policy term ${years(p.term)} · sum assured ${rupees(p.sumAssured)}",
            M, d.y + 31f, CW, pt(d.reg, 9.5f, GREY))
        d.y += 44f
        if (last > 0 && (prem.any { it > 0 } || ben.any { it > 0 })) chart(d, prem, ben)
        val head = pt(d.bold, 8.5f, GREY)
        val body = pt(d.reg, 9f, INK)
        tableHead(d, head)
        var net = 0.0
        for (y in 1..last) {
            if (prem[y] == 0.0 && ben[y] == 0.0) continue
            net += ben[y] - prem[y]
            if (!d.fits(ROW)) {
                d.page()
                d.text("${p.name} (continued)", M, d.y + 12f, CW, pt(d.bold, 11f, INK))
                d.y += 20f
                tableHead(d, head)
            }
            row(d, listOf("$y", "${age + y}", if (prem[y] > 0) rupees(prem[y]) else "", if (ben[y] > 0) rupees(ben[y]) else "", money(net)), body)
            d.rule(d.y)
        }
        if (!d.fits(ROW + 2f)) {
            d.page()
            d.text("${p.name} (continued)", M, d.y + 12f, CW, pt(d.bold, 11f, INK))
            d.y += 20f
            tableHead(d, head)
        }
        d.y += 2f
        row(d, listOf("Total", "", rupees(p.paid), rupees(p.received), money(p.received - p.paid)), pt(d.bold, 9f, INK))
        d.y += 28f
    }

    /** Premiums as grey bars below the axis, benefits as ink bars above it, one shared scale. */
    private fun chart(d: Doc, prem: DoubleArray, ben: DoubleArray) {
        val n = prem.size - 1
        val lp = pt(d.reg, 8f, GREY)
        val top = d.y
        d.box(M, top + 3f, M + 6f, top + 9f, GREY)
        d.text("Premium paid", M + 10f, top + 9f, 100f, lp)
        val x2 = M + 10f + lp.measureText("Premium paid") + 16f
        d.box(x2, top + 3f, x2 + 6f, top + 9f, INK)
        d.text("Benefit received", x2 + 10f, top + 9f, 120f, lp)
        val py = top + 20f
        val ph = 110f
        val maxB = ben.max()
        val maxP = prem.max()
        val s = ph / (maxB + maxP)
        val axis = py + (maxB * s).toFloat()
        val slot = CW / n
        val bw = min(14f, max(1f, slot * 0.7f))
        for (y in 1..n) {
            val cx = M + (y - 0.5f) * slot
            if (prem[y] > 0) d.box(cx - bw / 2, axis, cx + bw / 2, axis + max(1f, (prem[y] * s).toFloat()), GREY)
            if (ben[y] > 0) d.box(cx - bw / 2, axis - max(1f, (ben[y] * s).toFloat()), cx + bw / 2, axis, INK)
        }
        d.rule(axis, Paint().apply { color = INK; strokeWidth = 0.75f })
        val yp = pt(d.reg, 7f, GREY)
        val base = py + ph + 12f
        for (y in 1..n) {
            val show = y == 1 || y == n || (y % 5 == 0 && (n - y) * slot >= 16f && (y - 1) * slot >= 16f)
            if (!show) continue
            val t = "$y"
            val tw = yp.measureText(t)
            val x = (M + (y - 0.5f) * slot - tw / 2).coerceIn(M, W - M - tw)
            d.text(t, x, base, tw + 1f, yp)
        }
        d.text("Policy year", M, base + 11f, CW, yp, right = true)
        d.y = base + 24f
    }

    private fun tableHead(d: Doc, p: Paint) {
        row(d, listOf("Year", "Age", "Premium", "Benefit", "Net so far"), p)
        d.rule(d.y, Paint().apply { color = INK; strokeWidth = 0.75f })
    }

    private fun row(d: Doc, cells: List<String>, p: Paint) {
        var x = M
        cells.forEachIndexed { i, s -> d.text(s, x + 4f, d.y + 11f, COLS[i] - 8f, p, right = i > 0); x += COLS[i] }
        d.y += ROW
    }

    /** Draws [s] on one line within [w]: shrinks the font up to 25%, then ellipsizes. */
    private fun put(cv: Canvas, s: String, x: Float, base: Float, w: Float, p: Paint, right: Boolean = false) {
        if (s.isEmpty() || w <= 0f) return
        val q = Paint(p).apply { textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT }
        val floor = p.textSize * 0.75f
        while (q.measureText(s) > w && q.textSize > floor) q.textSize -= 0.25f
        val t = if (q.measureText(s) <= w) s
            else s.take(q.breakText(s, true, max(0f, w - q.measureText("…")), null)).trimEnd() + "…"
        cv.drawText(t, if (right) x + w else x, base, q)
    }

    /** Word-wraps [s] to [w]; at most 3 lines (the last is ellipsized by [put]). */
    private fun lines(s: String, p: Paint, w: Float): List<String> =
        wrap(s, p, w).let { if (it.size > 3) it.take(2) + it.drop(2).joinToString(" ") else it }

    private fun wrap(s: String, p: Paint, w: Float): List<String> {
        val out = mutableListOf<String>()
        var cur = ""
        s.split(' ').filter { it.isNotEmpty() }.forEach { word ->
            val t = if (cur.isEmpty()) word else "$cur $word"
            if (cur.isNotEmpty() && p.measureText(t) > w) { out += cur; cur = word } else cur = t
        }
        if (cur.isNotEmpty()) out += cur
        return out.ifEmpty { listOf("") }
    }

    private fun pt(tf: Typeface, size: Float, col: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = size; color = col }

    private fun years(n: Int) = if (n == 1) "1 year" else "$n years"

    private fun money(v: Double) = if (v <= -0.5) "-" + rupees(-v) else rupees(v)
}
