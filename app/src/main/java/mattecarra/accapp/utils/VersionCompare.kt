package mattecarra.accapp.utils

object VersionCompare {
    fun isNewer(candidate: String, installed: String): Boolean = compare(candidate, installed) > 0

    fun compare(a: String, b: String): Int {
        val (aCore, aPre) = split(a)
        val (bCore, bPre) = split(b)

        val core = compareCore(aCore, bCore)
        if (core != 0) return core

        return when {
            aPre.isEmpty() && bPre.isEmpty() -> 0
            aPre.isEmpty() -> 1
            bPre.isEmpty() -> -1
            else -> comparePre(aPre, bPre)
        }
    }

    private fun split(raw: String): Pair<String, String> {
        var v = raw.trim()
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1).trim()
        val plus = v.indexOf('+')
        if (plus >= 0) v = v.substring(0, plus)
        val dash = v.indexOf('-')
        return if (dash >= 0) v.substring(0, dash) to v.substring(dash + 1) else v to ""
    }

    private fun compareCore(a: String, b: String): Int {
        val ap = a.split('.')
        val bp = b.split('.')
        val n = maxOf(ap.size, bp.size)
        for (i in 0 until n) {
            val x = leadingInt(ap.getOrNull(i))
            val y = leadingInt(bp.getOrNull(i))
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun comparePre(a: String, b: String): Int {
        val ap = a.split('.')
        val bp = b.split('.')
        val n = maxOf(ap.size, bp.size)
        for (i in 0 until n) {
            val x = ap.getOrNull(i) ?: return -1
            val y = bp.getOrNull(i) ?: return 1
            val c = compareIdentifier(x, y)
            if (c != 0) return c
        }
        return 0
    }

    private fun compareIdentifier(a: String, b: String): Int {
        val ma = IDENT.matchEntire(a)
        val mb = IDENT.matchEntire(b)
        if (ma != null && mb != null) {
            val prefix = ma.groupValues[1].compareTo(mb.groupValues[1])
            if (prefix != 0) return prefix
            val na = ma.groupValues[2].toLongOrNull() ?: 0L
            val nb = mb.groupValues[2].toLongOrNull() ?: 0L
            return na.compareTo(nb)
        }
        val an = a.toLongOrNull()
        val bn = b.toLongOrNull()
        return when {
            an != null && bn != null -> an.compareTo(bn)
            an != null -> -1
            bn != null -> 1
            else -> a.compareTo(b)
        }
    }

    private val IDENT = Regex("^([A-Za-z.\\-]*?)(\\d+)$")
    private val LEADING = Regex("^(\\d+)")

    private fun leadingInt(seg: String?): Long {
        if (seg == null) return 0L
        val m = LEADING.find(seg.trim()) ?: return 0L
        return m.groupValues[1].toLongOrNull() ?: 0L
    }
}
