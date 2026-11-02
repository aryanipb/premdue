package com.aryan.premdue

import kotlin.math.pow

object Fin {
    /**
     * Yearly rate r where sum(flows[t] / (1+r)^t) = 0, or null when there is none in −99%..+1e8%.
     * Flows can change sign many times (benefits during the paying years), so there can be several such
     * rates; the one returned is the first met walking out from 0 towards the sign of the net gain (NPV at 0).
     */
    fun irr(flows: DoubleArray): Double? {
        if (flows.none { it > 0 } || flows.none { it < 0 }) return null
        fun npv(r: Double) = flows.foldIndexed(0.0) { t, acc, f -> acc + f / (1 + r).pow(t) }
        fun crossed(a: Double, b: Double) = b == 0.0 || (a > 0) != (b > 0)
        var lo = 0.0
        var fLo = npv(lo)
        if (!fLo.isFinite()) return null
        if (fLo == 0.0) return 0.0
        // Walk away from 0 towards the sign of the net gain until NPV crosses 0: 5% steps, doubling above 100%.
        val up = fLo > 0
        var hi: Double
        while (true) {
            hi = if (up) (if (lo < 1) lo + 0.05 else lo * 2) else maxOf(lo - 0.05, -0.99)
            val fHi = npv(hi)
            if (fHi.isNaN()) return null
            if (crossed(fLo, fHi)) break
            if (hi <= -0.99 || hi >= 1e6) return null
            lo = hi
            fLo = fHi
        }
        repeat(100) {
            val mid = (lo + hi) / 2
            val fMid = npv(mid)
            if (crossed(fLo, fMid)) hi = mid else { lo = mid; fLo = fMid }
        }
        return (lo + hi) / 2
    }
}

/**
 * One plan exactly as the agent copies it from LIC's official benefit illustration.
 * [premium] is the total premium for one policy year; [benefits] are (policy year, amount received).
 */
data class PlanInput(
    val name: String,
    val sumAssured: Double,
    val premium: Double,
    val payYears: Int,
    val term: Int,
    val deathCover: String,
    val benefits: List<Pair<Int, Double>>,
)

/** Cash flow by time t in years: premiums at the start of years 1..payYears (t = 0..payYears-1), benefits at the end of their year (t = year). */
fun PlanInput.flows(): DoubleArray =
    DoubleArray(term + 1).also { f ->
        for (t in 0 until payYears) f[t] -= premium
        benefits.forEach { (y, a) -> if (y in 1..term) f[y] += a }
    }

val PlanInput.paid get() = premium * payYears
val PlanInput.received get() = benefits.sumOf { it.second }

/** Effective yearly return of the plan's cash flows. */
fun PlanInput.yield(): Double? = Fin.irr(flows())

data class Presentation(val customer: String, val age: Int, val plans: List<PlanInput>)

data class Letterhead(val name: String, val phone: String, val line: String)
