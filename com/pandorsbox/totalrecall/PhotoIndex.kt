package com.pandorsbox.totalrecall

import android.content.Context
import android.net.Uri
import java.io.File

data class IndexedPhoto(val uri: Uri, val embedding: FloatArray)

object PhotoIndexStore {
    private const val FILE_NAME = "photo_index.tsv"

    fun save(context: Context, index: List<IndexedPhoto>) {
        val file = File(context.filesDir, FILE_NAME)
        file.bufferedWriter().use { writer ->
            for (item in index) {
                val embeddingStr = item.embedding.joinToString(",")
                writer.write("${item.uri}\t$embeddingStr")
                writer.newLine()
            }
        }
    }

    fun load(context: Context): List<IndexedPhoto> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()

        return file.bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split("\t")
                if (parts.size != 2) return@mapNotNull null
                try {
                    val uri = Uri.parse(parts[0])
                    val embedding = parts[1].split(",").map { it.toFloat() }.toFloatArray()
                    IndexedPhoto(uri, embedding)
                } catch (e: Exception) {
                    null // skip malformed lines rather than crashing the whole load
                }
            }.toList()
        }
    }
}