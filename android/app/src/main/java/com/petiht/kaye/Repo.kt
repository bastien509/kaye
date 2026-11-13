package com.petiht.kaye

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * Firestore data layer: users/{uid}/{entries|customers|reports}. Security rules allow only the owner.
 * Firestore's offline cache is on by default on Android, so every write lands on the phone first
 * and syncs when there is signal.
 */
class Repo(private val uid: String) {
    private val db = FirebaseFirestore.getInstance()
    private fun col(name: String) = db.collection("users").document(uid).collection(name)

    data class Profile(val name: String, val business: String, val currency: String, val market: String, val lang: String)

    suspend fun loadProfile(): Profile? = try {
        val s = db.collection("users").document(uid).get().await()
        if (!s.exists()) null else Profile(
            s.getString("name") ?: "", s.getString("business") ?: "", s.getString("currency") ?: "HTG",
            s.getString("market") ?: "", s.getString("lang") ?: "en",
        )
    } catch (e: Exception) {
        null
    }

    fun saveProfile(p: Profile, isNew: Boolean) {
        val data = hashMapOf<String, Any>(
            "name" to p.name.take(80), "business" to p.business.take(80), "currency" to p.currency.take(8),
            "market" to p.market.take(80), "lang" to p.lang.take(5), "updatedAt" to FieldValue.serverTimestamp(),
        )
        if (isNew) data["createdAt"] = FieldValue.serverTimestamp()
        db.collection("users").document(uid).set(data, com.google.firebase.firestore.SetOptions.merge())
    }

    private fun toEntry(d: DocumentSnapshot) = Entry(
        id = d.id, kind = d.getString("kind") ?: "sale", item = d.getString("item"),
        qty = d.getDouble("qty"), unitPrice = d.getDouble("unitPrice"), amount = d.getDouble("amount") ?: 0.0,
        customerId = d.getString("customerId"), date = d.getString("date") ?: "", method = d.getString("method") ?: "manual",
        ai = d.getString("ai") ?: "manual", pending = d.metadata.hasPendingWrites(),
    )

    fun watchMonth(month: String, onData: (List<Entry>, Boolean) -> Unit, onError: (Exception) -> Unit): ListenerRegistration {
        val (start, end) = Ledger.range(month)
        return col("entries").whereGreaterThanOrEqualTo("date", start).whereLessThanOrEqualTo("date", end)
            .orderBy("date", Query.Direction.DESCENDING)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null) { onError(err); return@addSnapshotListener }
                snap ?: return@addSnapshotListener
                onData(snap.documents.map(::toEntry), snap.metadata.hasPendingWrites())
            }
    }

    fun watchCredit(onData: (List<Entry>) -> Unit): ListenerRegistration =
        col("entries").whereIn("kind", listOf("credit", "payment")).addSnapshotListener { snap, _ ->
            snap?.let { onData(it.documents.map(::toEntry)) }
        }

    fun watchCustomers(onData: (List<Customer>) -> Unit): ListenerRegistration =
        col("customers").addSnapshotListener { snap, _ ->
            snap?.let { s -> onData(s.documents.map { Customer(it.id, it.getString("name") ?: "?") }) }
        }

    /** Finds or creates customers by name, then saves all lines in one batch (works offline). */
    fun saveLines(lines: List<Line>, date: String, method: String, ai: String, customers: List<Customer>): Int {
        val byName = customers.associate { it.name.trim().lowercase() to it.id }.toMutableMap()
        val batch = db.batch()
        var n = 0
        for (l in lines.filter { it.amount > 0 }) {
            var customerId: String? = null
            if ((l.kind == "credit" || l.kind == "payment") && !l.customer.isNullOrBlank()) {
                val key = l.customer!!.trim().lowercase()
                customerId = byName[key] ?: col("customers").document().also {
                    batch.set(it, hashMapOf("name" to l.customer!!.trim().take(80), "createdAt" to FieldValue.serverTimestamp()))
                    byName[key] = it.id
                }.id
            }
            batch.set(col("entries").document(), hashMapOf(
                "kind" to l.kind, "item" to l.item.take(120).ifBlank { null }, "amount" to r(l.amount),
                "qty" to l.qty, "unitPrice" to l.unitPrice, "customerId" to customerId, "date" to date,
                "method" to method, "ai" to ai, "note" to null, "pageId" to null, "createdAt" to FieldValue.serverTimestamp(),
            ))
            n++
        }
        batch.commit() // offline: committed locally, synced later
        return n
    }

    fun deleteEntry(id: String) {
        col("entries").document(id).delete()
    }

    fun saveReport(month: String, summary: String, highlights: List<String>, ai: String, ms: Long, t: Totals) {
        col("reports").document(month).set(hashMapOf(
            "month" to month, "summary" to summary.take(2000), "highlights" to highlights.take(8).map { it.take(200) },
            "ai" to (if (ai == "on_device" || ai == "in_cloud") ai else "rules"), "ms" to ms,
            "totals" to mapOf("sales" to t.sales, "expenses" to t.expenses, "profit" to t.profit, "owed" to t.owed),
            "createdAt" to FieldValue.serverTimestamp(),
        ))
    }

    suspend fun clear() {
        for (name in listOf("entries", "customers", "reports")) {
            val docs = col(name).get().await().documents
            docs.chunked(400).forEach { chunk -> db.batch().apply { chunk.forEach { delete(it.reference) } }.commit().await() }
        }
    }

    /** About six weeks of invented market lines, so anyone can explore. Same data shape as the web app. */
    suspend fun seedSample() {
        val people = listOf("Marie Joseph", "Madan Pòl", "Jean Baptiste", "Rose Michel", "Ti Jak")
        val goods = listOf("Diri (rice)" to 250.0, "Pwa (beans)" to 300.0, "Lwil (oil)" to 450.0, "Sik (sugar)" to 75.0, "Farin (flour)" to 1500.0, "Savon (soap)" to 60.0)
        val costs = listOf("Transport" to 250.0, "Chabon (charcoal)" to 400.0, "Stock: diri" to 4500.0, "Market fee" to 100.0)
        val batch = db.batch()
        val ids = people.map { p -> col("customers").document().also { batch.set(it, hashMapOf("name" to p, "createdAt" to FieldValue.serverTimestamp())) }.id }
        var seed = 7L
        fun rnd(): Double { seed = (seed * 9301L + 49297L) % 233280L; return seed / 233280.0 }
        val methods = listOf("photo", "photo", "text", "manual")
        val ais = listOf("on_device", "on_device", "on_device", "in_cloud", "rules")
        val now = LocalDate.now()
        for (back in 40 downTo 0) {
            val d = now.minusDays(back.toLong())
            if (d.dayOfWeek.value == 7) continue
            val date = d.toString()
            val ts = Timestamp(Date.from(d.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant()))
            val method = methods[(rnd() * methods.size).toInt()]
            val ai = if (method == "manual") "manual" else ais[(rnd() * ais.size).toInt()]
            fun put(kind: String, item: String, qty: Double?, unit: Double?, amount: Double, cid: String?, m: String = method, a: String = ai) =
                batch.set(col("entries").document(), hashMapOf(
                    "kind" to kind, "item" to item, "qty" to qty, "unitPrice" to unit, "amount" to amount, "customerId" to cid,
                    "date" to date, "method" to m, "ai" to a, "note" to null, "pageId" to null, "createdAt" to ts,
                ))
            repeat(2 + (rnd() * 3).toInt()) {
                val (item, price) = goods[(rnd() * goods.size).toInt()]
                val q = 1 + (rnd() * 4).toInt()
                put("sale", item, q.toDouble(), price, q * price, null)
            }
            if (rnd() < 0.5) costs[(rnd() * costs.size).toInt()].let { (item, amount) -> put("expense", item, null, null, amount, null) }
            if (rnd() < 0.3) put("credit", "Credit", null, null, listOf(300.0, 500.0, 750.0, 1200.0)[(rnd() * 4).toInt()], ids[(rnd() * ids.size).toInt()])
            if (rnd() < 0.2) put("payment", "Payment", null, null, 200.0, ids[(rnd() * ids.size).toInt()], "manual", "manual")
        }
        batch.commit().await()
    }
}
