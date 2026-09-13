package com.pandorasbox.totalrecall

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

class EmbeddingEngine(private val context: Context) {
    private var ortEnvironment: OrtEnvironment? = null
    private var visionSession: OrtSession? = null
    private var textSession: OrtSession? = null
    
    val clipTokenizer: ClipTokenizer = ClipTokenizer(context)

    var isInitialized = false
        private set

    companion object {
        private const val TAG = "EmbeddingEngine"
        private const val VISION_MODEL_NAME = "clip_vision.onnx"
        private const val TEXT_MODEL_NAME = "clip_text.onnx"
        const val DIMENSION = 512
        private const val MAX_SEQ_LENGTH = 77
    }

    init {
        initializeModels()
    }

    private fun initializeModels() {
        try {
            ortEnvironment = OrtEnvironment.getEnvironment()

            // Load Vision Model
            val visionFile = copyAssetToInternalStorage(VISION_MODEL_NAME)
            if (visionFile != null && visionFile.exists() && visionFile.length() > 0) {
                Log.d(TAG, "Loading ONNX Vision Model: ${visionFile.absolutePath} (${visionFile.length()} bytes)")
                visionSession = ortEnvironment?.createSession(visionFile.absolutePath)
                logSessionInfo("VISION MODEL", visionSession)
            } else {
                Log.w(TAG, "ONNX Vision Model ($VISION_MODEL_NAME) missing from assets.")
            }

            // Load Text Model
            val textFile = copyAssetToInternalStorage(TEXT_MODEL_NAME)
            if (textFile != null && textFile.exists() && textFile.length() > 0) {
                Log.d(TAG, "Loading ONNX Text Model: ${textFile.absolutePath} (${textFile.length()} bytes)")
                textSession = ortEnvironment?.createSession(textFile.absolutePath)
                logSessionInfo("TEXT MODEL", textSession)
            } else {
                Log.w(TAG, "ONNX Text Model ($TEXT_MODEL_NAME) missing from assets.")
            }

            isInitialized = (visionSession != null && textSession != null && clipTokenizer.isInitialized)
            if (isInitialized) {
                Log.i(TAG, "SUCCESS: Full ONNX Neural CLIP Pipeline initialized with BPE Tokenizer.")
            } else {
                Log.w(TAG, "ONNX models or tokenizer assets missing from assets folder.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ONNX environment or sessions", e)
            isInitialized = false
        }
    }

    private fun logSessionInfo(name: String, session: OrtSession?) {
        if (session == null) return
        try {
            Log.i(TAG, "=== $name METADATA ===")
            session.inputNames.forEach { inputName ->
                val info = session.inputInfo[inputName]
                Log.i(TAG, "Input: '$inputName', Info: ${info?.info}")
            }
            session.outputNames.forEach { outputName ->
                val info = session.outputInfo[outputName]
                Log.i(TAG, "Output: '$outputName', Info: ${info?.info}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting session $name", e)
        }
    }

    private fun copyAssetToInternalStorage(assetName: String): File? {
        val file = File(context.filesDir, assetName)
        try {
            if (!file.exists() || file.length() == 0L) {
                Log.d(TAG, "Copying asset $assetName to internal storage...")
                context.assets.open(assetName).use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Asset successfully copied to ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Asset $assetName not available in assets: ${e.message}")
            return null
        }
        return file
    }

    fun generateImageEmbedding(bitmap: Bitmap): FloatArray? {
        val startTime = System.currentTimeMillis()
        if (!isInitialized || visionSession == null || ortEnvironment == null) {
            Log.e(TAG, "Vision session not initialized.")
            return null
        }

        try {
            val resized = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
            val floatBuffer = preprocessBitmap(resized)
            val shape = longArrayOf(1, 3, 224, 224)

            OnnxTensor.createTensor(ortEnvironment, floatBuffer, shape).use { tensor ->
                val inputName = visionSession?.inputNames?.firstOrNull() ?: "pixel_values"
                val results = visionSession?.run(mapOf(inputName to tensor))
                results?.use {
                    val resultValue = if (it.get("image_embeds").isPresent) {
                        it.get("image_embeds").get().value
                    } else {
                        it[0].value
                    }
                    val rawOutput = extractFloatArray(resultValue)
                    if (rawOutput != null) {
                        val normalized = normalizeL2(rawOutput)
                        val latency = System.currentTimeMillis() - startTime
                        Log.d(TAG, "Neural Image Embedding generated (Dim: ${normalized.size}, Latency: ${latency}ms)")
                        return normalized
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error running ONNX vision inference", e)
        }

        return null
    }

    fun generateTextEmbedding(query: String): FloatArray? {
        val startTime = System.currentTimeMillis()
        if (!isInitialized || textSession == null || ortEnvironment == null) {
            Log.e(TAG, "Text session not initialized.")
            return null
        }

        try {
            val tokenIds = clipTokenizer.encode(query)
            val longBuffer = LongBuffer.wrap(tokenIds)
            val shape = longArrayOf(1, MAX_SEQ_LENGTH.toLong())

            val attentionMask = LongArray(MAX_SEQ_LENGTH) { 1L }
            val maskBuffer = LongBuffer.wrap(attentionMask)

            val inputMap = mutableMapOf<String, OnnxTensor>()
            
            val inputIdsName = textSession?.inputNames?.firstOrNull { it.contains("input_ids") } ?: "input_ids"
            inputMap[inputIdsName] = OnnxTensor.createTensor(ortEnvironment, longBuffer, shape)

            val maskName = textSession?.inputNames?.firstOrNull { it.contains("attention_mask") }
            if (maskName != null) {
                inputMap[maskName] = OnnxTensor.createTensor(ortEnvironment, maskBuffer, shape)
            }

            val results = textSession?.run(inputMap)
            results?.use {
                val resultValue = if (it.get("text_embeds").isPresent) {
                    it.get("text_embeds").get().value
                } else {
                    it[0].value
                }
                val rawOutput = extractFloatArray(resultValue)
                if (rawOutput != null) {
                    val normalized = normalizeL2(rawOutput)
                    val latency = System.currentTimeMillis() - startTime
                    Log.d(TAG, "Neural Text Embedding generated for '$query' (Dim: ${normalized.size}, Latency: ${latency}ms)")
                    return normalized
                }
            }
            
            inputMap.values.forEach { it.close() }
        } catch (e: Exception) {
            Log.e(TAG, "Error running ONNX text inference for query: $query", e)
        }

        return null
    }

    private fun extractFloatArray(value: Any?): FloatArray? {
        return when (value) {
            is FloatArray -> value
            is Array<*> -> {
                val first = value[0]
                if (first is FloatArray) first else null
            }
            else -> null
        }
    }

    private fun normalizeL2(vector: FloatArray): FloatArray {
        var normSq = 0f
        for (v in vector) {
            normSq += v * v
        }
        val norm = sqrt(normSq)
        if (norm > 0f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
        return vector
    }

    private fun preprocessBitmap(bitmap: Bitmap): FloatBuffer {
        val width = bitmap.width
        val height = bitmap.height
        val floatBuffer = FloatBuffer.allocate(3 * width * height)
        floatBuffer.rewind()

        val intValues = IntArray(width * height)
        bitmap.getPixels(intValues, 0, width, 0, 0, width, height)

        val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

        val channelSize = width * height
        val rBuffer = FloatArray(channelSize)
        val gBuffer = FloatArray(channelSize)
        val bBuffer = FloatArray(channelSize)

        for (i in intValues.indices) {
            val valPixel = intValues[i]
            val r = ((valPixel shr 16) and 0xFF) / 255.0f
            val g = ((valPixel shr 8) and 0xFF) / 255.0f
            val b = (valPixel and 0xFF) / 255.0f

            rBuffer[i] = (r - mean[0]) / std[0]
            gBuffer[i] = (g - mean[1]) / std[1]
            bBuffer[i] = (b - mean[2]) / std[2]
        }

        floatBuffer.put(rBuffer)
        floatBuffer.put(gBuffer)
        floatBuffer.put(bBuffer)
        floatBuffer.rewind()
        return floatBuffer
    }

    fun close() {
        try {
            visionSession?.close()
            textSession?.close()
            ortEnvironment?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ONNX sessions", e)
        }
    }
}
