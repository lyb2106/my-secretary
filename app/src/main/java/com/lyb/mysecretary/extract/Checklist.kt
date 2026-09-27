package com.lyb.mysecretary.extract

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** How the action list was produced (shown in the result screen). */
enum class ExtractionMethod(val label: String) {
    GEMINI_NANO("Gemini Nano"),
    RULE_BASED("규칙 기반"),
}

object Checklist {
    private val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE

    /** Obsidian-compatible Markdown checklist. */
    fun format(date: LocalDate, actions: List<String>): String {
        val header = "## ${date.format(dateFormat)} 할 일"
        if (actions.isEmpty()) return "$header\n(추출된 할 일이 없습니다. 원본 전사문을 확인하세요.)"
        return header + "\n" + actions.joinToString("\n") { "- [ ] $it" }
    }

    /**
     * Parses a model's line list ("- ...", "* ...", "1. ...", "- [ ] ...") into bare action
     * strings. Lines that only say there is nothing to do are dropped.
     */
    fun parseLines(raw: String): List<String> {
        val seen = HashSet<String>()
        return raw.lines()
            .map { line ->
                line.trim()
                    .replace(Regex("^(?:[-*•·]\\s*)?(?:\\[[ xX]?]\\s*)?(?:\\d+[.)]\\s*)?"), "")
                    .trim()
                    .trimEnd('.', '。')
                    .trim()
            }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it != "없음" && !it.startsWith("할 일 없음") }
            .filter { seen.add(it.replace(" ", "")) }
    }
}
