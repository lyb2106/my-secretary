package com.lyb.mysecretary.input

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Recording(
    val uri: Uri,
    val name: String,
    val addedAtMillis: Long,
    val durationMs: Long,
) {
    val isToday: Boolean
        get() = Instant.ofEpochMilli(addedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
}

/** Lists recent recordings, preferring Samsung Voice Recorder's folder (Recordings/Voice Recorder). */
class RecordingRepository(private val context: Context) {

    fun recent(limit: Int = 10): List<Recording> {
        val fromRecorder = query("${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?", arrayOf("Recordings/%"), limit)
        return fromRecorder.ifEmpty { query(null, null, limit) }
    }

    private fun query(selection: String?, args: Array<String>?, limit: Int): List<Recording> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DURATION,
        )
        val result = ArrayList<Recording>()
        context.contentResolver.query(
            collection, projection, selection, args, "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext() && result.size < limit) {
                result += Recording(
                    uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                    name = c.getString(nameCol) ?: "(이름 없음)",
                    addedAtMillis = c.getLong(addedCol) * 1000,
                    durationMs = c.getLong(durCol),
                )
            }
        }
        return result
    }
}
