package com.scriptreader.app

import android.util.Log
import com.scriptreader.app.Recognizer.Companion.MODEL_EXPECT_BYTES
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 首次运行的模型下载：把 model.int8.onnx 与 tokens.txt 下载到本地并缓存。
 * 模型要一点时间，这里带进度回调。
 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"
    private const val MODEL_URL =
        "https://www.modelscope.cn/models/WEAAEW/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/master/model.int8.onnx"
    private const val TOKEN_URL =
        "https://www.modelscope.cn/models/WEAAEW/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/master/tokens.txt"

    data class Progress(val name: String, val doneBytes: Long, val totalBytes: Long)

    /** 是否已就绪（两个文件都在且大小合理）。 */
    fun isReady(dir: File): Boolean {
        return File(dir, Recognizer.MODEL_NAME).length() > 1_000_000 &&
                File(dir, Recognizer.TOKEN_NAME).length() > 1000
    }

    /**
     * 阻塞式下载；在 IO 线程调用。onProgress 回调节流更新。
     */
    fun download(dir: File, onProgress: (Progress) -> Unit) {
        dir.mkdirs()
        downloadFile(MODEL_URL, File(dir, Recognizer.MODEL_NAME), "模型", MODEL_EXPECT_BYTES, onProgress)
        downloadFile(TOKEN_URL, File(dir, Recognizer.TOKEN_NAME), "词表", 0, onProgress)
        Log.i(TAG, "download done, dir=${dir.absolutePath}")
    }

    private fun downloadFile(
        url: String, target: File, name: String, expected: Long,
        onProgress: (Progress) -> Unit,
    ) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        conn.inputStream.use { input ->
            FileOutputStream(target).use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                var lastReport = 0L
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    total += r
                    if (total - lastReport > 1_000_000) {
                        lastReport = total
                        val totalBytes = if (expected > 0) expected else ftpSize(conn)
                        onProgress(Progress(name, total, totalBytes))
                    }
                }
                out.flush()
            }
        }
        conn.disconnect()
    }

    private fun ftpSize(conn: HttpURLConnection): Long {
        return conn.contentLengthLong.coerceAtLeast(0)
    }
}