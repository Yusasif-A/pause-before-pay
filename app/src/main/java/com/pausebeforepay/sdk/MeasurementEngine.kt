package com.pausebeforepay.sdk

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.Preview
import androidx.lifecycle.asFlow
import com.pausebeforepay.BuildConfig
import com.pausebeforepay.signal.SampleWindow
import com.pausebeforepay.signal.ScanMode
import com.pausebeforepay.signal.ScanSummary
import com.presagetech.smartspectra.CameraSelection
import com.presagetech.smartspectra.ProcessingStatus
import com.presagetech.smartspectra.SmartSpectraSdk
import com.presagetech.smartspectra.ValidationCode
import com.presagetech.smartspectra.proto.MetricTypesProto.MetricType
import com.presagetech.smartspectra.proto.MetricsProto.ExpressionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the scan screen renders while a measurement is running. */
data class LiveReading(
    val running: Boolean = false,
    val elapsedSeconds: Int = 0,
    val targetSeconds: Int = 0,
    val pastWarmup: Boolean = false,
    /** Latest pulse the SDK produced, stable or not, so the UI is never blank. */
    val pulseBpm: Double? = null,
    val breathingBrpm: Double? = null,
    val acceptedPulseSamples: Int = 0,
    val acceptedBreathingSamples: Int = 0,
    /** Latest confident expression, so the face state is visible live. */
    val expression: ExpressionType? = null,
    val acceptedExpressionSamples: Int = 0,
    /** Running share of accepted expression samples that were tense. */
    val tenseShare: Double? = null,
    val validation: ValidationCode? = null,
    /** The SDK's own human-readable framing hint, when it supplies one. */
    val validationHint: String? = null,
    /** The SDK's own lifecycle state: IDLE, STARTING, RUNNING, STOPPING, ERROR. */
    val processing: ProcessingStatus? = null,
    /**
     * The last error the SDK reported, retryable or not. Retryable errors used
     * to be logged and nothing else, which made a failing scan look merely slow.
     */
    val errorText: String? = null,
    /**
     * How many metrics payloads have arrived, and how many carried each bundle.
     * This separates "the stream is silent" from "the stream is delivering
     * empty payloads". Those look identical on screen and have different causes.
     */
    val payloads: Int = 0,
    val cardioPayloads: Int = 0,
    val breathingPayloads: Int = 0,
    val facePayloads: Int = 0,
)

/**
 * Everything that touches the SmartSpectra SDK, in one place.
 *
 * The SDK computes metrics on the device; its backend is contacted only to
 * authenticate the API key and validate the subscription. Telemetry is turned
 * off here (it defaults to on) so the consent screen can say, truthfully, that
 * nothing about a measurement leaves the phone.
 *
 * [runMeasurement] is a suspend function that owns one whole measurement:
 * observe, start, collect, stop. Cancelling the calling coroutine stops the
 * camera.
 */
class MeasurementEngine {

    private val sdk by lazy { SmartSpectraSdk.shared }

    private val _live = MutableStateFlow(LiveReading())
    val live: StateFlow<LiveReading> = _live.asStateFlow()

    /**
     * Whether to ask for facial expressions as well as pulse and breathing.
     *
     * Kept separate from the rest because EXPRESSIONS belongs to the face
     * metrics bundle, which an API key may not be authorised for. If the SDK
     * reports METRIC_NOT_AUTHORIZED, setting this to false falls back to pulse
     * and breathing alone, which the pause is designed to run on.
     */
    var requestExpressions: Boolean = true

    /** Set from the scan screen once its CameraX PreviewView exists. */
    fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
        sdk.config.previewSurfaceProvider = provider
    }

    val sdkVersion: String get() = runCatching { sdk.version }.getOrDefault("unknown")

    val apiKeyConfigured: Boolean get() = BuildConfig.PRESAGE_API_KEY.isNotBlank()

    private fun configure() {
        sdk.config.apiKey = BuildConfig.PRESAGE_API_KEY
        // We draw the camera ourselves through a PreviewView, so we do not need
        // the SDK's bitmap frames. This is the pairing the quickstart uses.
        sdk.config.imageOutputEnabled = false
        // Defaults to true. Off, so the consent screen can promise it is off.
        sdk.config.enableTelemetry = false
        // Ask for exactly the three metrics this app reads, and nothing else.
        //
        // Deliberately NOT the SmartSpectraConfig bundles. Reading the AAR
        // shows cardioMetrics = [PULSE_RATE, ARTERIAL_PRESSURE_TRACE, HRV] and
        // breathingMetrics = [CHEST_BREATHING, ABDOMEN_BREATHING,
        // BREATHING_RATE]. Requesting the bundles asks for four metrics this
        // app never reads, any of which a given API key may not be authorised
        // for. Asking only for what we use keeps the request as small as the
        // consent screen claims, and removes four chances for an authorisation
        // failure to take the whole measurement down with it.
        sdk.config.requestedMetrics = buildList {
            add(MetricType.PULSE_RATE)
            add(MetricType.BREATHING_RATE)
            if (requestExpressions) add(MetricType.EXPRESSIONS)
        }
        sdk.useCamera(CameraSelection.Front)
    }

    /**
     * Runs one measurement to completion and returns what it collected.
     *
     * Never throws for an SDK failure: an authentication, camera or network
     * problem comes back as [ScanSummary.failed], because the caller's job is
     * to tell the user and offer a retry, not to crash.
     */
    suspend fun runMeasurement(mode: ScanMode): ScanSummary = coroutineScope {
        if (!apiKeyConfigured) {
            return@coroutineScope ScanSummary.failed(
                "No Presage API key is configured in this build. " +
                    "See the README for where to put one.",
            )
        }

        val window = SampleWindow(mode)
        _live.value = LiveReading(running = true, targetSeconds = mode.targetSeconds)

        configure()

        // Restarted the moment start() returns, so "warm-up" means "since the
        // camera opened" rather than "since we began observing".
        var startedAt = SystemClock.elapsedRealtime()
        fun elapsed(): Double = (SystemClock.elapsedRealtime() - startedAt) / 1000.0

        var asyncFailure: String? = null
        var payloads = 0
        var cardioPayloads = 0
        var breathingPayloads = 0
        var facePayloads = 0

        fun pushCounts(now: Double) = publish(
            window = window,
            mode = mode,
            elapsedSeconds = now,
            payloads = payloads,
            cardioPayloads = cardioPayloads,
            breathingPayloads = breathingPayloads,
            facePayloads = facePayloads,
        )

        // Observers go up BEFORE start(), which is the order the SDK's own
        // quickstart uses. metricsFlow is a SharedFlow with no replay, so a
        // collector registered after start() silently misses anything emitted
        // in between.
        val observers = mutableListOf<Job>()

        observers += launch {
            sdk.validationStatus.asFlow().collect { status ->
                window.onValidation(status?.code)
                _live.value = _live.value.copy(
                    validation = status?.code,
                    validationHint = status?.hint?.takeIf { it.isNotBlank() },
                )
            }
        }

        observers += launch {
            sdk.processingStatus.asFlow().collect { status ->
                Log.i(TAG, "processingStatus = $status")
                _live.value = _live.value.copy(processing = status)
            }
        }

        observers += launch {
            sdk.error.asFlow().collect { error ->
                if (error == null) return@collect
                Log.e(
                    TAG,
                    "SDK error: code=" + error.code +
                        " retryable=" + error.retryable +
                        " message=" + error.message,
                )
                // Every error is surfaced now, retryable or not. A retryable
                // error used to be logged and nothing else, which produced a
                // scan that ran its full length, collected nothing, and offered
                // no explanation anywhere on screen.
                _live.value = _live.value.copy(
                    errorText = readableError(error.code.name, error.message),
                )
                if (!error.retryable) asyncFailure = readableError(error.code.name, error.message)
            }
        }

        observers += launch {
            sdk.metricsFlow.collect { metrics ->
                val now = elapsed()
                payloads++
                if (metrics.hasCardio()) {
                    cardioPayloads++
                    metrics.cardio.pulseRateList.forEach { sample ->
                        window.onPulseSample(
                            value = sample.value.toDouble(),
                            stable = sample.stable,
                            timestampMicros = sample.timestamp,
                            elapsedSeconds = now,
                        )
                    }
                }
                if (metrics.hasBreathing()) {
                    breathingPayloads++
                    metrics.breathing.rateList.forEach { sample ->
                        window.onBreathingSample(
                            value = sample.value.toDouble(),
                            stable = sample.stable,
                            timestampMicros = sample.timestamp,
                            elapsedSeconds = now,
                        )
                    }
                }
                if (metrics.hasFace()) {
                    facePayloads++
                    metrics.face.expressionList.forEach { expression ->
                        // Each sample carries a score per expression type; the
                        // one with the highest confidence is the read.
                        val top = expression.scoresList
                            .filter { it.confidence > 0f }
                            .maxByOrNull { it.confidence }
                            ?: return@forEach
                        window.onExpressionSample(
                            type = top.type,
                            confidence = top.confidence,
                            timestampMicros = expression.timestamp,
                            elapsedSeconds = now,
                        )
                    }
                }
                if (payloads <= 3 || payloads % 50 == 0) {
                    Log.i(
                        TAG,
                        "payload #" + payloads +
                            " cardio=" + metrics.hasCardio() +
                            " breathing=" + metrics.hasBreathing() +
                            " face=" + metrics.hasFace(),
                    )
                }
                pushCounts(now)
            }
        }

        try {
            sdk.start()
            startedAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "start() returned; requested = " + sdk.config.requestedMetrics)
        } catch (c: CancellationException) {
            observers.forEach { it.cancel() }
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "start() failed", t)
            observers.forEach { it.cancel() }
            _live.value = LiveReading()
            return@coroutineScope ScanSummary.failed(readableError(t))
        }

        try {
            while (isActive) {
                delay(TICK_MILLIS)
                val now = elapsed()
                pushCounts(now)
                if (asyncFailure != null) break
                if (window.shouldStop(now)) break
            }
        } finally {
            observers.forEach { it.cancel() }
            // stop() is a suspend function, so a cancelled scan would skip it and
            // leave the camera running. NonCancellable makes the shutdown happen.
            withContext(NonCancellable) {
                runCatching { sdk.stop() }
                    .onFailure { Log.w(TAG, "stop() failed", it) }
            }
            sdk.config.previewSurfaceProvider = null
            Log.i(
                TAG,
                "scan finished: payloads=" + payloads +
                    " cardio=" + cardioPayloads +
                    " breathing=" + breathingPayloads +
                    " face=" + facePayloads +
                    " acceptedPulse=" + window.acceptedPulseCount,
            )
            _live.value = LiveReading()
        }

        val failure = asyncFailure
        if (failure != null) ScanSummary.failed(failure) else window.summarize(elapsed())
    }

    private fun publish(
        window: SampleWindow,
        mode: ScanMode,
        elapsedSeconds: Double,
        payloads: Int,
        cardioPayloads: Int,
        breathingPayloads: Int,
        facePayloads: Int,
    ) {
        _live.value = _live.value.copy(
            running = true,
            elapsedSeconds = elapsedSeconds.toInt(),
            targetSeconds = mode.targetSeconds,
            pastWarmup = elapsedSeconds >= mode.warmupSeconds,
            pulseBpm = window.lastPulseSeen,
            breathingBrpm = window.lastBreathingSeen,
            acceptedPulseSamples = window.acceptedPulseCount,
            acceptedBreathingSamples = window.acceptedBreathingCount,
            expression = window.lastExpressionSeen,
            acceptedExpressionSamples = window.acceptedExpressionCount,
            tenseShare = window.tenseShareSoFar,
            payloads = payloads,
            cardioPayloads = cardioPayloads,
            breathingPayloads = breathingPayloads,
            facePayloads = facePayloads,
        )
    }

    private fun readableError(t: Throwable): String {
        val error = (t as? com.presagetech.smartspectra.SmartSpectraException)?.error
        return if (error != null) {
            readableError(error.code.name, error.message)
        } else {
            t.message ?: "The camera measurement could not start."
        }
    }

    private fun readableError(code: String, message: String?): String {
        val detail = message?.takeIf { it.isNotBlank() }
        return when (code) {
            "AUTHENTICATION_FAILED" ->
                "The Presage API key was rejected. Check the key in local.properties."
            "NETWORK_ERROR" ->
                "No internet connection. The SDK checks its API key once before measuring."
            "CREDIT_EXHAUSTED" ->
                "This Presage API key has run out of measurement credit."
            "INPUT_UNAVAILABLE" ->
                "The front camera is not available on this device."
            "METRIC_NOT_AUTHORIZED" ->
                "This API key is not authorized for a requested metric" +
                    (detail?.let { ": $it" } ?: ".")
            "CONFIGURATION_FAILED" ->
                "The SDK rejected the requested metrics" + (detail?.let { ": $it" } ?: ".")
            "SUSTAINED_LOW_FRAME_RATE" ->
                "The camera is running too slowly for a reliable reading. Try better light."
            else -> detail ?: "The camera measurement stopped unexpectedly ($code)."
        }
    }

    private companion object {
        const val TAG = "MeasurementEngine"
        const val TICK_MILLIS = 250L
    }
}
