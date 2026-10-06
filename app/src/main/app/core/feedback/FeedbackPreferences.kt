package com.starglean.app.core.feedback

import android.content.Context
import com.starglean.app.core.storage.AccountStorageContext
import com.starglean.app.core.storage.baseStorageContext
import com.starglean.app.core.storage.normalizeAccountId
import java.security.MessageDigest

data class FeedbackPreferenceState(val soundEnabled: Boolean = true, val hapticEnabled: Boolean = true)

internal fun applyFeedbackPreferences(
    system: EncounterFeedbackDecision,
    preferences: FeedbackPreferenceState,
    hapticHardwareAvailable: Boolean,
) = EncounterFeedbackDecision(
    sound = system.sound && preferences.soundEnabled,
    haptic = system.haptic && preferences.hapticEnabled && hapticHardwareAvailable,
)

sealed interface FeedbackPreferenceRead {
    data class Success(val value: FeedbackPreferenceState) : FeedbackPreferenceRead
    data object Unavailable : FeedbackPreferenceRead
}

/** Account-isolated preferences. Defaults preserve the original sound+haptic behaviour. */
class FeedbackPreferences(context: Context, accountId: String) {
    private val preferences = baseStorageContext(context).getSharedPreferences(fileName(accountId), Context.MODE_PRIVATE)

    fun read(): FeedbackPreferenceRead = try {
        FeedbackPreferenceRead.Success(FeedbackPreferenceState(
            soundEnabled = preferences.getBoolean(KEY_SOUND, true),
            hapticEnabled = preferences.getBoolean(KEY_HAPTIC, true),
        ))
    } catch (_: RuntimeException) {
        FeedbackPreferenceRead.Unavailable
    }

    /** Synchronous commit lets the UI report success only after durable persistence. */
    fun save(value: FeedbackPreferenceState): Boolean = try {
        preferences.edit().putBoolean(KEY_SOUND, value.soundEnabled)
            .putBoolean(KEY_HAPTIC, value.hapticEnabled).commit()
    } catch (_: RuntimeException) {
        false
    }

    companion object {
        fun fromAccountContext(context: Context): FeedbackPreferences? {
            val account = context.applicationContext as? AccountStorageContext ?: return null
            return FeedbackPreferences(account, account.accountId)
        }

        private fun fileName(accountId: String): String {
            val normalized = normalizeAccountId(accountId)
            val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return "encounter-feedback-v1-${digest.take(24)}"
        }

        private const val KEY_SOUND = "sound_enabled"
        private const val KEY_HAPTIC = "haptic_enabled"
    }
}
