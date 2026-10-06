package com.starglean.app.core.feedback

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class AndroidEncounterFeedback(
    context: Context,
    private val preferenceOverride: FeedbackPreferenceState? = null,
) : EncounterFeedback {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") (appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
    }.getOrNull()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "encounter-feedback").apply { isDaemon = true }
    }
    private val generation = AtomicLong()
    private val lock = Any()
    private var active = false
    private var released = false
    private var audioTrack: AudioTrack? = null
    private val vibrationOwnership = RunEffectOwnership()
    private val preferences = FeedbackPreferences.fromAccountContext(appContext)

    override fun play(): Boolean = startPlayback().started

    fun playPreview(): FeedbackPlaybackResult = startPlayback()

    private fun startPlayback(): FeedbackPlaybackResult {
        val policy = currentPolicy()
        val run = synchronized(lock) {
            if (released) return FeedbackPlaybackResult(false, false, false, "Preview is unavailable because the feedback player was closed.")
            if (active) return FeedbackPlaybackResult(false, false, false, "Feedback is already playing.")
            if (!policy.sound && !policy.haptic) return FeedbackPlaybackResult(false, false, false, policy.message)
            active = true
            generation.incrementAndGet()
        }
        return try {
            executor.execute { render(run, policy) }
            FeedbackPlaybackResult(true, policy.sound, policy.haptic, policy.message)
        } catch (_: RuntimeException) {
            synchronized(lock) { if (generation.get() == run) active = false }
            FeedbackPlaybackResult(false, false, false, "Preview could not start on this device.")
        }
    }

    override fun stop() {
        val track = synchronized(lock) {
            generation.incrementAndGet()
            active = false
            cancelVibrationLocked()
            audioTrack.also { audioTrack = null }
        }
        releaseTrack(track)
    }

    override fun release() {
        val shouldShutdown = synchronized(lock) {
            if (released) false else { released = true; true }
        }
        if (!shouldShutdown) return
        stop()
        executor.shutdownNow()
    }

    private fun render(run: Long, initialPolicy: FeedbackPolicy) {
        var track: AudioTrack? = null
        try {
            if (!isCurrent(run)) return
            val policy = currentPolicy()
            if (!policy.sound && !policy.haptic) return

            // Begin the first tactile beat before PCM synthesis and AudioTrack setup. Those
            // operations can take perceptible time on a cold device, leaving the reveal silent.
            val hapticDeadlineNanos = System.nanoTime() + EncounterFeedbackSignal.DURATION_MILLIS * 1_000_000L
            if (policy.haptic) startVibration(run)
            val pcm = if (initialPolicy.sound && policy.sound) {
                try { EncounterFeedbackSignal.synthesizePcm16() } catch (_: Exception) { null }
            } else null
            if (!isCurrent(run)) return

            if (policy.sound && pcm != null) {
                track = try { createTrack() } catch (_: Exception) { null }
                if (track != null) {
                    val accepted = synchronized(lock) {
                        if (released || generation.get() != run) false
                        else { audioTrack = track; true }
                    }
                    if (!accepted) return
                }
            }

            if (track != null && pcm != null) {
                try { streamAudio(run, track, pcm, policy.haptic, hapticDeadlineNanos) }
                catch (_: Exception) { stopTrackNow(track) }
            }
            // Audio startup or streaming can fail independently. Keep the tactile arrival alive
            // until its own deadline, unless the encounter or system policy cancels it.
            if (policy.haptic) waitForHaptic(run, hapticDeadlineNanos)
        } catch (_: Exception) {
            // Feedback is optional. Hardware, policy, and lifecycle races must never crash discovery.
        } finally {
            // Do not advertise an idle engine while our previous track is still playing.
            releaseTrack(track)
            synchronized(lock) {
                if (audioTrack === track) audioTrack = null
                if (generation.get() == run) active = false
                cancelVibrationLocked(run)
            }
        }
    }

    private fun streamAudio(run: Long, track: AudioTrack, pcm: ShortArray, hapticStarted: Boolean,
        hapticDeadlineNanos: Long) {
        // Before play(), isMusicActive can only belong to another source.
        val openingPolicy = currentPolicy(ownsPlayingTrack = false)
        if (!openingPolicy.haptic) synchronized(lock) { cancelVibrationLocked(run) }
        if (!openingPolicy.sound) {
            if (hapticStarted && openingPolicy.haptic) waitForHaptic(run, hapticDeadlineNanos)
            return
        }
        val chunkSamples = 768
        val first = minOf(chunkSamples, pcm.size)
        val firstWritten = track.write(pcm, 0, first, AudioTrack.WRITE_NON_BLOCKING)
        if (firstWritten <= 0 || !isCurrent(run)) return
        var offset = firstWritten
        track.play()
        val deadline = System.nanoTime() + (EncounterFeedbackSignal.DURATION_MILLIS + PLAYBACK_GRACE_MILLIS) * 1_000_000L
        while (offset < pcm.size && isCurrent(run) && System.nanoTime() < deadline) {
            // Once this owned track is playing, AudioManager.isMusicActive includes us.
            val policy = currentPolicy(ownsPlayingTrack = true)
            if (!policy.haptic) synchronized(lock) { cancelVibrationLocked(run) }
            if (!policy.sound) {
                stopTrackNow(track)
                if (hapticStarted && policy.haptic) waitForHaptic(run, hapticDeadlineNanos)
                return
            }
            val count = minOf(chunkSamples, pcm.size - offset)
            val written = track.write(pcm, offset, count, AudioTrack.WRITE_NON_BLOCKING)
            if (written < 0) {
                stopTrackNow(track)
                return
            }
            if (written == 0) Thread.sleep(8) else offset += written
        }
        while (offset == pcm.size && isCurrent(run) && track.playbackHeadPosition < pcm.size && System.nanoTime() < deadline) {
            val policy = currentPolicy(ownsPlayingTrack = true)
            if (!policy.haptic) synchronized(lock) { cancelVibrationLocked(run) }
            if (!policy.sound) {
                stopTrackNow(track)
                if (hapticStarted && policy.haptic) waitForHaptic(run, hapticDeadlineNanos)
                return
            }
            Thread.sleep(12)
        }
    }

    private fun waitForHaptic(run: Long, deadlineNanos: Long) {
        while (isCurrent(run) && System.nanoTime() < deadlineNanos) {
            if (!currentPolicy().haptic) break
            Thread.sleep(24)
        }
    }

    private fun createTrack(): AudioTrack {
        val minBytes = AudioTrack.getMinBufferSize(
            EncounterFeedbackSignal.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(3_072)
        val attributes = AudioAttributes.Builder().setLegacyStreamType(AudioManager.STREAM_MUSIC).build()
        val format = AudioFormat.Builder()
            .setSampleRate(EncounterFeedbackSignal.SAMPLE_RATE_HZ)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBytes)
            .build().also {
                if (it.state != AudioTrack.STATE_INITIALIZED) {
                    it.release()
                    error("Encounter audio track did not initialize")
                }
            }
    }

    private fun startVibration(run: Long) = synchronized(lock) {
        if (released || !active || generation.get() != run) return@synchronized
        val target = vibrator ?: return
        val started = runCatching {
            if (!target.hasVibrator()) return
            val effect = if (target.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(
                    EncounterFeedbackSignal.amplitudeVibrationTimings,
                    EncounterFeedbackSignal.amplitudeVibrationValues,
                    -1,
                )
            } else {
                VibrationEffect.createWaveform(EncounterFeedbackSignal.pulseVibrationTimings, -1)
            }
            // This accompanies the arrival animation and audio. Classifying it as media keeps
            // it independent of the device's screen-touch feedback toggle.
            if (Build.VERSION.SDK_INT >= 33) {
                target.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
            } else {
                @Suppress("DEPRECATION")
                target.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            }
            true
        }.getOrDefault(false)
        if (started && !released && active && generation.get() == run) vibrationOwnership.started(run)
    }

    private fun currentPolicy(ownsPlayingTrack: Boolean = false): FeedbackPolicy {
        val preference = when (val read = preferenceOverride?.let(FeedbackPreferenceRead::Success)
            ?: preferences?.read() ?: FeedbackPreferenceRead.Unavailable) {
            is FeedbackPreferenceRead.Success -> read.value
            FeedbackPreferenceRead.Unavailable -> return FeedbackPolicy(false, false,
                "Feedback settings are unavailable for this account.")
        }
        if (!preference.soundEnabled && !preference.hapticEnabled) return FeedbackPolicy(false, false,
            "Sound and vibration are turned off for this account.")
        val normalMode = runCatching { audioManager.mode == AudioManager.MODE_NORMAL }.getOrDefault(false)
        if (!normalMode) return FeedbackPolicy(false, false, "Feedback is paused during calls or communication audio.")
        val interruptionsAllowed = runCatching {
            notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
        }.getOrDefault(false)
        if (!interruptionsAllowed) return FeedbackPolicy(false, false, "Feedback is suppressed by Do Not Disturb.")
        val ringerMode = runCatching { audioManager.ringerMode }.getOrDefault(AudioManager.RINGER_MODE_SILENT)
        if (ringerMode == AudioManager.RINGER_MODE_SILENT) return FeedbackPolicy(false, false,
            "Feedback is suppressed while the device is silent.")
        val mediaVolume = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0 }.getOrDefault(false)
        val otherMusic = competingMusicActive(
            reportedMusicActive = runCatching { audioManager.isMusicActive }.getOrDefault(true),
            ownsPlayingTrack = ownsPlayingTrack,
        )
        val decision = decideEncounterFeedback(EncounterFeedbackEnvironment(
            sharedFeedbackAllowed = true,
            mediaVolumeAudible = mediaVolume,
            otherMusicActive = otherMusic,
            soundModeAllowed = ringerMode == AudioManager.RINGER_MODE_NORMAL,
        ))
        val hapticAllowed = runCatching { vibrator?.hasVibrator() == true }.getOrDefault(false)
        val selected = applyFeedbackPreferences(decision, preference, hapticAllowed)
        val sound = selected.sound
        val haptic = selected.haptic
        val message = when {
            sound && haptic -> "Playing the encounter sound and vibration."
            sound && preference.hapticEnabled && !hapticAllowed -> "Playing sound. Vibration is unavailable on this device."
            sound -> "Playing the encounter sound."
            haptic && preference.soundEnabled && otherMusic -> "Playing vibration only; other audio is already playing."
            haptic && preference.soundEnabled && !mediaVolume -> "Playing vibration only; media volume is muted."
            haptic -> "Playing the encounter vibration."
            preference.soundEnabled && otherMusic -> "Sound is suppressed because other audio is playing."
            preference.soundEnabled && !mediaVolume -> "Sound is suppressed because media volume is muted."
            preference.hapticEnabled && !haptic -> "Vibration is unavailable on this device."
            else -> "Feedback is unavailable under the current system settings."
        }
        return FeedbackPolicy(sound, haptic, message)
    }

    private fun isCurrent(run: Long): Boolean = synchronized(lock) { !released && active && generation.get() == run }

    /** Must be called with [lock] held so an old run cannot cancel a newly started run. */
    private fun cancelVibrationLocked(expectedRun: Long? = null) {
        if (!vibrationOwnership.takeForCancellation(expectedRun)) return
        runCatching { vibrator?.cancel() }
    }

    private fun releaseTrack(track: AudioTrack?) {
        if (track == null) return
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.release() }
    }

    private fun stopTrackNow(track: AudioTrack) {
        runCatching { track.pause() }
        runCatching { track.flush() }
    }

    private data class FeedbackPolicy(val sound: Boolean, val haptic: Boolean, val message: String)

    private companion object { const val PLAYBACK_GRACE_MILLIS = 300L }
}

data class FeedbackPlaybackResult(val started: Boolean, val sound: Boolean, val haptic: Boolean, val message: String)
