package com.pausebeforepay.signal

import com.presagetech.smartspectra.ValidationCode

/**
 * What one measurement produced, after warm-up discard and quality gating.
 * This is the only input the decision takes from the camera.
 */
data class ScanSummary(
    /** Median of accepted pulse samples, or null if there were none. */
    val pulseBpm: Double?,
    val pulseSamples: Int,
    /** Median of accepted breathing samples, or null if there were none. */
    val breathingBrpm: Double?,
    val breathingSamples: Int,
    /** Most frequent non-OK validation code seen, used for user guidance. */
    val dominantIssue: ValidationCode?,
    val durationSeconds: Int,
    /** Set when the SDK itself failed (auth, camera, network). */
    val failure: String? = null,
) {
    companion object {
        fun failed(message: String) = ScanSummary(
            pulseBpm = null,
            pulseSamples = 0,
            breathingBrpm = null,
            breathingSamples = 0,
            dominantIssue = null,
            durationSeconds = 0,
            failure = message,
        )
    }
}
