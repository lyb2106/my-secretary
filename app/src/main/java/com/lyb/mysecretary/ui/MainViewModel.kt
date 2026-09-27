package com.lyb.mysecretary.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lyb.mysecretary.App
import com.lyb.mysecretary.data.ExtractionMode
import com.lyb.mysecretary.data.HistoryEntry
import com.lyb.mysecretary.extract.Checklist
import com.lyb.mysecretary.extract.GeminiNanoExtractor
import com.lyb.mysecretary.input.Recording
import com.lyb.mysecretary.input.RecordingRepository
import com.lyb.mysecretary.learn.Correction
import com.lyb.mysecretary.pipeline.ActionExtraction
import com.lyb.mysecretary.pipeline.Pipeline
import com.lyb.mysecretary.pipeline.TranscriptionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val recordingsRepo = RecordingRepository(application)

    val jobState = Pipeline.state

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    private val _selected = MutableStateFlow<Uri?>(null)
    val selected: StateFlow<Uri?> = _selected.asStateFlow()

    private val _gemini = MutableStateFlow(GeminiNanoExtractor.Availability.UNKNOWN)
    val gemini: StateFlow<GeminiNanoExtractor.Availability> = _gemini.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _whitelist = MutableStateFlow<List<Correction>>(emptyList())
    val whitelist: StateFlow<List<Correction>> = _whitelist.asStateFlow()

    private val _autoCopy = MutableStateFlow(app.settings.autoCopy)
    val autoCopy: StateFlow<Boolean> = _autoCopy.asStateFlow()

    private val _mode = MutableStateFlow(app.settings.extractionMode)
    val mode: StateFlow<ExtractionMode> = _mode.asStateFlow()

    private val _busyMessage = MutableStateFlow<String?>(null)
    val busyMessage: StateFlow<String?> = _busyMessage.asStateFlow()

    fun refreshRecordings() = viewModelScope.launch {
        val list = withContext(Dispatchers.IO) { runCatching { recordingsRepo.recent() }.getOrDefault(emptyList()) }
        _recordings.value = list
        if (_selected.value == null || list.none { it.uri == _selected.value }) {
            _selected.value = (list.firstOrNull { it.isToday } ?: list.firstOrNull())?.uri
        }
    }

    fun select(uri: Uri) {
        _selected.value = uri
    }

    fun convertSelected() {
        val rec = _recordings.value.firstOrNull { it.uri == _selected.value } ?: return
        TranscriptionService.start(app, rec.uri, rec.name, rec.addedAtMillis)
    }

    fun convertPicked(uri: Uri) {
        val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "선택한 파일"
        TranscriptionService.start(app, uri, name, System.currentTimeMillis())
    }

    fun cancel() = TranscriptionService.cancel(app)

    fun acknowledgeJob() = Pipeline.reset()

    fun refreshGemini() = viewModelScope.launch {
        if (_mode.value == ExtractionMode.RULE_ONLY) return@launch
        _gemini.value = app.gemini.availability()
    }

    fun downloadGemini() = viewModelScope.launch {
        _gemini.value = GeminiNanoExtractor.Availability.DOWNLOADING
        runCatching { app.gemini.download().collect { } }
        _gemini.value = app.gemini.availability()
    }

    fun entry(id: Long): HistoryEntry? = app.history.get(id)

    fun refreshHistory() = viewModelScope.launch {
        _history.value = withContext(Dispatchers.IO) { app.history.all() }
    }

    /** Persists the user's edit and learns corrections from what changed since the last save. */
    fun saveEdit(id: Long, text: String) = viewModelScope.launch(Dispatchers.IO) {
        val entry = app.history.get(id) ?: return@launch
        if (entry.edited == text) return@launch
        app.whitelist.learn(entry.edited, text)
        app.history.save(entry.copy(edited = text))
        _whitelist.value = app.whitelist.all()
    }

    /** Re-runs action extraction (Gemini Nano first) on a stored transcript. */
    fun regenerate(id: Long, onDone: (HistoryEntry) -> Unit) = viewModelScope.launch {
        val entry = app.history.get(id) ?: return@launch
        _busyMessage.value = "AI로 다시 정리하는 중…"
        val outcome = withContext(Dispatchers.Default) {
            ActionExtraction.run(entry.transcript, ExtractionMode.AUTO, app.gemini)
        }
        val date = Instant.ofEpochMilli(entry.recordingTime).atZone(ZoneId.systemDefault()).toLocalDate()
        val checklist = Checklist.format(date, outcome.actions)
        val updated = entry.copy(generated = checklist, edited = checklist, method = outcome.method)
        withContext(Dispatchers.IO) { app.history.save(updated) }
        _busyMessage.value = outcome.note
        onDone(updated)
    }

    fun clearBusyMessage() {
        _busyMessage.value = null
    }

    fun setAutoCopy(value: Boolean) {
        app.settings.autoCopy = value
        _autoCopy.value = value
    }

    fun setMode(value: ExtractionMode) {
        app.settings.extractionMode = value
        _mode.value = value
        refreshGemini()
    }

    fun refreshWhitelist() {
        _whitelist.value = app.whitelist.all()
    }

    fun addRule(wrong: String, right: String) {
        app.whitelist.addManual(wrong, right)
        refreshWhitelist()
    }

    fun removeRule(rule: Correction) {
        app.whitelist.remove(rule)
        refreshWhitelist()
    }
}
