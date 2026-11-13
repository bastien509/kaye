package com.petiht.kaye

/**
 * Offline rules: turn a short note into notebook lines with no AI and no network, and hide customer
 * names and phone numbers before any cloud call. English, French, Haitian Kreyòl and Spanish.
 * Same behaviour as client/src/rules.js on the web.
 */
object Rules {
    private const val NUM = """(\d{1,3}(?:[ ,.]\d{3})+|\d+(?:[.,]\d{1,2})?)"""
    private const val NAME = """((?:(?:Madan|Madam|Msye|Mme|Mr|Mrs|Ms|Doña|Don|Sr|Sra|Manmi|Papi|Tonton|Tant|Ti)\.?\s+)?[A-ZÀ-ÝÒ][\p{L}'-]+(?:\s+[A-ZÀ-ÝÒ][\p{L}'-]+)?)"""
    private const val OWES = "owes|owe|dwe|doit|debe|took on credit|pran kredi|a pris à crédit"
    private const val PAID = "paid back|paid|peye|remèt|a payé|a remboursé|pagó|pago|devolvió"
    private const val SOLD = "sold|sell|vann|vand|vendu|vends|vendí|vendi|vendió"
    private const val BOUGHT = "bought|buy|paid for|achte|peye pou|acheté|achat|compré|compre|pagué"
    private const val EACH = """each|apiece|chak|chacun|chacune|la pièce|cada|c/u"""
    private const val LINK = "at|for|a|à|pou|pour|por|=|:|x"
    private const val CUR = """(?:goud|gourdes?|htg|g|\$|usd|dollars?|pesos?)?"""

    private val opts = setOf(RegexOption.IGNORE_CASE)
    private val reOwes = Regex("""^$NAME\s+(?:$OWES)\s+(?:me\s+|mwen\s+|moi\s+|m\s+)?$NUM""", opts)
    private val rePaid = Regex("""^$NAME\s+(?:$PAID)\s+(?:me\s+|mwen\s+)?$NUM""", opts)
    private val reSold = Regex("""(?:$SOLD)\s+(?:(\d+)\s+)?(.+?)[,\s]+(?:(?:$LINK)\s+)?$NUM\s*$CUR\s*($EACH)?\s*$""", opts)
    private val reBought = Regex("""(?:$BOUGHT)\s+(?:(\d+)\s+)?(.+?)[,\s]+(?:(?:$LINK)\s+)?$NUM\s*$CUR\s*($EACH)?\s*$""", opts)
    private val reSplit = Regex("""\n|;|\.(?=\s|$)|,\s*(?=[A-ZÀ-ÝÒ])|\s+(?:and|epi|ak|et|y)\s+(?=[A-ZÀ-ÝÒ]|(?:sold|vann|vendu|vendí|bought|achte|acheté|compré)\b)""")
    private val rePhone = Regex("""(?:\+?509[\s-]?)?\b\d{4}[\s-]?\d{4}\b|\+?\d[\d\s().-]{8,}\d""")

    fun toNumber(s: String?): Double? {
        if (s == null) return null
        var t = s.trim().replace(Regex("""\s"""), "")
        t = if (Regex("""^\d{1,3}([.,]\d{3})+$""").matches(t)) t.replace(Regex("[.,]"), "") else t.replace(',', '.')
        return t.toDoubleOrNull()
    }

    data class Parsed(val lines: List<Line>, val names: List<String>)

    fun parse(text: String): Parsed {
        val lines = mutableListOf<Line>()
        val names = linkedSetOf<String>()
        for (c in text.replace("\r", "").split(reSplit).map { it.trim() }.filter { it.isNotEmpty() }) {
            reOwes.find(c)?.let { m ->
                names += m.groupValues[1]
                lines += Line("credit", "Credit", amount = toNumber(m.groupValues[2]) ?: 0.0, customer = m.groupValues[1])
            } ?: rePaid.find(c)?.let { m ->
                names += m.groupValues[1]
                lines += Line("payment", "Payment", amount = toNumber(m.groupValues[2]) ?: 0.0, customer = m.groupValues[1])
            } ?: reSold.find(c)?.let { m -> lines += make("sale", m) }
            ?: reBought.find(c)?.let { m -> lines += make("expense", m) }
        }
        return Parsed(lines.filter { it.amount > 0 }, names.toList())
    }

    private fun make(kind: String, m: MatchResult): Line {
        val qty = m.groupValues[1].toDoubleOrNull()
        val n = toNumber(m.groupValues[3]) ?: 0.0
        val each = m.groupValues[4].isNotBlank()
        val item = m.groupValues[2].replace(Regex("""\s{2,}"""), " ").trim(' ', ',', ':', '-').take(60)
        return Line(
            kind, item.ifBlank { if (kind == "sale") "Sale" else "Expense" },
            qty = qty, unitPrice = if (each) n else null, amount = if (each && qty != null) qty * n else n,
        )
    }

    data class Hidden(val text: String, val map: Map<String, String>, val count: Int)

    /** Replaces customer names (known ones + names found by the rules) and phone numbers with tokens. */
    fun hideNames(text: String, known: List<String>): Hidden {
        var count = 0
        var out = rePhone.replace(text) { count++; "[phone]" }
        val found = (known.filter { it.length > 1 } + parse(out).names).toSet().sortedByDescending { it.length }
        val map = linkedMapOf<String, String>()
        var i = 0
        for (name in found) {
            val re = Regex("""(^|[^\p{L}])${Regex.escape(name)}(?=$|[^\p{L}])""", opts)
            if (!re.containsMatchIn(out)) continue
            val token = "C${++i}"
            map[token] = name
            out = re.replace(out) { count++; it.groupValues[1] + token }
        }
        return Hidden(out, map, count)
    }

    fun restore(value: String?, map: Map<String, String>): String? =
        value?.let { v -> Regex("""\bC(\d+)\b""").replace(v) { map[it.value] ?: it.value } }

    data class Sample(val lang: String, val label: String, val text: String)

    val SAMPLES = listOf(
        Sample("en", "EN · market day", "Sold 3 bags of rice, 250 each. Sold beans 600. Bought charcoal for 400. Marie owes 500."),
        Sample("ht", "HT · jounen mache", "Vann 4 mamit diri 250 chak. Vann lwil 450. Achte chabon 300. Madan Pòl dwe 1200, Jean peye 300."),
        Sample("fr", "FR · journée", "Vendu 2 sacs de farine 1500 chacun. Acheté transport 250. Rose doit 700."),
        Sample("es", "ES · día de venta", "Vendí 5 plátanos 25 cada. Compré bolsas por 100. Ana pagó 200."),
    )
}

/** One notebook line, before it is saved. */
data class Line(
    var kind: String,
    var item: String,
    var qty: Double? = null,
    var unitPrice: Double? = null,
    var amount: Double = 0.0,
    var customer: String? = null,
)
