package com.lyb.mysecretary.extract

/**
 * Deterministic fallback for turning a spoken Korean to-do monologue into one short line per
 * action. Used when Gemini Nano is unavailable or fails.
 *
 * Steps: split into clauses → keep clauses that express an intention/obligation or contain an
 * action verb → drop fillers → rewrite the verb ending into a nominal form ("보내야 돼" →
 * "보내기", "확인해야 함" → "확인") → move a mentioned time to the front.
 */
class RuleBasedExtractor {

    fun extract(transcript: String): List<String> {
        val seen = HashSet<String>()
        val result = ArrayList<String>()
        for (clause in splitClauses(transcript)) {
            val cleaned = stripFillers(clause)
            if (cleaned.isBlank() || !isAction(cleaned)) continue
            val time = TimeNormalizer.extract(cleaned)
            val nominal = toNominal(time.text)
            if (nominal.length < 2) continue
            val line = if (time.prefix != null) "${time.prefix} $nominal" else nominal
            if (seen.add(line.replace(" ", ""))) result.add(line)
        }
        return result
    }

    internal fun splitClauses(text: String): List<String> {
        val sentences = text
            .split(Regex("\\s*\\n+\\s*"))
            .flatMap { it.split(Regex("(?<=[.!?。])\\s+|\\s*[.!?。]+$")) }
            .flatMap { it.split(CONNECTOR) }
            // A spoken item number starts a new item even without punctuation ("... 2번 290만 원 ...").
            .flatMap { it.split(Regex("\\s+(?=\\d{1,2}\\s*번\\s)")) }
        // "메일 보내고, 보고서 써야 돼" → two clauses; only split on a comma after a "-고" verb.
        return sentences
            .flatMap { it.split(Regex("(?<=고),\\s*")) }
            .map { it.trim().trimEnd('.', '!', '?', ',', '。', '…', '~').trim() }
            .filter { it.isNotEmpty() }
    }

    internal fun stripFillers(clause: String): String {
        var words = clause.split(Regex("\\s+")).filter { it.isNotEmpty() }
            .map { it.trim(',', '…', '~') }
            .filter { it.isNotEmpty() && it !in FILLERS }
        // Leading scaffolding such as "오늘 할 일은", "첫 번째로".
        var text = words.joinToString(" ")
        for (prefix in LEADING_PHRASES) {
            text = text.replace(prefix, "").trim()
        }
        words = text.split(" ").filter { it.isNotEmpty() }
        while (words.isNotEmpty() && words.first() in LEADING_WORDS) words = words.drop(1)
        return words.joinToString(" ")
            .replace(EMPHASIS, "")
            .trim()
    }

    internal fun isAction(clause: String): Boolean {
        if (QUESTION.containsMatchIn(clause)) return false
        // "1번 290만원 토스뱅크": an explicitly numbered item is a to-do even without a verb.
        if (ORDINAL.containsMatchIn(clause)) return true
        if (OBLIGATION.containsMatchIn(clause)) return true
        if (PAST.containsMatchIn(clause)) return false
        return ACTION_WORDS.any { clause.contains(it) }
    }

    internal fun toNominal(clause: String): String {
        var text = clause.trim().removeSuffix("요").trim()
        text = ORDINAL.replace(text, "").trim()

        text = OBLIGATION_END.replace(text) { nominalize(it.groupValues[1]) }
        text = FUTURE_END.replace(text) { m -> dropRieul(m.groupValues[1])?.let { nominalize(it) } ?: m.value }
        text = FUTURE_GE_END.replace(text) { m -> dropRieul(m.groupValues[1])?.let { nominalize(it) } ?: m.value }
        text = INTENT_END.replace(text) { nominalize(dropEu(it.groupValues[1])) }
        text = LETS_END.replace(text) { nominalize(it.groupValues[1]) }
        text = CONNECTIVE_GO_END.replace(text) { nominalize(it.groupValues[1]) }

        // Sino-Korean verbs read better without "하기": "메일 발송하기" → "메일 발송".
        text = text.replace(Regex("(\\S{2,})하기$"), "$1")
        text = text.replace(Regex("\\s+하기$"), "")
        return text.trim()
    }

    /** Verb stem (as written before "-야", "-고", …) + "기". */
    internal fun nominalize(stem: String): String {
        if (stem.isEmpty()) return stem
        val last = stem.last()
        val head = stem.dropLast(1)
        if (last == '해') return head + "하기"
        val s = Hangul.decompose(last) ?: return stem + "기"
        if (s.final != Hangul.FINAL_NONE) return stem + "기"
        val fixed: String = when {
            // 먹어 → 먹, 받아 → 받
            s.initial == Hangul.INITIAL_IEUNG && (s.medial == Hangul.MEDIAL_EO || s.medial == Hangul.MEDIAL_A) && head.isNotEmpty() -> head
            // 챙겨 → 챙기, 버려 → 버리
            s.medial == Hangul.MEDIAL_YEO -> head + Hangul.compose(s.copy(medial = Hangul.MEDIAL_I))
            // 줘 → 주, 배워 → 배우
            s.medial == Hangul.MEDIAL_WO -> head + Hangul.compose(s.copy(medial = Hangul.MEDIAL_U))
            // 봐 → 보, 와 → 오
            s.medial == Hangul.MEDIAL_WA -> head + Hangul.compose(s.copy(medial = Hangul.MEDIAL_O))
            // 돼 → 되
            s.medial == Hangul.MEDIAL_WAE -> head + Hangul.compose(s.copy(medial = Hangul.MEDIAL_OE))
            // 써 → 쓰, 꺼 → 끄
            s.medial == Hangul.MEDIAL_EO &&
                (s.initial == Hangul.INITIAL_SSANGSIOS || s.initial == Hangul.INITIAL_SSANGGIYEOK) ->
                head + Hangul.compose(s.copy(medial = Hangul.MEDIAL_EU))
            else -> stem
        }
        return fixed + "기"
    }

    /** "보낼" → "보내", "먹을" → "먹"; null when the word has no future "-ㄹ". */
    private fun dropRieul(word: String): String? {
        if (word.isEmpty()) return null
        val s = Hangul.decompose(word.last()) ?: return null
        if (word.last() == '을' && word.length > 1) return word.dropLast(1)
        if (s.final != Hangul.FINAL_RIEUL) return null
        return word.dropLast(1) + Hangul.compose(s.copy(final = Hangul.FINAL_NONE))
    }

    /** "먹으" (from 먹으려고) → "먹". */
    private fun dropEu(stem: String): String =
        if (stem.length > 1 && stem.last() == '으') stem.dropLast(1) else stem

    companion object {
        private val CONNECTOR = Regex(
            "\\s+(?:그리고 나서|그리고나서|그 다음에|그다음에|그 다음|그다음|그리고|다음으로|마지막으로|또한|그리고요)\\s+",
        )

        private val FILLERS = setOf(
            "음", "음..", "음...", "으음", "어", "어..", "어...", "아", "아..", "그러니까", "그니까",
            "뭐", "막", "약간", "좀", "흠", "에", "에..", "자", "어어", "음음", "저기", "그게",
        )
        private val LEADING_WORDS = setOf(
            "그", "저", "이제", "일단", "우선", "오늘은", "그럼", "그래서", "그리고", "그리고요", "또",
            "그다음에", "다음으로", "마지막으로", "그리고나서",
        )
        private val LEADING_PHRASES = listOf(
            "오늘 해야 할 일은", "오늘 할 일은", "오늘의 할 일은", "오늘의 할 일", "오늘의 할일", "그 다음에", "그리고 나서", "해야 할 일은", "할 일은", "오늘 할 일",
            "첫 번째로", "첫번째로", "두 번째로", "두번째로", "세 번째로", "세번째로", "첫째", "둘째", "셋째",
        )

        private val ORDINAL = Regex("^(?:\\d{1,2}|하나|둘|셋|넷|다섯)\\s*(?:번째|번)\\s*[.,)]?\\s*")
        // "또 중요한 게", "중요한 건" — emphasis, not part of the action.
        private val EMPHASIS = Regex("^(?:또\\s*)?(?:제일\\s*|가장\\s*|진짜\\s*)?중요한\\s*(?:게|건|거는|것은|거)\\s*")

        private val QUESTION = Regex("(까\\?|나\\?|\\?$|을까$|ㄹ까$|할까$|될까$)")
        private val OBLIGATION = Regex(
            "(야\\s*(돼|된다|되|됨|해|한다|함|하고|겠|지|할|합니다|됩니다)|할\\s*(거|것|게|예정|계획)|" +
                "[ㄹ을]\\s*(거|것)\\s*(야|다|임)|하자|해야지|하려고|잊지\\s*말|챙기|까먹지)",
        )
        private val PAST = Regex("(했다|했어|했음|했고|었다|았다|였다|했습니다|했었)$")
        private val ACTION_WORDS = listOf(
            "보내", "제출", "확인", "연락", "전화", "예약", "구매", "주문", "준비", "정리", "작성",
            "검토", "회의", "미팅", "방문", "들르", "들러", "결제", "납부", "신청", "등록", "수정",
            "업데이트", "발송", "답장", "회신", "출력", "인쇄", "챙기", "사기", "사야", "가져가",
            "가져오", "마감", "처리", "요청", "공유", "업로드", "다운로드", "백업", "청소", "운동",
        )

        private val OBLIGATION_END = Regex(
            "(\\S+?)야\\s*(되고|되구|돼서|돼요|돼|된다|되|됨|해|한다|함|하고|겠다|겠어|겠네|겠음|지|합니다|됩니다|할 듯)$",
        )
        private val FUTURE_END = Regex("(\\S+)\\s+(거야|거다|거임|거예요|것|예정이다|예정|계획이다|계획)$")
        private val FUTURE_GE_END = Regex("(\\S+)게$")
        private val INTENT_END = Regex("(\\S+?)려고(\\s*(해|한다|함|합니다))?$")
        private val LETS_END = Regex("(\\S*하)자$")
        // Only verbs we are sure about; nouns such as "보고"/"광고" also end in "고".
        private val CONNECTIVE_GO_END = Regex("(\\S*(?:하|보내|챙기|들르|가져가|가져오))고$")
    }
}
