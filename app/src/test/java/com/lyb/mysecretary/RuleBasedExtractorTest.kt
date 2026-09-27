package com.lyb.mysecretary

import com.lyb.mysecretary.extract.RuleBasedExtractor
import com.lyb.mysecretary.extract.TimeNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedExtractorTest {
    private val extractor = RuleBasedExtractor()

    @Test
    fun extractsOneLinePerActionAndSkipsChatter() {
        val transcript = """
            음 오늘 할 일은 김 교수님께 IRB 수정본 메일 보내야 돼.
            아 너무 졸리다. 고양이 밥은 아까 줬고.
            오후 2시 반에 랩미팅 자료 최종 확인해야 함.
            그리고 은행 가서 공과금 납부할 거야. 약국 들러서 비타민 사기.
        """.trimIndent()
        val actions = extractor.extract(transcript)
        assertEquals(
            listOf(
                "김 교수님께 IRB 수정본 메일 보내기",
                "14:30 랩미팅 자료 최종 확인",
                "은행 가서 공과금 납부",
                "약국 들러서 비타민 사기",
            ),
            actions,
        )
    }

    /** testdata/transcript/260927_1.txt (the user's reference transcript). */
    @Test
    fun handlesLineSeparatedMemoWithNumberedItem() {
        val transcript = """
            아아아

            오늘의 할일

            1번 290만원 토스뱅크

            그 다음에 또 중요한게

            내일까지 타이탄바이오 메일보내기

            그리고

            내일까지 마이크로바이옴도 대응해야되고

            그리고

            심혈관 성차 라디오믹스 논문 나머지 70편 평가 마쳐야 함.
        """.trimIndent()
        assertEquals(
            listOf(
                "290만원 토스뱅크",
                "내일까지 타이탄바이오 메일보내기",
                "내일까지 마이크로바이옴도 대응",
                "심혈관 성차 라디오믹스 논문 나머지 70편 평가 마치기",
            ),
            extractor.extract(transcript),
        )
    }

    /** Same memo as whisper small-q5_1 printed it: one line, no punctuation. */
    @Test
    fun handlesSingleLineTranscript() {
        val transcript = "아 오늘의 할 일 2번 290만 원 토스뱅크 그 다음에 또 중요한 게 내일까지 타이탄 바이오 " +
            "매일 보내기 그리고 내일까지 마이크로 바이오드도 대응해야 되고 그리고 심혈관 성차 레디오믹스 논문 " +
            "나머지 70편 평가 맞춰야 함"
        assertEquals(
            listOf(
                "290만 원 토스뱅크",
                "내일까지 타이탄 바이오 매일 보내기",
                "내일까지 마이크로 바이오드도 대응",
                "심혈관 성차 레디오믹스 논문 나머지 70편 평가 맞추기",
            ),
            extractor.extract(transcript),
        )
    }

    @Test
    fun nominalizesVerbStems() {
        assertEquals("챙기기", extractor.nominalize("챙겨"))
        assertEquals("먹기", extractor.nominalize("먹어"))
        assertEquals("하기", extractor.nominalize("해"))
        assertEquals("보기", extractor.nominalize("봐"))
        assertEquals("쓰기", extractor.nominalize("써"))
        assertEquals("보내기", extractor.nominalize("보내"))
    }

    @Test
    fun rewritesEndings() {
        assertEquals("보고서 초안 작성", extractor.toNominal("보고서 초안 작성해야겠다"))
        assertEquals("우산 챙기기", extractor.toNominal("우산 챙겨야 돼"))
        assertEquals("택배 보내기", extractor.toNominal("택배 보낼게"))
        assertEquals("점심 먹기", extractor.toNominal("점심 먹을 거야"))
        assertEquals("주간 보고 정리", extractor.toNominal("주간 보고 정리하자"))
    }

    @Test
    fun actionDetection() {
        assertTrue(extractor.isAction("세미나 발표 자료 준비"))
        assertFalse(extractor.isAction("어제 회의 너무 길었다"))
        assertFalse(extractor.isAction("날씨 좋네"))
        assertFalse(extractor.isAction("회의 몇 시에 하지?"))
    }

    @Test
    fun timeNormalization() {
        assertEquals("09:00", TimeNormalizer.extract("오전 9시에 병원 예약").prefix)
        assertEquals("병원 예약", TimeNormalizer.extract("오전 9시에 병원 예약").text)
        assertEquals("15:00까지", TimeNormalizer.extract("세 시까지 보고서 제출").prefix)
        assertEquals("16:20", TimeNormalizer.extract("16:20에 전화").prefix)
        assertEquals(null, TimeNormalizer.extract("두 시간 동안 집중 작업").prefix)
    }
}
