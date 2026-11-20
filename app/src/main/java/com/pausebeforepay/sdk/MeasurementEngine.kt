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
import com.presagetech.smartspectra.SmartSpectraConfig
import com.presagetech.smartspectra.SmartSpectraSdk
import com.presagetech.smartspectra.ValidationCode
import kotlinx.coroutines.CancellationException
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
    val validation: ValidationCode? = null,
    /** The SDK's own human-readable framing hint, when it supplies one. */
    val validationHint: String? = null,
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
 * start, collect, stop. Cancelling the calling coroutine stops the camera.
 */
class MeasurementEngine {

    private val sdk by lazy { SmartSpectraSdk.shared }

    private val _live = MutableStateFlow(LiveReading())
    val live: StateFlow<LiveReading> = _live.asStateFlow()

    /** Set from the scan screen once its CameraX PreviewView exists. */
    fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
        sdk.config.previewSurfaceProvider = provider
    }

    val sdkVersion: String get() = runCatching { sdk.version }.getOrDefault("unknown")

    val apiKeyConfigured: Boolean get() = BuildConfig.PRESAGE_API_KEY.isNotBlank()

    private fun configure() {
        sdk.config.apiKey = BuildConfig.PRESAGE_API_KEY
        // We draw the camera ourselves through a PreviewView, so we do not need
        // the SDK's bitmap frames.
        sdk.config.imageOutputEnabled = false
        // Defaults to true. Off, so the consent screen can promise it is off.
        sdk.config.enableTelemetry = false
        // The default request is breathing only -- without cardioMetrics,
        // cardio.pulseRateList stays empty and pulse never appears.
        sdk.config.requestedMetrics =
            SmartSpectraConfig.breathingMetrics + SmartSpectraConfig.cardioMetrics
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

        try {
            sdk.start()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "start() failed", t)
            _live.value = LiveReading()
            return@coroutineScope ScanSummary.failed(readableError(t))
        }

        val startedAt = SystemClock.elapsedRealtime()
        fun elapsed(): Double = (SystemClock.elapsedRealtime() - startedAt) / 1000.0

        var asyncFailure: String? = null

        val validationJob = launch {
            sdk.validationStatus.asFlow().collect { status ->
                window.onValidation(status?.code)
                _live.value = _live.value.copy(
                    validation = status?.code,
                    validationHint = status?.hint?.takeIf { it.isNotBlank() },
                )
            }
        }

        val errorJob = launch {
            sdk.error.asFlow().collect { error ->
                if (error != null) {
                    Log.e(TAG, "SDK error: ${error.code} ${error.message}")
                    if (!error.retryable) asyncFailure = readableError(error.code.name, error.message)
                }
            }
        }

        val metricsJob = launch {
            sdk.metricsFlow.collect { metrics ->
                val now = elapsed()
                if (metrics.hasCardio()) {
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
                    metrics.breathing.rateList.forEach { sample ->
                        window.onBreathingSample(
                            value = sample.value.toDouble(),
                            stable = sample.stable,
                            timestampMicros = sample.timestamp,
                            elapsedSeconds = now,
                        )
                    }
                }
                publish(window, mode, now)
            }
        }

        try {
            while (isActive) {
                delay(TICK_MILLIS)
                val now = elapsed()
                publish(window, mode, now)
                if (asyncFailure != null) break
                if (window.shouldStop(now)) break
            }
        } finally {
            metricsJob.cancel()
            validationJob.cancel()
            errorJob.cancel()
            // stop() is a suspend function, so a cancelled scan would skip it and
            // leave the camera running. NonCancellable makes the shutdown happen.
            withContext(NonCancellable) {
                runCatching { sdk.stop() }
                    .onFailure { Log.w(TAG, "stop() failed", it) }
            }
            sdk.config.previewSurfaceProvider = null
            _live.value = LiveReading()
        }

        val failure = asyncFailure
        if (failure != null) ScanSummary.failed(failure) else window.summarize(elapsed())
    }

    private fun publish(window: SampleWindow, mode: ScanMode, elapsedSeconds: Double) {
        _live.value = _live.value.copy(
            running = true,
            elapsedSeconds = elapsedSeconds.toInt(),
            targetSeconds = mode.targetSeconds,
            pastWarmup = elapsedSeconds >= mode.warmupSeconds,
            pulseBpm = window.lastPulseSeen,
            breathingBrpm = window.lastBreathingSeen,
            acceptedPulseSamples = window.acceptedPulseCount,
            acceptedBreathingSamples = window.acceptedBreathingCount,
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

    private fun readableError(code: String, message: String?): String = when (code) {
        "AUTHENTICATION_FAILED" ->
            "The Presage API key was rejected. Check the key in local.properties."
        "NETWORK_ERROR" ->
            "No internet connection. The SDK needs to check its API key once before measuring."
        "CREDIT_EXHAUSTED" ->
            "This Presage API key has run out of measurement credit."
        "INPUT_UNAVAILABLE" ->
            "The front camera is not available on this device."
        "METRIC_NOT_AUTHORIZED" ->
            "This API key is not authorized for pulse and breathing metrics."
        else -> message?.takeIf { it.isNotBlank() }
            ?: "The camera measurement stopped unexpectedly ($code)."
    }

    private companion object {
        const val TAG = "MeasurementEngine"
        const val TICK_MILLIS = 250L
    }
}
