package com.pandorsbox.totalrecall

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

fun benchmarkVisionModel(context: Context): String {
    val env = OrtEnvironment.getEnvironment()
    val modelBytes = context.assets.open("clip_vision.onnx").readBytes()
    val session = env.createSession(modelBytes, OrtSession.SessionOptions())

    val dummyInput = FloatArray(1 * 3 * 224 * 224) { 0.0f }
    val shape = longArrayOf(1, 3, 224, 224)
    val tensor = ai.onnxruntime.OnnxTensor.createTensor(env, FloatBuffer.wrap(dummyInput), shape)

    // Warm-up run (first inference is always slower - don't count it)
    session.run(mapOf(session.inputNames.first() to tensor))

    val timings = mutableListOf<Long>()
    repeat(20) {
        val start = System.nanoTime()
        session.run(mapOf(session.inputNames.first() to tensor))
        timings.add((System.nanoTime() - start) / 1_000_000) // ms
    }

    session.close()
    val avg = timings.average()
    return "Vision model: avg ${avg}ms over 20 runs (min ${timings.min()}, max ${timings.max()})"
}