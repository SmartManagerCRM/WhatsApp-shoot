package com.smartmanager.whatsappshoot.logic

/**
 * Finds the "notes" icon (a rounded document outline with three short bars inside) on a picture, for order pages where the word "Notes" is not printed.
 * Works on a plain brightness map ([lum], one byte per pixel, row by row) so it can be tested on the computer.
 *
 * The icon is looked for in the left part of the picture as a dark, hollow, roughly square outline of a plausible size that holds at least three
 * horizontal bars of about the same left edge. The note text is then read from the area around it.
 */
object NoteIconLocator {
    data class Icon(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
    }

    private const val DARK = 120
    private const val LEFT_PART = 0.30

    /** The icon found, or null. When several candidates exist the lowest on the picture wins (the notes block sits below the order lines). */
    fun find(lum: ByteArray, w: Int, h: Int): Icon? {
        if (w < 200 || h < 200 || lum.size < w * h) return null
        val xMax = (w * LEFT_PART).toInt()
        val label = IntArray(xMax * h)
        val minS = (w * 0.018).toInt().coerceAtLeast(10)
        val maxW = (w * 0.085).toInt()
        val maxH = (w * 0.11).toInt()
        val comps = ArrayList<IntArray>() // l, t, r, b, area
        fun dark(x: Int, y: Int) = (lum[y * w + x].toInt() and 0xFF) < DARK
        val stack = IntArray(xMax * h)
        for (y0 in 0 until h) for (x0 in 0 until xMax) {
            if (label[y0 * xMax + x0] != 0 || !dark(x0, y0)) continue
            val id = comps.size + 1
            var sp = 0
            stack[sp++] = y0 * xMax + x0; label[y0 * xMax + x0] = id
            var l = x0; var r = x0; var t = y0; var b = y0; var area = 0
            while (sp > 0) {
                val p = stack[--sp]; val x = p % xMax; val y = p / xMax
                area++
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= xMax || ny >= h) continue
                    val q = ny * xMax + nx
                    if (label[q] == 0 && dark(nx, ny)) { label[q] = id; stack[sp++] = q }
                }
            }
            comps += intArrayOf(l, t, r, b, area)
        }
        var best: Icon? = null
        for (c in comps) {
            val cw = c[2] - c[0] + 1; val ch = c[3] - c[1] + 1
            if (cw < minS || ch < minS || cw > maxW || ch > maxH) continue
            val aspect = cw.toDouble() / ch
            if (aspect < 0.6 || aspect > 1.0) continue
            if (c[4] > 0.75 * cw * ch) continue // solid blobs are not an outline
            var bars = 0
            val barLefts = ArrayList<Int>()
            for (d in comps) {
                if (d === c) continue
                if (d[0] < c[0] || d[2] > c[2] || d[1] < c[1] || d[3] > c[3]) continue
                val dw = d[2] - d[0] + 1; val dh = d[3] - d[1] + 1
                if (dw >= 2.2 * dh && dw >= 0.25 * cw) { bars++; barLefts += d[0] }
            }
            if (bars < 3) continue
            barLefts.sort()
            if (barLefts[2] - barLefts[0] > 0.08 * cw + 2) continue
            val icon = Icon(c[0], c[1], c[2], c[3])
            if (best == null || icon.top > best.top) best = icon
        }
        return best
    }

    /**
     * The strip to read for the note around [icon]: from a little above the icon (a line such as "Write on the card:" may start above it) to the next section line
     * below it, or a fixed window. [boundaries] are the tops of English section lines found by the Latin reader. Always full width.
     */
    fun region(icon: Icon, boundaries: List<Int>, imageWidth: Int, imageHeight: Int): NoteRegion? {
        val ih = icon.height
        val top = (icon.top - (ih * 1.4).toInt()).coerceAtLeast(0)
        var bottom = (icon.bottom + (ih * 4.2).toInt()).coerceAtMost(imageHeight)
        boundaries.filter { it > icon.bottom + ih / 2 }.minOrNull()?.let { bottom = Math.min(bottom, it - ih / 6) }
        return if (bottom - top >= ih * 2) NoteRegion(0, top, imageWidth, bottom) else null
    }
}
