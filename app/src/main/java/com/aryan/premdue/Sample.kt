package com.aryan.premdue

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A fictional due list in LIC's layout, for trying PremDue without a real one. No real people or policies. */
object Sample {
    const val AGENT = "SAMPLE AGENT"
    private const val CODE = "SAMPLE001"
    private val dmy = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val my = DateTimeFormatter.ofPattern("MM/yyyy")

    private val names =
        listOf(
            "ASHA R KULKARNI", "RAHUL S DESAI", "MEERA P JOSHI", "VIKRAM N PATIL", "SUNITA A RAO", "ANIL K SHARMA",
            "PRIYA M NAIR", "SANJAY V GUPTA", "KAVITA D IYER", "RAJESH T MEHTA", "NEHA B KAPOOR", "AMIT J VERMA",
            "POOJA L SHAH", "DEEPAK G MISHRA", "ANJALI C REDDY", "SURESH H PILLAI", "REKHA F BOSE", "MANOJ E SINHA",
        )

    /** Months behind, mode, plan, instalment, flag for each row: a mix of lapse risk, new, maturing and regular. */
    private val shape =
        listOf(
            Triple(0, "Mly", 914), Triple(0, "Qly", 936), Triple(0, "Mly", 945), Triple(1, "Mly", 814), Triple(0, "Hly", 165),
            Triple(2, "Mly", 914), Triple(0, "Yly", 943), Triple(0, "Mly", 936), Triple(1, "Mly", 945), Triple(0, "Qly", 814),
            Triple(3, "Mly", 165), Triple(0, "Mly", 914), Triple(0, "Hly", 936), Triple(0, "Mly", 945), Triple(1, "Mly", 943),
            Triple(0, "Yly", 814), Triple(0, "Mly", 165), Triple(0, "Qly", 914),
        )
    private val inst = listOf(1250, 4875, 2049, 682, 8775, 1546, 21375, 512, 3320, 2410, 1021, 1786, 10105, 4032, 950, 33754, 510, 6142)
    private val flags = mapOf(2 to "FY", 6 to "MT", 12 to "FY", 15 to "LP")

    /** The list's lines as LIC prints them, each row as its cells; premiums fall due from [today] over the next ten days. */
    fun lines(month: YearMonth, today: Int): Triple<List<String>, List<List<String>>, String> {
        var premium = 0L
        var comPaise = 0L
        val rows =
            names.indices.map { i ->
                val (behind, mode, plan) = shape[i]
                val flag = flags[i] ?: ""
                val fup = month.minusMonths(behind.toLong())
                val dues = if (mode == "Mly") behind + 1 else 1
                val total = inst[i].toLong() * dues
                val com = total * 5 // paise: 5% of the total
                premium += total
                comPaise += com
                val doc =
                    (if (flag == "FY") month.minusMonths(3L + i % 5) else month.minusYears(4L + i % 11)).atDay((today - 1 + (i * 3) % 10) % 28 + 1)
                listOf(
                    "${i + 1}", "${100200300 + i * 4111}", names[i], doc.format(dmy), "$plan/${15 + i % 11}", mode, fup.format(my), flag,
                    "%.2f".format(Locale.ROOT, inst[i].toDouble()), "$dues", "0.00", "$total", "%.2f".format(Locale.ROOT, com / 100.0),
                )
            }
        val head =
            listOf(
                "Branch Code: SAMPLE",
                "Agent Name : $AGENT",
                "Agent Code : $CODE",
                "Premium Due List For The Agent $CODE For ${month.format(my)}",
                "SAMPLE DATA - fictional names and policies, for trying PremDue",
            )
        val grand = "Grand Total    Premium : $premium    GST:0    Estimated Comn : ${"%.2f".format(Locale.ROOT, comPaise / 100.0)}"
        return Triple(head, rows, grand)
    }

    /** Plain text in the shape the PDF text extractor produces; the parser reads it the same way. */
    fun text(month: YearMonth, today: Int = 1): String {
        val (head, rows, grand) = lines(month, today)
        return (head + rows.map { r -> r.filter { it.isNotEmpty() }.joinToString("  ") } + grand).joinToString("\n")
    }

    /** Writes the sample as an A4 PDF, columns laid out like LIC's. */
    fun writePdf(month: YearMonth, today: Int, f: File) {
        val (head, rows, grand) = lines(month, today)
        val xs = floatArrayOf(18f, 34f, 78f, 196f, 240f, 274f, 298f, 334f, 352f, 392f, 410f, 438f, 470f)
        val labels = listOf("S.No", "PolicyNo", "Name of Assured", "D.o.C", "Pln/Tm", "Mod", "FUP", "Flg", "InstPrem", "Due", "GST", "TotPrem", "EstCom")
        val p = Paint().apply { typeface = Typeface.SANS_SERIF; textSize = 7f; isAntiAlias = true }
        val bold = Paint(p).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            val c = page.canvas
            var y = 40f
            head.forEach { c.drawText(it, 150f, y, bold); y += 14f }
            y += 6f
            labels.forEachIndexed { j, s -> c.drawText(s, xs[j], y, bold) }
            y += 16f
            rows.forEach { r ->
                r.forEachIndexed { j, s -> if (s.isNotEmpty()) c.drawText(s, xs[j], y, p) }
                y += 14f
            }
            y += 10f
            c.drawText(grand, xs[0], y, bold)
            doc.finishPage(page)
            f.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }
}
