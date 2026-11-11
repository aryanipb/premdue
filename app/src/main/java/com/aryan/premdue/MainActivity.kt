package com.aryan.premdue

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import android.text.*
import android.view.*
import android.widget.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.time.LocalDate
import java.io.File
import java.time.YearMonth
import java.util.Locale
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class MainActivity : UiActivity() {
    private lateinit var data: Data
    private var tab = "Today"
    private var query = ""
    private var awaiting: Policy? = null // opened WhatsApp/dialer for this policy; ask the outcome on return
    private var inQueue = false
    private val queue = ArrayDeque<Policy>()
    private val done = mutableSetOf<String>()
    private var doneDay = LocalDate.now() // [done] holds this day's outcomes only
    private var shownDay: LocalDate? = null
    private var pendingLink: Pair<Policy, Int>? = null // policy being linked, then opened with this channel (-1: only save)
    private var contacts: List<Pair<String, String>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        data = Store.load(this)
        DailyReceiver.schedule(this)
        savedInstanceState?.let { restore(it) }
        render()
        // A recreated activity gets its original share intent again; that file was already imported.
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        handle(i)
    }

    override fun onResume() {
        super.onResume()
        contacts = null // pick up contacts added while away
        if (shownDay != LocalDate.now()) render()
        awaiting?.let {
            awaiting = null
            outcome(it)
        }
    }

    // The process may be killed while the agent is in WhatsApp, the dialer or the contact picker.
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("tab", tab)
        out.putString("query", query)
        out.putString("awaiting", awaiting?.no)
        out.putBoolean("inQueue", inQueue)
        out.putStringArrayList("queue", ArrayList(queue.map { it.no }))
        out.putStringArrayList("done", ArrayList(done))
        out.putString("doneDay", doneDay.toString())
        out.putString("linkNo", pendingLink?.first?.no)
        out.putInt("linkHow", pendingLink?.second ?: -1)
    }

    private fun restore(b: Bundle) {
        tab = b.getString("tab") ?: tab
        query = b.getString("query") ?: ""
        awaiting = policy(b.getString("awaiting"))
        b.getStringArrayList("queue")?.mapNotNull { policy(it) }?.let { queue.addAll(it) }
        b.getStringArrayList("done")?.let { done.addAll(it) }
        b.getString("doneDay")?.let { doneDay = LocalDate.parse(it) }
        pendingLink = policy(b.getString("linkNo"))?.let { it to b.getInt("linkHow", -1) }
        // Any other queue step was an open dialog, which did not survive.
        inQueue = b.getBoolean("inQueue") && (awaiting != null || pendingLink != null)
    }

    private fun policy(no: String?) = data.list?.policies?.firstOrNull { it.no == no }

    private fun save() = Store.save(this, data)

    // ---------- Screens ----------

    private fun render() {
        val y = scroll?.scrollY ?: 0
        shownDay = LocalDate.now()
        shell(if (tab == "Today") "PremDue" else tab)
        val l = data.list
        when {
            tab == "Tools" -> tools()
            tab == "Settings" -> settings()
            l == null -> empty()
            tab == "Month" -> month(l)
            tab == "Insights" -> insights(l)
            else -> today(l)
        }
        navBar(TABS, tab) {
            tab = it
            render()
            scroll?.scrollTo(0, 0)
        }
        scroll?.let { sv -> sv.post { sv.scrollTo(0, y) } }
    }

    /** No due list yet: how to bring one in. */
    private fun empty() {
        heading("Share your due list", "Open the LIC Premium Due List email, tap the PDF, then Share → PremDue.")
        button(body, "Open due list PDF", true) { pickPdf() }
        button(body, "Try with a sample list") { trySample() }
        button(body, "Restore from backup") { pickBackup() }
        space()
        text(body, "Your customers' data stays on this phone and in your own Google backups. It is never sent to us.", 12f, muted)
    }

    private fun settings() {
        val due = group("Due list")
        val l = data.list
        item(due, "Open due list PDF", l?.let { "${monthName(it.month)} · ${it.policies.size} policies" } ?: "No list loaded yet") { pickPdf() }
        item(due, "Try a sample list", "Fictional customers, to see how PremDue works.") { trySample() }
        val langs = listOf("mr" to "मराठी", "hi" to "हिंदी", "en" to "English")
        item(due, "Reminder message language", value = (langs.firstOrNull { it.first == data.lang } ?: langs[0]).second) {
            choose("Reminder message language", langs.map { it.second }) { j ->
                data.lang = langs[j].first
                save()
                render()
            }
        }

        val alerts = group("Daily summary")
        item(alerts, "9 AM notification", "Today's dues, promises and policies close to lapsing.")
        item(alerts, "Not getting the notification?", "Allow notifications and remove battery limits.") {
            confirm(
                "Daily 9 AM summary",
                "Some phones (Xiaomi, Vivo, Oppo, Realme) stop apps in the background. On the next screen, allow Notifications, set Battery to \"No restrictions\" and turn on Auto-start if shown.",
            ) {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } catch (e: ActivityNotFoundException) {
                    toast("Open Settings → Apps → PremDue.")
                }
            }
        }

        val backup = group("Backup")
        item(backup, "Back up to Google Drive", "Save a copy of all your data. Choose Drive on the next screen.") { backup() }
        item(backup, "Restore from backup", "Replace this phone's data with a backup file.") { pickBackup() }
        item(backup, "Automatic backup", "Android also backs up PremDue to your Google account, encrypted.")

        val about = group("About")
        item(about, "Privacy policy") { openUrl(Links.PRIVACY) }
        item(about, "Contact support", Links.SUPPORT) {
            runCatching { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Links.SUPPORT}?subject=" + Uri.encode("PremDue support")))) }
                .onFailure { toast("Write to ${Links.SUPPORT}") }
        }
        item(about, "Version", value = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "")
        space()
        text(body, "Your customers' data stays on this phone and in your own Google backups. It is never sent to us.", 12f, muted)
    }

    private fun today(l: DueList) {
        val day = LocalDate.now()
        if (YearMonth.from(day) != l.month)
            text(body, "This list is for ${monthName(l.month)}. Share the new month's PDF.", 13f, muted)
        val items = data.today(day)
        text(body, "Today · ${short(day)}", 13f, muted)
        text(body, "${items.size} dues · ${rupees(items.sumOf { it.total })} · ${rupees(items.sumOf { it.com })} com", 20f, bold = true)
        space()
        if (items.isEmpty()) text(body, "Nothing due today.", 15f, muted)
        sections(body, l, items)
        if (items.isNotEmpty()) button(footer, "▶  Send all reminders", true) { sendAll(items) }
    }

    private fun month(l: DueList) {
        val search =
            EditText(this).apply {
                hint = "Search name or policy no."
                setHintTextColor(muted)
                setTextColor(ink)
                typeface = sans
                textSize = 15f
                setSingleLine()
                background = shape()
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setText(query)
                setSelection(query.length)
            }
        body.addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val results = column(body)
        fun fill() {
            results.removeAllViews()
            val q = query.trim().uppercase()
            val match = l.policies.filter { q.isEmpty() || it.name.uppercase().contains(q) || it.no.contains(q) }
            val (open, paid) = match.partition { data.unpaid(it) }
            text(results, "${open.size} still due · ${rupees(open.sumOf { it.total })}", 13f, muted)
            sections(results, l, open)
            if (paid.isNotEmpty()) {
                text(results, "✅ Paid (${paid.size}) · ${rupees(paid.sumOf { it.total })}", 15f, bold = true)
                paid.forEach { policyCard(results, l, it) }
            }
        }
        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s.toString()
                    fill()
                }
            }
        )
        fill()
    }

    private fun sections(parent: LinearLayout, l: DueList, items: List<Policy>) {
        val grouped = items.groupBy { l.section(it) }
        Section.entries.forEach { s ->
            val ps = grouped[s] ?: return@forEach
            text(parent, "${s.title} (${ps.size})", 15f, bold = true)
            ps.sortedBy { if (s == Section.LAPSE) it.graceEnd() else it.dueOn(l.month) }.forEach { policyCard(parent, l, it) }
            space(parent, 4)
        }
    }

    private fun policyCard(parent: LinearLayout, l: DueList, p: Policy) {
        val c = card(parent)
        val top = row(c)
        text(top, p.name, 16f, bold = true).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        text(top, rupees(p.total), 16f, bold = true).layoutParams = LinearLayout.LayoutParams(-2, -2)
        text(c, "${p.no} · ${p.plan} · ${p.mode}" + (if (p.dues > 1) " · ${p.dues} dues" else "") + (data.links[p.no]?.let { " · ${it.second.ifBlank { "+" + it.first }}" } ?: ""), 12f, muted)
        text(c, chip(l, p), 13f)
        if (data.unpaid(p)) {
            val b = row(c)
            listOf("Call" to CALL, "SMS" to SMS, "WhatsApp" to WHATSAPP).forEach { (label, how) ->
                button(b, label) { inQueue = false; reach(p, how) }.apply { contentDescription = "$label ${p.name}" }.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = if (how == WHATSAPP) 0 else dp(6) }
            }
        }
        c.isClickable = true
        c.setOnClickListener { inQueue = false; outcome(p) }
        Design.press(c)
    }

    private fun tools() {
        ToolsActivity.TOOLS.forEach { t ->
            link(body, t.title, t.subtitle) { startActivity(Intent(this, ToolsActivity::class.java).putExtra("tool", t.key)) }
        }
    }

    private fun chip(l: DueList, p: Policy): String {
        val today = LocalDate.now()
        val st = data.status[p.no]
        val status =
            when (st?.kind) {
                "paid" -> "Paid ✓"
                "promise" -> "📅 Promised ${st.date?.let(::short) ?: ""}"
                "unreachable" -> "❌ Not reachable"
                else -> null
            }
        val due =
            if (p.fup < l.month) {
                val days = ChronoUnit.DAYS.between(today, p.graceEnd())
                if (days >= 0) "Grace ends in $days days (${short(p.graceEnd())})" else "Grace ended ${-days} days ago"
            } else "Due ${short(p.dueOn(l.month))}"
        val flag =
            when (p.flag) {
                "FY" -> "First year"
                "ST" -> "2nd/3rd year"
                "MT" -> "Matures after this due"
                "LP" -> "Last premium"
                else -> null
            }
        return listOfNotNull(status, due, flag).joinToString(" · ")
    }

    private fun insights(l: DueList) {
        val paid = l.policies.filter { !data.unpaid(it) }
        fun title(t: String) = text(body, t, 20f).apply { typeface = serif }

        title("Collected this month")
        text(body, "${rupees(paid.sumOf { it.total })} of ${rupees(l.premium)}", 22f, bold = true)
        bar(body, "", paid.sumOf { it.total }.toDouble() / l.premium, "${paid.size}/${l.policies.size}")
        text(body, "Commission ${rupees(paid.sumOf { it.com })} of ${rupees(l.commission)} · marked paid in PremDue", 12f, muted)
        space(n = 18)

        title("Lapse risk by months behind")
        val overdue = l.policies.filter { it.fup < l.month && data.unpaid(it) }
        val behind = overdue.groupBy { ChronoUnit.MONTHS.between(it.fup, l.month).let { m -> if (m <= 1) "1 month" else if (m <= 3) "2–3 months" else "4+ months" } }
        val maxOver = behind.values.maxOfOrNull { g -> g.sumOf { it.total } }?.toDouble() ?: 1.0
        listOf("1 month", "2–3 months", "4+ months").forEach { k ->
            val g = behind[k].orEmpty()
            bar(body, k, g.sumOf { it.total } / maxOver, "${g.size} · ${rupees(g.sumOf { it.total })}")
        }
        text(body, "Commission at risk ${rupees(overdue.sumOf { it.com })}", 12f, muted)
        space(n = 18)

        title("Due by week")
        val weeks = l.policies.filter { data.unpaid(it) }.groupBy { (it.dueOn(l.month).dayOfMonth - 1) / 7 }
        val maxWeek = weeks.values.maxOfOrNull { g -> g.sumOf { it.total } }?.toDouble() ?: 1.0
        (0..4).forEach { w ->
            val g = weeks[w].orEmpty()
            val end = if (w == 4) l.month.lengthOfMonth() else w * 7 + 7
            bar(body, "${w * 7 + 1}–$end", g.sumOf { it.total } / maxWeek, "${g.size} · ${rupees(g.sumOf { it.total })}")
        }
        space(n = 18)

        title("Policy mix")
        val mix = l.policies.groupBy { l.section(it) }
        Section.entries.forEach { s ->
            val g = mix[s].orEmpty()
            bar(body, s.title.substringAfter(" ").substringBefore(" /"), g.size.toDouble() / l.policies.size, "${g.size} · ${rupees(g.sumOf { it.com })} com")
        }
        space(n = 18)

        title("Biggest premiums")
        l.policies.sortedByDescending { it.total }.take(5).forEach { p ->
            val r = row(body)
            text(r, p.name, 14f).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            text(r, rupees(p.total), 14f, bold = true).layoutParams = LinearLayout.LayoutParams(-2, -2)
        }

        if (data.history.size >= 2) {
            space(n = 18)
            title("Month to month")
            val maxP = data.history.maxOf { it.premium }.toDouble()
            data.history.takeLast(6).forEach { h ->
                bar(body, h.month.format(DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH)), h.premium / maxP, "${h.policies} · ${rupees(h.premium)}")
                if (h.closedFromPrev + h.carriedFromPrev > 0)
                    text(body, "From previous list: ${h.closedFromPrev} paid/closed (${rupees(h.closedPremium)}), ${h.carriedFromPrev} still due", 12f, muted)
            }
        }
    }

    /** One horizontal bar: label, track filled to [fraction], value. */
    private fun bar(parent: LinearLayout, label: String, fraction: Double, value: String) {
        val f = (if (fraction.isNaN()) 0.0 else fraction).coerceIn(0.0, 1.0).toFloat() // 0/0 when a list or bucket totals zero
        val r = row(parent).apply { setPadding(0, 0, 0, dp(10)) }
        if (label.isNotEmpty()) text(r, label, 12f, muted).layoutParams = LinearLayout.LayoutParams(dp(84), -2)
        val track = LinearLayout(this).apply { background = shape(surface, 5) }
        r.addView(track, LinearLayout.LayoutParams(0, dp(10), 1f))
        track.addView(View(this).apply { background = shape(ink, 5) }, LinearLayout.LayoutParams(0, -1, f))
        track.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f - f))
        text(r, value, 12f).apply { gravity = Gravity.END }.layoutParams = LinearLayout.LayoutParams(dp(132), -2)
    }

    // ---------- Reaching customers ----------

    private fun reach(p: Policy, how: Int) {
        if (data.links[p.no] == null) linkContact(p, how) else open(p, how)
    }

    /** Opens the dialer, SMS app or WhatsApp with the number filled in; SMS text is also put on the clipboard. */
    private fun open(p: Policy, how: Int) {
        val (num, cname) = data.links[p.no] ?: return
        val l = data.list ?: return
        val msg = message(data.lang, cname.ifBlank { p.name }, p, l)
        val i =
            when (how) {
                CALL -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:+$num"))
                SMS -> {
                    getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Reminder", msg))
                    if (Build.VERSION.SDK_INT < 33) toast("Message copied. Paste it if the SMS box is empty.")
                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:+$num")).putExtra("sms_body", msg)
                }
                else -> Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$num?text=" + Uri.encode(msg)))
            }
        try {
            startActivity(i)
            awaiting = p
        } catch (e: ActivityNotFoundException) {
            inQueue = false
            toast(when (how) { CALL -> "No phone app found."; SMS -> "No SMS app found."; else -> "WhatsApp is not installed." })
        }
    }

    private fun sendAll(items: List<Policy>) {
        val day = LocalDate.now()
        if (doneDay != day) {
            done.clear()
            doneDay = day
        }
        queue.clear()
        queue.addAll(items.filter { it.no !in done })
        if (queue.isEmpty()) return toast("All of today's reminders are done.")
        inQueue = true
        next()
    }

    private fun next() {
        val p = queue.removeFirstOrNull()
        if (p == null) {
            inQueue = false
            return toast("All of today's reminders are done.")
        }
        reach(p, SMS)
    }

    /** The bottom sheet after a message or call: Paid · Will pay on · Not reachable. */
    private fun outcome(p: Policy) {
        val queued = inQueue
        val opts = mutableListOf("✅  Paid", "📅  Will pay on…", "❌  Not reachable")
        opts += if (queued) listOf("Skip →", "Stop reminders") else listOf("Clear status", "Change number")
        fun set(s: Status?) {
            if (s == null) {
                data.status.remove(p.no)
                done -= p.no // cleared: include again in "Send all reminders"
            } else {
                data.status[p.no] = s
                done += p.no
            }
            save()
            render()
            if (queued) next()
        }
        AlertDialog.Builder(this)
            .setTitle("${p.name} · ${rupees(p.total)}")
            .setItems(opts.toTypedArray()) { _, i ->
                when (i) {
                    0 -> set(Status("paid", LocalDate.now()))
                    1 -> {
                        val t = LocalDate.now().plusDays(1)
                        DatePickerDialog(this, { _, y, m, d -> set(Status("promise", LocalDate.of(y, m + 1, d))) }, t.year, t.monthValue - 1, t.dayOfMonth)
                            .apply { setOnCancelListener { if (queued) next() } }
                            .show()
                    }
                    2 -> set(Status("unreachable", LocalDate.now()))
                    3 -> if (queued) { done += p.no; next() } else set(null)
                    4 ->
                        if (queued) inQueue = false
                        else linkContact(p, -1)
                }
            }
            .setOnCancelListener { inQueue = false }
            .show()
    }

    // ---------- Linking a phone number once per customer ----------

    /** Asks for the customer's number, saves it, then opens [how] (CALL/SMS/WHATSAPP; -1 only saves). */
    private fun linkContact(p: Policy, how: Int) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this)
                .setTitle("Find ${p.name}'s number")
                .setMessage("PremDue can look through this phone's contacts to suggest the right number. Contacts are read only on this phone and never leave it.")
                .setPositiveButton("Allow") { _, _ ->
                    pendingLink = p to how
                    requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
                }
                .setNeutralButton("Pick contact") { _, _ -> pickContact(p, how) }
                .setNegativeButton("Type number") { _, _ -> typeNumber(p, how) }
                .setOnCancelListener { inQueue = false }
                .show()
            return
        }
        val sugg = suggestions(p)
        AlertDialog.Builder(this)
            .setTitle("Number for ${p.name}")
            .setItems((sugg.map { "${it.first}\n${it.second}" } + "Pick from contacts" + "Type number").toTypedArray()) { _, i ->
                when {
                    i < sugg.size -> setLink(p, sugg[i].second, sugg[i].first, how)
                    i == sugg.size -> pickContact(p, how)
                    else -> typeNumber(p, how)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> inQueue = false }
            .setOnCancelListener { inQueue = false }
            .show()
    }

    /** Matches LIC's short names ("MORE KB", "S.J.CHATE S.J.C") to contacts: surname words, then initials. */
    private fun suggestions(p: Policy): List<Pair<String, String>> {
        val all =
            contacts
                ?: (runCatching {
                    contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null)?.use { c ->
                        buildList { while (c.moveToNext()) add((c.getString(0) ?: "") to (c.getString(1) ?: "")) }
                    }
                }.getOrNull() ?: emptyList()).also { contacts = it }
        val tokens = p.name.uppercase().split(Regex("[^A-Z]+")).filter { it.isNotEmpty() }
        val words = tokens.filter { it.length >= 3 }
        val initials = tokens.filter { it.length <= 2 }.flatMap { it.toList() }.toSet()
        return all
            .map { (name, num) ->
                val ct = name.uppercase().split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
                var score = if (name.contains(p.no)) 100 else 0
                words.forEach { w -> score += if (w in ct) 3 else if (ct.any { it.length >= 3 && (it.startsWith(w) || w.startsWith(it)) }) 2 else 0 }
                if (score >= 2) score += initials.count { i -> ct.any { it.first() == i } }
                Triple(name, num, score)
            }
            .filter { it.third >= 2 && normalize(it.second) != null }
            .sortedByDescending { it.third }
            .distinctBy { normalize(it.second) }
            .take(10)
            .map { it.first to it.second }
    }

    private fun pickContact(p: Policy, how: Int) {
        pendingLink = p to how
        try {
            startActivityForResult(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI), REQ_PICK_CONTACT)
        } catch (e: ActivityNotFoundException) {
            pendingLink = null
            typeNumber(p, how)
        }
    }

    private fun typeNumber(p: Policy, how: Int) {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_PHONE; hint = "10-digit mobile number" }
        AlertDialog.Builder(this)
            .setTitle("Number for ${p.name}")
            .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) })
            .setPositiveButton("Save") { _, _ -> setLink(p, input.text.toString(), "", how) }
            .setNegativeButton("Cancel") { _, _ -> inQueue = false }
            .setOnCancelListener { inQueue = false }
            .show()
    }

    private fun normalize(raw: String): String? {
        var d = raw.filter { it.isDigit() }
        if (d.startsWith("00")) d = d.drop(2) // international prefix: 0091 98765 43210
        if (d.length == 11 && d.startsWith("0")) d = d.drop(1)
        if (d.length == 10) d = "91$d"
        return d.takeIf { it.length in 11..15 }
    }

    /** Saves the number for this policy and every other policy under the same name. */
    private fun setLink(p: Policy, number: String, name: String, how: Int) {
        val n = normalize(number) ?: return toast("That number doesn't look valid.").also { inQueue = false }
        data.list?.policies?.filter { it.name.equals(p.name, true) }?.forEach { data.links[it.no] = n to name }
        data.links[p.no] = n to name
        save()
        render()
        if (how >= 0) open(p, how)
    }

    // ---------- PDF import ----------

    private fun handle(i: Intent?) {
        val uri =
            when (i?.action) {
                // The typed getter is unreliable on API 33 (b/232589966); use it from 34.
                Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 34) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
                Intent.ACTION_VIEW -> i.data
                else -> null
            } ?: return
        i?.action = null
        // Only provider URIs: a file:// URI would make PremDue read paths with its own permissions.
        if (uri.scheme != "content") return info("Could not read this PDF", "Share the PDF to PremDue from your email or files app.")
        import(uri)
    }

    private fun pickPdf() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf"), REQ_PDF)
        } catch (e: ActivityNotFoundException) {
            toast("No file picker found. Share the PDF to PremDue from your email app.")
        }
    }

    /** Builds the fictional sample list as a PDF and reads it like a real one. */
    private fun trySample() {
        val f = File(cacheDir, "sample-due-list.pdf")
        runCatching { Sample.writePdf(YearMonth.now(), LocalDate.now().dayOfMonth, f) }.onFailure { return toast("Could not create the sample list.") }
        import(Uri.fromFile(f))
    }

    private fun import(uri: Uri) {
        toast("Reading due list…")
        Thread {
                // runCatching also catches OutOfMemoryError and SecurityException from a stale content URI.
                val r = runCatching {
                    PDFBoxResourceLoader.init(applicationContext)
                    val text =
                        (contentResolver.openInputStream(uri) ?: error("The file could not be opened.")).use { s ->
                            // Buffer large files in the cache dir instead of the heap.
                            PDDocument.load(s, MemoryUsageSetting.setupMixed(16L shl 20)).use { PDFTextStripper().apply { sortByPosition = true }.getText(it) }
                        }
                    DueListParser.parse(text)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    r.onSuccess { loaded(it) }.onFailure { info("Could not read this PDF", "${reason(it)}\n\nYour current list was not changed.") }
                }
            }
            .start()
    }

    private fun reason(e: Throwable): String =
        when (e) {
            is InvalidPasswordException -> "This PDF is password-protected. Open it in a PDF app, save a copy without a password, then share that copy."
            is OutOfMemoryError -> "This PDF is too large to read on this phone."
            is SecurityException, is java.io.FileNotFoundException -> "PremDue could not open this file. Share it again from your email app."
            else -> e.message ?: "The file could not be read."
        }

    private fun loaded(new: DueList) {
        val old = data.list
        if (old != null && new.month < old.month && old.agent != Sample.AGENT)
            return info("Older list", "This PDF is for ${new.month}, but ${old.month} is already loaded. Nothing was changed.")
        if (old != null)
            return AlertDialog.Builder(this)
                .setTitle("Load ${monthName(new.month)} list?")
                .setMessage("It replaces the ${monthName(old.month)} list (${new.policies.size} policies, ${rupees(new.premium)}).")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Load") { _, _ -> apply(new) }
                .show()
                .let {}
        apply(new)
    }

    private fun monthName(m: YearMonth) = m.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))

    private fun apply(new: DueList) {
        val s = data.import(new)
        save()
        tab = "Today"
        render()
        var msg = "${new.policies.size} policies · ${rupees(new.premium)}\nMatches the PDF's Grand Total ✓"
        if (s.closedFromPrev + s.carriedFromPrev > 0)
            msg += "\n\nSince the last list:\n• ${s.closedFromPrev} no longer due (paid or closed) · ${rupees(s.closedPremium)}\n• ${s.carriedFromPrev} still due"
        info("Due list for ${monthName(new.month)}", msg)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
    }

    private fun info(title: String, msg: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    // ---------- Backup ----------

    /** Opens Android's save screen; the agent picks Google Drive (or any folder). */
    private fun backup() {
        toast("Choose Google Drive, then tap Save.")
        try {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, "PremDue-backup-${LocalDate.now()}.json"),
                REQ_BACKUP,
            )
        } catch (e: ActivityNotFoundException) {
            toast("No file app found to save the backup.")
        }
    }

    private fun pickBackup() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_RESTORE)
        } catch (e: ActivityNotFoundException) {
            toast("No file app found to open the backup.")
        }
    }

    private fun writeBackup(uri: Uri) {
        val bytes = Backup.export(this, data)
        Thread {
                val r = runCatching { (contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the file.")).use { it.write(bytes) } }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    r.onSuccess { toast("Backup saved ✓") }.onFailure { info("Backup failed", it.message ?: "Could not save the backup. Try again.") }
                }
            }
            .start()
    }

    private fun readBackup(uri: Uri) {
        Thread {
                val r = runCatching {
                    Backup.parse((contentResolver.openInputStream(uri) ?: error("Could not open the file.")).use { it.readBytes() })
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    r.onFailure { info("Could not restore", it.message ?: "This file could not be read.") }
                        .onSuccess { (d, lh) ->
                            val what = d.list?.let { "${monthName(it.month)} list, ${it.policies.size} policies" } ?: "no due list"
                            AlertDialog.Builder(this)
                                .setTitle("Restore this backup?")
                                .setMessage("It has the $what, ${d.links.size} linked numbers and ${d.status.size} statuses. It replaces everything in PremDue now.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Restore") { _, _ ->
                                    Backup.restore(this, d, lh)
                                    data = d
                                    tab = "Today"
                                    render()
                                    toast("Backup restored ✓")
                                }
                                .show()
                        }
                }
            }
            .start()
    }

    @Deprecated("Platform result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, result: Intent?) {
        super.onActivityResult(requestCode, resultCode, result)
        val uri = result?.data
        if (resultCode != RESULT_OK || uri == null) {
            if (requestCode == REQ_PICK_CONTACT) {
                pendingLink = null
                inQueue = false
            }
            return
        }
        when (requestCode) {
            REQ_PDF -> import(uri)
            REQ_BACKUP -> writeBackup(uri)
            REQ_RESTORE -> readBackup(uri)
            REQ_PICK_CONTACT -> {
                val (p, how) = pendingLink ?: return
                pendingLink = null
                // The picker grants a one-time read of this row; some pickers return rows without a number.
                val picked =
                    runCatching {
                        contentResolver.query(uri, arrayOf(Phone.NUMBER, Phone.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) (c.getString(0) ?: "") to (c.getString(1) ?: "") else null
                        }
                    }.getOrNull()
                if (picked == null) {
                    toast("Could not read that contact's number. Type it instead.")
                    return typeNumber(p, how)
                }
                setLink(p, picked.first, picked.second, how)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != REQ_CONTACTS) return
        val (p, how) = pendingLink ?: return
        pendingLink = null
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) linkContact(p, how) else pickContact(p, how)
    }

    // Back from another tab returns to Today before leaving the app.
    @Deprecated("Platform back API")
    override fun onBackPressed() {
        if (tab != "Today") {
            tab = "Today"
            render()
            scroll?.scrollTo(0, 0)
        } else super.onBackPressed()
    }

    companion object {
        val TABS =
            listOf(
                "Today" to R.drawable.ic_tab_today,
                "Month" to R.drawable.ic_tab_month,
                "Insights" to R.drawable.ic_tab_insights,
                "Tools" to R.drawable.ic_tab_tools,
                "Settings" to R.drawable.ic_tab_settings,
            )
        const val REQ_PDF = 1
        const val REQ_PICK_CONTACT = 2
        const val REQ_CONTACTS = 3
        const val REQ_NOTIFY = 4
        const val REQ_BACKUP = 5
        const val REQ_RESTORE = 6
        const val CALL = 0
        const val SMS = 1
        const val WHATSAPP = 2
    }
}
