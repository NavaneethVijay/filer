package com.navaneeth.filer.data.ai

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/** One ONNX Runtime environment for the plugins that use it (text in photos, speech). */
object OrtRuntime {
    val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    /** A CPU session: works on every phone; threads kept below the core count so the UI stays smooth. */
    fun session(file: File): OrtSession {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(DeviceCapability.inferenceThreads())
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return env.createSession(file.path, options)
    }
}
