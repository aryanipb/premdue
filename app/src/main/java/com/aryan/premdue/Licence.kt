package com.aryan.premdue

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Trial and purchase state. Only the Google account reaches the server; customer data never does. */
object Licence {
    const val API = "https://3v33izps4qsmehcru2h75hqkna0mwnkw.lambda-url.ap-south-1.on.aws"
    const val PRIVACY = "https://finscious.github.io/premdue/privacy.html"
    const val SUPPORT = "ceo.finscious@gmail.com"
    const val WEB_CLIENT_ID = "929034176026-jp7nk66v542136i4bmc659epjbau85t5.apps.googleusercontent.com"

    private fun prefs(c: Context) = c.getSharedPreferences("licence", Context.MODE_PRIVATE)

    private fun now() = System.currentTimeMillis() / 1000

    /** Paid, or inside the trial; a clock moved back more than a day counts as expired. */
    fun allowed(c: Context): Boolean {
        val p = prefs(c)
        if (p.getBoolean("paid", false)) return true
        val now = now()
        return now >= p.getLong("seen", 0) - 86_400 && now < p.getLong("trialEnd", 0)
    }

    fun paid(c: Context) = prefs(c).getBoolean("paid", false)

    fun email(c: Context) = prefs(c).getString("email", "") ?: ""

    fun setEmail(c: Context, email: String) = prefs(c).edit().putString("email", email).apply()

    /** Forgets this phone's sign-in; the trial and purchase stay on the server under the Google account. */
    fun signOut(c: Context) = prefs(c).edit().clear().apply()

    fun trialDaysLeft(c: Context) = ((prefs(c).getLong("trialEnd", 0) - now() + 86_399) / 86_400).coerceAtLeast(0)

    /** Calls the licence server; must run off the main thread. */
    fun call(c: Context, path: String, idToken: String): JSONObject {
        val con = URL(API + path).openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 10_000
            con.readTimeout = 15_000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json")
            con.outputStream.use { it.write(JSONObject().put("idToken", idToken).toString().toByteArray()) }
            val code = con.responseCode
            val body = (if (code < 400) con.inputStream else con.errorStream).bufferedReader().use { it.readText() }
            val j = JSONObject(body)
            if (code >= 400) error(j.optString("error", "Server error $code"))
            prefs(c).edit()
                .putBoolean("paid", j.optBoolean("paid"))
                .putLong("trialEnd", j.optLong("trialEnd"))
                .putLong("seen", maxOf(prefs(c).getLong("seen", 0), j.optLong("now", now())))
                .apply()
            return j
        } finally {
            con.disconnect()
        }
    }
}

/** Gets a fresh Google ID token (remembering the account's email), then runs [then]. */
fun UiActivity.googleToken(then: (String) -> Unit) {
    val option = GetSignInWithGoogleOption.Builder(Licence.WEB_CLIENT_ID).build()
    val req = GetCredentialRequest.Builder().addCredentialOption(option).build()
    CredentialManager.create(this).getCredentialAsync(
        this,
        req,
        CancellationSignal(),
        ContextCompat.getMainExecutor(this),
        object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
            override fun onResult(result: GetCredentialResponse) {
                val c = result.credential
                if (c is CustomCredential && c.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val g = GoogleIdTokenCredential.createFrom(c.data)
                    Licence.setEmail(this@googleToken, g.id)
                    then(g.idToken)
                } else toast("Google sign-in failed. Please try again.")
            }

            override fun onError(e: GetCredentialException) {
                toast("Google sign-in was not completed.")
            }
        },
    )
}

/** Runs a licence server call off the main thread; [done] gets the reply, [failed] runs when the server is unreachable. */
fun UiActivity.licence(path: String, token: String, failed: () -> Unit = { toast("Could not reach the server. Check your internet and try again.") }, done: (JSONObject) -> Unit) {
    Thread {
            val r = runCatching { Licence.call(applicationContext, path, token) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                r.onSuccess(done).onFailure { failed() }
            }
        }
        .start()
}

/** Signs in and opens the ₹299 Razorpay payment page; [paid] runs if the account has already paid. Returns the token for the check on return. */
fun UiActivity.pay(token: (String) -> Unit, paid: () -> Unit) = googleToken { t ->
    token(t)
    toast("Opening payment…")
    licence("/order", t) {
        if (it.optBoolean("paid")) paid()
        else it.optString("payUrl").takeIf { u -> u.startsWith("https://") }?.let(::openUrl) ?: toast("Could not start the payment. Please try again.")
    }
}

fun UiActivity.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { toast("No browser found.") }
}

/** Sign in → 7-day trial → ₹299 one-time unlock. Forwards the original launch intent once allowed. */
class GateActivity : UiActivity() {
    private var payToken: String? = null // set while the payment page is open, so returning checks automatically

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render(if (getSharedPreferences("licence", MODE_PRIVATE).contains("trialEnd")) "ended" else "start")
    }

    override fun onResume() {
        super.onResume()
        if (Licence.allowed(this)) return proceed()
        val t = payToken ?: return
        payToken = null
        licence("/status", t, failed = {}) { if (Licence.allowed(this)) paidAndProceed() }
    }

    private fun render(state: String, note: String = "") {
        shell("PremDue")
        when (state) {
            "start" -> {
                heading("Welcome", "Due lists, reminders, calculators and plan presentations for LIC agents.")
                text(body, "Start your 7-day free trial. After that, unlock PremDue for ₹299 — one time, all inclusive.", 15f)
                space()
                button(body, "Sign in with Google", true) { googleToken { check(it) } }
            }
            "ended" -> {
                heading("Your trial has ended", "Unlock PremDue for ₹299 — one time, all inclusive. No subscription.")
                button(body, "Unlock PremDue · ₹299", true) { pay({ payToken = it }) { paidAndProceed() } }
                button(body, "I've paid — check again") { googleToken { check(it) } }
            }
        }
        if (note.isNotBlank()) text(body, note, 13f)
        space(n = 18)
        text(body, "We use only your Google account email to manage your trial and purchase. Your customers' data stays on this phone and in your own Google backups; it is never sent to us.", 12f, muted)
        button(body, "Privacy policy") { openUrl(Licence.PRIVACY) }
    }

    private fun check(token: String) {
        toast("Checking…")
        licence("/status", token, failed = { render(if (Licence.allowed(this)) "start" else "ended", "Could not reach the server. Check your internet and try again.") }) {
            if (Licence.allowed(this)) {
                if (!it.optBoolean("paid")) toast("Free trial: ${Licence.trialDaysLeft(this)} days left")
                proceed()
            } else render("ended", "Payment not found yet. If you just paid, wait a minute and check again.")
        }
    }

    private fun paidAndProceed() {
        toast("PremDue unlocked. Thank you!")
        proceed()
    }

    private fun proceed() {
        val forward =
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("forward", Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra("forward")
        startActivity((forward ?: Intent()).setClass(this, MainActivity::class.java))
        finish()
    }
}
