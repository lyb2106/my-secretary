package com.lyb.mysecretary.extract

/**
 * Finds a spoken clock time in an action ("오후 2시 반까지", "14:00에") and moves it to the
 * front of the line as "HH:MM" / "HH:MM까지".
 */
object TimeNormalizer {
    private val koreanHours = mapOf(
        "한" to 1, "두" to 2, "세" to 3, "네" to 4, "다섯" to 5, "여섯" to 6,
        "일곱" to 7, "여덟" to 8, "아홉" to 9, "열" to 10, "열한" to 11, "열두" to 12,
    )

    private const val PERIOD = "(오전|오후|아침|저녁|밤|낮)?\\s*"
    private const val SUFFIX = "\\s*(까지|부터|쯤에|쯤|에)?"

    private val spokenTime = Regex(
        PERIOD +
            "(\\d{1,2}|열한|열두|한|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\\s*시(?!간|작|험|청|장|키)" +
            "\\s*(반|(\\d{1,2})\\s*분)?" + SUFFIX,
    )
    private val clockTime = Regex(PERIOD + "(\\d{1,2}):(\\d{2})" + SUFFIX)

    data class Result(val text: String, val prefix: String?)

    fun extract(line: String): Result {
        clockTime.find(line)?.let { m ->
            val hour = m.groupValues[2].toInt()
            val minute = m.groupValues[3].toInt()
            return build(line, m, m.groupValues[1], hour, minute, m.groupValues[4])
        }
        spokenTime.find(line)?.let { m ->
            val hourToken = m.groupValues[2]
            val hour = hourToken.toIntOrNull() ?: koreanHours[hourToken] ?: return Result(line, null)
            val minute = when {
                m.groupValues[3] == "반" -> 30
                m.groupValues[4].isNotEmpty() -> m.groupValues[4].toInt()
                else -> 0
            }
            return build(line, m, m.groupValues[1], hour, minute, m.groupValues[5])
        }
        return Result(line, null)
    }

    private fun build(
        line: String,
        m: MatchResult,
        period: String,
        rawHour: Int,
        minute: Int,
        suffix: String,
    ): Result {
        if (rawHour !in 0..24 || minute !in 0..59) return Result(line, null)
        var hour = rawHour
        when (period) {
            "오후", "저녁", "밤", "낮" -> if (hour in 1..11) hour += 12
            "오전", "아침" -> if (hour == 12) hour = 0
            // No period given: a working-day plan rarely means 1–7 a.m.
            else -> if (hour in 1..7) hour += 12
        }
        val clock = "%02d:%02d".format(hour, minute)
        val prefix = if (suffix == "까지") "${clock}까지" else clock
        val rest = line.removeRange(m.range).replace(Regex("\\s{2,}"), " ").trim()
        return Result(rest, prefix)
    }
}
