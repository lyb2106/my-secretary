package com.lyb.mysecretary.data

import android.content.Context
import com.lyb.mysecretary.extract.ExtractionMethod
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ProcessingStats(
    val elapsedMs: Long,
    val audioSeconds: Double,
    val model: String,
    val threads: Int,
    val peakThermal: Int,
)

data class HistoryEntry(
    val id: Long,
    val recordingName: String,
    val recordingTime: Long,
    val transcript: String,
    val generated: String,
    val edited: String,
    val method: ExtractionMethod,
    val stats: ProcessingStats,
)

/** Last 7 days of results, as a small JSON file (text only; audio is never copied). */
class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.json")
    private val lock = Any()

    fun all(): List<HistoryEntry> = synchronized(lock) { read() }

    fun get(id: Long): HistoryEntry? = all().firstOrNull { it.id == id }

    fun save(entry: HistoryEntry) = synchronized(lock) {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val entries = read().filter { it.id != entry.id && it.id >= cutoff } + entry
        write(entries.sortedByDescending { it.id })
    }

    private fun read(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun write(entries: List<HistoryEntry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(toJson(it)) }
        val tmp = File(file.parentFile, "history.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    private fun toJson(e: HistoryEntry) = JSONObject()
        .put("id", e.id)
        .put("recordingName", e.recordingName)
        .put("recordingTime", e.recordingTime)
        .put("transcript", e.transcript)
        .put("generated", e.generated)
        .put("edited", e.edited)
        .put("method", e.method.name)
        .put("elapsedMs", e.stats.elapsedMs)
        .put("audioSeconds", e.stats.audioSeconds)
        .put("model", e.stats.model)
        .put("threads", e.stats.threads)
        .put("peakThermal", e.stats.peakThermal)

    private fun fromJson(o: JSONObject) = HistoryEntry(
        id = o.getLong("id"),
        recordingName = o.optString("recordingName"),
        recordingTime = o.optLong("recordingTime"),
        transcript = o.optString("transcript"),
        generated = o.optString("generated"),
        edited = o.optString("edited"),
        method = runCatching { ExtractionMethod.valueOf(o.optString("method")) }.getOrDefault(ExtractionMethod.RULE_BASED),
        stats = ProcessingStats(
            elapsedMs = o.optLong("elapsedMs"),
            audioSeconds = o.optDouble("audioSeconds", 0.0),
            model = o.optString("model"),
            threads = o.optInt("threads"),
            peakThermal = o.optInt("peakThermal"),
        ),
    )

    companion object {
        private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
