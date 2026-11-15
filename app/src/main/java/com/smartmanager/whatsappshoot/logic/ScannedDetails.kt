package com.smartmanager.whatsappshoot.logic

/** What was read on the photo besides the product: an order number, a date and a branch (each only if found). */
data class ScannedDetails(val orderNumber: String? = null, val date: String? = null, val branch: String? = null) {
    val isEmpty: Boolean get() = orderNumber == null && date == null && branch == null
}

/** Words put in front of each detail in the message (the app language), supplied by the platform layer. */
data class DetailLabels(val order: String = "Order no.", val date: String = "Date", val branch: String = "Branch")

/**
 * Finds an order number, a date and a branch / shop name in text read from a photo. Recognises English and French labels written in Latin letters
 * ("Order No: 10458", "Commande n° 4587", "Date: 12/11/2026", "Branch: Sfax", "Agence Tunis", "Shop name: ...", "Magasin ..."), Arabic-Indic digits, and a few
 * order-code shapes without a label ("CMD-1234", "INV 2026/045"). Nothing is guessed: a detail is returned only when its label, a well-formed date or such a code is present.
 */
object DetailExtractor {
    // Tier 1: an explicit "order / invoice" label.
    private const val ORDER_KEYWORD =
        "(?:n[°º]\\s*(?:de\\s+)?commande|bon\\s+de\\s+commande|order\\s*(?:no\\.?|number|num\\.?|nr\\.?|id|ref(?:erence)?)?|commande\\s*(?:n[°º]\\.?|num(?:éro)?)?|" +
            "invoice\\s*(?:no\\.?|number|num\\.?|id)?|facture\\s*(?:n[°º]\\.?|num(?:éro)?)?|receipt\\s*(?:no\\.?|number)?|ticket\\s*(?:no\\.?|number)?|" +
            "reçu\\s*(?:n[°º]\\.?)?|bl\\s*(?:n[°º]\\.?)?|transaction\\s*(?:no\\.?|id|ref)?|voucher\\s*(?:no\\.?)?)"
    private val ORDER = Regex("(?i)\\b$ORDER_KEYWORD[\\s:#.\\-–]*([A-Za-z0-9][A-Za-z0-9\\-/]{2,24})")
    // Tier 2: a generic reference label.
    private val REFERENCE = Regex("(?i)(?:\\bref(?:erence)?\\.?|\\bréf(?:érence)?\\.?|\\bn[°º]|\\bno\\.|\\bnum(?:éro|ber)?\\b|#)[\\s:#.\\-–]*([A-Za-z0-9][A-Za-z0-9\\-/]{2,24})")
    // Tier 3: a code that looks like an order number by itself.
    private val ORDER_CODE = Regex("\\b(?:CMD|ORD|INV|FAC|PO|SO|BC|BL)[-_/ ]?\\d{3,}[A-Z0-9\\-/]*", RegexOption.IGNORE_CASE)

    private const val MONTHS = "jan(?:uary|vier)?|feb(?:ruary)?|fév(?:rier)?|fev(?:rier)?|mar(?:ch|s)?|apr(?:il)?|avr(?:il)?|may|mai|jun(?:e)?|juin|jul(?:y)?|juil(?:let)?|aug(?:ust)?|août|aout|sep(?:t(?:ember|embre)?)?|oct(?:ober|obre)?|nov(?:ember|embre)?|dec(?:ember)?|déc(?:embre)?"
    private val DATE_NUMERIC = Regex("\\b(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{4}|\\d{2})\\b")
    private val DATE_ISO = Regex("\\b(\\d{4})[/\\-.](\\d{1,2})[/\\-.](\\d{1,2})\\b")
    private val DATE_DAY_MONTH = Regex("(?i)\\b(\\d{1,2})(?:st|nd|rd|th|er)?[\\s\\-/.]+(?:$MONTHS)\\.?,?[\\s\\-/.]*(\\d{4}|\\d{2})\\b")
    private val DATE_MONTH_DAY = Regex("(?i)\\b(?:$MONTHS)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})\\b")
    private val DATE_NO_YEAR = Regex("(?i)\\b(\\d{1,2})(?:st|nd|rd|th|er)?[\\s\\-/.]+(?:$MONTHS)\\b")

    private const val BRANCH_WORDS = "branch|store|shop|outlet|showroom|agency|agence|succursale|magasin|boutique|point\\s+of\\s+sale|point\\s+de\\s+vente|location|emplacement|site"
    private const val SELLER_WORDS = "shop\\s+name|store\\s+name|nom\\s+du\\s+magasin|nom\\s+de\\s+la\\s+boutique|seller|vendor|merchant|vendeur|sold\\s+by|vendu\\s+par"
    private val BRANCH = Regex("(?i)(?:^|[^\\p{L}])(?:$SELLER_WORDS|$BRANCH_WORDS)\\s*[:#\\-–]?\\s*(\\S.{1,40})")

    /** Arabic-Indic and Persian digits become ASCII digits; odd spaces become plain spaces. */
    fun normalise(text: String): String = buildString {
        for (c in text) append(
            when (c) {
                in '\u0660'..'\u0669' -> '0' + (c - '\u0660')
                in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0')
                '\u00A0', '\u200B', '\u200E', '\u200F', '\u202F' -> ' '
                else -> c
            },
        )
    }

    fun extract(text: String): ScannedDetails {
        if (text.isBlank()) return ScannedDetails()
        val t = normalise(text)
        return ScannedDetails(order(t), date(t), branch(t))
    }

    private fun clean(v: String) = v.trim('-', '/', '.', ':', '#')

    private fun order(text: String): String? {
        ORDER.findAll(text).map { clean(it.groupValues[1]) }.firstOrNull { v -> v.any { it.isDigit() } && v.length >= 3 }?.let { return it }
        REFERENCE.findAll(text).map { clean(it.groupValues[1]) }.firstOrNull { v -> v.any { it.isDigit() } && v.length >= 3 && !looksLikeDate(v) }?.let { return it }
        return ORDER_CODE.find(text)?.value?.trim()
    }

    private fun looksLikeDate(v: String) = DATE_NUMERIC.matches(v) || DATE_ISO.matches(v)

    private fun validDate(d: Int, m: Int) = d in 1..31 && m in 1..12

    private fun date(text: String): String? {
        // A date on a line that says "date" wins; otherwise the first well-formed date in the text.
        val labelled = text.lines().filter { Regex("(?i)\\bdate\\b").containsMatchIn(it) }
        return findDate(labelled.joinToString("\n")) ?: findDate(text) ?: DATE_NO_YEAR.find(labelled.joinToString("\n"))?.value
    }

    private fun findDate(text: String): String? {
        val hits = ArrayList<Pair<Int, String>>()
        DATE_NUMERIC.findAll(text).forEach { m -> if (validDate(m.groupValues[1].toInt(), m.groupValues[2].toInt())) hits += m.range.first to m.value }
        DATE_ISO.findAll(text).forEach { m -> if (validDate(m.groupValues[3].toInt(), m.groupValues[2].toInt())) hits += m.range.first to m.value }
        DATE_DAY_MONTH.findAll(text).forEach { m -> if (m.groupValues[1].toInt() in 1..31) hits += m.range.first to m.value }
        DATE_MONTH_DAY.findAll(text).forEach { m -> if (m.groupValues[1].toInt() in 1..31) hits += m.range.first to m.value }
        return hits.minByOrNull { it.first }?.second
    }

    private fun branch(text: String): String? {
        for (line in text.lines()) {
            val m = BRANCH.find(line) ?: continue
            val v = m.groupValues[1].split(Regex("\\s{2,}|\\s[|•·]\\s|\\s-\\s")).first().trim().trim(',', ';', ':', '.', '-', '–').trim()
            if (v.length >= 2 && v.any { it.isLetterOrDigit() }) return v
        }
        return null
    }
}
