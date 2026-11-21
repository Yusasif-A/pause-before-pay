package com.pausebeforepay.signal

import com.presagetech.smartspectra.ValidationCode
import com.presagetech.smartspectra.proto.MetricsProto.ExpressionType

/**
 * Every tunable number in PauseBeforePay lives here.
 *
 * Why one file: this logic decides whether to interrupt somebody sending money.
 * It has to be auditable, so it is plain arithmetic over named constants — no
 * model, no language model, no hidden state. A reviewer can read this file and
 * know exactly what the app will do.
 *
 * Timing numbers are grounded in the SmartSpectra docs rather than invented:
 * pulse rate produces its first value at roughly 12 s and is itself a 12-second
 * average; breathing rate appears at roughly 10 s but carries zero confidence
 * until roughly 30 s; HRV needs roughly 60 s (we do not use HRV).
 * See https://smartspectra.presagetech.com/docs/android/option-1-api-key
 */
object Thresholds {

    // ---- Baseline calibration (one-time, 60 s) -------------------------------

    /** Total length of a calibration measurement. */
    const val BASELINE_SCAN_SECONDS = 60

    /** Samples arriving before this are discarded as warm-up / stabilisation. */
    const val BASELINE_WARMUP_SECONDS = 15

    /** Below this many accepted pulse samples, calibration fails honestly. */
    const val BASELINE_MIN_PULSE_SAMPLES = 12

    /**
     * Breathing is optional in a baseline: its confidence is zero for the first
     * ~30 s, so a cooperative 60 s scan usually clears this but not always.
     * When it does not, we store a pulse-only baseline and simply never apply
     * the breathing rule.
     */
    const val BASELINE_MIN_BREATHING_SAMPLES = 6

    // ---- Payment-time scan ---------------------------------------------------

    /** Earliest the scan may conclude, if it already has enough samples. */
    const val SCAN_TARGET_SECONDS = 25

    /** Hard cap. We stop here even without enough samples (→ LOW_CONFIDENCE). */
    const val SCAN_MAX_SECONDS = 40

    /**
     * Warm-up discard. Pulse produces nothing before ~12 s anyway; naming it
     * makes the SDK's latency explicit instead of accidental.
     */
    const val SCAN_WARMUP_SECONDS = 12

    /** Below this many accepted pulse samples we refuse to make a call. */
    const val SCAN_MIN_PULSE_SAMPLES = 6

    // ---- The decision -------------------------------------------------------

    /**
     * "Clearly above your own resting rate". The SDK's stated pulse accuracy is
     * +/-3 bpm for samples marked `stable`, so a 12 bpm gap is four times the
     * published error band — comfortably outside measurement noise.
     */
    const val ELEVATED_PULSE_DELTA_BPM = 12.0

    /** Breathing accuracy for `stable` samples is +/-1 br/min; 5 clears it. */
    const val ELEVATED_BREATHING_DELTA_BRPM = 5.0

    /** Used only when no personal baseline has been recorded yet. */
    const val ABSOLUTE_PULSE_BPM = 100.0
    const val ABSOLUTE_BREATHING_BRPM = 20.0

    // ---- Sanity filters -----------------------------------------------------

    /** Physiologically implausible readings are dropped before any maths. */
    val PLAUSIBLE_PULSE_BPM = 30.0..220.0
    val PLAUSIBLE_BREATHING_BRPM = 4.0..45.0

    // ---- Signal-quality gating ---------------------------------------------

    /**
     * A sample is accepted only when the SDK marks it `stable` AND the current
     * validation code is not a blocker for that metric.
     *
     * `stable` is the SDK's own verdict that confidence has reached the
     * published accuracy standard (>= 40 for pulse, >= 45 for breathing), which
     * is a far better quality gate than anything we could invent.
     *
     * We deliberately do NOT require `ValidationCode.OK` for everything. Some
     * codes do not affect the metric in question — a chest that is out of frame
     * ruins breathing but says nothing about pulse, and CAMERA_TUNING is a
     * transient start-up state. Requiring a bare OK throughout would starve
     * real scans and push honest users into LOW_CONFIDENCE for no reason.
     */
    val PULSE_BLOCKERS: Set<ValidationCode> = setOf(
        ValidationCode.NO_FACE_FOUND,
        ValidationCode.MULTIPLE_FACES_FOUND,
        ValidationCode.TOO_DARK,
        ValidationCode.TOO_BRIGHT,
        ValidationCode.FACE_SIZE_OUT_OF_RANGE,
        ValidationCode.FACE_TOO_CLOSE,
        ValidationCode.FACE_TOO_FAR,
        ValidationCode.EXCESSIVE_MOTION,
        ValidationCode.FRAME_RATE_TOO_LOW,
    )

    /** Breathing additionally needs the chest in frame and the face forward. */
    val BREATHING_BLOCKERS: Set<ValidationCode> = PULSE_BLOCKERS + setOf(
        ValidationCode.CHEST_NOT_VISIBLE,
        ValidationCode.FACE_NOT_FORWARD,
    )

    // ---- Facial expression --------------------------------------------------

    /**
     * A sustained tense face can raise the pause on its own, alongside pulse
     * and breathing. It catches the case the pulse rules miss: someone who is
     * frightened but whose heart rate has not moved yet.
     *
     * The rule is still plain arithmetic over named constants — count the
     * confident samples, count how many were tense, compare the share with
     * [TENSE_SHARE] — so it stays as auditable as the pulse rule.
     *
     * The honest caveat, which is why the share is set high: an expression
     * model is noisier than a pulse rate. Concentrating reads as scowling and
     * squinting at a bright screen reads as angry. [TENSE_SHARE] is the dial to
     * turn if it fires too often in real use.
     *
     * The SDK's expression model emits ANGRY, CONTEMPT, DISGUST, FEAR, HAPPY,
     * NEUTRAL, SAD and SURPRISE with a confidence in 0..100. It does not do
     * face recognition: this says how a face looks, never whose face it is.
     */
    val TENSE_EXPRESSIONS: Set<ExpressionType> = setOf(
        ExpressionType.ANGRY,
        ExpressionType.FEAR,
        ExpressionType.SAD,
        ExpressionType.CONTEMPT,
        ExpressionType.DISGUST,
        ExpressionType.SURPRISE,
    )

    /** Below this confidence an expression sample is ignored entirely. */
    const val MIN_EXPRESSION_CONFIDENCE = 50f

    /** Fewer accepted samples than this and the face is not judged at all. */
    const val MIN_EXPRESSION_SAMPLES = 10

    /**
     * Share of accepted samples that must be tense before the face counts as
     * tense. Set above half deliberately: the face can trigger a pause, so a
     * brief frown must not be enough.
     */
    const val TENSE_SHARE = 0.55

    // ---- Breath pacer -------------------------------------------------------

    const val PACER_BREATHS = 3
    const val PACER_INHALE_SECONDS = 4
    const val PACER_EXHALE_SECONDS = 6
}
