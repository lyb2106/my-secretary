package com.lyb.mysecretary.learn

/**
 * A learned "misrecognised → correct" pair.
 * Rules become active once seen [PROMOTE_AT] times (or when entered manually).
 */
data class Correction(
    val wrong: String,
    val right: String,
    val count: Int,
    val manual: Boolean = false,
) {
    val active: Boolean get() = manual || count >= PROMOTE_AT

    companion object {
        const val PROMOTE_AT = 2
    }
}

/** Pure logic for mining, merging and applying user corrections. */
object Corrections {
    private const val MAX_GAP = 3
    private const val MAX_TOKEN_LEN = 30
    private const val MAX_VOCAB = 40

    private val markdownTokens = setOf("-", "*", "[", "]", "[]", "[ ]", "##", "#", "[x]")

    /** Aligns the generated text with the user's edit and returns candidate (wrong, right) pairs. */
    fun mine(original: String, edited: String): List<Pair<String, String>> {
        val a = tokenize(original)
        val b = tokenize(edited)
        if (a.isEmpty() || b.isEmpty()) return emptyList()

        // Longest common subsequence over tokens.
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }

        val pairs = ArrayList<Pair<String, String>>()
        var i = 0
        var j = 0
        val gapA = ArrayList<String>()
        val gapB = ArrayList<String>()
        fun flushGap() {
            if (gapA.isNotEmpty() && gapB.isNotEmpty() && gapA.size <= MAX_GAP && gapB.size <= MAX_GAP) {
                if (gapA.size == gapB.size) {
                    for (k in gapA.indices) addPair(pairs, gapA[k], gapB[k])
                } else {
                    addPair(pairs, gapA.joinToString(" "), gapB.joinToString(" "))
                }
            }
            gapA.clear()
            gapB.clear()
        }
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { flushGap(); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> gapA.add(a[i++])
                else -> gapB.add(b[j++])
            }
        }
        while (i < a.size) gapA.add(a[i++])
        while (j < b.size) gapB.add(b[j++])
        flushGap()
        return pairs
    }

    private fun addPair(out: MutableList<Pair<String, String>>, wrong: String, right: String) {
        var w = wrong
        var r = right
        // Strip a shared particle/suffix ("아이알비를" → "IRB를" becomes "아이알비" → "IRB").
        val suffix = w.commonSuffixWith(r)
        if (suffix.isNotEmpty() && suffix.length < w.length && suffix.length < r.length) {
            w = w.dropLast(suffix.length)
            r = r.dropLast(suffix.length)
        }
        val prefix = w.commonPrefixWith(r)
        if (prefix.isNotEmpty() && prefix.length < w.length && prefix.length < r.length) {
            w = w.drop(prefix.length)
            r = r.drop(prefix.length)
        }
        w = w.trim()
        r = r.trim()
        // Spacing-only fixes ("랩 미팅" → "랩미팅") vanish when stripped; keep the full pair.
        if (w.isEmpty() || r.isEmpty() || w == r) {
            w = wrong.trim()
            r = right.trim()
        }
        if (w.isEmpty() || r.isEmpty() || w == r) return
        if (w.length > MAX_TOKEN_LEN || r.length > MAX_TOKEN_LEN) return
        // Times and numbers are content edits, not recognition errors.
        if (w.all { it.isDigit() || it == ':' } || r.all { it.isDigit() || it == ':' }) return
        out.add(w to r)
    }

    /** Adds the newly mined pairs to the existing rule set (incrementing counts). */
    fun merge(existing: List<Correction>, mined: List<Pair<String, String>>): List<Correction> {
        val map = LinkedHashMap<Pair<String, String>, Correction>()
        existing.forEach { map[it.wrong to it.right] = it }
        mined.distinct().forEach { key ->
            val prev = map[key]
            map[key] = prev?.copy(count = prev.count + 1) ?: Correction(key.first, key.second, 1)
        }
        return map.values.toList()
    }

    /** Applies active rules at token starts, so trailing particles are preserved. */
    fun apply(text: String, rules: List<Correction>): String {
        var out = text
        rules.filter { it.active }
            .sortedByDescending { it.wrong.length }
            .forEach { rule ->
                val pattern = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(rule.wrong))
                out = pattern.replace(out, Regex.escapeReplacement(rule.right))
            }
        return out
    }

    /** Vocabulary hint passed to Whisper as initial_prompt. */
    fun vocabularyPrompt(rules: List<Correction>): String =
        rules.filter { it.active }
            .sortedByDescending { it.count }
            .map { it.right }
            .distinct()
            .take(MAX_VOCAB)
            .joinToString(", ")

    private fun tokenize(text: String): List<String> =
        text.split(Regex("\\s+"))
            .map { it.trim(',', '.', '!', '?', '·', '…') }
            .filter { it.isNotEmpty() && it !in markdownTokens }
}
