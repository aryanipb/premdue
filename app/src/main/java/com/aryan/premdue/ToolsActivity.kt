package com.aryan.premdue

import android.os.Bundle

data class Tool(val key: String, val title: String, val subtitle: String, val render: ToolsActivity.() -> Unit)

/** Hosts one tool per launch; the tool renders itself into [body]. */
class ToolsActivity : UiActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tool = TOOLS.firstOrNull { it.key == intent.getStringExtra("tool") } ?: return finish()
        shell(tool.title)
        tool.render(this)
    }

    companion object {
        val TOOLS =
            listOf(
                Tool("present", "Plan presentation", "LIC illustration → PDF to share") { presentation() },
                Tool("yield", "Policy return", "What a policy really earns per year") { yieldCalc() },
                Tool("hlv", "Human life value", "How much cover a family needs") { hlv() },
                Tool("retire", "Retirement", "Corpus and monthly saving needed") { retirement() },
                Tool("goal", "Child goal", "Education or marriage fund") { goal() },
                Tool("inflation", "Inflation", "What money will be worth") { inflation() },
                Tool("growth", "Growth", "Lump sum and yearly saving") { growth() },
                Tool("emi", "Loan EMI", "Monthly instalment and interest") { emi() },
                Tool("loansip", "Loan + SIP", "Longer loan, invest the EMI saved, finish sooner") { loanSip() },
            )
    }
}
