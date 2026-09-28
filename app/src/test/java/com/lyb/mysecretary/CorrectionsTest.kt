package com.lyb.mysecretary

import com.lyb.mysecretary.extract.Checklist
import com.lyb.mysecretary.learn.Correction
import com.lyb.mysecretary.learn.Corrections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CorrectionsTest {
    @Test
    fun learnsWholeWordsNotSyllables() {
        // Previously mined as "영 → 형" and "매 → 메".
        assertEquals(listOf("김보영" to "김보형"), Corrections.mine("- [ ] 김보영 교수님께 연락", "- [ ] 김보형 교수님께 연락"))
        assertEquals(listOf("매일" to "메일"), Corrections.mine("- [ ] 타이탄바이오 매일 보내기", "- [ ] 타이탄바이오 메일 보내기"))
    }

    @Test
    fun skipsMergedOrRewrittenWordsButKeepsTheWordFix() {
        // "현 교수님" → "윤교수님" is a merge (name); only "내연권" → "뇌연구원" is a word fix.
        assertEquals(
            listOf("내연권" to "뇌연구원"),
            Corrections.mine("- [ ] 현 교수님 내연권 방문", "- [ ] 윤교수님 뇌연구원 방문"),
        )
    }

    @Test
    fun stripsOnlyASharedParticle() {
        assertEquals(listOf("아이알비" to "IRB"), Corrections.mine("아이알비를 수정해서 제출", "IRB를 수정해서 제출"))
    }

    @Test
    fun ignoresSpacingTimeAndUnrelatedRewrites() {
        assertEquals(emptyList<Pair<String, String>>(), Corrections.mine("- [ ] 랩 미팅 준비", "- [ ] 랩미팅 준비"))
        assertEquals(emptyList<Pair<String, String>>(), Corrections.mine("- [ ] 14:00 미팅", "- [ ] 15:00 미팅"))
        assertEquals(emptyList<Pair<String, String>>(), Corrections.mine("- [ ] 은행 방문", "- [ ] 서류 제출"))
    }

    @Test
    fun appliesOnlyToWholeWords() {
        val rules = listOf(
            Correction("김보영", "김보형", 2),
            Correction("매일", "메일", 2),
        )
        assertEquals("김보형 교수님께 메일을 보내기", Corrections.apply("김보영 교수님께 매일을 보내기", rules))
        // Words that merely contain the syllables are untouched.
        assertEquals("영어 매일매일 박영", Corrections.apply("영어 매일매일 박영", rules))
    }

    @Test
    fun rulesActivateAfterTwoObservations() {
        var rules = Corrections.merge(emptyList(), listOf("아이알비" to "IRB"))
        assertEquals("아이알비를 제출", Corrections.apply("아이알비를 제출", rules))
        rules = Corrections.merge(rules, listOf("아이알비" to "IRB"))
        assertEquals("IRB를 제출", Corrections.apply("아이알비를 제출", rules))
        assertEquals("IRB", Corrections.vocabularyPrompt(rules))
    }

    @Test
    fun oldSyllableAndPhraseRulesAreInvalid() {
        assertFalse(Corrections.isValidRule(Correction("영", "형", 3)))
        assertFalse(Corrections.isValidRule(Correction("현 교수님 내연권", "윤교수님 뇌연구원", 2)))
        assertTrue(Corrections.isValidRule(Correction("내연권", "뇌연구원", 2)))
        // Invalid rules are never applied even if stored.
        assertEquals("영어", Corrections.apply("영어", listOf(Correction("영", "형", 5))))
    }

    @Test
    fun checklistFormatAndParse() {
        val text = Checklist.format(LocalDate.of(2026, 9, 27), listOf("A 하기", "B 확인"))
        assertEquals("## 2026-09-27 할 일\n- [ ] A 하기\n- [ ] B 확인", text)
        assertEquals("", Checklist.format(LocalDate.of(2026, 9, 27), emptyList()))
        assertEquals(listOf("메일 발송", "자료 확인"), Checklist.parseLines("- 메일 발송\n* 자료 확인.\n- 메일 발송\n- 없음"))
        assertEquals(listOf("X"), Checklist.parseLines("1. X"))
    }
}
