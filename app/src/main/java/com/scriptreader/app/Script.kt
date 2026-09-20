package com.scriptreader.app

/**
 * 稿子：切成句子的列表，并用句子做定位。
 */
class Script(
    val sentences: List<String>,
) {
    val size: Int get() = sentences.size
    fun isNotEmpty() = sentences.isNotEmpty()
}

object ScriptLoader {

    /** 把一段文本切分为句序列。按中英文标点断句。 */
    fun fromText(text: String): Script {
        val cleaned = text.replace('\r', ' ').replace('\u00a0', ' ')
        var cur = StringBuilder()
        val out = ArrayList<String>()
        var lastCh = ' '
        for (ch in cleaned) {
            cur.append(ch)
            lastCh = ch
            if (isSentenceEnd(ch)) {
                out.add(cur.toString().trim())
                cur = StringBuilder()
            }
        }
        if (cur.isNotBlank()) out.add(cur.toString().trim())
        val filtered = out.filter { it.isNotBlank() }
        return Script(if (filtered.isEmpty()) listOf(cleaned) else filtered)
    }

    private fun isSentenceEnd(ch: Char): Boolean = when (ch) {
        '。', '！', '？', '；', '\n', '.', '!', '?', ';' -> true
        // 英文句点后需要看下一个字符是否是空格/结束，这里简单处理：句点可能出现在缩写里。
        '.' -> true
        else -> false
    }
}