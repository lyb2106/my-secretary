package com.lyb.mysecretary

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lyb.mysecretary.data.ExtractionMode
import com.lyb.mysecretary.data.HistoryEntry
import com.lyb.mysecretary.pipeline.JobState
import com.lyb.mysecretary.ui.HistoryScreen
import com.lyb.mysecretary.ui.HomeScreen
import com.lyb.mysecretary.ui.MainViewModel
import com.lyb.mysecretary.ui.ResultScreen
import com.lyb.mysecretary.ui.SecretaryTheme
import com.lyb.mysecretary.ui.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SecretaryTheme { AppRoot() } }
    }
}

private const val HOME = "home"
private const val HISTORY = "history"
private const val SETTINGS = "settings"
private const val RESULT = "result:"
private const val SAMSUNG_RECORDER = "com.sec.android.app.voicenote"

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("오늘 할 일", text))
    // Android 13+ shows its own confirmation, so no extra toast.
}

/**
 * Opens Samsung Voice Recorder; falls back to any app that handles the standard
 * "record sound" intent. Returns false when no recorder is available.
 */
private fun openRecorder(context: Context): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage(SAMSUNG_RECORDER)
        ?: Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "할 일 공유"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    var route by rememberSaveable { mutableStateOf(HOME) }
    var resultNote by rememberSaveable { mutableStateOf<String?>(null) }

    fun audioGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    var hasPermission by remember { mutableStateOf(audioGranted()) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = audioGranted()
        vm.refreshRecordings()
    }
    val requestPermissions = {
        permissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
    }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.convertPicked(uri)
    }

    val job by vm.jobState.collectAsStateWithLifecycle()
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val gemini by vm.gemini.collectAsStateWithLifecycle()
    val autoCopy by vm.autoCopy.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (!hasPermission) requestPermissions()
        vm.refreshGemini()
    }
    LifecycleResumeEffect(Unit) {
        hasPermission = audioGranted()
        vm.refreshRecordings()
        // The recorder may finish writing the file just after we come back; refresh on change.
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                vm.refreshRecordings()
            }
        }
        if (hasPermission) {
            context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        }
        onPauseOrDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    LaunchedEffect(job) {
        val done = job as? JobState.Done ?: return@LaunchedEffect
        val entry = withContext(Dispatchers.IO) { vm.entry(done.entryId) }
        if (entry != null && autoCopy && entry.edited.isNotBlank()) copyToClipboard(context, entry.edited)
        resultNote = done.note
        route = RESULT + done.entryId
        vm.acknowledgeJob()
    }

    BackHandler(enabled = route != HOME) { route = HOME }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        when {
                            route == HISTORY -> "최근 기록"
                            route == SETTINGS -> "설정"
                            route.startsWith(RESULT) -> "오늘의 할 일"
                            else -> "나의 아침비서"
                        },
                    )
                },
                navigationIcon = {
                    if (route != HOME) TextButton(onClick = { route = HOME }) { Text("‹ 처음") }
                },
                actions = {
                    if (route == HOME) {
                        TextButton(onClick = { vm.refreshHistory(); route = HISTORY }) { Text("기록") }
                        TextButton(onClick = { vm.refreshWhitelist(); vm.refreshGemini(); route = SETTINGS }) { Text("설정") }
                    }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when {
            route == HISTORY -> {
                val entries by vm.history.collectAsStateWithLifecycle()
                HistoryScreen(
                    entries,
                    onOpen = { resultNote = null; route = RESULT + it },
                    onDelete = vm::deleteHistory,
                    modifier = modifier,
                )
            }
            route == SETTINGS -> {
                val rules by vm.whitelist.collectAsStateWithLifecycle()
                SettingsScreen(
                    autoCopy = autoCopy,
                    mode = mode,
                    gemini = gemini,
                    rules = rules,
                    onAutoCopy = vm::setAutoCopy,
                    onMode = vm::setMode,
                    onDownloadGemini = { vm.downloadGemini() },
                    onAddRule = vm::addRule,
                    onRemoveRule = vm::removeRule,
                    modifier = modifier,
                )
            }
            route.startsWith(RESULT) -> {
                val id = route.removePrefix(RESULT).toLong()
                ResultRoute(vm, id, resultNote, modifier)
            }
            else -> HomeScreen(
                hasPermission = hasPermission,
                recordings = recordings,
                selected = selected,
                job = job,
                gemini = gemini,
                geminiEnabled = mode == ExtractionMode.AUTO,
                onRequestPermission = requestPermissions,
                onSelect = vm::select,
                onConvert = vm::convertSelected,
                onPickFile = { pickLauncher.launch(arrayOf("audio/*")) },
                onRecord = {
                    vm.onRecorderLaunched()
                    if (!openRecorder(context)) {
                        Toast.makeText(context, "음성 녹음 앱을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                },
                onCancel = vm::cancel,
                onDismissError = vm::acknowledgeJob,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun ResultRoute(vm: MainViewModel, id: Long, jobNote: String?, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entry by remember(id) { mutableStateOf<HistoryEntry?>(null) }
    var text by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var note by remember(id) { mutableStateOf(jobNote) }
    var busy by remember(id) { mutableStateOf(false) }

    LaunchedEffect(id) {
        val loaded = withContext(Dispatchers.IO) { vm.entry(id) }
        entry = loaded
        if (text == null) text = loaded?.edited
    }

    // Save (and learn from) edits when leaving the screen.
    val latestText by rememberUpdatedState(text)
    DisposableEffect(id) {
        onDispose { latestText?.let { vm.saveEdit(id, it) } }
    }

    val current = entry ?: return
    val currentText = text ?: current.edited
    ResultScreen(
        entry = current,
        note = note,
        busy = busy,
        text = currentText,
        onTextChange = { text = it },
        onCopy = {
            copyToClipboard(context, currentText)
            vm.saveEdit(id, currentText)
        },
        onShare = {
            vm.saveEdit(id, currentText)
            share(context, currentText)
        },
        onRegenerate = {
            busy = true
            scope.launch {
                vm.saveEdit(id, currentText).join()
                vm.regenerate(id) { updated ->
                    entry = updated
                    text = updated.edited
                    note = vm.busyMessage.value
                    vm.clearBusyMessage()
                    busy = false
                }
            }
        },
        modifier = modifier,
    )
}
