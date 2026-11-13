package com.petiht.kaye

import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlin.math.roundToInt

/** A saved notebook line (Firestore users/{uid}/entries). */
data class Entry(
    val id: String,
    val kind: String,
    val item: String?,
    val qty: Double?,
    val unitPrice: Double?,
    val amount: Double,
    val customerId: String?,
    val date: String,
    val method: String,
    val ai: String,
    val pending: Boolean = false,
)

data class Customer(val id: String, val name: String)
data class Balance(val id: String, val name: String, val owed: Double, val last: String)

data class Totals(
    val month: String,
    val currency: String,
    val sales: Double,
    val expenses: Double,
    val profit: Double,
    val owed: Double,
    val customersOwing: Int,
    val byWeek: List<Double>,
    val topItems: List<Pair<String, Double>>,
    val daysRecorded: Int,
    val entries: Int,
    val sources: Map<String, Int>,
) {
    val onDeviceShare: Int?
        get() {
            val ai = (sources["on_device"] ?: 0) + (sources["in_cloud"] ?: 0)
            return if (ai == 0) null else ((100.0 * (sources["on_device"] ?: 0)) / ai).roundToInt()
        }

    /** What the cloud may see for the month record: totals only, no names, no single entries. */
    fun toPromptJson(): String {
        val top = topItems.joinToString(",") { """{"item":"${it.first.replace("\"", "")}","amount":${r(it.second)}}""" }
        return """{"month":"$month","currency":"$currency","sales":${r(sales)},"expenses":${r(expenses)},"profit":${r(profit)},""" +
            """"owed":${r(owed)},"customers_owing":$customersOwing,"by_week":[${byWeek.joinToString(",") { r(it).toString() }}],""" +
            """"top_items":[$top],"days_recorded":$daysRecorded,"entries":$entries}"""
    }
}

object Ledger {
    fun today(): String = LocalDate.now().toString()
    fun month(): String = YearMonth.now().toString()
    fun range(month: String): Pair<String, String> {
        val ym = YearMonth.parse(month)
        return ym.atDay(1).toString() to ym.atEndOfMonth().toString()
    }

    fun totals(entries: List<Entry>, month: String = "", currency: String = "HTG", owed: Double = 0.0, owing: Int = 0): Totals {
        var sales = 0.0
        var expenses = 0.0
        val weeks = DoubleArray(5)
        val items = linkedMapOf<String, Pair<String, Double>>()
        val days = mutableSetOf<String>()
        val src = mutableMapOf("on_device" to 0, "in_cloud" to 0, "rules" to 0, "manual" to 0)
        for (e in entries) {
            src[e.ai] = (src[e.ai] ?: 0) + 1
            when (e.kind) {
                "sale" -> {
                    sales += e.amount
                    days += e.date
                    val d = e.date.takeLast(2).toIntOrNull() ?: 1
                    weeks[minOf(4, (d - 1) / 7)] += e.amount
                    val k = (e.item ?: "Item").lowercase()
                    items[k] = (e.item ?: "Item") to ((items[k]?.second ?: 0.0) + e.amount)
                }
                "expense" -> expenses += e.amount
            }
        }
        return Totals(
            month, currency, r(sales), r(expenses), r(sales - expenses), r(owed), owing,
            weeks.map { r(it) }, items.values.sortedByDescending { it.second }.take(5),
            days.size, entries.size, src,
        )
    }

    /** Who owes me: credit minus payments per customer. */
    fun balances(credit: List<Entry>, customers: List<Customer>): List<Balance> {
        val owed = mutableMapOf<String, Double>()
        val last = mutableMapOf<String, String>()
        for (e in credit) {
            val id = e.customerId ?: continue
            owed[id] = (owed[id] ?: 0.0) + (if (e.kind == "credit") e.amount else -e.amount)
            if (e.date > (last[id] ?: "")) last[id] = e.date
        }
        val names = customers.associate { it.id to it.name }
        return (names.keys + owed.keys).toSet().map { Balance(it, names[it] ?: "(deleted)", r(owed[it] ?: 0.0), last[it] ?: "") }
            .sortedByDescending { it.owed }
    }

    fun money(n: Double, currency: String): String {
        val f = NumberFormat.getNumberInstance(Locale.US).apply { maximumFractionDigits = if (n % 1.0 != 0.0) 2 else 0 }
        val s = f.format(n)
        return when (currency) {
            "USD" -> "$$s"
            "HTG" -> "$s G"
            else -> "$s $currency"
        }
    }
}

fun r(n: Double): Double = (n * 100).roundToInt() / 100.0
