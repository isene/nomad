package com.isene.vox.audio

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * Thin MediaRecorder wrapper. Records mono AAC in an MPEG-4 container
 * (.m4a) at 16 kHz — speech-grade, small to upload (battery: less radio
 * time), and accepted directly by the Whisper API. One file in cacheDir,
 * overwritten each capture.
 */
class Recorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    fun start(): Boolean {
        cancel()
        val f = File(context.cacheDir, "vox-capture.m4a")
        if (f.exists()) f.delete()
        @Suppress("DEPRECATION")
        val r = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            output = f
            true
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) {}
            recorder = null
            false
        }
    }

    /** Stop and return the recorded file, or null on failure. */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        return try {
            r.stop()
            r.release()
            output
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) {}
            null
        }
    }

    /** Abort an in-progress recording and drop the file. */
    fun cancel() {
        val r = recorder
        recorder = null
        if (r != null) {
            try { r.stop() } catch (_: Exception) {}
            try { r.release() } catch (_: Exception) {}
        }
        output?.delete()
        output = null
    }
}
