package com.lyb.mysecretary.learn

/**
 * A learned "misrecognised word → correct word" pair.
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

/**
 * Pure logic for mining, merging and applying user corrections.
 *
 * Rules are always whole words: a rule never rewrites part of a word, so "김보영 → 김보형" can
 * not turn into "영 → 형" and touch unrelated words that merely share a syllable. Only a
 * trailing particle (조사) is allowed to differ around the word.
 */
object Corrections {
    private const val MAX_GAP = 4
    private const val MAX_TOKEN_LEN = 30
    private const val MAX_VOCAB = 40
    private const val MIN_WORD_LEN = 2
    private const val MIN_SIMILARITY = 0.5

    private val markdownTokens = setOf("-", "*", "[", "]", "[]", "[ ]", "##", "#", "[x]")

    /** Longest first, so "으로" is stripped before "로". */
    private val particles = listOf(
        "에게서", "으로", "에게", "까지", "부터", "하고", "이랑", "처럼", "보다", "한테", "께서", "에서",
        "을", "를", "이", "가", "은", "는", "도", "에", "의", "로", "와", "과", "만", "께", "랑",
    )
    private val particlePattern = particles.joinToString("|")

    /** Aligns the generated text with the user's edit and returns candidate (wrong, right) word pairs. */
    fun mine(original: String, edited: String): List<Pair<String, String>> {
        val a = tokenize(original)
        val b = tokenize(edited)
        if (a.isEmpty() || b.isEmpty()) return emptyList()

        // Longest common subsequence over words; the unmatched stretches are the edits.
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
                pairWords(gapA, gapB).forEach { (w, r) -> wordRule(w, r)?.let(pairs::add) }
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

    /**
     * Pairs words one-to-one inside an edited stretch, choosing the order-preserving pairing with
     * the highest spelling similarity. Words that were merged, split or rewritten ("현 교수님" →
     * "윤교수님") have no similar one-to-one partner and are left out.
     */
    private fun pairWords(a: List<String>, b: List<String>): List<Pair<String, String>> {
        val n = a.size
        val m = b.size
        val score = Array(n + 1) { DoubleArray(m + 1) }
        for (x in 1..n) {
            for (y in 1..m) {
                val sim = pairScore(a[x - 1], b[y - 1])
                val take = if (sim >= MIN_SIMILARITY) score[x - 1][y - 1] + sim else Double.NEGATIVE_INFINITY
                score[x][y] = maxOf(score[x - 1][y], score[x][y - 1], take)
            }
        }
        val out = ArrayList<Pair<String, String>>()
        var x = n
        var y = m
        while (x > 0 && y > 0) {
            val sim = pairScore(a[x - 1], b[y - 1])
            when {
                sim >= MIN_SIMILARITY && score[x][y] == score[x - 1][y - 1] + sim -> {
                    out.add(a[x - 1] to b[y - 1]); x--; y--
                }
                score[x][y] == score[x - 1][y] -> x--
                else -> y--
            }
        }
        return out.reversed()
    }

    /**
     * Spelling similarity, except that a Hangul word rewritten in Latin letters (or the reverse,
     * "아이알비" → "IRB") counts as a match: transliterated terms share no letters by definition.
     */
    private fun pairScore(a: String, b: String): Double =
        if (startsLatin(a) != startsLatin(b)) 1.0 else similarity(a, b)

    /** Script of the word itself; a trailing particle ("IRB를") does not count. */
    private fun startsLatin(s: String) = s.firstOrNull()?.let { it in 'a'..'z' || it in 'A'..'Z' } ?: false

    /** Turns an aligned word pair into a whole-word rule, or null when it is not a safe rule. */
    internal fun wordRule(wrong: String, right: String): Pair<String, String>? {
        var w = wrong.trim()
        var r = right.trim()
        // Same particle on both sides ("아이알비를" → "IRB를"): keep the words, drop the particle.
        val particle = particles.firstOrNull { w.endsWith(it) && r.endsWith(it) }
        if (particle != null && w.length > particle.length && r.length > particle.length) {
            w = w.dropLast(particle.length)
            r = r.dropLast(particle.length)
        }
        if (!isWord(w) || !isWord(r) || w == r) return null
        if (w.length < MIN_WORD_LEN || r.length < MIN_WORD_LEN) return null
        // One word contained in the other is a merge/split or an added prefix ("교수님" → "윤교수님").
        if (r.contains(w) || w.contains(r)) return null
        // Times and numbers are content edits, not recognition errors.
        if (w.any { it.isDigit() } || r.any { it.isDigit() }) return null
        return w to r
    }

    /** A single word: no whitespace, reasonable length, letters only at the ends. */
    fun isWord(s: String): Boolean =
        s.isNotEmpty() && s.length <= MAX_TOKEN_LEN && s.none { it.isWhitespace() } &&
            s.first().isLetterOrDigit() && s.last().isLetterOrDigit()

    /** Old (pre-v0.1.8) rules could be syllables or phrases; only whole-word rules are kept. */
    fun isValidRule(rule: Correction): Boolean =
        isWord(rule.wrong) && isWord(rule.right) && rule.wrong != rule.right &&
            (rule.manual || (rule.wrong.length >= MIN_WORD_LEN && rule.right.length >= MIN_WORD_LEN))

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

    /** Replaces whole words only (a trailing particle is allowed and preserved). */
    fun apply(text: String, rules: List<Correction>): String {
        var out = text
        rules.filter { it.active && isValidRule(it) }
            .sortedByDescending { it.wrong.length }
            .forEach { rule ->
                val pattern = Regex(
                    "(?<![\\p{L}\\p{N}])" + Regex.escape(rule.wrong) +
                        "(?=(?:$particlePattern)?(?![\\p{L}\\p{N}]))",
                )
                out = pattern.replace(out, Regex.escapeReplacement(rule.right))
            }
        return out
    }

    /** Vocabulary hint passed to Whisper as initial_prompt. */
    fun vocabularyPrompt(rules: List<Correction>): String =
        rules.filter { it.active && isValidRule(it) }
            .sortedByDescending { it.count }
            .map { it.right }
            .distinct()
            .take(MAX_VOCAB)
            .joinToString(", ")

    /** 0..1 spelling similarity on Hangul jamo, so "매일"/"메일" or "내연권"/"뇌연구원" score high. */
    internal fun similarity(a: String, b: String): Double {
        val x = jamo(a)
        val y = jamo(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val prev = IntArray(y.length + 1) { it }
        val cur = IntArray(y.length + 1)
        for (i in 1..x.length) {
            cur[0] = i
            for (j in 1..y.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return 1.0 - prev[y.length].toDouble() / maxOf(x.length, y.length)
    }

    private fun jamo(s: String): String {
        val sb = StringBuilder()
        for (c in s.lowercase()) {
            val code = c.code - 0xAC00
            if (code in 0..11171) {
                sb.append((0x1100 + code / 588).toChar())
                sb.append((0x1161 + (code % 588) / 28).toChar())
                if (code % 28 != 0) sb.append((0x11A7 + code % 28).toChar())
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun tokenize(text: String): List<String> =
        text.split(Regex("\\s+"))
            .map { it.trim(',', '.', '!', '?', '·', '…', '(', ')', '"', '\'') }
            .filter { it.isNotEmpty() && it !in markdownTokens }
}
