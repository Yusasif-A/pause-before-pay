package com.pausebeforepay.signal

import com.presagetech.smartspectra.ValidationCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision gates somebody's money, so it is tested rather than trusted.
 * These run on the JVM: `./gradlew :app:testDebugUnitTest`.
 */
class DecisionEngineTest {

    private fun scan(
        pulse: Double? = 70.0,
        pulseSamples: Int = 10,
        breathing: Double? = null,
        breathingSamples: Int = 0,
        issue: ValidationCode? = null,
        tenseShare: Double? = null,
        expressionSamples: Int = 0,
    ) = ScanSummary(
        pulseBpm = pulse,
        pulseSamples = pulseSamples,
        breathingBrpm = breathing,
        breathingSamples = breathingSamples,
        dominantIssue = issue,
        durationSeconds = 25,
        tenseShare = tenseShare,
        expressionSamples = expressionSamples,
    )


    private fun baseline(pulse: Double = 68.0, breathing: Double? = 14.0) = Baseline(
        restingPulseBpm = pulse,
        pulseMad = 2.0,
        pulseSamples = 30,
        restingBreathingBrpm = breathing,
        breathingMad = 1.0,
        breathingSamples = 20,
        recordedAtEpochMillis = 0L,
    )

    // ---- Baseline comparison ----------------------------------------------

    @Test
    fun `pulse well above baseline is elevated`() {
        val decision = DecisionEngine.decide(scan(pulse = 85.0), baseline(pulse = 68.0))
        assertEquals(Verdict.ELEVATED, decision.verdict)
        assertEquals(Basis.PERSONAL_BASELINE, decision.basis)
        assertTrue(decision.blocksConfirm)
    }

    @Test
    fun `pulse just under the threshold is calm`() {
        // 68 + 12 = 80 fires; 79.9 must not.
        val decision = DecisionEngine.decide(scan(pulse = 79.9), baseline(pulse = 68.0))
        assertEquals(Verdict.CALM, decision.verdict)
        assertFalse(decision.blocksConfirm)
    }

    @Test
    fun `pulse exactly at the threshold is elevated`() {
        val decision = DecisionEngine.decide(scan(pulse = 80.0), baseline(pulse = 68.0))
        assertEquals(Verdict.ELEVATED, decision.verdict)
    }

    @Test
    fun `breathing alone can trigger the pause`() {
        val decision = DecisionEngine.decide(
            scan(pulse = 70.0, breathing = 20.0, breathingSamples = 8),
            baseline(pulse = 68.0, breathing = 14.0),
        )
        assertEquals(Verdict.ELEVATED, decision.verdict)
        assertEquals(6.0, decision.breathingDeltaBrpm!!, 0.001)
    }

    @Test
    fun `a pulse-only baseline never applies the breathing rule`() {
        val decision = DecisionEngine.decide(
            scan(pulse = 70.0, breathing = 30.0, breathingSamples = 9),
            baseline(pulse = 68.0, breathing = null),
        )
        assertEquals(Verdict.CALM, decision.verdict)
        assertNull(decision.breathingDeltaBrpm)
    }

    @Test
    fun `a pulse below baseline is calm`() {
        val decision = DecisionEngine.decide(scan(pulse = 60.0), baseline(pulse = 68.0))
        assertEquals(Verdict.CALM, decision.verdict)
        assertEquals(-8.0, decision.pulseDeltaBpm!!, 0.001)
    }

    // ---- Absolute fallback -------------------------------------------------

    @Test
    fun `without a baseline it falls back to absolute thresholds`() {
        val decision = DecisionEngine.decide(scan(pulse = 104.0), baseline = null)
        assertEquals(Verdict.ELEVATED, decision.verdict)
        assertEquals(Basis.ABSOLUTE_FALLBACK, decision.basis)
        assertNull(decision.pulseDeltaBpm)
    }

    @Test
    fun `a high-but-not-absolute pulse is calm without a baseline`() {
        // 95 bpm would be clearly elevated for someone resting at 60, but with
        // no baseline the app has no right to that conclusion.
        val decision = DecisionEngine.decide(scan(pulse = 95.0), baseline = null)
        assertEquals(Verdict.CALM, decision.verdict)
    }

    // ---- Low confidence never blocks --------------------------------------

    @Test
    fun `too few samples yields low confidence and does not block`() {
        val decision = DecisionEngine.decide(
            scan(pulse = 130.0, pulseSamples = Thresholds.SCAN_MIN_PULSE_SAMPLES - 1),
            baseline(pulse = 60.0),
        )
        assertEquals(Verdict.LOW_CONFIDENCE, decision.verdict)
        assertEquals(Basis.INSUFFICIENT_SIGNAL, decision.basis)
        assertFalse(decision.blocksConfirm)
    }

    @Test
    fun `no pulse at all yields low confidence with guidance`() {
        val decision = DecisionEngine.decide(
            scan(pulse = null, pulseSamples = 0, issue = ValidationCode.TOO_DARK),
            baseline(),
        )
        assertEquals(Verdict.LOW_CONFIDENCE, decision.verdict)
        assertTrue(decision.guidance!!.contains("dark"))
    }

    @Test
    fun `every validation code has guidance`() {
        ValidationCode.values().forEach { code ->
            assertTrue("missing guidance for $code", DecisionEngine.guidanceFor(code).isNotBlank())
        }
        assertTrue(DecisionEngine.guidanceFor(null).isNotBlank())
    }

    // ---- Facial expression -------------------------------------------------

    @Test
    fun `a tense face raises the pause even with a calm pulse`() {
        val decision = DecisionEngine.decide(
            scan(
                pulse = 70.0,
                tenseShare = Thresholds.TENSE_SHARE + 0.1,
                expressionSamples = Thresholds.MIN_EXPRESSION_SAMPLES + 5,
            ),
            baseline(pulse = 68.0),
        )
        assertEquals(Verdict.ELEVATED, decision.verdict)
        assertTrue(decision.faceFired)
        assertFalse(decision.pulseFired)
        assertEquals("You seem tense", decision.headline)
    }

    @Test
    fun `a face just under the share threshold does not raise the pause`() {
        val decision = DecisionEngine.decide(
            scan(
                pulse = 70.0,
                tenseShare = Thresholds.TENSE_SHARE - 0.01,
                expressionSamples = Thresholds.MIN_EXPRESSION_SAMPLES + 5,
            ),
            baseline(pulse = 68.0),
        )
        assertEquals(Verdict.CALM, decision.verdict)
        assertFalse(decision.faceFired)
        assertEquals(false, decision.faceTense)
    }

    @Test
    fun `too few face samples leaves the face unjudged rather than settled`() {
        val decision = DecisionEngine.decide(
            scan(
                pulse = 70.0,
                tenseShare = 1.0,
                expressionSamples = Thresholds.MIN_EXPRESSION_SAMPLES - 1,
            ),
            baseline(pulse = 68.0),
        )
        assertEquals(Verdict.CALM, decision.verdict)
        assertNull(decision.faceTense)
        assertNull(decision.faceLabel)
    }

    @Test
    fun `a tense face still reads when the pulse was unusable`() {
        val decision = DecisionEngine.decide(
            scan(
                pulse = null,
                pulseSamples = 0,
                tenseShare = 1.0,
                expressionSamples = Thresholds.MIN_EXPRESSION_SAMPLES + 5,
            ),
            baseline(pulse = 68.0),
        )
        assertEquals(Verdict.ELEVATED, decision.verdict)
        assertEquals(Basis.FACE_ONLY, decision.basis)
        assertTrue(decision.blocksConfirm)
    }

    @Test
    fun `an unusable pulse and an unjudged face never blocks`() {
        val decision = DecisionEngine.decide(
            scan(pulse = null, pulseSamples = 0),
            baseline(pulse = 68.0),
        )
        assertEquals(Verdict.LOW_CONFIDENCE, decision.verdict)
        assertFalse(decision.blocksConfirm)
    }

    @Test
    fun `pulse and face together give the combined headline`() {
        val decision = DecisionEngine.decide(
            scan(
                pulse = 90.0,
                tenseShare = 1.0,
                expressionSamples = Thresholds.MIN_EXPRESSION_SAMPLES + 5,
            ),
            baseline(pulse = 68.0),
        )
        assertEquals("You seem rushed and tense", decision.headline)
        assertTrue(decision.pulseFired && decision.faceFired)
    }

    @Test
    fun `a calm verdict says so plainly`() {
        val decision = DecisionEngine.decide(scan(pulse = 70.0), baseline(pulse = 68.0))
        assertEquals("You look settled", decision.headline)
    }

    @Test
    fun `every headline hedges rather than asserting a state of mind`() {
        val headlines = listOf(
            DecisionEngine.decide(scan(pulse = 90.0), baseline(pulse = 68.0)),
            DecisionEngine.decide(
                scan(pulse = 70.0, tenseShare = 1.0, expressionSamples = 20),
                baseline(pulse = 68.0),
            ),
            DecisionEngine.decide(
                scan(pulse = 90.0, tenseShare = 1.0, expressionSamples = 20),
                baseline(pulse = 68.0),
            ),
        ).map { it.headline }
        headlines.forEach { assertTrue("not hedged: $it", it.startsWith("You seem")) }
    }

    // ---- Copy rules --------------------------------------------------------

    @Test
    fun `explanations never make a clinical or fraud claim`() {
        val banned = listOf(
            "diagnos", "scam", "fraud", "coerc", "forced", "disease", "stress",
            "anxiety", "blocked",
        )
        val decisions = listOf(
            DecisionEngine.decide(
                scan(pulse = 70.0, tenseShare = 1.0, expressionSamples = 20),
                baseline(pulse = 68.0),
            ),
            DecisionEngine.decide(
                scan(pulse = null, pulseSamples = 0, tenseShare = 1.0, expressionSamples = 20),
                baseline(pulse = 68.0),
            ),
            DecisionEngine.decide(scan(pulse = 95.0), baseline(pulse = 68.0)),
            DecisionEngine.decide(scan(pulse = 60.0), baseline(pulse = 68.0)),
            DecisionEngine.decide(scan(pulse = 120.0), null),
            DecisionEngine.decide(scan(pulse = null, pulseSamples = 0), null),
            DecisionEngine.decide(
                scan(pulse = 70.0, breathing = 25.0, breathingSamples = 9),
                baseline(pulse = 68.0, breathing = 14.0),
            ),
        )
        decisions.forEach { decision ->
            val text = (decision.explanation + " " + (decision.guidance ?: "")).lowercase()
            banned.forEach { word ->
                assertFalse("$word appeared in: $text", text.contains(word))
            }
        }
    }
}
