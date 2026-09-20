package com.scriptreader.app

import android.net.Uri
import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

object ScriptTextExtractor {

    /** 从 Uri 读取并解析稿子文本。支持 .txt 与 .pdf。 */
    fun extract(context: Context, uri: Uri, displayName: String): String {
        val lower = displayName.lowercase()
        return if (lower.endsWith(".pdf")) {
            extractPdf(context, uri)
        } else {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IllegalStateException("无法读取文件")
        }
    }

    private fun extractPdf(context: Context, uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc ->
                return PDFTextStripper().getText(doc)
            }
        } ?: throw IllegalStateException("无法读取 PDF")
    }
}