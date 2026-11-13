package com.petiht.kaye

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.ai
import com.google.firebase.ai.ondevice.DownloadStatus
import com.google.firebase.ai.ondevice.FirebaseAIOnDevice
import com.google.firebase.ai.ondevice.OnDeviceModelStatus
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/**
 * Kaye's hybrid AI engine on Android (Firebase AI Logic + Gemini Nano through ML Kit).
 * Device first. The cloud only when needed and only with what it needs:
 *  - a notebook photo goes to the cloud only after the vendor says yes,
 *  - a quick note goes with customer names and phone numbers replaced by tokens,
 *  - the month record is written from totals only.
 */
@OptIn(PublicPreviewAPI::class)
class Ai(private val context: Context) {

    // Remote Config (in-app defaults first).
    var cloudModel = "gemini-3.5-flash-lite"
    var deviceLanguages = listOf("en")
    var tolerance = 0.02
    var cloudPhotos = true
    var maxLines = 20
    var configSource = "app defaults"

    suspend fun loadRemoteConfig() {
        try {
            val rc = Firebase.remoteConfig
            rc.setConfigSettingsAsync(remoteConfigSettings { minimumFetchIntervalInSeconds = 1800 }).await()
            rc.setDefaultsAsync(mapOf(
                "kaye_cloud_model" to cloudModel, "kaye_device_languages_android" to "en", "kaye_sum_tolerance" to "0.02",
                "kaye_cloud_photos_allowed" to "true", "kaye_max_lines" to "20",
            )).await()
            rc.fetchAndActivate().await()
            cloudModel = rc.getString("kaye_cloud_model").ifBlank { cloudModel }
            deviceLanguages = rc.getString("kaye_device_languages_android").split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("en") }
            rc.getDouble("kaye_sum_tolerance").takeIf { it > 0 && it < 0.2 }?.let { tolerance = it }
            cloudPhotos = rc.getBoolean("kaye_cloud_photos_allowed")
            rc.getLong("kaye_max_lines").toInt().takeIf { it in 5..40 }?.let { maxLines = it }
            configSource = "Firebase Remote Config"
        } catch (e: Exception) {
            configSource = "app defaults (offline)"
        }
    }

    suspend fun modelStatus(): String = try {
        when (FirebaseAIOnDevice.checkStatus()) {
            OnDeviceModelStatus.AVAILABLE -> "available"
            OnDeviceModelStatus.DOWNLOADABLE -> "downloadable"
            OnDeviceModelStatus.DOWNLOADING -> "downloading"
            else -> "unavailable"
        }
    } catch (e: Throwable) {
        "unavailable"
    }

    fun download(): Flow<DownloadStatus> = FirebaseAIOnDevice.download()

    fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private val lineSchema = Schema.obj(
        mapOf(
            "kind" to Schema.enumeration(KINDS), "item" to Schema.string(), "qty" to Schema.double(),
            "unit_price" to Schema.double(), "amount" to Schema.double(), "customer" to Schema.string(),
        ),
        optionalProperties = listOf("qty", "unit_price", "customer"),
    )
    private val linesSchema = Schema.obj(
        mapOf("readable" to Schema.boolean(), "total_written" to Schema.double(), "lines" to Schema.array(lineSchema)),
        optionalProperties = listOf("total_written"),
    )

    private fun model(mode: InferenceMode, json: Boolean): GenerativeModel =
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = cloudModel,
            generationConfig = generationConfig {
                if (json) {
                    responseMimeType = "application/json"
                    responseSchema = linesSchema
                }
                temperature = 0.1f
                maxOutputTokens = 1200
            },
            // Gemini Nano on Android is validated for short outputs (about 256 tokens).
            onDeviceConfig = OnDeviceConfig(mode = mode, maxOutputTokens = 256, temperature = 0.1f),
        )

    private data class Answer(val text: String, val source: String, val ms: Long)

    private suspend fun run(mode: InferenceMode, json: Boolean, feature: String, parts: com.google.firebase.ai.type.Content.Builder.() -> Unit): Answer {
        val t0 = System.currentTimeMillis()
        val res = withTimeout(90_000) { model(mode, json).generateContent(content { parts() }) }
        val ms = System.currentTimeMillis() - t0
        val src = if (res.inferenceSource == InferenceSource.ON_DEVICE) "on_device" else "in_cloud"
        runCatching {
            Firebase.analytics.logEvent("ai_call") {
                param("feature", feature); param("source", src); param("ms", ms); param("mode", mode.toString())
            }
        }
        return Answer(res.text ?: "", src, ms)
    }

    // ---------- lines: parsing + arithmetic check ----------

    data class Check(val ok: Boolean, val issues: List<String>, val sum: Double, val total: Double?)
    data class Read(val lines: MutableList<Line>, val totalWritten: Double?, val source: String, val ms: Long, val trace: List<String>, val hidden: Int = 0)

    fun check(lines: List<Line>, totalWritten: Double?): Check {
        val issues = mutableListOf<String>()
        for (l in lines) {
            val q = l.qty
            val p = l.unitPrice
            if (q != null && p != null && q > 0 && p > 0) {
                val expect = q * p
                if (abs(expect - l.amount) > max(1.0, tolerance * l.amount)) issues += "“${l.item}”: ${fmt(q)} × ${fmt(p)} = ${fmt(expect)}, but the line says ${fmt(l.amount)}."
            }
        }
        val pool = lines.filter { it.kind == "sale" }.ifEmpty { lines }
        val sum = pool.sumOf { it.amount }
        if (totalWritten != null && totalWritten > 0 && abs(sum - totalWritten) > max(1.0, tolerance * totalWritten)) {
            issues += "The lines add up to ${fmt(sum)}, but the page total says ${fmt(totalWritten)}."
        }
        if (lines.isEmpty()) issues += "No money lines were found."
        return Check(issues.isEmpty(), issues, sum, totalWritten)
    }

    /** Compact on-device format: one "kind|item|qty|unit_price|amount|customer" per line, plus "TOTAL|n". */
    private fun parsePipes(text: String): Pair<List<Line>, Double?> {
        var total: Double? = null
        val lines = mutableListOf<Line>()
        for (raw in text.lines()) {
            val p = raw.trim().trim('`', '-', '*', ' ').split('|').map { it.trim() }
            if (p.isEmpty()) continue
            if (p[0].equals("TOTAL", true)) { total = Rules.toNumber(p.getOrNull(1)?.filter { it.isDigit() || it == '.' || it == ',' }); continue }
            val kind = p[0].lowercase()
            if (kind !in KINDS || p.size < 5) continue
            val amount = num(p[4]) ?: continue
            lines += Line(kind, cleanItem(p[1]), num(p[2]), num(p[3]), amount, p.getOrNull(5)?.takeIf { it.isNotBlank() && it != "-" })
        }
        return splitTotal(lines.take(maxLines), total)
    }

    // Small models sometimes list the page's "Total" row as a sale. A total is never a line.
    private val totalRe = Regex("""^\s*(grand\s+)?(total|tot[aà]l|totalman|sous[- ]total|subtotal|sum|som|suma)\b""", RegexOption.IGNORE_CASE)
    private fun splitTotal(lines: List<Line>, total: Double?): Pair<List<Line>, Double?> {
        val totals = lines.filter { totalRe.containsMatchIn(it.item) }
        return lines.filterNot { totalRe.containsMatchIn(it.item) } to (total ?: totals.lastOrNull()?.amount)
    }

    private fun parseJson(text: String): Pair<List<Line>, Double?> {
        val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val j = JSONObject(t.substring(t.indexOf('{'), t.lastIndexOf('}') + 1))
        val arr = j.optJSONArray("lines")
        val lines = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val o = arr!!.getJSONObject(i)
            val amount = o.optDouble("amount").takeIf { !it.isNaN() && it > 0 } ?: return@mapNotNull null
            Line(
                o.optString("kind").takeIf { it in KINDS } ?: "sale", cleanItem(o.optString("item")),
                o.optDouble("qty").takeIf { !it.isNaN() && it > 0 }, o.optDouble("unit_price").takeIf { !it.isNaN() && it > 0 },
                r(amount), o.optString("customer").takeIf { it.isNotBlank() },
            )
        }
        val total = j.optDouble("total_written").takeIf { !it.isNaN() && it > 0 }
        return splitTotal(lines.take(maxLines), total)
    }

    /** "Rice 3 cups x 250 = 750" -> "Rice": the numbers already live in qty / unit price / amount. */
    private fun cleanItem(raw: String): String =
        raw.replace(Regex("""\s*=\s*[\d.,]+\s*$"""), "")
            .replace(Regex("""\s+\d+(?:[.,]\d+)?\s*(?:\p{L}+\s*)?[x×*]\s*[\d.,]+.*$""", RegexOption.IGNORE_CASE), "")
            .trim().take(80).ifBlank { "Item" }

    /**
     * If the sales alone don't match the page total but every sale+cost line together does, the small model
     * mislabelled some lines: count them as sales. Returns a note for the trace.
     */
    private fun reconcileKinds(lines: List<Line>, total: Double?): String? {
        if (total == null || total <= 0) return null
        val money = lines.filter { it.kind == "sale" || it.kind == "expense" }
        val sales = money.filter { it.kind == "sale" }
        fun close(a: Double) = abs(a - total) <= max(1.0, tolerance * total)
        if (sales.isNotEmpty() && sales.size < money.size && sales.size * 2 >= money.size &&
            !close(sales.sumOf { it.amount }) && close(money.sumOf { it.amount })) {
            val n = money.size - sales.size
            money.forEach { it.kind = "sale" }
            return "$n line(s) were read as costs, but all lines together match the page total, so they were counted as sales. Check before saving."
        }
        return null
    }

    private fun num(s: String?): Double? = s?.let { Rules.toNumber(it.filter { c -> c.isDigit() || c == '.' || c == ',' }) }?.takeIf { it > 0 }

    // ---------- 1. Read a notebook page or receipt (photo) ----------

    private val pageRules = """
        - kind: sale (money received for goods), expense (money spent: stock, transport, fees), credit (a customer took goods and still owes), payment (a customer paid back).
        - Headings help: Lavant/Ventes/Sales = sale; Depans/Dépenses/Achte = expense; Kredi/Crédit/Dwe = credit; Peye/Remboursé = payment.
        - item as written (keep Kreyòl or French words). customer only for credit and payment lines.
        - Copy numbers exactly as written, even if the arithmetic looks wrong. Do not invent lines.
    """.trimIndent()

    private fun devicePagePrompt() = """
        You read a photo of a market vendor's handwritten notebook page or a receipt.
        Write one line per money entry, at most $maxLines, in this exact format:
        kind|item|qty|unit_price|amount|customer
        Leave a field empty if it is not written. Then one line: TOTAL|<total written on the page, or empty>.
        $pageRules
        Example:
        sale|Diri|4|250|1000|
        credit|Credit|||500|Madan Pòl
        TOTAL|1900
    """.trimIndent()

    private fun cloudPagePrompt() = """
        You read a photo of a market vendor's handwritten notebook page or a paper receipt.
        List every money line, at most $maxLines, as JSON: readable, total_written (if a total is written), lines[kind,item,qty,unit_price,amount,customer].
        $pageRules
        readable is false if this is not a notebook page or receipt, or it is too blurry.
    """.trimIndent()

    suspend fun readPage(photo: Bitmap, confirmCloud: suspend (String) -> Boolean): Read {
        val t0 = System.currentTimeMillis()
        val trace = mutableListOf<String>()
        val online = online()
        val status = modelStatus()
        trace += "Gemini Nano on this phone: $status. Network: ${if (online) "online" else "offline"}."
        var lines: List<Line> = emptyList()
        var total: Double? = null
        var source = "manual"
        var ms = 0L
        var gotAnswer = false
        if (status == "available") {
            trace += "Photos stay on the phone first: ONLY_ON_DEVICE."
            try {
                val a = run(InferenceMode.ONLY_ON_DEVICE, false, "read_page") { image(photo); text(devicePagePrompt()) }
                val (l, t) = parsePipes(a.text)
                lines = l; total = t; source = a.source; ms = a.ms; gotAnswer = true
            } catch (e: Throwable) {
                trace += "Gemini Nano could not read it (${e.message ?: e.javaClass.simpleName})."
            }
        } else {
            trace += "No on-device model on this phone, so the photo cannot be read here."
        }
        reconcileKinds(lines, total)?.let { trace += it }
        var c = check(lines, total)
        if (gotAnswer) trace += if (c.ok) "Arithmetic check passed: every line and the page total add up." else "Arithmetic check failed: ${c.issues.joinToString(" ")}"

        if ((!gotAnswer || !c.ok) && online && cloudPhotos) {
            val why = when {
                !gotAnswer && status == "available" -> "Gemini Nano could not read this photo."
                !gotAnswer -> "This phone has no on-device model for photos."
                else -> "The numbers did not add up when read on this phone."
            }
            if (confirmCloud(why)) {
                trace += "You agreed to send this one photo to Gemini in the cloud (ONLY_IN_CLOUD, $cloudModel)."
                try {
                    val a = run(InferenceMode.ONLY_IN_CLOUD, true, "read_page") { image(photo); text(cloudPagePrompt()) }
                    val (l, t) = parseJson(a.text)
                    lines = l; total = t; source = a.source; ms = a.ms
                    reconcileKinds(lines, total)?.let { trace += it }
                    c = check(lines, total)
                    trace += if (c.ok) "Cloud read: the numbers add up." else "Cloud read: ${c.issues.joinToString(" ")} Check the lines before saving."
                } catch (e: Throwable) {
                    trace += "The cloud could not read it either (${e.message ?: "error"})."
                }
            } else {
                trace += "You kept the photo on this phone. Fix the lines by hand."
            }
        } else if (!gotAnswer && !online) {
            trace += "Offline: type the lines by hand; they will sync later."
        }
        return Read(lines.toMutableList(), total, source, if (ms > 0) ms else System.currentTimeMillis() - t0, trace)
    }

    // ---------- 2. Quick note ----------

    suspend fun readNote(text: String, lang: String, known: List<String>): Read {
        val t0 = System.currentTimeMillis()
        val trace = mutableListOf<String>()
        val rules = Rules.parse(text)
        trace += "Offline rules found ${rules.lines.size} line(s) instantly."
        val online = online()
        val status = modelStatus()
        trace += "Gemini Nano on this phone: $status. Network: ${if (online) "online" else "offline"}."
        val langOk = lang in deviceLanguages
        val langName = LANG_NAMES[lang] ?: "English"

        try {
            if (status == "available" && langOk) {
                trace += "$langName note and Gemini Nano is ready: ONLY_ON_DEVICE. Nothing leaves this phone."
                val a = run(InferenceMode.ONLY_ON_DEVICE, false, "read_note") {
                    text("A market vendor wrote this note in $langName. Write one line per money entry, at most $maxLines, as kind|item|qty|unit_price|amount|customer (kind: sale, expense, credit = customer owes, payment = customer paid back; \"each\" means unit price; amount is the line total; empty fields allowed). No other text.\nNote: \"\"\"$text\"\"\"")
                }
                val (l, _) = parsePipes(a.text)
                if (l.isNotEmpty()) return Read(l.toMutableList(), null, a.source, a.ms, trace)
                trace += "Gemini Nano found no lines; using the offline rules."
            } else if (online) {
                val h = Rules.hideNames(text, known + rules.names)
                trace += if (status == "available") "Gemini Nano on this phone is validated for ${deviceLanguages.joinToString()}, not $langName, so this note goes to the cloud." else "No on-device model on this phone, so this note goes to the cloud."
                trace += "Before sending, ${h.count} name(s) and phone number(s) were replaced on this phone (e.g. “C1”)."
                val a = run(InferenceMode.ONLY_IN_CLOUD, true, "read_note") {
                    text("A market vendor wrote this note about today's business, in $langName. Turn it into notebook lines, at most $maxLines, as JSON (readable, lines[kind,item,qty,unit_price,amount,customer]). kind: sale, expense, credit (customer owes), payment (customer paid back). Customer names appear as tokens like C1: copy them exactly into customer. For credit and payment lines, item is \"Credit\" or \"Payment\". qty and unit_price only if given (\"each\", \"chak\", \"chacun\", \"cada\" = unit price). amount is the line total. Do not invent lines.\nNote: \"\"\"${h.text}\"\"\"")
                }
                val (l, _) = parseJson(a.text)
                val restored = l.map { it.copy(customer = Rules.restore(it.customer, h.map), item = Rules.restore(it.item, h.map) ?: it.item) }
                if (restored.isNotEmpty()) {
                    trace += "Names were put back on this phone."
                    return Read(restored.toMutableList(), null, a.source, a.ms, trace, h.count)
                }
                trace += "The AI found no lines; using the offline rules."
            } else {
                trace += "Offline and no on-device model for $langName: the offline rules answer."
            }
        } catch (e: Throwable) {
            trace += "The AI did not answer (${e.message ?: e.javaClass.simpleName}); using the offline rules."
        }
        return Read(rules.lines.toMutableList(), null, "rules", System.currentTimeMillis() - t0, trace)
    }

    // ---------- 3. Month record (totals only) ----------

    data class Written(val text: String, val highlights: List<String>, val source: String, val ms: Long, val trace: List<String>)

    suspend fun writeRecord(t: Totals, lang: String): Written {
        val trace = mutableListOf("Built from totals only: no customer names, no single entries.")
        val langName = LANG_NAMES[lang] ?: "English"
        val status = modelStatus()
        val online = online()
        val mode = when {
            status == "available" && lang in deviceLanguages -> InferenceMode.PREFER_ON_DEVICE.also { trace += "Gemini Nano is ready: PREFER_ON_DEVICE." }
            online -> InferenceMode.PREFER_IN_CLOUD.also { trace += "${if (status == "available") "Gemini Nano is not validated for $langName" else "No on-device model"}: PREFER_IN_CLOUD with the totals only." }
            else -> null
        }
        if (mode != null) {
            try {
                val a = run(mode, false, "month_record") {
                    text("You help a market vendor show their monthly business record to a microfinance officer. Write in $langName, plain and factual, 3 to 5 sentences: sales, costs, profit, the trend across the weeks, best-selling items and money still owed by customers. Then up to 3 short highlights, each on its own line starting with \"- \". No financial advice, no loan recommendations. Use only these totals (currency ${t.currency}):\n${t.toPromptJson()}")
                }
                val body = a.text.lines().filter { !it.trim().startsWith("- ") }.joinToString("\n").trim()
                val hl = a.text.lines().filter { it.trim().startsWith("- ") }.map { it.trim().removePrefix("- ").take(160) }.take(4)
                if (body.isNotBlank()) return Written(body.take(1800), hl, a.source, a.ms, trace)
            } catch (e: Throwable) {
                trace += "The AI did not answer (${e.message ?: "error"})."
            }
        } else trace += "Offline and no on-device model: a plain template summary."
        val c = t.currency
        return Written(
            "In ${t.month}, sales were ${Ledger.money(t.sales, c)} and costs ${Ledger.money(t.expenses, c)}, so profit was ${Ledger.money(t.profit, c)}. Sales were recorded on ${t.daysRecorded} day(s). Customers still owe ${Ledger.money(t.owed, c)}.",
            emptyList(), "rules", 0, trace,
        )
    }

    // ---------- 4. Polite reminder ----------

    suspend fun writeReminder(name: String, amount: Double, currency: String, lang: String): Written {
        val trace = mutableListOf<String>()
        val langName = LANG_NAMES[lang] ?: "Haitian Creole"
        val status = modelStatus()
        val mode = when {
            status == "available" && lang in deviceLanguages -> InferenceMode.ONLY_ON_DEVICE.also { trace += "Written on this phone (ONLY_ON_DEVICE)." }
            online() -> InferenceMode.ONLY_IN_CLOUD.also { trace += "Written in the cloud. The customer's name was never sent: the AI only saw {NAME}, filled in on this phone." }
            else -> null
        }
        if (mode != null) {
            try {
                val a = run(mode, false, "reminder") {
                    text("Write one short, warm, respectful message (max 40 words) from a market vendor to a regular customer called {NAME}, reminding them they owe ${Ledger.money(amount, currency)}. Write it in $langName. Keep the token {NAME} exactly. No threats, no interest, no pressure. Only the message.")
                }
                val msg = a.text.trim().trim('"').replace("{NAME}", name).take(400)
                if (msg.isNotBlank()) return Written(msg, emptyList(), a.source, a.ms, trace)
            } catch (e: Throwable) {
                trace += "The AI did not answer (${e.message ?: "error"})."
            }
        }
        trace += "Template message used."
        val a = Ledger.money(amount, currency)
        val msg = when (lang) {
            "ht" -> "Bonjou $name, se mwen menm nan mache a. Ti rapèl: ou gen $a ki rete pou peye. Lè ou kapab, pa gen pwoblèm. Mèsi anpil!"
            "fr" -> "Bonjour $name, petit rappel amical : il reste $a à régler. Quand vous pourrez. Merci beaucoup !"
            "es" -> "Hola $name, un recordatorio amable: quedan $a por pagar. Cuando pueda. ¡Muchas gracias!"
            else -> "Hello $name, a friendly reminder: $a is still open. Whenever you can. Thank you!"
        }
        return Written(msg, emptyList(), "rules", 0, trace)
    }

    companion object {
        val KINDS = listOf("sale", "expense", "credit", "payment")
        val LANG_NAMES = mapOf("en" to "English", "ht" to "Haitian Creole (Kreyòl)", "fr" to "French", "es" to "Spanish")
        fun fmt(n: Double): String = Ledger.money(n, "").trim()
    }
}
