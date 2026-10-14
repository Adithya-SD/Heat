package app.heat.core.settings

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One search result: the setting, how well it matched, and where in its title (to highlight). */
class SettingHit(val def: SettingDef, val score: Double, val titleMatch: IntRange?)

/**
 * Finds a setting from whatever a person types: part of its name, a word they would use for it, a typo, the name of
 * the section it lives in.
 *
 * Every word of the query must find something (so "quiet night" narrows rather than widens), and each word is scored
 * by how well it matches: a whole word in the title beats the start of one, which beats a piece of one, which beats a
 * synonym, a hint, or the section name. Words of four letters or more forgive one typo, eight or more forgive two.
 * Punctuation and spacing are ignored when comparing, so "wifi" finds "Wi-Fi address".
 */
object SettingsSearch {

    fun search(query: String, defs: List<SettingDef>): List<SettingHit> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return defs.map { SettingHit(it, 0.0, null) }

        val hits = ArrayList<SettingHit>()
        for ((order, def) in defs.withIndex()) {
            val title = def.title.lowercase()
            val titleWords = words(title)
            val keywordWords = def.keywords.flatMap { words(it.lowercase()) }
            val hintWords = def.hint?.let { words(it.lowercase()) }.orEmpty()
            val sectionWords = words(def.section.title.lowercase()) + def.section.keywords.flatMap { words(it) }

            var total = 0.0
            var allInTitle = true
            var range: IntRange? = null
            var ok = true
            for (t in tokens) {
                val inTitle = best(t, titleWords)
                val inKeys = best(t, keywordWords) * 0.8
                val inHint = best(t, hintWords) * 0.4
                val inSection = best(t, sectionWords) * 0.5
                val s = max(max(inTitle, inKeys), max(inHint, inSection))
                if (s <= 0.0) { ok = false; break }
                total += s
                if (inTitle <= 0.0) allInTitle = false
                if (range == null && inTitle > 0.0) range = locate(title, t)
            }
            if (!ok) continue
            if (allInTitle) total += 2.0
            if (title.startsWith(tokens.joinToString(" "))) total += 3.0
            // Catalog order breaks ties: the order settings are listed in is the order people expect them.
            hits += SettingHit(def, total - order * 1e-6, range)
        }
        return hits.sortedByDescending { it.score }
    }

    // ── internals ────────────────────────────────────────────────────

    private fun tokenize(q: String): List<String> = words(q.lowercase())

    private fun words(s: String): List<String> = s.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    private fun compact(s: String) = s.filter { it.isLetterOrDigit() }

    /** How well [token] matches the best of [candidates]: 0 for not at all. */
    private fun best(token: String, candidates: List<String>): Double {
        var top = 0.0
        for (w in candidates) {
            val s = when {
                w == token -> 10.0
                w.startsWith(token) -> 7.0
                token.length >= 3 && w.contains(token) -> 4.0
                token.length >= 3 && token.startsWith(w) && w.length >= 3 -> 5.0     // "exposures" finds "exposure"
                token.length >= 4 && distance(token, w, if (token.length >= 8) 2 else 1) <= (if (token.length >= 8) 2 else 1) -> 3.0
                else -> 0.0
            }
            if (s > top) top = s
        }
        // "wifi" against "wi-fi": the words are "wi" and "fi", but the compact title contains it.
        if (top == 0.0 && token.length >= 3) {
            val joined = compact(candidates.joinToString(""))
            if (joined.contains(token)) top = 3.5
        }
        return top
    }

    /** Where [token] sits in [title] (letters only compared), for highlighting. */
    private fun locate(title: String, token: String): IntRange? {
        val i = title.indexOf(token)
        if (i >= 0) return i until (i + token.length)
        // A typo or punctuation difference: highlight the nearest word that scored.
        var from = 0
        for (w in title.split(Regex("(?<=[^a-z0-9])|(?=[^a-z0-9])"))) {
            if (w.isNotEmpty() && w[0].isLetterOrDigit() && distance(token, w, 2) <= 2) return from until (from + w.length)
            from += w.length
        }
        return null
    }

    /** Edit distance, giving up once it must exceed [limit]. */
    private fun distance(a: String, b: String, limit: Int): Int {
        if (abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                rowMin = min(rowMin, cur[j])
            }
            if (rowMin > limit) return limit + 1
            prev = cur
        }
        return prev[b.length]
    }
}
