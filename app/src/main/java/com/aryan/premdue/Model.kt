package com.aryan.premdue

import android.content.Context
import android.util.AtomicFile
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/** LIC grace period in days: 15 for monthly premiums, 30 for every other mode. */
fun graceDays(mode: String) = if (mode == "Mly") 15L else 30L

data class Policy(
    val sno: Int,
    val no: String,
    val name: String,
    val doc: LocalDate,
    val plan: String,
    val mode: String,
    val fup: YearMonth,
    val flag: String,
    val inst: Double,
    val dues: Int,
    val total: Long,
    val com: Double,
) {
    /** Premiums fall due on the commencement day of the month, clamped to short months. */
    fun dueOn(month: YearMonth): LocalDate = month.atDay(minOf(doc.dayOfMonth, month.lengthOfMonth()))

    /** LIC allows 15 days for monthly premiums, else "one month but not less than 30 days". */
    fun graceEnd(): LocalDate = dueOn(fup).let { if (mode == "Mly") it.plusDays(graceDays(mode)) else maxOf(it.plusDays(graceDays(mode)), it.plusMonths(1)) }
}

data class DueList(
    val month: YearMonth,
    val agent: String,
    val policies: List<Policy>,
    val premium: Long,
    val commission: Double,
)

enum class Section(val title: String) {
    LAPSE("🔴 Lapse risk"),
    NEW("🟢 New policies"),
    MATURE("🟣 Maturing / last premium"),
    REGULAR("⚪ Regular"),
}

fun DueList.section(p: Policy) =
    when {
        p.fup < month -> Section.LAPSE
        p.flag == "FY" || p.flag == "ST" -> Section.NEW
        p.flag == "MT" || p.flag == "LP" -> Section.MATURE
        else -> Section.REGULAR
    }

/** Reads the text of LIC's "Premium Due List" PDF; throws with a plain reason if it does not add up. */
object DueListParser {
    private val row =
        Regex(
            """^\s*(\d+)\s+(\d{9})\s+(.*?)\s*(\d{2}/\d{2}/\d{4})\s*(.*?)\s*(\d+/\d+)\s+(Mly|Qly|Hly|Yly)\s+(\d{2}/\d{4})\s+(?:(FY|ST|LP|MT)\s+)?(\d+\.\d{2})\s+(\d+)\s+(\d+\.\d{2})\s+(\d+)\s+(\d+\.\d{2})\s*$"""
        )
    private val rowStart = Regex("""^\s*\d+\s+\d{9}\b.*""")
    private val monthRx = Regex("""Premium Due List For The Agent \S+ For (\d{2}/\d{4})""")
    private val agentRx = Regex("""Agent Name\s*:\s*(.+)""")
    private val honorific = Regex("""^(?:SHRI|SMT|KUM|KU|MRS|MR|MS)(?:\.\s*|\s+)""")
    private val grandRx = Regex("""Grand Total\s*Premium\s*:\s*(\d+).*?Estimated Comn\s*:\s*(\d+\.\d+)""")
    private val dmy = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val my = DateTimeFormatter.ofPattern("MM/yyyy")

    fun parse(text: String): DueList {
        val month = monthRx.find(text)?.let { YearMonth.parse(it.groupValues[1], my) }
            ?: error("This is not an LIC Premium Due List PDF.")
        val agent = agentRx.find(text)?.groupValues?.get(1)?.trim()?.replace(honorific, "")?.trim() ?: ""
        val grand = grandRx.find(text) ?: error("The PDF's Grand Total line was not found.")
        // A tall row (long name) can come out as two text lines; join it with the next line.
        val lines = text.lines()
        val rows = mutableListOf<MatchResult>()
        var i = 0
        while (i < lines.size) {
            val m = row.matchEntire(lines[i])
                ?: if (rowStart.matches(lines[i]) && i + 1 < lines.size && !rowStart.matches(lines[i + 1])) row.matchEntire(lines[i] + " " + lines[i + 1])?.also { i++ } else null
            if (m != null) rows.add(m)
            i++
        }
        val policies =
            rows.map {
                val g = it.groupValues
                Policy(
                    sno = g[1].toInt(),
                    no = g[2],
                    name = "${g[3]} ${g[5]}".trim().ifEmpty { error("Row ${g[1]} has no name.") },
                    doc = LocalDate.parse(g[4], dmy),
                    plan = g[6],
                    mode = g[7],
                    fup = YearMonth.parse(g[8], my),
                    flag = g[9],
                    inst = g[10].toDouble(),
                    dues = g[11].toInt(),
                    total = g[13].toLong(),
                    com = g[14].toDouble(),
                )
            }
        val premium = grand.groupValues[1].toLong()
        val commission = grand.groupValues[2].toDouble()
        val sumPremium = policies.sumOf { it.total }
        val sumCom = policies.sumOf { it.com }
        check(policies.map { it.sno } == (1..policies.size).toList()) {
            "Rows read: ${policies.size}, but serial numbers are not continuous."
        }
        check(sumPremium == premium) {
            "Rows add up to ${rupees(sumPremium)}, but the PDF's Grand Total is ${rupees(premium)}."
        }
        check(abs(sumCom - commission) < 0.05) {
            "Rows' commission adds up to ${"%.2f".format(Locale.ROOT, sumCom)}, but the PDF's total is ${"%.2f".format(Locale.ROOT, commission)}."
        }
        return DueList(month, agent, policies, premium, commission)
    }
}

data class Status(val kind: String, val date: LocalDate?) // kind: paid | promise | unreachable

data class MonthSummary(
    val month: YearMonth,
    val policies: Int,
    val premium: Long,
    val commission: Double,
    val overdue: Int,
    val closedFromPrev: Int,
    val closedPremium: Long,
    val carriedFromPrev: Int,
)

/** All app state, kept in one JSON file on this phone. */
class Data(
    var list: DueList? = null,
    val status: MutableMap<String, Status> = mutableMapOf(),
    val links: MutableMap<String, Pair<String, String>> = mutableMapOf(), // policy no -> (number, contact name)
    val history: MutableList<MonthSummary> = mutableListOf(),
    var lang: String = "mr",
) {
    fun unpaid(p: Policy) = status[p.no]?.kind != "paid"

    /** The day's action list: dues falling today, grace ending within 3 days, and promises now due. */
    fun today(day: LocalDate): List<Policy> {
        val l = list ?: return emptyList()
        return l.policies.filter { p ->
            unpaid(p) &&
                (p.dueOn(l.month) == day ||
                    (p.fup < l.month && p.graceEnd() in day..day.plusDays(3)) ||
                    status[p.no]?.let { it.kind == "promise" && it.date != null && !it.date.isAfter(day) } == true)
        }
    }

    /** Replaces the list. A newer month is reconciled against the old one first. */
    fun import(new: DueList): MonthSummary {
        val old = list
        val newNos = new.policies.map { it.no }.toSet()
        val closed = if (old != null && new.month > old.month) old.policies.filter { it.no !in newNos } else emptyList()
        val carried = if (old != null && new.month > old.month) old.policies.size - closed.size else 0
        if (old == null || new.month > old.month) {
            // Outcomes belonged to the old dues, but a promise dated in the new month stands while that due is still unpaid.
            val start = new.month.atDay(1)
            val stillOverdue = new.policies.filter { it.fup < new.month }.map { it.no }.toSet()
            status.entries.removeAll { (no, s) -> no !in stillOverdue || s.kind != "promise" || s.date == null || s.date.isBefore(start) }
        } else status.keys.retainAll(newNos)
        list = new
        val summary =
            MonthSummary(
                new.month,
                new.policies.size,
                new.premium,
                new.commission,
                new.policies.count { it.fup < new.month },
                closed.size,
                closed.sumOf { it.total },
                carried,
            )
        history.removeAll { it.month == new.month }
        history.add(summary)
        history.sortBy { it.month }
        return summary
    }

    fun toJson(): JSONObject =
        JSONObject().apply {
            list?.let { l ->
                put(
                    "list",
                    JSONObject()
                        .put("month", l.month.toString())
                        .put("agent", l.agent)
                        .put("premium", l.premium)
                        .put("commission", l.commission)
                        .put(
                            "policies",
                            JSONArray(
                                l.policies.map {
                                    JSONArray(
                                        listOf(it.sno, it.no, it.name, it.doc.toString(), it.plan, it.mode, it.fup.toString(), it.flag, it.inst, it.dues, it.total, it.com)
                                    )
                                }
                            ),
                        ),
                )
            }
            put("status", JSONObject(status.mapValues { (_, s) -> JSONArray(listOf(s.kind, s.date?.toString() ?: "")) }))
            put("links", JSONObject(links.mapValues { (_, v) -> JSONArray(listOf(v.first, v.second)) }))
            put(
                "history",
                JSONArray(history.map { JSONArray(listOf(it.month.toString(), it.policies, it.premium, it.commission, it.overdue, it.closedFromPrev, it.closedPremium, it.carriedFromPrev)) }),
            )
            put("lang", lang)
        }

    companion object {
        fun fromJson(o: JSONObject): Data {
            val d = Data(lang = o.optString("lang", "mr"))
            o.optJSONObject("list")?.let { l ->
                val arr = l.getJSONArray("policies")
                d.list =
                    DueList(
                        YearMonth.parse(l.getString("month")),
                        l.getString("agent"),
                        (0 until arr.length()).map {
                            val a = arr.getJSONArray(it)
                            Policy(a.getInt(0), a.getString(1), a.getString(2), LocalDate.parse(a.getString(3)), a.getString(4), a.getString(5), YearMonth.parse(a.getString(6)), a.getString(7), a.getDouble(8), a.getInt(9), a.getLong(10), a.getDouble(11))
                        },
                        l.getLong("premium"),
                        l.getDouble("commission"),
                    )
            }
            o.optJSONObject("status")?.let { s ->
                s.keys().forEach { k -> val a = s.getJSONArray(k); d.status[k] = Status(a.getString(0), a.getString(1).takeIf { it.isNotEmpty() }?.let(LocalDate::parse)) }
            }
            o.optJSONObject("links")?.let { s -> s.keys().forEach { k -> val a = s.getJSONArray(k); d.links[k] = a.getString(0) to a.getString(1) } }
            o.optJSONArray("history")?.let { h ->
                (0 until h.length()).forEach {
                    val a = h.getJSONArray(it)
                    d.history.add(MonthSummary(YearMonth.parse(a.getString(0)), a.getInt(1), a.getLong(2), a.getDouble(3), a.getInt(4), a.getInt(5), a.getLong(6), a.getInt(7)))
                }
            }
            return d
        }
    }
}

object Store {
    private fun file(c: Context) = AtomicFile(c.filesDir.resolve("data.json"))

    /** Missing file = first run. An unreadable file is copied aside first, so the next save() cannot destroy it. */
    @Synchronized
    fun load(c: Context): Data {
        val bytes =
            try {
                file(c).readFully()
            } catch (e: java.io.FileNotFoundException) {
                return Data()
            } catch (e: java.io.IOException) {
                null
            }
        bytes?.let { b -> runCatching { Data.fromJson(JSONObject(String(b))) }.getOrNull() }?.let { return it }
        val f = file(c).baseFile
        runCatching { f.copyTo(c.filesDir.resolve("data-unreadable-${f.lastModified()}.json")) }
        return Data()
    }

    @Synchronized
    fun save(c: Context, d: Data) {
        val f = file(c)
        val out = f.startWrite()
        runCatching { out.write(d.toJson().toString().toByteArray()); f.finishWrite(out) }
            .onFailure { f.failWrite(out); throw it }
    }
}

private val inr =
    java.text.NumberFormat.getNumberInstance(Locale("en", "IN")).apply {
        maximumFractionDigits = 0
        roundingMode = java.math.RoundingMode.HALF_UP
    }

/** NumberFormat is not thread-safe; the parser calls this off the UI thread. */
/** Indian grouping, whole rupees; values that round to zero never show as "₹-0". */
fun rupees(v: Number) = "₹" + synchronized(inr) { inr.format(v.toDouble().let { if (kotlin.math.abs(it) < 0.5) 0.0 else it }) }

fun short(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))

/** Reminder text in the chosen language. Overdue policies get the "pending since" wording. */
fun message(lang: String, name: String, p: Policy, list: DueList): String {
    val overdue = p.fup < list.month
    val date = (if (overdue) p.dueOn(p.fup) else p.dueOn(list.month)).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    val amt = rupees(p.total)
    val agent = list.agent.ifBlank { null }
    return when (lang) {
        "hi" ->
            if (overdue) "नमस्ते $name, आपकी LIC पॉलिसी क्र. ${p.no} की $amt की किस्त $date से बकाया है। पॉलिसी बंद होने से बचाने के लिए कृपया जल्द भुगतान करें। – ${agent?.plus(", ") ?: ""}LIC एजेंट"
            else "नमस्ते $name, आपकी LIC पॉलिसी क्र. ${p.no} की $amt की किस्त $date को देय है। कृपया समय पर भुगतान करें। – ${agent?.plus(", ") ?: ""}LIC एजेंट"
        "en" ->
            if (overdue) "Dear $name, the premium of $amt for your LIC policy no. ${p.no} is pending since $date. Please pay soon to keep your policy active. – ${agent?.plus(", ") ?: ""}LIC Agent"
            else "Dear $name, the premium of $amt for your LIC policy no. ${p.no} is due on $date. Kindly pay on time. – ${agent?.plus(", ") ?: ""}LIC Agent"
        else ->
            if (overdue) "नमस्कार $name, आपल्या LIC पॉलिसी क्र. ${p.no} चा $amt चा हप्ता $date पासून थकित आहे. पॉलिसी बंद होऊ नये म्हणून कृपया लवकर भरावा. – ${agent?.plus(", ") ?: ""}LIC प्रतिनिधी"
            else "नमस्कार $name, आपल्या LIC पॉलिसी क्र. ${p.no} चा $amt चा हप्ता $date रोजी देय आहे. कृपया वेळेवर भरावा. – ${agent?.plus(", ") ?: ""}LIC प्रतिनिधी"
    }
}
