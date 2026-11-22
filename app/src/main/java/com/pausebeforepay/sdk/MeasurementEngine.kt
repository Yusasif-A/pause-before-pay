package com.pausebeforepay.sdk

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.Preview
import androidx.lifecycle.asFlow
import com.pausebeforepay.BuildConfig
import com.pausebeforepay.diag.DeviceLog
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
 * ## Threading
 *
 * Only [SmartSpectraSdk.start] and [SmartSpectraSdk.stop] run on the main
 * thread, because CameraX binds its use cases there. Everything else -- the
 * metrics, validation, processing and error collectors, and the progress tick
 * -- runs on a single background thread.
 *
 * That split is not tidiness. On a Redmi Note 11 the old main-thread version
 * logged `Slow Binder: BpBinder transact took 793ms` against
 * `ICameraDeviceUser` and frames of 1858ms: parsing eight expression scores at
 * 30 Hz, and recomposing the whole screen just as often, starved the camera's
 * own binder calls. The SDK saw the resulting holes in the frame timestamps and
 * reported TIMESTAMP_GAP, which it must, because a pulse cannot be read from an
 * unevenly sampled signal. The work is the same; it just no longer sits in
 * front of the camera.
 *
 * One background thread, not several: [SampleWindow] is not thread-safe, and
 * the validation collector and the metrics collector both touch it.
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
        // config.logLevel is left alone: its enum type is obfuscated in the
        // published AAR and has no importable name.
        //
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

        DeviceLog.beginSection("scan: $mode")
        DeviceLog.add("sdk ${sdkVersion}, requested ${sdk.config.requestedMetrics}")

        // Restarted the moment start() returns, so "warm-up" means "since the
        // camera opened" rather than "since we began observing".
        var startedAt = SystemClock.elapsedRealtime()
        fun elapsed(): Double = (SystemClock.elapsedRealtime() - startedAt) / 1000.0

        var asyncFailure: String? = null
        var lastErrorAt = 0L
        var payloads = 0
        var cardioPayloads = 0
        var breathingPayloads = 0
        var facePayloads = 0
        var pulseSamplesSeen = 0
        var lastLoggedValidation: ValidationCode? = null
        // The SDK appears to produce metrics only while the frame actually
        // passes validation, so a scan that never reaches OK explains itself.
        var sawValidationOk = false
        var processingFailed = false

        fun pushCounts(now: Double) = publish(
            window = window,
            mode = mode,
            elapsedSeconds = now,
            payloads = payloads,
            cardioPayloads = cardioPayloads,
            breathingPayloads = breathingPayloads,
            facePayloads = facePayloads,
        )

        // Everything except start()/stop() runs here, off the main thread, and
        // single-threaded because SampleWindow is not thread-safe.
        val work: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

        // Observers go up BEFORE start(), which is the order the SDK's own
        // quickstart uses. metricsFlow is a SharedFlow with no replay, so a
        // collector registered after start() silently misses anything emitted
        // in between.
        val observers = mutableListOf<Job>()

        observers += launch(work) {
            sdk.validationStatus.asFlow().collect { status ->
                window.onValidation(status?.code)
                if (status?.code == ValidationCode.OK) sawValidationOk = true
                if (status?.code != lastLoggedValidation) {
                    lastLoggedValidation = status?.code
                    DeviceLog.add("validation ${status?.code} ${status?.hint.orEmpty()}")
                }
                _live.value = _live.value.copy(
                    validation = status?.code,
                    validationHint = status?.hint?.takeIf { it.isNotBlank() },
                )
            }
        }

        observers += launch(work) {
            sdk.processingStatus.asFlow().collect { status ->
                Log.i(TAG, "processingStatus = $status")
                DeviceLog.add("processing $status")
                // ERROR is terminal: the SDK does not recover on its own, and
                // the old code sat through the remaining 17 seconds of a dead
                // pipeline before reporting anything.
                if (status == ProcessingStatus.ERROR) processingFailed = true
                _live.value = _live.value.copy(processing = status)
            }
        }

        observers += launch(work) {
            sdk.error.asFlow().collect { error ->
                if (error == null) return@collect
                Log.e(
                    TAG,
                    "SDK error: code=" + error.code +
                        " retryable=" + error.retryable +
                        " message=" + error.message,
                )
                DeviceLog.add(
                    "ERROR ${error.code} retryable=${error.retryable} ${error.message}",
                )
                lastErrorAt = SystemClock.elapsedRealtime()
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

        observers += launch(work) {
            sdk.metricsFlow.collect { metrics ->
                val now = elapsed()
                payloads++
                if (metrics.hasCardio()) {
                    cardioPayloads++
                    metrics.cardio.pulseRateList.forEach { sample ->
                        pulseSamplesSeen++
                        if (pulseSamplesSeen <= 8) {
                            DeviceLog.add(
                                "pulse ${sample.value} stable=${sample.stable} " +
                                    "conf=${sample.confidence}",
                            )
                        }
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
                if (payloads <= 3 || payloads % 100 == 0) {
                    DeviceLog.add(
                        "payload #$payloads cardio=${metrics.hasCardio()} " +
                            "breathing=${metrics.hasBreathing()} face=${metrics.hasFace()}",
                    )
                }
                // Deliberately no UI push here. These arrive at frame rate, and
                // recomposing the whole scan screen 30 times a second was part
                // of what starved the camera. The 250ms tick publishes instead.
            }
        }

        try {
            // On the caller's dispatcher, which is the main thread: CameraX
            // binds its use cases there.
            sdk.start()
            startedAt = SystemClock.elapsedRealtime()
            DeviceLog.add("start() returned")
            Log.i(TAG, "start() returned; requested = " + sdk.config.requestedMetrics)
        } catch (c: CancellationException) {
            observers.forEach { it.cancel() }
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "start() failed", t)
            DeviceLog.add("start() FAILED ${t.message}")
            observers.forEach { it.cancel() }
            _live.value = LiveReading()
            return@coroutineScope ScanSummary.failed(readableError(t))
        }

        try {
            withContext(work) {
                while (isActive) {
                    delay(TICK_MILLIS)
                    val now = elapsed()
                    // A dropped-frame error is usually a passing blip. Leaving
                    // it on screen for the rest of the scan hides the framing
                    // guidance underneath it, so it fades once nothing has gone
                    // wrong for a few seconds. A fatal error breaks the loop.
                    if (_live.value.errorText != null &&
                        SystemClock.elapsedRealtime() - lastErrorAt > ERROR_VISIBLE_MILLIS
                    ) {
                        _live.value = _live.value.copy(errorText = null)
                    }
                    pushCounts(now)
                    if (asyncFailure != null) break
                    if (processingFailed) break
                    if (window.shouldStop(now)) break
                }
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
            DeviceLog.add(
                "finished: payloads=$payloads cardio=$cardioPayloads " +
                    "breathing=$breathingPayloads face=$facePayloads " +
                    "acceptedPulse=${window.acceptedPulseCount} " +
                    "acceptedFace=${window.acceptedExpressionCount}",
            )
            Log.i(TAG, "scan finished: payloads=$payloads")
            _live.value = LiveReading()
        }

        // A scan that collected nothing is reported as a failure with a reason,
        // not as a merely unclear reading. "Reading was unclear" is true but
        // useless when the real answer is "your chest was never in the picture".
        val failure = asyncFailure ?: when {
            payloads > 0 -> null
            !sawValidationOk ->
                "The camera never got a clear enough view to measure. Keep your " +
                    "whole face AND the top of your chest in the picture, about " +
                    "an arm's length away, in bright even light."
            processingFailed ->
                "The camera skipped frames and the measurement stopped. Brighter " +
                    "light and closing other apps both help."
            else ->
                "The SDK sent no readings during this check. Try again in " +
                    "brighter light, holding still."
        }
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
            "TIMESTAMP_GAP", "NON_MONOTONIC_TIMESTAMP" ->
                "The camera skipped frames, so the reading restarted. " +
                    "Brighter light and closing other apps both help."
            "SUSTAINED_LOW_FRAME_RATE" ->
                "The camera is delivering frames too slowly to read a pulse. " +
                    "Move into brighter, even light, and stop screen recording " +
                    "or anything else using the camera."
            else -> detail ?: "The camera measurement stopped unexpectedly ($code)."
        }
    }

    private companion object {
        const val TAG = "MeasurementEngine"
        const val TICK_MILLIS = 250L
        const val ERROR_VISIBLE_MILLIS = 6_000L
    }
}
