package com.lyb.mysecretary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lyb.mysecretary.data.HistoryEntry
import com.lyb.mysecretary.device.ThermalGuard
import com.lyb.mysecretary.extract.ExtractionMethod

@Composable
fun ResultScreen(
    entry: HistoryEntry,
    note: String?,
    busy: Boolean,
    onTextChange: (String) -> Unit,
    text: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showTranscript by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                textStyle = MaterialTheme.typography.bodyLarge,
                label = { Text("할 일 (직접 수정 가능)") },
            )
            Spacer(Modifier.height(8.dp))
            val s = entry.stats
            Text(
                "정리: ${entry.method.label} · 모델 ${s.model.removePrefix("ggml-").removeSuffix(".bin")} · " +
                    "처리 %.1f초 (녹음 %.0f초) · 스레드 %d · 최고 발열 %s".format(
                        s.elapsedMs / 1000.0, s.audioSeconds, s.threads, ThermalGuard.label(s.peakThermal),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            if (entry.method == ExtractionMethod.RULE_BASED) {
                TextButton(onClick = onRegenerate, enabled = !busy) {
                    Text(if (busy) "정리하는 중…" else "AI(Gemini Nano)로 다시 정리")
                }
            }
            TextButton(onClick = { showTranscript = !showTranscript }) {
                Text(if (showTranscript) "원본 전사문 숨기기" else "원본 전사문 보기")
            }
            if (showTranscript) {
                SelectionContainer {
                    Text(
                        entry.transcript.ifBlank { "(인식된 음성이 없습니다)" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Default,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onCopy, modifier = Modifier.weight(1f).height(56.dp)) { Text("전체 복사") }
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f).height(56.dp)) { Text("공유") }
        }
    }
}
