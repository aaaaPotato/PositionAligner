package com.scriptreader.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 麦克风采集 + 轻量能量 VAD + 句/停顿切分。
 * 只在检测到人声时才回调，静音时几乎不占用 CPU（省电）。
 *
 * onUtterance(float[]) 在主线程回调一段 16kHz 单声道语音。
 */
class AudioRecorder(
    private val context: Context,
    private val onUtterance: (FloatArray) -> Unit,
) {
    private var thread: Thread? = null
    @Volatile private var running = false

    private companion object {
        const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        // 20ms 一帧
        const val FRAME = SAMPLE_RATE / 50
        // 静音判定：连续 silentHangover 帧无语音才认为一句话结束
        const val SILENT_HANGOVER = 20     // 400ms
        const val MIN_UTTER_MS = 400
        const val MAX_UTTER_MS = 10000
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "audio-capture").also { it.start() }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf, FRAME * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败")
            running = false
            return
        }
        val pcm = ShortArray(FRAME)
        var noiseFloor = 150.0          // 自适应噪声底，单位是位能的和
        val accum = ArrayList<Float>(SAMPLE_RATE)

        try {
            record.startRecording()
            var inSpeech = false
            var silentCount = 0
            var speechFrames = 0

            while (running) {
                val n = record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue

                var energy = 0.0
                for (i in 0 until n) {
                    val v = pcm[i].toDouble()
                    energy += v * v
                }
                energy = sqrt(energy / n)   // RMS

                // 自适应噪声底（缓慢跟随）
                noiseFloor = 0.97 * noiseFloor + 0.03 * max(energy, 20.0)

                val threshold = max(noiseFloor * 1.6, 120.0)
                val isSpeech = energy > threshold

                if (isSpeech) {
                    if (!inSpeech) inSpeech = true
                    silentCount = 0
                    speechFrames++
                    for (i in 0 until n) accum.add(pcm[i] / 32767f)
                } else {
                    if (inSpeech) {
                        silentCount++
                        // 说完了但留有尾音，先保留
                        for (i in 0 until n) accum.add(pcm[i] / 32767f)
                        if (silentCount >= SILENT_HANGOVER) {
                            val ms = accum.size * 1000 / SAMPLE_RATE
                            if (ms >= MIN_UTTER_MS) {
                                val chunk = accum.toFloatArray()
                                postUtterance(chunk)
                            }
                            accum.clear(); inSpeech = false; speechFrames = 0; silentCount = 0
                        }
                    }
                    // 非语音且非尾音阶段：丢弃，CPU 空转
                }

                // 防止一句话过长不中断
                if (inSpeech && accum.size >= MAX_UTTER_MS * SAMPLE_RATE / 1000) {
                    val chunk = accum.toFloatArray()
                    postUtterance(chunk)
                    accum.clear(); inSpeech = false; speechFrames = 0; silentCount = 0
                }

                if (Thread.currentThread().isInterrupted) break
            }
        } catch (e: Exception) {
            Log.e(TAG, "采集异常: ${e.message}")
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            running = false
        }
    }

    private fun postUtterance(chunk: FloatArray) {
        try {
            android.os.Handler(context.mainLooper).post { onUtterance(chunk) }
        } catch (e: Exception) {
            Log.e(TAG, "回调异常: ${e.message}")
        }
    }
}