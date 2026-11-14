package com.smartmanager.whatsappshoot.logic

import java.text.Normalizer

/** One catalogue entry. Its picture is stored by the platform layer under the product id. */
data class Product(val id: String, val name: String, val price: String, val description: String)

/** Name of the temporary gallery copies that versions 1.4.0-1.4.2 made of product pictures (removed at start-up; never "the newest photo"). */
object ProductPhotos {
    const val PREFIX = "WSS_PRODUCT_"
    const val ALBUM_PATH = "Pictures/WSS Products"
}

/** Plain-text storage format of the catalogue (one product per line, tab separated, escaped): easy to test, no Android types. */
object CatalogueCodec {
    private fun esc(s: String) = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\"); '\t' -> append("\\t"); '\n' -> append("\\n"); '\r' -> append("\\r")
            else -> append(c)
        }
    }

    private fun unesc(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 't' -> append('\t'); 'n' -> append('\n'); 'r' -> append('\r'); else -> append(s[i + 1]) }
                i += 2
            } else { append(c); i++ }
        }
    }

    fun encode(products: List<Product>): String =
        products.joinToString("\n") { listOf(it.id, it.name, it.price, it.description).joinToString("\t") { f -> esc(f) } }

    fun decode(text: String): List<Product> =
        text.split('\n').filter { it.isNotBlank() }.mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 4 || f[0].isBlank()) null else Product(unesc(f[0]), unesc(f[1]), unesc(f[2]), unesc(f[3]))
        }
}

/**
 * Decides which catalogue product a piece of recognised text (what the phone read on the newest photo) names.
 * Case, accents and punctuation are ignored; a product matches when all of its name words are found (a long name may miss one word);
 * a word of 5+ letters tolerates one misread letter. The product with the most matched words wins; an exact tie between two
 * different products is "no match" (the caller falls back instead of guessing).
 */
object ProductMatcher {
    fun tokens(s: String): List<String> =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    fun match(text: String, products: List<Product>): Product? {
        val ocr = tokens(text)
        if (ocr.isEmpty() || products.isEmpty()) return null
        val set = ocr.toSet()
        val joined = ocr.joinToString("")
        fun has(t: String): Boolean =
            t in set || (t.length >= 4 && joined.contains(t)) || (t.length >= 5 && ocr.any { Math.abs(it.length - t.length) <= 1 && edit1(it, t) })
        data class Scored(val p: Product, val matched: Int, val chars: Int)
        val scored = products.mapNotNull { p ->
            val toks = tokens(p.name).distinct()
            if (toks.isEmpty()) return@mapNotNull null
            val matched = toks.count(::has)
            val need = if (toks.size >= 4) Math.ceil(toks.size * 0.75).toInt() else toks.size
            if (matched >= need) Scored(p, matched, toks.sumOf { it.length }) else null
        }
        val best = scored.maxWithOrNull(compareBy<Scored>({ it.matched }, { it.chars })) ?: return null
        val tied = scored.filter { it.matched == best.matched && it.chars == best.chars && it.p.id != best.p.id }
        return if (tied.isEmpty()) best.p else null
    }

    /** True if the two words differ by at most one inserted, deleted or replaced letter. */
    private fun edit1(a: String, b: String): Boolean {
        if (a == b) return true
        if (Math.abs(a.length - b.length) > 1) return false
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return when {
            a.length == b.length -> a.substring(i + 1) == b.substring(i + 1)
            a.length < b.length -> a.substring(i) == b.substring(i + 1)
            else -> a.substring(i + 1) == b.substring(i)
        }
    }
}

/** The message sent with the newest photo: the product's name, price and description (each when filled in), then the user's own text. */
object CaptionBuilder {
    fun build(product: Product?, userText: String, includeDescription: Boolean = true): String {
        val lines = ArrayList<String>()
        if (product != null) {
            lines += product.name.trim()
            if (product.price.isNotBlank()) lines += product.price.trim()
            if (includeDescription && product.description.isNotBlank()) lines += product.description.trim()
        }
        val head = lines.filter { it.isNotEmpty() }.joinToString("\n")
        val tail = userText.trim()
        return when {
            head.isEmpty() -> tail
            tail.isEmpty() -> head
            else -> "$head\n\n$tail"
        }
    }
}

/** What the catalogue step decided for the newest photo. */
sealed class ProductResolution {
    /** Feature off or catalogue empty: behave as before. */
    object Disabled : ProductResolution()

    /** The photo names no catalogue product (or could not be read): the newest photo is sent as before. */
    object NoMatch : ProductResolution()

    /** The photo names [product]: the newest photo is sent with [caption] (name, price, description and the user's text). */
    data class Matched(val product: Product, val caption: String) : ProductResolution()
}

interface ProductRecognizer {
    /** Reads [image] and matches it against the catalogue. Callback on the main thread, exactly once. */
    fun resolve(image: LatestImage, settings: AutomationSettings, callback: (ProductResolution) -> Unit)
}

object NoProducts : ProductRecognizer {
    override fun resolve(image: LatestImage, settings: AutomationSettings, callback: (ProductResolution) -> Unit) = callback(ProductResolution.Disabled)
}
