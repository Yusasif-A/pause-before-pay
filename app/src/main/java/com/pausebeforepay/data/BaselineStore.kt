package com.pausebeforepay.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.pausebeforepay.signal.Baseline

/**
 * The app's only persistent storage.
 *
 * Holds a summary of one calibration scan -- two medians, two spreads, two
 * sample counts, a timestamp -- plus one user preference. These are vital
 * signs, so the file is encrypted with a hardware-backed key via
 * EncryptedSharedPreferences. No per-payment reading is ever written here, and
 * [clear] actually deletes the file's contents rather than flagging it as
 * deleted.
 */
class BaselineStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): Baseline? {
        if (!prefs.contains(KEY_RESTING_PULSE)) return null
        return try {
            Baseline(
                restingPulseBpm = prefs.getFloat(KEY_RESTING_PULSE, 0f).toDouble(),
                pulseMad = prefs.getFloat(KEY_PULSE_MAD, 0f).toDouble(),
                pulseSamples = prefs.getInt(KEY_PULSE_SAMPLES, 0),
                restingBreathingBrpm = if (prefs.contains(KEY_RESTING_BREATHING)) {
                    prefs.getFloat(KEY_RESTING_BREATHING, 0f).toDouble()
                } else {
                    null
                },
                breathingMad = if (prefs.contains(KEY_BREATHING_MAD)) {
                    prefs.getFloat(KEY_BREATHING_MAD, 0f).toDouble()
                } else {
                    null
                },
                breathingSamples = prefs.getInt(KEY_BREATHING_SAMPLES, 0),
                recordedAtEpochMillis = prefs.getLong(KEY_RECORDED_AT, 0L),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Stored baseline could not be read; treating it as absent", t)
            null
        }
    }

    fun save(baseline: Baseline) {
        prefs.edit().apply {
            putFloat(KEY_RESTING_PULSE, baseline.restingPulseBpm.toFloat())
            putFloat(KEY_PULSE_MAD, baseline.pulseMad.toFloat())
            putInt(KEY_PULSE_SAMPLES, baseline.pulseSamples)
            val breathing = baseline.restingBreathingBrpm
            if (breathing != null) {
                putFloat(KEY_RESTING_BREATHING, breathing.toFloat())
                putFloat(KEY_BREATHING_MAD, (baseline.breathingMad ?: 0.0).toFloat())
            } else {
                remove(KEY_RESTING_BREATHING)
                remove(KEY_BREATHING_MAD)
            }
            putInt(KEY_BREATHING_SAMPLES, baseline.breathingSamples)
            putLong(KEY_RECORDED_AT, baseline.recordedAtEpochMillis)
        }.apply()
    }

    /** Wipes the stored baseline. Keeps the user preference below. */
    fun clear() {
        prefs.edit().apply {
            remove(KEY_RESTING_PULSE)
            remove(KEY_PULSE_MAD)
            remove(KEY_PULSE_SAMPLES)
            remove(KEY_RESTING_BREATHING)
            remove(KEY_BREATHING_MAD)
            remove(KEY_BREATHING_SAMPLES)
            remove(KEY_RECORDED_AT)
        }.apply()
    }

    /**
     * Opt-in for the optional plain-language phrasing, which is the one feature
     * that would send the two numbers off the device. Off unless the user turns
     * it on, every time the app is installed.
     */
    var plainLanguageOptIn: Boolean
        get() = prefs.getBoolean(KEY_LLM_OPT_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_LLM_OPT_IN, value).apply()

    private companion object {
        const val TAG = "BaselineStore"
        const val FILE_NAME = "pausebeforepay_baseline"
        const val KEY_RESTING_PULSE = "resting_pulse_bpm"
        const val KEY_PULSE_MAD = "pulse_mad"
        const val KEY_PULSE_SAMPLES = "pulse_samples"
        const val KEY_RESTING_BREATHING = "resting_breathing_brpm"
        const val KEY_BREATHING_MAD = "breathing_mad"
        const val KEY_BREATHING_SAMPLES = "breathing_samples"
        const val KEY_RECORDED_AT = "recorded_at_epoch_millis"
        const val KEY_LLM_OPT_IN = "plain_language_opt_in"
    }
}
