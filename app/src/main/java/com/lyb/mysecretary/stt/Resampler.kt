package com.lyb.mysecretary.stt

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** Growable float buffer that avoids boxing. */
class FloatArrayBuilder(initialCapacity: Int = 16_000) {
    private var data = FloatArray(maxOf(16, initialCapacity))
    var size = 0
        private set

    fun add(value: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

/**
 * Streaming band-limited resampler (Hann-windowed sinc, table lookup).
 *
 * Audio is fed in chunks as it is decoded, so the full source-rate signal never has to be
 * held in memory at once.
 */
class StreamingResampler(
    private val srcRate: Int,
    private val dstRate: Int,
    halfTaps: Int = 10,
) {
    private val step = srcRate.toDouble() / dstRate
    // Low-pass at 95 % of the lower Nyquist frequency, expressed in source-sample units.
    private val cutoff = min(1.0, dstRate.toDouble() / srcRate) * 0.95
    private val halfWidth = halfTaps / cutoff
    private val table: FloatArray
    private val tableRes = 256

    private var buf = FloatArray(4096)
    private var bufStart = 0L  // absolute index of buf[0]
    private var bufLen = 0
    private var outIndex = 0L
    private var totalIn = 0L

    init {
        val n = ceil(halfWidth * tableRes).toInt() + 2
        table = FloatArray(n) { i ->
            val x = i.toDouble() / tableRes
            if (x > halfWidth) 0f else {
                val y = cutoff * x
                val sinc = if (y == 0.0) 1.0 else sin(PI * y) / (PI * y)
                val window = 0.5 * (1.0 + cos(PI * x / halfWidth))
                (cutoff * sinc * window).toFloat()
            }
        }
    }

    private fun kernel(distance: Double): Float {
        val pos = distance * tableRes
        val i = pos.toInt()
        if (i + 1 >= table.size) return 0f
        val frac = (pos - i).toFloat()
        return table[i] + (table[i + 1] - table[i]) * frac
    }

    fun push(samples: FloatArray, count: Int, out: FloatArrayBuilder) {
        ensureCapacity(bufLen + count)
        System.arraycopy(samples, 0, buf, bufLen, count)
        bufLen += count
        totalIn += count
        produce(out)
    }

    /** Emits the remaining output samples (zero-padded tail). */
    fun finish(out: FloatArrayBuilder) {
        val pad = ceil(halfWidth).toInt() + 2
        ensureCapacity(bufLen + pad)
        java.util.Arrays.fill(buf, bufLen, bufLen + pad, 0f)
        bufLen += pad
        produce(out)
    }

    private fun produce(out: FloatArrayBuilder) {
        val expectedTotal = floor(totalIn / step).toLong()
        while (outIndex < expectedTotal) {
            val t = outIndex * step
            val last = floor(t + halfWidth).toLong()
            if (last >= bufStart + bufLen) break
            val first = ceil(t - halfWidth).toLong()
            var acc = 0.0
            var wsum = 0.0
            var i = first
            while (i <= last) {
                val w = kernel(abs(t - i))
                wsum += w
                if (i >= bufStart) acc += w * buf[(i - bufStart).toInt()]
                i++
            }
            out.add(if (wsum > 1e-6) (acc / wsum).toFloat() else 0f)
            outIndex++
        }
        compact()
    }

    private fun compact() {
        val keepFrom = ceil(outIndex * step - halfWidth).toLong() - 1
        val drop = (keepFrom - bufStart).coerceIn(0, bufLen.toLong()).toInt()
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            bufStart += drop
        }
    }

    private fun ensureCapacity(n: Int) {
        if (n > buf.size) buf = buf.copyOf(maxOf(n, buf.size * 2))
    }
}
