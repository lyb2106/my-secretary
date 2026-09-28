package com.lyb.mysecretary.pipeline

import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import com.lyb.mysecretary.App
import com.lyb.mysecretary.data.HistoryEntry
import com.lyb.mysecretary.data.ProcessingStats
import com.lyb.mysecretary.device.ThermalGuard
import com.lyb.mysecretary.extract.Checklist
import com.lyb.mysecretary.extract.Grounding
import com.lyb.mysecretary.learn.Corrections
import com.lyb.mysecretary.stt.AudioDecoder
import com.lyb.mysecretary.stt.SpeechToText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId

enum class Stage(val label: String) {
    DECODING("오디오 변환 중"),
    TRANSCRIBING("음성 인식 중"),
    EXTRACTING("할 일 정리 중"),
}

sealed interface JobState {
    data object Idle : JobState
    data class Running(val stage: Stage, val progress: Float?) : JobState
    data class Done(val entryId: Long, val note: String?) : JobState
    data class Failed(val message: String) : JobState
}

/** Single-job pipeline: decode → Whisper → whitelist → action extraction → history. */
object Pipeline {
    private val _state = MutableStateFlow<JobState>(JobState.Idle)
    val state: StateFlow<JobState> = _state.asStateFlow()

    @Volatile private var stt: SpeechToText? = null
    @Volatile private var thermalAbort = false

    fun isRunning() = _state.value is JobState.Running

    fun reset() {
        if (!isRunning()) _state.value = JobState.Idle
    }

    fun cancel() {
        stt?.abort()
    }

    suspend fun run(app: App, uri: Uri, name: String, recordingTime: Long) {
        val started = SystemClock.elapsedRealtime()
        val thermal = ThermalGuard(app)
        var peak = thermal.current()
        thermalAbort = false
        val speech = SpeechToText(app).also { stt = it }
        val watch = thermal.watch { status ->
            peak = maxOf(peak, status)
            if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                thermalAbort = true
                speech.abort()
            }
        }
        try {
            _state.value = JobState.Running(Stage.DECODING, 0f)
            val samples = AudioDecoder.decode(app, uri) { p ->
                _state.value = JobState.Running(Stage.DECODING, p)
            }
            val audioSeconds = samples.size / AudioDecoder.TARGET_RATE.toDouble()

            _state.value = JobState.Running(Stage.TRANSCRIBING, 0f)
            val rules = app.whitelist.all()
            val threads = thermal.threadsFor(thermal.current())
            val raw = try {
                speech.transcribe(samples, threads, Corrections.vocabularyPrompt(rules)) { pct ->
                    _state.value = JobState.Running(Stage.TRANSCRIBING, pct / 100f)
                }
            } catch (e: SpeechToText.SttException) {
                if (thermalAbort) throw SpeechToText.SttException("기기 발열이 심해 변환을 중단했습니다. 잠시 후 다시 시도하세요.")
                throw e
            }
            val transcript = Corrections.apply(Grounding.cleanTranscript(raw), rules)

            _state.value = JobState.Running(Stage.EXTRACTING, null)
            val outcome = ActionExtraction.run(transcript, app.settings.extractionMode, app.gemini)
            val date = Instant.ofEpochMilli(recordingTime).atZone(ZoneId.systemDefault()).toLocalDate()
            val checklist = Checklist.format(date, outcome.actions)

            val entry = HistoryEntry(
                id = System.currentTimeMillis(),
                recordingName = name,
                recordingTime = recordingTime,
                transcript = transcript,
                generated = checklist,
                edited = checklist,
                method = outcome.method,
                stats = ProcessingStats(
                    elapsedMs = SystemClock.elapsedRealtime() - started,
                    audioSeconds = audioSeconds,
                    model = speech.modelName,
                    threads = threads,
                    peakThermal = peak,
                ),
            )
            app.history.save(entry)
            _state.value = JobState.Done(entry.id, outcome.note)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                _state.value = JobState.Failed("변환이 취소되었습니다.")
                throw e
            }
            _state.value = JobState.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            watch.close()
            stt = null
        }
    }
}
