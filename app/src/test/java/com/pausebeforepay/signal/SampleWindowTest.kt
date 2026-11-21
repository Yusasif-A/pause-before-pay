package com.pausebeforepay.signal

import com.presagetech.smartspectra.ValidationCode
import com.presagetech.smartspectra.proto.MetricsProto.ExpressionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTest {

    @Test
    fun `median of an odd list is the middle value`() {
        assertEquals(70.0, Stats.median(listOf(68.0, 70.0, 300.0))!!, 0.001)
    }

    @Test
    fun `median of an even list averages the middle pair`() {
        assertEquals(69.0, Stats.median(listOf(68.0, 70.0))!!, 0.001)
    }

    @Test
    fun `median ignores a single wild outlier that a mean would not`() {
        val values = listOf(70.0, 71.0, 69.0, 70.0, 500.0)
        assertEquals(70.0, Stats.median(values)!!, 0.001)
    }

    @Test
    fun `empty input gives null rather than zero`() {
        assertNull(Stats.median(emptyList()))
        assertNull(Stats.mad(emptyList()))
    }

    @Test
    fun `mad is zero for a flat series`() {
        assertEquals(0.0, Stats.mad(listOf(70.0, 70.0, 70.0))!!, 0.001)
    }
}

class SampleWindowTest {

    private fun window(mode: ScanMode = ScanMode.PAYMENT) = SampleWindow(mode)

    @Test
    fun `samples inside the warm-up window are discarded`() {
        val w = window()
        w.onValidation(ValidationCode.OK)
        w.onPulseSample(72.0, stable = true, timestampMicros = 1, elapsedSeconds = 5.0)
        assertEquals(0, w.acceptedPulseCount)
        // Still shown live, so the UI is not blank during warm-up.
        assertEquals(72.0, w.lastPulseSeen!!, 0.001)
    }

    @Test
    fun `unstable samples are shown but not counted`() {
        val w = window()
        w.onValidation(ValidationCode.OK)
        w.onPulseSample(72.0, stable = false, timestampMicros = 1, elapsedSeconds = 20.0)
        assertEquals(0, w.acceptedPulseCount)
        assertEquals(72.0, w.lastPulseSeen!!, 0.001)
    }

    @Test
    fun `a blocking validation code rejects pulse samples`() {
        val w = window()
        w.onValidation(ValidationCode.TOO_DARK)
        w.onPulseSample(72.0, stable = true, timestampMicros = 1, elapsedSeconds = 20.0)
        assertEquals(0, w.acceptedPulseCount)
    }

    @Test
    fun `a chest-only problem still allows pulse but not breathing`() {
        val w = window()
        w.onValidation(ValidationCode.CHEST_NOT_VISIBLE)
        w.onPulseSample(72.0, stable = true, timestampMicros = 1, elapsedSeconds = 20.0)
        w.onBreathingSample(16.0, stable = true, timestampMicros = 1, elapsedSeconds = 20.0)
        assertEquals(1, w.acceptedPulseCount)
        assertEquals(0, w.acceptedBreathingCount)
    }

    @Test
    fun `repeated timestamps are not double counted`() {
        val w = window()
        w.onValidation(ValidationCode.OK)
        w.onPulseSample(72.0, stable = true, timestampMicros = 100, elapsedSeconds = 20.0)
        w.onPulseSample(72.0, stable = true, timestampMicros = 100, elapsedSeconds = 20.0)
        w.onPulseSample(73.0, stable = true, timestampMicros = 101, elapsedSeconds = 20.0)
        assertEquals(2, w.acceptedPulseCount)
    }

    @Test
    fun `implausible values are dropped entirely`() {
        val w = window()
        w.onValidation(ValidationCode.OK)
        w.onPulseSample(5.0, stable = true, timestampMicros = 1, elapsedSeconds = 20.0)
        w.onPulseSample(400.0, stable = true, timestampMicros = 2, elapsedSeconds = 20.0)
        assertEquals(0, w.acceptedPulseCount)
        assertNull(w.lastPulseSeen)
    }

    @Test
    fun `a payment scan stops early once it has enough samples`() {
        val w = window()
        w.onValidation(ValidationCode.OK)
        repeat(Thresholds.SCAN_MIN_PULSE_SAMPLES) { index ->
            w.onPulseSample(
                70.0 + index,
                stable = true,
                timestampMicros = index.toLong() + 1,
                elapsedSeconds = 20.0,
            )
        }
        assertTrue(w.shouldStop(Thresholds.SCAN_TARGET_SECONDS.toDouble()))
        assertTrue(!w.shouldStop(Thresholds.SCAN_TARGET_SECONDS - 1.0))
    }

    @Test
    fun `a payment scan always stops at the hard cap`() {
        val w = window()
        assertTrue(w.shouldStop(Thresholds.SCAN_MAX_SECONDS.toDouble()))
    }

    @Test
    fun `a calibration runs its full length even with plenty of samples`() {
        val w = window(ScanMode.BASELINE)
        w.onValidation(ValidationCode.OK)
        repeat(40) { index ->
            w.onPulseSample(
                70.0,
                stable = true,
                timestampMicros = index.toLong() + 1,
                elapsedSeconds = 30.0,
            )
        }
        assertTrue(!w.shouldStop(40.0))
        assertTrue(w.shouldStop(Thresholds.BASELINE_SCAN_SECONDS.toDouble()))
    }

    @Test
    fun `a calibration with too few samples produces no baseline`() {
        val w = window(ScanMode.BASELINE)
        w.onValidation(ValidationCode.OK)
        repeat(Thresholds.BASELINE_MIN_PULSE_SAMPLES - 1) { index ->
            w.onPulseSample(
                70.0,
                stable = true,
                timestampMicros = index.toLong() + 1,
                elapsedSeconds = 30.0,
            )
        }
        assertNull(w.toBaseline(nowEpochMillis = 0L))
    }

    @Test
    fun `a calibration without breathing still produces a pulse-only baseline`() {
        val w = window(ScanMode.BASELINE)
        w.onValidation(ValidationCode.OK)
        repeat(Thresholds.BASELINE_MIN_PULSE_SAMPLES) { index ->
            w.onPulseSample(
                70.0,
                stable = true,
                timestampMicros = index.toLong() + 1,
                elapsedSeconds = 30.0,
            )
        }
        val baseline = w.toBaseline(nowEpochMillis = 123L)
        assertNotNull(baseline)
        assertEquals(70.0, baseline!!.restingPulseBpm, 0.001)
        assertNull(baseline.restingBreathingBrpm)
        assertEquals(123L, baseline.recordedAtEpochMillis)
    }

    @Test
    fun `low-confidence expression samples are ignored`() {
        val w = window()
        w.onValidation(ValidationCode.OK)
        w.onExpressionSample(
            ExpressionType.ANGRY,
            confidence = Thresholds.MIN_EXPRESSION_CONFIDENCE - 1f,
            timestampMicros = 1,
            elapsedSeconds = 20.0,
        )
        assertEquals(0, w.acceptedExpressionCount)
        assertNull(w.lastExpressionSeen)
    }

    @Test
    fun `expression samples are gated by warm-up and framing`() {
        val w = window()
        w.onValidation(ValidationCode.OK)
        w.onExpressionSample(ExpressionType.ANGRY, 90f, 1, elapsedSeconds = 5.0)
        assertEquals(0, w.acceptedExpressionCount)
        // Shown live even during warm-up.
        assertEquals(ExpressionType.ANGRY, w.lastExpressionSeen)

        w.onValidation(ValidationCode.NO_FACE_FOUND)
        w.onExpressionSample(ExpressionType.ANGRY, 90f, 2, elapsedSeconds = 20.0)
        assertEquals(0, w.acceptedExpressionCount)

        w.onValidation(ValidationCode.OK)
        w.onExpressionSample(ExpressionType.ANGRY, 90f, 3, elapsedSeconds = 20.0)
        assertEquals(1, w.acceptedExpressionCount)
    }

    @Test
    fun `tense share counts only tense expressions`() {
        val w = window()
        w.onValidation(ValidationCode.OK)
        w.onExpressionSample(ExpressionType.ANGRY, 90f, 1, 20.0)
        w.onExpressionSample(ExpressionType.FEAR, 90f, 2, 20.0)
        w.onExpressionSample(ExpressionType.NEUTRAL, 90f, 3, 20.0)
        w.onExpressionSample(ExpressionType.HAPPY, 90f, 4, 20.0)
        assertEquals(4, w.acceptedExpressionCount)
        assertEquals(0.5, w.tenseShareSoFar!!, 0.001)
    }

    @Test
    fun `the summary carries the face reading through`() {
        val w = window()
        w.onValidation(ValidationCode.OK)
        repeat(Thresholds.MIN_EXPRESSION_SAMPLES + 2) { index ->
            w.onExpressionSample(ExpressionType.FEAR, 90f, index.toLong() + 1, 20.0)
        }
        val summary = w.summarize(25.0)
        assertEquals(ExpressionType.FEAR, summary.dominantExpression)
        assertEquals(true, summary.faceTense)
    }

    @Test
    fun `the dominant issue is the most frequent non-OK code`() {
        val w = window()
        w.onValidation(ValidationCode.OK)
        w.onValidation(ValidationCode.TOO_DARK)
        w.onValidation(ValidationCode.TOO_DARK)
        w.onValidation(ValidationCode.EXCESSIVE_MOTION)
        assertEquals(ValidationCode.TOO_DARK, w.dominantIssue())
    }
}
