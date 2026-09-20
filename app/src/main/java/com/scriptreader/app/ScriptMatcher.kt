package com.scriptreader.app

/**
 * 句级文本定位匹配。
 *
 * 不需要逐字：对识别文本与稿子句子做「词/token 重叠度」Dice 相似度匹配，
 * 并用一个窗口限定在「上次位置附近」搜索，既快又避免跳段。
 * 支持一个识别片段跨 1~2 句。
 */
class ScriptMatcher(private val script: Script) {

    private val sentenceTokens: List<Set<String>> = script.sentences.map { tokenize(it) }
    private val tokenCache = HashMap<String, Set<String>>()

    /** 从 lastIndex 近似向前二分滑动，永不回退太远。当前简化：上次位置仅作为搜索中心窗口。 */
    private var lastIndex = 0

    /** 识别一段语音，返回最可能对应的句子下标；无法确认时返回 -1（保持原位置）。 */
    fun locate(recognizedText: String): Int {
        if (script.size == 0) return -1
        val hyp = tokens(recognizedText)
        if (hyp.isEmpty()) return -1

        val minScore = 0.16f
        val window = 7   // 只在 last 前后 7 句内搜，控制计算量

        val lo = 0.coerceAtLeast(lastIndex - window)
        val hi = (script.size - 1).coerceAtMost(lastIndex + window)

        var bestStart = -1
        var bestScore = 0f

        // 若窗口太小先在整个稿子做一次粗定位，把中心放过去
        var start = lo
        if (script.size > window * 2) {
            var coarse = coarseLocate(hyp)
            if (coarse < 0) coarse = lastIndex
            start = (coarse - window).coerceAtLeast(0)
        }

        val end = (start + window * 2 + 1).coerceAtMost(script.size)
        for (i in start until end) {
            val s1 = dice(hyp, sentenceTokens[i])
            var s2 = s1
            if (i + 1 < script.size) {
                // 允许跨两句话（识别片段可能包含 1~2 句）
                val pair = union(sentenceTokens[i], sentenceTokens[i + 1])
                s2 = dice(hyp, pair)
            }
            val s = maxOf(s1, s2)
            if (s > bestScore) { bestScore = s; bestStart = i }
        }

        if (bestStart >= 0 && bestScore >= minScore) {
            lastIndex = bestStart
            return bestStart
        }
        return -1
    }

    /** 全稿粗定位：找出最高相似句，作为窗口中心。 */
    private fun coarseLocate(hyp: Set<String>): Int {
        var best = -1; var bestScore = 0f
        // 全稿扫描太贵，按句取 4 等分采样做粗筛选
        for (sample in intArrayOf(0, script.size / 4, script.size / 2, script.size * 3 / 4)) {
            val s = dice(hyp, sentenceTokens[sample])
            if (s > bestScore) { bestScore = s; best = sample }
        }
        return if (bestScore >= 0.12f) best else -1
    }

    fun currentIndex(): Int = lastIndex

    fun size(): Int = script.size

    private fun tokens(text: String): Set<String> =
        tokenCache.getOrPut(text) { tokenize(text) }

    private fun union(a: Set<String>, b: Set<String>): Set<String> =
        if (a.size + b.size > 4000) a else (a + b)

    private fun dice(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        var inter = 0
        val base = if (a.size <= b.size) a else b
        val other = if (a.size <= b.size) b else a
        for (t in base) if (t in other) inter++
        return 2f * inter / (a.size + b.size)
    }

    /** 中文按字切 token，英文按整词切 token；忽略大小写与标点。 */
    private fun tokenize(s: String): Set<String> {
        val set = HashSet<String>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { set.add(sb.toString()); sb.clear() } }
        for (ch in s) {
            val c = ch.code
            val isCjk = (c in 0x3400..0x4dbf) || (c in 0x4e00..0x9fff) || (c in 0xf900..0xfaff)
            if (isCjk) {
                flush()
                set.add(ch.toString())
            } else if (ch.isLetterOrDigit()) {
                sb.append(ch.lowercaseChar())
            } else {
                flush()
            }
        }
        flush()
        return set
    }
}