package com.scriptreader.app

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import java.io.File

/**
 * 封装 sherpa-onnx 的 SenseVoice 离线识别。
 * 模型文件放在本地目录，路径用绝对磁盘路径交给 AssetManager 包装器加载。
 */
class Recognizer private constructor(
    private val rec: OfflineRecognizer,
) {
    @Synchronized
    fun recognize(samples: FloatArray): String {
        val stream: OfflineStream = rec.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            return cleanText(rec.getResult(stream).text)
        } finally {
            stream.release()
        }
    }

    fun release() {
        rec.release()
    }

    private fun cleanText(t: String): String {
        // SenseVoice 可能带 [xxx] 或 |xxx| 这类事件/情感标签，去掉
        return t
            .replace(Regex("\\[[^\\]]*\\]"), "")
            .replace(Regex("\\|[^|]*\\|"), "")
            .trim()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val TAG = "Recognizer"

        fun create(context: Context, modelDir: File, numThreads: Int = 2): Recognizer {
            val modelFile = File(modelDir, MODEL_NAME)
            val tokenFile = File(modelDir, TOKEN_NAME)
            require(modelFile.exists()) { "模型文件不存在: ${modelFile.absolutePath}" }
            require(tokenFile.exists()) { "tokens 文件不存在: ${tokenFile.absolutePath}" }

            val senseVoice = OfflineSenseVoiceModelConfig(
                modelFile.absolutePath, "zh", false, QnnConfig("", "", ""),
            )
            val modelCfg = OfflineModelConfig()
            modelCfg.senseVoice = senseVoice
            modelCfg.tokens = tokenFile.absolutePath
            modelCfg.numThreads = numThreads
            modelCfg.debug = false

            val cfg = OfflineRecognizerConfig()
            cfg.modelConfig = modelCfg
            cfg.decodingMethod = "greedy_search"

            val rec = OfflineRecognizer(context.assets, cfg)
            Log.i(TAG, "SenseVoice recognizer created")
            return Recognizer(rec)
        }

        const val MODEL_NAME = "model.int8.onnx"
        const val TOKEN_NAME = "tokens.txt"
        // 下载大小参考，(字节)，仅用于提前提示
        const val MODEL_EXPECT_BYTES = 239_233_841L
    }
}