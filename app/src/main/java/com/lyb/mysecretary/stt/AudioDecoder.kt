package com.lyb.mysecretary.stt

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Decodes an audio file (Samsung Voice Recorder writes AAC in .m4a) with the platform codecs
 * and returns 16 kHz mono float PCM, as expected by Whisper.
 */
object AudioDecoder {
    const val TARGET_RATE = 16_000
    private const val TIMEOUT_US = 10_000L

    class DecodeException(message: String) : Exception(message)

    fun decode(context: Context, uri: Uri, onProgress: (Float) -> Unit = {}): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw DecodeException("오디오 트랙을 찾을 수 없습니다.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return drain(codec, extractor, format, durationUs, onProgress)
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun drain(
        codec: MediaCodec,
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        durationUs: Long,
        onProgress: (Float) -> Unit,
    ): FloatArray {
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val expected = if (durationUs > 0) (durationUs / 1_000_000.0 * TARGET_RATE).toInt() + TARGET_RATE else 16 * TARGET_RATE
        val out = FloatArrayBuilder(expected)
        var resampler: StreamingResampler? = null
        var mono = FloatArray(8192)

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                        extractor.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                }
                outIndex >= 0 -> {
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val frames: Int
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = buffer.asFloatBuffer()
                            frames = fb.remaining() / channels
                            if (mono.size < frames) mono = FloatArray(frames)
                            for (i in 0 until frames) {
                                var sum = 0f
                                for (c in 0 until channels) sum += fb.get()
                                mono[i] = sum / channels
                            }
                        } else {
                            val sb = buffer.asShortBuffer()
                            frames = sb.remaining() / channels
                            if (mono.size < frames) mono = FloatArray(frames)
                            for (i in 0 until frames) {
                                var sum = 0f
                                for (c in 0 until channels) sum += sb.get()
                                mono[i] = sum / (channels * 32768f)
                            }
                        }
                        if (sampleRate == TARGET_RATE) {
                            for (i in 0 until frames) out.add(mono[i])
                        } else {
                            val r = resampler ?: StreamingResampler(sampleRate, TARGET_RATE).also { resampler = it }
                            r.push(mono, frames, out)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        resampler?.finish(out)
        if (out.size == 0) throw DecodeException("디코딩된 오디오가 비어 있습니다.")
        return out.toArray()
    }
}
