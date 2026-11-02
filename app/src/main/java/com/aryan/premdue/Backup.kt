package com.aryan.premdue

import android.content.Context
import org.json.JSONObject

/** Manual backup file: due list, statuses, contact links, history, language and the presentation letterhead. Never the licence. */
object Backup {
    private const val MAX_BYTES = 20 shl 20

    fun export(c: Context, d: Data): ByteArray =
        JSONObject()
            .put("app", "PremDue")
            .put("version", 1)
            .put("data", d.toJson())
            .put("letterhead", JSONObject(c.getSharedPreferences("presentation", Context.MODE_PRIVATE).all.mapValues { it.value.toString() }))
            .toString()
            .toByteArray()

    /** Validates a backup file; throws with a plain message if it is not one. */
    fun parse(bytes: ByteArray): Pair<Data, Map<String, String>> {
        require(bytes.size <= MAX_BYTES) { "This file is too large to be a PremDue backup." }
        val o = runCatching { JSONObject(String(bytes)) }.getOrNull()
        require(o != null && o.optString("app") == "PremDue") { "This is not a PremDue backup file." }
        val d = runCatching { Data.fromJson(o.getJSONObject("data")) }.getOrElse { error("This backup file is damaged.") }
        val lh = o.optJSONObject("letterhead")
        return d to (lh?.keys()?.asSequence()?.associateWith { lh.getString(it) } ?: emptyMap())
    }

    /** Applies a parsed backup: replaces the stored data and the letterhead. */
    fun restore(c: Context, d: Data, letterhead: Map<String, String>) {
        Store.save(c, d)
        c.getSharedPreferences("presentation", Context.MODE_PRIVATE).edit().clear().apply { letterhead.forEach { (k, v) -> putString(k, v) } }.apply()
    }
}
