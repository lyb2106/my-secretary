package com.lyb.mysecretary

import com.lyb.mysecretary.stt.FloatArrayBuilder
import com.lyb.mysecretary.stt.StreamingResampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {
    private fun resample(src: Int, dst: Int, seconds: Double, freq: Double, chunk: Int = 1024): FloatArray {
        val n = (src * seconds).toInt()
        val input = FloatArray(n) { (0.5 * sin(2 * PI * freq * it / src)).toFloat() }
        val out = FloatArrayBuilder()
        val r = StreamingResampler(src, dst)
        var pos = 0
        val tmp = FloatArray(chunk)
        while (pos < n) {
            val c = minOf(chunk, n - pos)
            System.arraycopy(input, pos, tmp, 0, c)
            r.push(tmp, c, out)
            pos += c
        }
        r.finish(out)
        return out.toArray()
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double =
        sqrt((from until to).sumOf { (x[it] * x[it]).toDouble() } / (to - from))

    @Test
    fun downsample48kKeepsLengthAndSpeechBandAmplitude() {
        val out = resample(48_000, 16_000, 2.0, 440.0)
        assertTrue("length ${out.size}", abs(out.size - 32_000) <= 1)
        // A 0.5-amplitude sine has RMS 0.3536.
        assertEquals(0.3536, rms(out, 1000, 31_000), 0.01)
    }

    @Test
    fun downsample44kAttenuatesAboveNyquist() {
        val pass = resample(44_100, 16_000, 1.0, 1000.0)
        val stop = resample(44_100, 16_000, 1.0, 12_000.0)
        assertTrue(abs(pass.size - 16_000) <= 1)
        assertEquals(0.3536, rms(pass, 500, 15_500), 0.01)
        assertTrue("alias rms ${rms(stop, 500, 15_500)}", rms(stop, 500, 15_500) < 0.02)
    }
}
