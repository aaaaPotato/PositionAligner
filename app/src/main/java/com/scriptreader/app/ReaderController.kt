package com.scriptreader.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * 把录音、识别、定位串起来，向上层回调结果。
 * 识别使用单一低优先级后台线程串行执行，避免并发解码导致发热。
 */
class ReaderController(private val context: Context) {

    interface Listener {
        /** 识别出一段话并完成定位。index 为 -1 表示未匹配（维持原位置）。 */
        fun onRecognized(recognized: String, index: Int, positionPercent: Int)
        fun onListeningChanged(listening: Boolean)
    }

    @Volatile var listener: Listener? = null

    @Volatile var matcher: ScriptMatcher? = null

    private var recognizer: Recognizer? = null
    private var audioRecorder: AudioRecorder? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "recognition").apply { priority = Thread.MIN_PRIORITY }
    }

    @Volatile var listening = false
        private set

    private fun modelDir(): File = File(context.filesDir, "models")

    fun isModelReady(): Boolean = ModelDownloader.isReady(modelDir())

    @get:Synchronized
    val engineReady: Boolean get() = recognizer != null

    /** 在后台准备：必要时下载模型 + 创建识别器。onDone(true) 表示引擎已可用。 */
    fun prepare(
        onProgress: (ModelDownloader.Progress) -> Unit,
        onDone: (Boolean) -> Unit,
    ) {
        worker.execute {
            val ok = try {
                if (!ModelDownloader.isReady(modelDir())) {
                    ModelDownloader.download(modelDir(), onProgress)
                }
                if (recognizer == null) {
                    recognizer = Recognizer.create(context, modelDir())
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "prepare failed: ${e.message}")
                false
            }
            main.post { onDone(ok) }
        }
    }

    @Synchronized
    fun start() {
        if (listening) return
        val rec = recognizer ?: return // 引擎未就绪
        val m = matcher ?: return // 没有稿子就不启动

        val recorder = AudioRecorder(context) { chunk ->
            worker.execute {
                try {
                    val text = rec.recognize(chunk)
                    if (text.isBlank()) return@execute
                    val index = m.locate(text)
                    val percent = if (m.size() > 0) {
                        (m.currentIndex() * 100 / m.size()).coerceIn(0, 100)
                    } else 0
                    main.post { listener?.onRecognized(text, index, percent) }
                } catch (e: Exception) {
                    Log.e(TAG, "rec: ${e.message}")
                }
            }
        }
        audioRecorder = recorder
        listening = true
        recorder.start()
        main.post { listener?.onListeningChanged(true) }
    }

    @Synchronized
    fun stop() {
        if (!listening) return
        audioRecorder?.stop()
        audioRecorder = null
        listening = false
        main.post { listener?.onListeningChanged(false) }
    }

    fun shutdown() {
        stop()
        worker.shutdownNow()
    }

    private companion object {
        const val TAG = "ReaderController"
    }
}