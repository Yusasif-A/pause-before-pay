package com.pausebeforepay.signal

import com.presagetech.smartspectra.ValidationCode
import com.presagetech.smartspectra.proto.MetricsProto.ExpressionType

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
    /**
     * Most frequent confident expression, or null when the face was not read at
     * all — too few samples, or an API key without the face-metrics bundle.
     */
    val dominantExpression: ExpressionType? = null,
    /** Share of accepted expression samples that were a tense expression. */
    val tenseShare: Double? = null,
    val expressionSamples: Int = 0,
    /** Set when the SDK itself failed (auth, camera, network). */
    val failure: String? = null,
) {
    /**
     * True when the face read as tense for a sustained part of the scan. Null
     * means the face was not read, which is different from "read as settled".
     */
    val faceTense: Boolean?
        get() {
            if (expressionSamples < Thresholds.MIN_EXPRESSION_SAMPLES) return null
            val share = tenseShare ?: return null
            return share >= Thresholds.TENSE_SHARE
        }

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
