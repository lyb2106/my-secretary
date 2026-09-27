package com.lyb.mysecretary

import com.lyb.mysecretary.extract.Checklist
import com.lyb.mysecretary.learn.Correction
import com.lyb.mysecretary.learn.Corrections
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class CorrectionsTest {
    @Test
    fun minesSubstitutionAndStripsParticle() {
        val original = "- [ ] 아이알비를 수정해서 제출\n- [ ] 랩 미팅 준비"
        val edited = "- [ ] IRB를 수정해서 제출\n- [ ] 랩미팅 준비"
        val pairs = Corrections.mine(original, edited)
        assertEquals(listOf("아이알비" to "IRB", "랩 미팅" to "랩미팅"), pairs)
    }

    @Test
    fun rulesActivateAfterTwoObservationsAndKeepParticles() {
        var rules = Corrections.merge(emptyList(), listOf("아이알비" to "IRB"))
        assertEquals("아이알비를 제출", Corrections.apply("아이알비를 제출", rules))
        rules = Corrections.merge(rules, listOf("아이알비" to "IRB"))
        assertEquals("IRB를 제출", Corrections.apply("아이알비를 제출", rules))
        assertEquals("IRB", Corrections.vocabularyPrompt(rules))
    }

    @Test
    fun manualRuleIsActiveImmediately() {
        val rules = listOf(Correction("엑셀", "Excel", 0, manual = true))
        assertEquals("Excel 파일 정리", Corrections.apply("엑셀 파일 정리", rules))
    }

    @Test
    fun ignoresPureTimeEdits() {
        assertEquals(emptyList<Pair<String, String>>(), Corrections.mine("- [ ] 14:00 미팅", "- [ ] 15:00 미팅"))
    }

    @Test
    fun checklistFormatAndParse() {
        val text = Checklist.format(LocalDate.of(2026, 9, 27), listOf("A 하기", "B 확인"))
        assertEquals("## 2026-09-27 할 일\n- [ ] A 하기\n- [ ] B 확인", text)
        assertEquals(listOf("메일 발송", "자료 확인"), Checklist.parseLines("- 메일 발송\n* 자료 확인.\n- 메일 발송\n- 없음"))
        assertEquals(listOf("X"), Checklist.parseLines("1. X"))
    }
}
