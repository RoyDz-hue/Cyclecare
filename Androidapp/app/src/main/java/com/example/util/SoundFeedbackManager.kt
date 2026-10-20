package com.example.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.sin

/**
 * Lightweight, zero-asset procedural crystal harmonic sound synthesizer.
 *
 * Uses a short-lived [AudioTrack] in [AudioTrack.MODE_STATIC] that is immediately
 * released after playback (260ms - 420ms), consuming zero permanent RAM and zero APK file size.
 */
object SoundFeedbackManager {
    @Volatile
    var isSoundEnabled: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val SAMPLE_RATE = 24000

    /**
     * Plays a warm crystal fifth chord when the user taps or orbits a 3D artifact.
     */
    fun playArtifactInteraction(baseFreqHz: Float = 528f) {
        if (!isSoundEnabled) return
        scope.launch {
            playChord(
                frequencies = floatArrayOf(baseFreqHz, baseFreqHz * 1.498f, baseFreqHz * 2.0f),
                amplitudes = floatArrayOf(0.20f, 0.10f, 0.05f),
                durationMs = 260
            )
        }
    }

    /**
     * Plays an ascending 4-note crystal arpeggio when an artifact is unlocked or gifted.
     */
    fun playArtifactAcquired(baseFreqHz: Float = 528f) {
        if (!isSoundEnabled) return
        scope.launch {
            playArpeggio(
                notesHz = floatArrayOf(
                    baseFreqHz,
                    baseFreqHz * 1.25f,
                    baseFreqHz * 1.498f,
                    baseFreqHz * 2.0f
                ),
                noteSpacingMs = 65,
                totalDurationMs = 420
            )
        }
    }

    /**
     * Plays a soft 2-note appreciative chime on daily check-in or coin claim.
     */
    fun playGentleRewardChime() {
        if (!isSoundEnabled) return
        scope.launch {
            playArpeggio(
                notesHz = floatArrayOf(528f, 660f, 792f),
                noteSpacingMs = 55,
                totalDurationMs = 300
            )
        }
    }

    private suspend fun playChord(
        frequencies: FloatArray,
        amplitudes: FloatArray,
        durationMs: Int
    ) {
        try {
            val numSamples = (SAMPLE_RATE * durationMs) / 1000
            val pcm = ShortArray(numSamples)
            val twoPi = (2.0 * Math.PI).toFloat()

            for (i in 0 until numSamples) {
                val t = i.toFloat() / SAMPLE_RATE
                val attack = (i.toFloat() / (SAMPLE_RATE * 0.012f)).coerceAtMost(1f)
                val decay = exp(-6.5f * t / (durationMs / 1000f))
                val env = attack * decay
                var sample = 0f
                for (k in frequencies.indices) {
                    sample += amplitudes[k] * sin(twoPi * frequencies[k] * t)
                }
                val scaled = (sample * env * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                pcm[i] = scaled.toShort()
            }
            playStaticPcm(pcm, durationMs.toLong())
        } catch (_: Throwable) {
            // Ignore audio hardware unavailability on headless/test devices
        }
    }

    private suspend fun playArpeggio(
        notesHz: FloatArray,
        noteSpacingMs: Int,
        totalDurationMs: Int
    ) {
        try {
            val numSamples = (SAMPLE_RATE * totalDurationMs) / 1000
            val pcm = ShortArray(numSamples)
            val twoPi = (2.0 * Math.PI).toFloat()

            for (i in 0 until numSamples) {
                val tMs = (i * 1000f) / SAMPLE_RATE
                val tSec = i.toFloat() / SAMPLE_RATE
                var sample = 0f
                for (nIdx in notesHz.indices) {
                    val startMs = nIdx * noteSpacingMs
                    if (tMs >= startMs) {
                        val localSec = (tMs - startMs) / 1000f
                        val attack = (localSec / 0.010f).coerceAtMost(1f)
                        val decay = exp(-5.8f * localSec)
                        val freq = notesHz[nIdx]
                        sample += 0.16f * attack * decay * (
                            sin(twoPi * freq * tSec) + 0.35f * sin(twoPi * freq * 2f * tSec)
                        )
                    }
                }
                val scaled = (sample * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                pcm[i] = scaled.toShort()
            }
            playStaticPcm(pcm, totalDurationMs.toLong())
        } catch (_: Throwable) {
            // Ignore audio hardware unavailability on headless/test devices
        }
    }

    private suspend fun playStaticPcm(pcm: ShortArray, durationMs: Long) {
        var track: AudioTrack? = null
        try {
            val byteSize = pcm.size * 2
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(byteSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcm, 0, pcm.size)
            track.play()
            delay(durationMs + 40L)
        } catch (_: Throwable) {
            // Safe no-op in unit tests or muted environments
        } finally {
            try {
                track?.stop()
            } catch (_: Throwable) {}
            try {
                track?.release()
            } catch (_: Throwable) {}
        }
    }
}
