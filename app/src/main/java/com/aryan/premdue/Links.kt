package com.aryan.premdue

import android.content.Intent
import android.net.Uri

/** Public pages and the support address. PremDue has no account and no server. */
object Links {
    const val PRIVACY = "https://finscious.github.io/premdue/privacy.html"
    const val SUPPORT = "ceo.finscious@gmail.com"
}

fun UiActivity.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { toast("No browser found.") }
}
