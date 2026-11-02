package com.aryan.premdue

import android.app.*
import android.content.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/** Posts the 9 AM summary and keeps the next day's alarm scheduled (also after reboot). */
class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        schedule(c) // first, so a failure while notifying cannot break the daily chain
        // Boot or clock change after 9 AM: the alarm was lost, so post today's summary now if it hasn't been.
        if (i.action == ACTION || !LocalTime.now().isBefore(LocalTime.of(9, 0))) notify(c)
    }

    companion object {
        private const val ACTION = "com.aryan.premdue.DAILY"
        private const val CHANNEL = "daily"

        fun schedule(c: Context) {
            val now = java.time.LocalDateTime.now()
            var next = LocalDate.now().atTime(LocalTime.of(9, 0))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val pi =
                PendingIntent.getBroadcast(
                    c,
                    0,
                    Intent(c, DailyReceiver::class.java).setAction(ACTION),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            c.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pi)
        }

        private fun notify(c: Context) {
            val prefs = c.getSharedPreferences("daily", Context.MODE_PRIVATE)
            if (prefs.getString("posted", "") == LocalDate.now().toString()) return
            val d = Store.load(c)
            val list = d.list ?: return
            val today = LocalDate.now()
            val due = if (YearMonth.from(today) > list.month) emptyList() else d.today(today)
            val title: String
            val text: String
            if (YearMonth.from(today) > list.month) {
                title = "New month"
                text = "Share this month's Premium Due List PDF to PremDue."
            } else {
                if (due.isEmpty()) return
                val promised = due.count { d.status[it.no]?.let { s -> s.kind == "promise" && s.date?.isAfter(today) == false } == true }
                val lapsing = list.policies.count { d.unpaid(it) && it.fup < list.month && it.graceEnd() in today..today.plusDays(3) }
                title = "Today's premiums · ${rupees(due.sumOf { it.total })}"
                text = "${due.size} dues today · $promised promised · $lapsing close to lapsing"
            }
            val m = c.getSystemService(NotificationManager::class.java)
            m.createNotificationChannel(NotificationChannel(CHANNEL, "Daily dues", NotificationManager.IMPORTANCE_DEFAULT))
            val open =
                PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            m.notify(
                1,
                Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(
                        Notification.InboxStyle().setSummaryText(text).also { st ->
                            due.take(6).forEach { st.addLine("${it.name} · ${rupees(it.total)}") }
                            if (due.size > 6) st.addLine("+${due.size - 6} more")
                        }
                    )
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .build(),
            )
            prefs.edit().putString("posted", today.toString()).apply()
        }
    }
}
