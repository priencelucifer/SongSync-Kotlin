package com.songsync.app.calibration

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import kotlin.concurrent.thread

/**
 * Records the host microphone for echo calibration. Audio stays in memory and is discarded
 * after the analysis; nothing is stored or sent.
 */
class MicRecorder(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(maxDurationMs: Long): ActiveRecording {
        val sampleRate = CalibrationSignal.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // Echo cancellation / noise suppression would remove the host's own chirps or smear timing.
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val record = AudioRecord.Builder()
            .setAudioSource(if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate / 5 * 2))
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("Microphone unavailable")
        }
        return ActiveRecording(record, sampleRate, (maxDurationMs * sampleRate / 1_000).toInt())
    }

    class ActiveRecording internal constructor(
        private val record: AudioRecord,
        private val sampleRate: Int,
        maxSamples: Int,
    ) {
        private val buffer = ShortArray(maxSamples)
        @Volatile private var size = 0
        @Volatile private var running = true
        /** Earliest plausible start from read() timing; fallback when audio timestamps are unavailable. */
        @Volatile private var readEstimateNs = Long.MAX_VALUE
        @Volatile private var timestampStartNs: Long? = null

        private val worker = thread(name = "calibration-mic") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val chunk = ShortArray(sampleRate / 50)
            val timestamp = AudioTimestamp()
            try {
                record.startRecording()
                while (running && size < buffer.size) {
                    val read = record.read(chunk, 0, minOf(chunk.size, buffer.size - size))
                    if (read < 0) break
                    if (read == 0) continue
                    val now = SystemClock.elapsedRealtimeNanos()
                    System.arraycopy(chunk, 0, buffer, size, read)
                    size += read
                    readEstimateNs = minOf(readEstimateNs, now - size * 1_000_000_000L / sampleRate)
                    if (timestampStartNs == null && size > sampleRate / 2 &&
                        record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                    ) {
                        // AudioTimestamp uses System.nanoTime(); the sync engine uses elapsedRealtime.
                        val elapsedMinusMonotonic = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
                        timestampStartNs = timestamp.nanoTime + elapsedMinusMonotonic -
                            timestamp.framePosition * 1_000_000_000L / sampleRate
                    }
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }

        /** Stops (if still running) and returns everything recorded so far. */
        fun stop(): Recording {
            running = false
            worker.join(2_000)
            val start = timestampStartNs ?: readEstimateNs
            return Recording(buffer, size, sampleRate, start)
        }
    }
}
