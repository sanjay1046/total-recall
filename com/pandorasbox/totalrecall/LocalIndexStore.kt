package com.pandorasbox.totalrecall

import android.content.Context
import android.util.Log
import java.io.*

data class IndexedImage(
    val mediaId: Long,
    val uriString: String,
    val modifiedTime: Long,
    val embedding: FloatArray
)

class LocalIndexStore(private val context: Context) {
    companion object {
        private const val TAG = "LocalIndexStore"
        private const val INDEX_FILE_NAME = "total_recall_index_v3_real.dat"
        const val EXPECTED_DIMENSION = 512
    }

    private val indexFile: File
        get() = File(context.filesDir, INDEX_FILE_NAME)

    fun loadIndex(): MutableMap<Long, IndexedImage> {
        val map = mutableMapOf<Long, IndexedImage>()
        val file = indexFile
        if (!file.exists()) return map

        try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { dis ->
                val version = dis.readInt()
                if (version == 3) {
                    val size = dis.readInt()
                    for (i in 0 until size) {
                        val mediaId = dis.readLong()
                        val uriString = dis.readUTF()
                        val modifiedTime = dis.readLong()
                        val dim = dis.readInt()
                        val embedding = FloatArray(dim)
                        var isValid = (dim == EXPECTED_DIMENSION)
                        
                        for (j in 0 until dim) {
                            val v = dis.readFloat()
                            embedding[j] = v
                            if (v.isNaN() || v.isInfinite()) {
                                isValid = false
                            }
                        }
                        
                        if (isValid) {
                            map[mediaId] = IndexedImage(mediaId, uriString, modifiedTime, embedding)
                        }
                    }
                }
            }
            Log.d(TAG, "Loaded ${map.size} valid indexed images from storage.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load index or file corrupted. Re-initializing empty index.", e)
            clearIndex()
        }
        return map
    }

    fun saveIndex(indexMap: Map<Long, IndexedImage>) {
        val validMap = indexMap.filter { (_, item) ->
            item.embedding.size == EXPECTED_DIMENSION && isValidVector(item.embedding)
        }

        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(indexFile))).use { dos ->
                dos.writeInt(3) // version 3
                dos.writeInt(validMap.size)
                for ((_, item) in validMap) {
                    dos.writeLong(item.mediaId)
                    dos.writeUTF(item.uriString)
                    dos.writeLong(item.modifiedTime)
                    dos.writeInt(item.embedding.size)
                    for (v in item.embedding) {
                        dos.writeFloat(v)
                    }
                }
            }
            Log.d(TAG, "Saved ${validMap.size} valid indexed images to storage.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save index", e)
        }
    }

    private fun isValidVector(vector: FloatArray): Boolean {
        for (v in vector) {
            if (v.isNaN() || v.isInfinite()) return false
        }
        return true
    }

    fun clearIndex() {
        try {
            if (indexFile.exists()) {
                indexFile.delete()
                Log.d(TAG, "Cleared persistent index file.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear index", e)
        }
    }
}
