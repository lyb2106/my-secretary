package com.lyb.mysecretary.extract

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import kotlinx.coroutines.flow.Flow

/**
 * Action extraction with on-device Gemini Nano (ML Kit GenAI Prompt API / AICore).
 * The model lives in the system, so it adds nothing to the APK or to this app's memory.
 */
class GeminiNanoExtractor {

    enum class Availability(val label: String) {
        AVAILABLE("사용 가능"),
        DOWNLOADABLE("다운로드 필요"),
        DOWNLOADING("다운로드 중"),
        UNAVAILABLE("이 기기에서 사용 불가"),
        UNKNOWN("확인 불가"),
    }

    private fun client(): GenerativeModel = Generation.getClient(generationConfig { })

    suspend fun availability(): Availability = try {
        val model = client()
        try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> Availability.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> Availability.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> Availability.DOWNLOADING
                FeatureStatus.UNAVAILABLE -> Availability.UNAVAILABLE
                else -> Availability.UNKNOWN
            }
        } finally {
            model.close()
        }
    } catch (e: Exception) {
        Availability.UNKNOWN
    }

    /** Asks AICore to fetch the model; the caller collects progress. */
    fun download(): Flow<DownloadStatus> = client().download()

    /** Throws when the model is unavailable or inference fails; the caller falls back to rules. */
    suspend fun extract(transcript: String): List<String> {
        val model = client()
        try {
            val lines = ArrayList<String>()
            for (chunk in chunks(transcript)) {
                val request = generateContentRequest(SystemInstruction(SYSTEM), TextPart(prompt(chunk))) {
                    temperature = 0.2f
                    topK = 16
                    maxOutputTokens = 512
                }
                val response = model.generateContent(request)
                val text = response.candidates.firstOrNull()?.text.orEmpty()
                lines += Checklist.parseLines(text)
            }
            val seen = HashSet<String>()
            return lines.filter { it.length <= 120 && seen.add(it.replace(" ", "")) }
        } finally {
            model.close()
        }
    }

    // No concrete example sentences here: with little input the model tends to copy examples
    // verbatim, which produced invented to-dos for a short recording.
    private fun prompt(transcript: String) = """
        아래 <녹음> 안의 글은 사용자가 녹음한 음성을 자동으로 받아쓴 것이다.
        규칙:
        1. <녹음>에 실제로 나온 내용 중, 사용자 본인이 해야 하는 행동만 추출한다.
        2. <녹음>에 없는 내용은 절대 만들지 않는다. 추측하거나 예시를 지어내지 않는다.
        3. 행동 하나당 한 줄로 쓰고, 각 줄은 "- "로 시작한다.
        4. 녹음에 나온 단어를 그대로 살려 개조식 명사형으로 짧게 끝낸다.
        5. 녹음에서 시각을 말했다면 24시간제 HH:MM 형식으로 줄 맨 앞에 쓴다.
        6. 잡담, 감탄사, 감정 표현, 이미 끝난 일, 할 일과 무관한 말은 제외한다.
        7. 해야 할 일이 하나도 없으면 "- 없음" 한 줄만 쓴다.
        8. 목록 외의 설명은 쓰지 않는다.

        <녹음>
        $transcript
        </녹음>
    """.trimIndent()

    /** Keeps each request well inside Gemini Nano's input limit. */
    private fun chunks(text: String): List<String> {
        if (text.length <= CHUNK_CHARS) return listOf(text)
        val sentences = text.split(Regex("(?<=[.!?。\\n])\\s*"))
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (s in sentences) {
            if (current.length + s.length > CHUNK_CHARS && current.isNotEmpty()) {
                out += current.toString()
                current.clear()
            }
            current.append(s).append(' ')
        }
        if (current.isNotBlank()) out += current.toString()
        return out
    }

    companion object {
        private const val CHUNK_CHARS = 1500
        private const val SYSTEM = "너는 사용자의 음성 메모에서 오늘의 할 일만 정확하게 뽑아 체크리스트로 정리하는 비서다."
    }
}
