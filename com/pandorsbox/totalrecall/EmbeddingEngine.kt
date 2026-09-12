package com.pandorsbox.totalrecall

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer
import android.util.Log

class EmbeddingEngine(context: Context) {
    private val env = OrtEnvironment.getEnvironment()
    private val visionSession = env.createSession(
        copyModelToFile(context, "clip_vision.onnx").absolutePath,
        OrtSession.SessionOptions()
    )

    private val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    private fun copyModelToFile(context: Context, assetName: String): File {
        val outFile = File(context.filesDir, assetName)
        // Only copy once — skip if already present from a previous run.
        // NOTE: if you ever change the .onnx file itself, bump the filename
        // or delete the old copy, or this check will silently serve stale data.
        if (!outFile.exists() || outFile.length() == 0L) {
            context.assets.open(assetName).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output) // streams in chunks, no giant byte[] allocation
                }
            }
        }
        return outFile
    }

    fun embedImage(bitmap: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
        val chw = FloatArray(3 * 224 * 224)
        var idx = 0
        for (c in 0 until 3) {
            for (y in 0 until 224) {
                for (x in 0 until 224) {
                    val pixel = resized.getPixel(x, y)
                    val value = when (c) {
                        0 -> ((pixel shr 16) and 0xFF) / 255f
                        1 -> ((pixel shr 8) and 0xFF) / 255f
                        else -> (pixel and 0xFF) / 255f
                    }
                    chw[idx++] = (value - mean[c]) / std[c]
                }
            }
        }
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, 224, 224))
        val result = visionSession.run(mapOf(visionSession.inputNames.first() to tensor))

        //val output3d = result[0].value as Array<Array<FloatArray>>
        //val clsToken = output3d[0][0]  // [0]=batch, [0]=CLS token (index 0 of 50 tokens)
        val output2d = result[0].value as Array<FloatArray>
        val imageEmbedding = output2d[0]
        tensor.close()
        return l2Normalize(imageEmbedding)
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        val norm = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / norm }
    }
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot // since both vectors are already L2-normalized, dot product == cosine similarity
    }
}