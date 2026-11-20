package com.pausebeforepay.signal

import com.presagetech.smartspectra.ValidationCode

/** Which of the two measurement contexts a window is collecting for. */
enum class ScanMode {
    /** One-time 60 s calibration that produces a stored [Baseline]. */
    BASELINE,

    /** Short scan taken just before a transfer is confirmed. */
    PAYMENT,
    ;

    val warmupSeconds: Int
        get() = when (this) {
            BASELINE -> Thresholds.BASELINE_WARMUP_SECONDS
            PAYMENT -> Thresholds.SCAN_WARMUP_SECONDS
        }

    val targetSeconds: Int
        get() = when (this) {
            BASELINE -> Thresholds.BASELINE_SCAN_SECONDS
            PAYMENT -> Thresholds.SCAN_TARGET_SECONDS
        }

    val maxSeconds: Int
        get() = when (this) {
            BASELINE -> Thresholds.BASELINE_SCAN_SECONDS
            PAYMENT -> Thresholds.SCAN_MAX_SECONDS
        }

    val minPulseSamples: Int
        get() = when (this) {
            BASELINE -> Thresholds.BASELINE_MIN_PULSE_SAMPLES
            PAYMENT -> Thresholds.SCAN_MIN_PULSE_SAMPLES
        }
}

/**
 * Accumulates accepted samples for one measurement.
 *
 * Three filters, applied in this order to every sample:
 *  1. **Warm-up** -- anything before [ScanMode.warmupSeconds] is dropped.
 *  2. **Stability** -- the SDK must mark the sample `stable`, meaning its
 *     confidence has reached the published accuracy standard.
 *  3. **Validation** -- the current validation code must not be a blocker for
 *     that metric (see [Thresholds.PULSE_BLOCKERS]).
 *
 * Plus a plausibility range, to drop anything physiologically absurd.
 *
 * Samples are deduplicated by their SDK timestamp, because the metrics stream
 * can redeliver a sample across payloads.
 *
 * Not thread-safe: the engine ingests from a single collector coroutine.
 */
class SampleWindow(private val mode: ScanMode) {

    private val pulse = mutableListOf<Double>()
    private val breathing = mutableListOf<Double>()
    private var lastPulseTimestamp = Long.MIN_VALUE
    private var lastBreathingTimestamp = Long.MIN_VALUE
    private val issueCounts = mutableMapOf<ValidationCode, Int>()

    /** Last value seen at all, stable or not — shown live so the UI is never blank. */
    var lastPulseSeen: Double? = null
        private set
    var lastBreathingSeen: Double? = null
        private set

    var currentValidation: ValidationCode? = null
        private set

    val acceptedPulseCount: Int get() = pulse.size
    val acceptedBreathingCount: Int get() = breathing.size

    fun onValidation(code: ValidationCode?) {
        currentValidation = code
        if (code != null && code != ValidationCode.OK) {
            issueCounts[code] = (issueCounts[code] ?: 0) + 1
        }
    }

    /**
     * @param elapsedSeconds seconds since the measurement started, measured by
     *   the app's own monotonic clock rather than the sample timestamp, so that
     *   warm-up means "since the camera opened".
     */
    fun onPulseSample(value: Double, stable: Boolean, timestampMicros: Long, elapsedSeconds: Double) {
        if (timestampMicros <= lastPulseTimestamp) return
        lastPulseTimestamp = timestampMicros
        if (value !in Thresholds.PLAUSIBLE_PULSE_BPM) return
        lastPulseSeen = value
        if (elapsedSeconds < mode.warmupSeconds) return
        if (!stable) return
        if (currentValidation in Thresholds.PULSE_BLOCKERS) return
        pulse += value
    }

    fun onBreathingSample(value: Double, stable: Boolean, timestampMicros: Long, elapsedSeconds: Double) {
        if (timestampMicros <= lastBreathingTimestamp) return
        lastBreathingTimestamp = timestampMicros
        if (value !in Thresholds.PLAUSIBLE_BREATHING_BRPM) return
        lastBreathingSeen = value
        if (elapsedSeconds < mode.warmupSeconds) return
        if (!stable) return
        if (currentValidation in Thresholds.BREATHING_BLOCKERS) return
        breathing += value
    }

    /**
     * A payment scan may finish early once it has what it needs, but never
     * before its target length; a calibration always runs its full length.
     */
    fun shouldStop(elapsedSeconds: Double): Boolean {
        if (elapsedSeconds >= mode.maxSeconds) return true
        return elapsedSeconds >= mode.targetSeconds && pulse.size >= mode.minPulseSamples
    }

    fun dominantIssue(): ValidationCode? = issueCounts.maxByOrNull { it.value }?.key

    fun summarize(elapsedSeconds: Double): ScanSummary = ScanSummary(
        pulseBpm = Stats.median(pulse),
        pulseSamples = pulse.size,
        breathingBrpm = Stats.median(breathing),
        breathingSamples = breathing.size,
        dominantIssue = dominantIssue(),
        durationSeconds = elapsedSeconds.toInt(),
    )

    /**
     * Turns a calibration window into a stored baseline, or returns null when
     * the scan did not produce enough trustworthy samples. Breathing is
     * optional: a pulse-only baseline is valid, and the breathing rule is then
     * simply never applied.
     */
    fun toBaseline(nowEpochMillis: Long): Baseline? {
        val restingPulse = Stats.median(pulse) ?: return null
        if (pulse.size < Thresholds.BASELINE_MIN_PULSE_SAMPLES) return null
        val enoughBreathing = breathing.size >= Thresholds.BASELINE_MIN_BREATHING_SAMPLES
        return Baseline(
            restingPulseBpm = restingPulse,
            pulseMad = Stats.mad(pulse) ?: 0.0,
            pulseSamples = pulse.size,
            restingBreathingBrpm = if (enoughBreathing) Stats.median(breathing) else null,
            breathingMad = if (enoughBreathing) Stats.mad(breathing) else null,
            breathingSamples = breathing.size,
            recordedAtEpochMillis = nowEpochMillis,
        )
    }
}
