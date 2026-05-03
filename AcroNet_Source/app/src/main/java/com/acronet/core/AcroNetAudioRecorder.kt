package com.acronet.core

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.util.Arrays

/**
 * AcroNetAudioRecorder — IAudioMatrix Implementation (Phase 3)
 *
 * Uses native Android MediaRecorder with AAC codec.
 * Phase 4 will swap to Opus without touching UI code.
 *
 * Security protocol:
 *   1. Record to temp cache file
 *   2. Read bytes into memory
 *   3. DELETE temp file immediately
 *   4. Zero-fill the plaintext byte array after encryption
 */
class AcroNetAudioRecorder(private val context: Context) : IAudioMatrix {

    private var recorder: MediaRecorder? = null
    private var tempFile: File? = null
    private var recording = false

    override fun startRecording() {
        try {
            tempFile = File.createTempFile("acro_audio_", ".aac", context.cacheDir)

            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(tempFile!!.absolutePath)
                prepare()
                start()
            }
            recording = true
            Log.d(TAG, "[AUDIO] Recording started → ${tempFile?.name}")
        } catch (e: Exception) {
            Log.e(TAG, "[AUDIO] Failed to start recording: ${e.message}")
            recording = false
        }
    }

    override fun stopRecording(): ByteArray? {
        return try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            recording = false

            // Read audio bytes into memory
            val audioBytes = tempFile?.readBytes()

            // CRITICAL: Delete temp file immediately
            val deleted = tempFile?.delete() ?: false
            Log.d(TAG, "[AUDIO] Temp file deleted: $deleted")
            tempFile = null

            Log.d(TAG, "[AUDIO] Recording stopped. ${audioBytes?.size ?: 0} bytes captured.")
            audioBytes
        } catch (e: Exception) {
            Log.e(TAG, "[AUDIO] Failed to stop recording: ${e.message}")
            recorder?.release()
            recorder = null
            recording = false
            tempFile?.delete()
            tempFile = null
            null
        }
    }

    override fun cancelRecording() {
        try {
            recorder?.stop()
        } catch (_: Exception) { }
        recorder?.release()
        recorder = null
        recording = false
        tempFile?.delete()
        tempFile = null
        Log.d(TAG, "[AUDIO] Recording cancelled and temp file deleted.")
    }

    override fun isRecording(): Boolean = recording

    override fun getCurrentAmplitude(): Float {
        val maxAmp = recorder?.maxAmplitude ?: 0
        return (maxAmp / 32767f).coerceIn(0f, 1f)
    }

    override fun release() {
        cancelRecording()
    }

    companion object {
        private const val TAG = "ACRO_VOID_CRYPTO"
    }
}
