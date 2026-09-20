package com.scriptreader.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val controller by lazy { ReaderController(this) }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }

    @Composable
    private fun App() {
        val scope = rememberCoroutineScope()

        // ---- 状态 ----
        var engineReady by remember { mutableStateOf(controller.engineReady) }
        var downloading by remember { mutableStateOf(!controller.isModelReady()) }
        var progress by remember { mutableFloatStateOf(0f) }
        var progressText by remember { mutableStateOf("准备下载模型…") }
        var sentences by remember { mutableStateOf(listOf<String>()) }
        var currentIndex by remember { mutableIntStateOf(0) }
        var recognized by remember { mutableStateOf("") }
        var listening by remember { mutableStateOf(false) }
        var followScroll by remember { mutableStateOf(true) }
        var lastMatched by remember { mutableStateOf(-1) }

        val listState = rememberLazyListState()

        // ---- 权限 ----
        val permLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }
        fun requestMic(): Boolean {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) return true
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return false
        }

        // ---- 模型下载 ----
        LaunchedEffect(Unit) {
            if (!engineReady) {
                controller.prepare(
                    onProgress = { p ->
                        downloading = true
                        progress = if (p.totalBytes > 0) (p.doneBytes.toFloat() / p.totalBytes) else progress
                        progressText = "${p.name} ${mb(p.doneBytes)}/${mb(p.totalBytes)}"
                    },
                    onDone = { ok ->
                        downloading = false
                        if (ok) {
                            engineReady = true
                        } else {
                            Toast.makeText(this@MainActivity, "模型下载失败，请检查网络后重试", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
        }

        controller.listener = object : ReaderController.Listener {
            override fun onRecognized(text: String, index: Int, percent: Int) {
                recognized = text
                if (index >= 0) {
                    currentIndex = index
                    lastMatched = index
                    if (followScroll) {
                        scope.launch { listState.animateScrollToItem(index) }
                    }
                }
            }
            override fun onListeningChanged(l: Boolean) { listening = l }
        }

        // ---- 稿子导入 ----
        fun setScript(text: String) {
            val script = ScriptLoader.fromText(text)
            sentences = script.sentences
            controller.matcher = ScriptMatcher(script)
            currentIndex = 0
            lastMatched = -1
            recognized = ""
        }

        fun loadScript(uri: Uri) {
            val text = try {
                ScriptTextExtractor.extract(this, uri, uri.lastPathSegment ?: "script")
            } catch (e: Exception) {
                Toast.makeText(this, "读取失败: ${e.message}", Toast.LENGTH_LONG).show()
                return
            }
            setScript(text)
        }

        val filePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) loadScript(uri)
        }

        // =================== 界面 ===================
        MaterialTheme {
            Scaffold { padding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    // 顶栏
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("稿读", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { filePicker.launch(arrayOf("text/plain", "application/pdf", "text/*")) }) {
                            Text("导入")
                        }
                    }

                    when {
                        downloading -> DownloadView(progress, progressText)
                        else -> {
                            if (sentences.isEmpty()) {
                                EmptyView(onPick = { filePicker.launch(arrayOf("text/plain", "application/pdf", "text/*")) })
                            } else {
                                ScriptView(
                                    sentences, currentIndex, lastMatched > 0 /*有定位*/, listState
                                )
                                BottomBar(
                                    listening = listening,
                                    recognized = recognized,
                                    percent = if (sentences.isNotEmpty()) (currentIndex * 100 / sentences.size).coerceIn(0, 100) else 0,
                                    onToggle = {
                                        if (listening) controller.stop()
                                        else if (requestMic()) controller.start()
                                    },
                                    onFollowToggle = { followScroll = !followScroll },
                                    followScroll = followScroll,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun mb(bytes: Long): String = String.format("%.1f MB", bytes / 1048576.0)
}

@Composable
private fun DownloadView(progress: Float, text: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("首次使用需要下载识别模型（约 230MB）", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(progress = progress.coerceIn(0f, 1f), Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Text(text)
        Spacer(Modifier.height(8.dp))
        Text("模型下载一次后即可完全离线使用", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyView(onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("还没有稿子", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text("点击下面按钮导入你的稿子（支持 .txt / .pdf）", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onPick) { Text("导入稿子") }
    }
}

@Composable
private fun ScriptView(
    sentences: List<String>,
    currentIndex: Int,
    hasMatch: Boolean,
    listState: LazyListState,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        itemsIndexed(sentences) { index, sentence ->
            val isCurrent = index == currentIndex && hasMatch
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (isCurrent) Modifier.background(Color(0xFFBFE3FF)) else Modifier)
                    .padding(vertical = 6.dp, horizontal = 4.dp)
            ) {
                Text(
                    sentence,
                    fontSize = 18.sp,
                    lineHeight = 30.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun BottomBar(
    listening: Boolean,
    recognized: String,
    percent: Int,
    followScroll: Boolean,
    onFollowToggle: () -> Unit,
    onToggle: () -> Unit,
) {
    Surface(shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("朗读进度：$percent%", style = MaterialTheme.typography.bodyMedium)
            if (recognized.isNotEmpty()) {
                Text("最近识别：$recognized",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .clickable { onFollowToggle() }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = followScroll, onCheckedChange = null)
                    Text("跟随")
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onToggle) {
                    Text(if (listening) "停止" else "开始朗读", fontSize = 16.sp)
                }
            }
        }
    }
}