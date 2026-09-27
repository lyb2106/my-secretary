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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lyb.mysecretary.BuildConfig
import com.lyb.mysecretary.data.ExtractionMode
import com.lyb.mysecretary.extract.GeminiNanoExtractor
import com.lyb.mysecretary.learn.Correction

@Composable
fun SettingsScreen(
    autoCopy: Boolean,
    mode: ExtractionMode,
    gemini: GeminiNanoExtractor.Availability,
    rules: List<Correction>,
    onAutoCopy: (Boolean) -> Unit,
    onMode: (ExtractionMode) -> Unit,
    onDownloadGemini: () -> Unit,
    onAddRule: (String, String) -> Unit,
    onRemoveRule: (Correction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("변환 후 자동으로 클립보드에 복사", style = MaterialTheme.typography.titleSmall)
            }
            Switch(checked = autoCopy, onCheckedChange = onAutoCopy)
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("할 일 정리 방식", style = MaterialTheme.typography.titleSmall)
        ExtractionMode.entries.forEach { m ->
            Row(Modifier.fillMaxWidth().clickable { onMode(m) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = m == mode, onClick = { onMode(m) })
                Text(m.label)
            }
        }
        Text("Gemini Nano 상태: ${gemini.label}", style = MaterialTheme.typography.bodySmall)
        if (gemini == GeminiNanoExtractor.Availability.DOWNLOADABLE) {
            TextButton(onClick = onDownloadGemini) { Text("Gemini Nano 모델 받기 (시스템이 다운로드)") }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("교정 사전 (whitelist)", style = MaterialTheme.typography.titleSmall)
        Text(
            "결과를 수정한 뒤 복사/공유하면 바뀐 단어를 기록합니다. 같은 교정이 2회 이상 나오면 자동 적용되고, " +
                "음성 인식 단계에서도 해당 단어를 우선 인식하도록 힌트로 사용합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        var wrong by rememberSaveable { mutableStateOf("") }
        var right by rememberSaveable { mutableStateOf("") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(wrong, { wrong = it }, Modifier.weight(1f), label = { Text("잘못 인식") }, singleLine = true)
            OutlinedTextField(right, { right = it }, Modifier.weight(1f), label = { Text("올바른 표기") }, singleLine = true)
        }
        Button(
            onClick = { onAddRule(wrong, right); wrong = ""; right = "" },
            enabled = wrong.isNotBlank() && right.isNotBlank(),
        ) { Text("추가") }
        Spacer(Modifier.height(8.dp))
        if (rules.isEmpty()) {
            Text("아직 기록된 교정이 없습니다.", style = MaterialTheme.typography.bodySmall)
        }
        rules.sortedWith(compareByDescending<Correction> { it.active }.thenByDescending { it.count }).forEach { rule ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${rule.wrong} → ${rule.right}")
                    Text(
                        when {
                            rule.manual -> "직접 추가 · 적용 중"
                            rule.active -> "${rule.count}회 · 적용 중"
                            else -> "${rule.count}회 · 한 번 더 수정하면 적용"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onRemoveRule(rule) }) { Text("삭제") }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(
            "음성 인식 모델: ${BuildConfig.WHISPER_MODEL} (whisper.cpp, 기기 내 CPU)\n버전 ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
