package com.lyb.mysecretary.extract

/**
 * Guards against invented to-dos: every action line must be traceable to the transcript.
 *
 * A line is kept only when at least half of its content words (2+ characters, time prefix and
 * particles ignored) share a two-character sequence with the transcript. Rephrasing such as
 * "메일 보내야 돼" → "메일 발송" still passes on "메일"; a line with no words from the
 * recording is dropped.
 */
object Grounding {
    private const val MIN_SUPPORTED = 0.5
    private val timePrefix = Regex("^\\d{1,2}:\\d{2}(까지)?\\s*")

    /** Phrases Whisper is known to produce on silence or noise (subtitle-style endings). */
    private val hallucinations = listOf(
        Regex("시청(해|하여)\\s*주셔서\\s*감사합니다\\.?"),
        Regex("구독과\\s*좋아요[^.\\n]*\\.?"),
        Regex("좋아요와\\s*구독[^.\\n]*\\.?"),
        Regex("다음\\s*영상에서\\s*(만나요|뵙겠습니다)\\.?"),
        Regex("MBC\\s*뉴스[^.\\n]*\\.?"),
        Regex("자막\\s*(제공|by)[^.\\n]*"),
    )

    fun cleanTranscript(text: String): String {
        var out = text
        hallucinations.forEach { out = it.replace(out, "") }
        return out.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** True when the transcript has no meaningful speech left (e.g. a few filler syllables). */
    fun isEffectivelyEmpty(transcript: String): Boolean =
        transcript.count { it.isLetterOrDigit() } < 4

    fun filter(actions: List<String>, transcript: String): List<String> {
        val source = compact(transcript)
        if (source.length < 2) return emptyList()
        val bigrams = HashSet<String>()
        for (i in 0 until source.length - 1) bigrams.add(source.substring(i, i + 2))
        return actions.filter { isSupported(it, bigrams) }
    }

    private fun isSupported(line: String, bigrams: Set<String>): Boolean {
        val words = line.replace(timePrefix, "")
            .split(Regex("\\s+"))
            .map { compact(it) }
            .filter { it.length >= 2 }
        if (words.isEmpty()) return false
        val supported = words.count { w -> (0 until w.length - 1).any { w.substring(it, it + 2) in bigrams } }
        return supported.toDouble() / words.size >= MIN_SUPPORTED
    }

    private fun compact(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
}
