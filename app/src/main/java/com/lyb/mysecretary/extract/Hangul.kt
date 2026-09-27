package com.lyb.mysecretary.extract

/** Minimal Hangul syllable (de)composition helpers. */
object Hangul {
    private const val BASE = 0xAC00
    private const val LAST = 0xD7A3

    const val FINAL_NONE = 0
    const val FINAL_RIEUL = 8  // ㄹ
    const val INITIAL_SSANGGIYEOK = 1  // ㄲ
    const val INITIAL_SSANGSIOS = 10  // ㅆ
    const val INITIAL_IEUNG = 11  // ㅇ
    const val INITIAL_HIEUH = 18  // ㅎ

    const val MEDIAL_A = 0  // ㅏ
    const val MEDIAL_AE = 1  // ㅐ
    const val MEDIAL_EO = 4  // ㅓ
    const val MEDIAL_YEO = 6  // ㅕ
    const val MEDIAL_O = 8  // ㅗ
    const val MEDIAL_WA = 9  // ㅘ
    const val MEDIAL_WAE = 10  // ㅙ
    const val MEDIAL_OE = 11  // ㅚ
    const val MEDIAL_U = 13  // ㅜ
    const val MEDIAL_WO = 14  // ㅝ
    const val MEDIAL_EU = 18  // ㅡ
    const val MEDIAL_I = 20  // ㅣ

    data class Syllable(val initial: Int, val medial: Int, val final: Int)

    fun isSyllable(c: Char): Boolean = c.code in BASE..LAST

    fun decompose(c: Char): Syllable? {
        if (!isSyllable(c)) return null
        val code = c.code - BASE
        return Syllable(code / (21 * 28), (code / 28) % 21, code % 28)
    }

    fun compose(s: Syllable): Char = (BASE + (s.initial * 21 + s.medial) * 28 + s.final).toChar()
}
