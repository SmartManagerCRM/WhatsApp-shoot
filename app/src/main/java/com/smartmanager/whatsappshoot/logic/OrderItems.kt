package com.smartmanager.whatsappshoot.logic

/**
 * One order page can list several items ("1X A11", "2X L6" under "Order Summary"). Each item line starts with a quantity; every catalogue product named by such a line is
 * one product to send (top to bottom, each product once), all with the same order details and notes.
 */
object OrderItems {
    private val ITEM = Regex("^\\W*\\d{1,3}\\s*[xX×*]\\s*\\S+.*")

    fun products(lines: List<OcrBox>, products: List<Product>): List<Product> {
        if (products.isEmpty()) return emptyList()
        val out = ArrayList<Product>()
        for (l in lines.sortedBy { it.top }) {
            if (!ITEM.matches(l.text.trim())) continue
            val p = ProductMatcher.match(l.text, products) ?: continue
            if (out.none { it.id == p.id }) out += p
        }
        return out
    }
}
