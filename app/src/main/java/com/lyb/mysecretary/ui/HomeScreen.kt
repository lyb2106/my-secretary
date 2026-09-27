package com.lyb.mysecretary.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lyb.mysecretary.extract.GeminiNanoExtractor
import com.lyb.mysecretary.input.Recording
import com.lyb.mysecretary.pipeline.JobState
import android.net.Uri
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("M/d (E) HH:mm")

@Composable
fun HomeScreen(
    hasPermission: Boolean,
    recordings: List<Recording>,
    selected: Uri?,
    job: JobState,
    gemini: GeminiNanoExtractor.Availability,
    geminiEnabled: Boolean,
    onRequestPermission: () -> Unit,
    onSelect: (Uri) -> Unit,
    onConvert: () -> Unit,
    onPickFile: () -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        if (geminiEnabled) {
            Text(
                "할 일 정리: Gemini Nano ${gemini.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (job is JobState.Running) {
            ProgressCard(job, onCancel)
        } else {
            if (job is JobState.Failed) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("변환 실패", fontWeight = FontWeight.Bold)
                        Text(job.message)
                        TextButton(onClick = onDismissError) { Text("확인") }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (hasPermission) {
                RecordingPicker(recordings, selected, onSelect, onConvert, onPickFile, Modifier.weight(1f))
            } else {
                Text("녹음 파일 목록을 보려면 오디오 접근 권한이 필요합니다.")
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRequestPermission) { Text("권한 허용") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onPickFile) { Text("다른 파일 선택") }
            }
        }
    }
}

@Composable
private fun RecordingPicker(
    recordings: List<Recording>,
    selected: Uri?,
    onSelect: (Uri) -> Unit,
    onConvert: () -> Unit,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text("최근 녹음", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (recordings.isEmpty()) {
            Text(
                "Recordings/Voice Recorder 폴더에서 녹음을 찾지 못했습니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(recordings, key = { it.uri.toString() }) { rec ->
                RecordingRow(rec, rec.uri == selected) { onSelect(rec.uri) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onConvert,
            enabled = selected != null,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("변환", style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) { Text("다른 파일 선택") }
    }
}

@Composable
private fun RecordingRow(rec: Recording, isSelected: Boolean, onClick: () -> Unit) {
    val time = Instant.ofEpochMilli(rec.addedAtMillis).atZone(ZoneId.systemDefault()).format(timeFormat)
    val seconds = rec.durationMs / 1000
    val tooLong = seconds > 5 * 60
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = isSelected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(
                (if (rec.isToday) "오늘 · " else "") + time + " · %d분 %02d초".format(seconds / 60, seconds % 60),
                fontWeight = if (rec.isToday) FontWeight.Bold else FontWeight.Normal,
            )
            Text(rec.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tooLong) {
                Text("5분을 넘는 녹음입니다. 변환 시간이 길어질 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ProgressCard(job: JobState.Running, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val pct = job.progress?.let { " ${(it * 100).toInt()}%" } ?: ""
            Text(job.stage.label + pct, style = MaterialTheme.typography.titleMedium)
            val progress = job.progress
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                "화면을 꺼도 변환은 계속됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onCancel) { Text("취소") }
        }
    }
}
