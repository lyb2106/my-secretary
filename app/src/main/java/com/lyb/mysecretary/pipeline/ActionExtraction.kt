package com.lyb.mysecretary.pipeline

import com.lyb.mysecretary.data.ExtractionMode
import com.lyb.mysecretary.extract.ExtractionMethod
import com.lyb.mysecretary.extract.GeminiNanoExtractor
import com.lyb.mysecretary.extract.Grounding
import com.lyb.mysecretary.extract.RuleBasedExtractor
import kotlinx.coroutines.withTimeout

object ActionExtraction {
    private const val GEMINI_TIMEOUT_MS = 90_000L

    data class Outcome(val actions: List<String>, val method: ExtractionMethod, val note: String? = null)

    suspend fun run(transcript: String, mode: ExtractionMode, gemini: GeminiNanoExtractor): Outcome {
        val method = if (mode == ExtractionMode.RULE_ONLY) ExtractionMethod.RULE_BASED else ExtractionMethod.GEMINI_NANO
        // Nothing (or only fillers) was said: never ask a model to "find" to-dos in it.
        if (Grounding.isEffectivelyEmpty(transcript)) return Outcome(emptyList(), method)
        val rules = { RuleBasedExtractor().extract(transcript) }
        if (mode == ExtractionMode.RULE_ONLY) return Outcome(rules(), ExtractionMethod.RULE_BASED)
        return try {
            // Drop any line that cannot be traced back to the recording.
            val actions = Grounding.filter(withTimeout(GEMINI_TIMEOUT_MS) { gemini.extract(transcript) }, transcript)
            if (actions.isEmpty()) {
                val fallback = rules()
                if (fallback.isNotEmpty()) {
                    Outcome(fallback, ExtractionMethod.RULE_BASED, "Gemini Nano가 할 일을 찾지 못해 규칙 기반 결과를 표시합니다.")
                } else {
                    Outcome(emptyList(), ExtractionMethod.GEMINI_NANO)
                }
            } else {
                Outcome(actions, ExtractionMethod.GEMINI_NANO)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            Outcome(rules(), ExtractionMethod.RULE_BASED, "Gemini Nano를 사용할 수 없어 규칙 기반으로 정리했습니다. (${e.javaClass.simpleName})")
        }
    }
}
