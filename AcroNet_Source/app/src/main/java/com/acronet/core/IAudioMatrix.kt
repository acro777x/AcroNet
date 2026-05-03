package com.acronet.core

/**
 * IAudioMatrix — Modular Audio Recording Interface (V8)
 *
 * Abstraction layer for audio recording. Phase 2 uses native
 * Android MediaRecorder (AAC). Phase 3 will swap in Opus codec
 * without touching the UI or Chat Fragment logic.
 *
 * STRUCTURAL LOCK: Do not modify this interface. Only swap implementations.
 */
interface IAudioMatrix {

    /** Start recording audio to an in-memory buffer */
    fun startRecording()

    /** Stop recording and return the encrypted audio bytes */
    fun stopRecording(): ByteArray?

    /** Cancel the current recording without saving */
    fun cancelRecording()

    /** Whether a recording is currently in progress */
    fun isRecording(): Boolean

    /** Get the current amplitude for waveform visualization (0.0 - 1.0) */
    fun getCurrentAmplitude(): Float

    /** Release all resources */
    fun release()
}
