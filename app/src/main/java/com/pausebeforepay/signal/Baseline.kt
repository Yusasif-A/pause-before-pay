package com.pausebeforepay.signal

/**
 * The only thing this app keeps between sessions: a summary of one calibration
 * scan. Two medians, two spreads, two sample counts, one timestamp. No raw
 * samples, no per-payment history, no video, ever.
 */
data class Baseline(
    val restingPulseBpm: Double,
    val pulseMad: Double,
    val pulseSamples: Int,
    val restingBreathingBrpm: Double?,
    val breathingMad: Double?,
    val breathingSamples: Int,
    val recordedAtEpochMillis: Long,
) {
    val hasBreathing: Boolean get() = restingBreathingBrpm != null
}
