package com.lyb.mysecretary

import com.lyb.mysecretary.extract.Grounding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundingTest {
    @Test
    fun dropsInventedActions() {
        val transcript = "아 오늘 날씨 진짜 좋다 커피나 한잔 마셔야지"
        val model = listOf("김 교수님께 IRB 수정본 메일 발송", "14:30 랩미팅 자료 최종 확인", "커피 한잔 마시기")
        assertEquals(listOf("커피 한잔 마시기"), Grounding.filter(model, transcript))
    }

    @Test
    fun keepsRephrasedActions() {
        val transcript = "내일까지 타이탄바이오 메일 보내야 돼. 두 시 반에 랩미팅 자료 확인해야 함"
        val model = listOf("내일까지 타이탄바이오 메일 발송", "14:30 랩미팅 자료 확인")
        assertEquals(model, Grounding.filter(model, transcript))
    }

    @Test
    fun removesSilenceHallucinations() {
        assertEquals("", Grounding.cleanTranscript("시청해 주셔서 감사합니다."))
        assertTrue(Grounding.isEffectivelyEmpty(Grounding.cleanTranscript("음... 아.\n구독과 좋아요 부탁드려요.")))
    }
}
