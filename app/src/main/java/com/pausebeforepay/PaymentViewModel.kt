package com.pausebeforepay

import android.app.Application
import androidx.camera.core.Preview
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pausebeforepay.data.BaselineStore
import com.pausebeforepay.llm.PlainLanguage
import com.pausebeforepay.sdk.MeasurementEngine
import com.pausebeforepay.signal.Baseline
import com.pausebeforepay.signal.Decision
import com.pausebeforepay.signal.DecisionEngine
import com.pausebeforepay.signal.ScanMode
import com.pausebeforepay.signal.ScanSummary
import com.pausebeforepay.signal.Thresholds
import com.pausebeforepay.signal.Verdict
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One screen of the flow. */
sealed interface Step {
    data object SendForm : Step

    /** Explains the calibration scan before the camera is involved. */
    data object BaselineIntro : Step

    /** Consent, shown before the camera is ever started. */
    data class Consent(val mode: ScanMode) : Step

    data class Scanning(val mode: ScanMode) : Step

    /** The SDK could not measure: auth, camera, network, or too little signal. */
    data class ScanProblem(val mode: ScanMode, val message: String, val canRetry: Boolean) : Step

    data class BaselineSaved(val baseline: Baseline) : Step

    data object Pause : Step

    data object Confirm : Step

    data object Success : Step
}

data class UiState(
    val step: Step = Step.SendForm,
    val amount: String = "25000",
    val recipient: String = "Ada Obi",
    val baseline: Baseline? = null,
    val decision: Decision? = null,
    /** The user chose to send without waiting for a calm reading. */
    val sentAnyway: Boolean = false,
    /** The user declined the camera, so no measurement was taken. */
    val cameraDeclined: Boolean = false,
    /** Optional LLM rewording of [Decision.explanation]. Cosmetic only. */
    val plainLanguage: String? = null,
    val plainLanguageOptIn: Boolean = false,
    val apiKeyConfigured: Boolean = true,
) {
    /**
     * The single source of truth for the Confirm button.
     *
     * Confirm is locked only while the reading is ELEVATED and the user has not
     * chosen to send anyway. A poor reading never locks it, and a declined
     * camera never locks it.
     */
    val confirmEnabled: Boolean
        get() = sentAnyway || cameraDeclined || decision?.blocksConfirm != true
}

class PaymentViewModel(app: Application) : AndroidViewModel(app) {

    private val store = BaselineStore(app)
    private val engine = MeasurementEngine()

    private val _state = MutableStateFlow(
        UiState(
            baseline = store.load(),
            plainLanguageOptIn = store.plainLanguageOptIn,
            apiKeyConfigured = engine.apiKeyConfigured,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    val live = engine.live

    val sdkVersion: String get() = engine.sdkVersion
    val plainLanguageAvailable: Boolean get() = PlainLanguage.available
    val plainLanguageHost: String? get() = PlainLanguage.endpointHost

    private var scanJob: Job? = null

    // ---- Send form ---------------------------------------------------------

    fun onAmountChange(value: String) {
        _state.value = _state.value.copy(amount = value.filter { it.isDigit() }.take(9))
    }

    fun onRecipientChange(value: String) {
        _state.value = _state.value.copy(recipient = value.take(40))
    }

    fun onContinueToConfirm() {
        _state.value = _state.value.copy(
            decision = null,
            plainLanguage = null,
            sentAnyway = false,
            cameraDeclined = false,
            step = Step.Consent(ScanMode.PAYMENT),
        )
    }

    fun onOpenBaselineSetup() {
        _state.value = _state.value.copy(step = Step.BaselineIntro)
    }

    /** Consent is shown before the camera starts, for either kind of scan. */
    fun onConsentRequested(mode: ScanMode) {
        _state.value = _state.value.copy(step = Step.Consent(mode))
    }

    // ---- Consent -----------------------------------------------------------

    fun onConsentAccepted(mode: ScanMode) {
        _state.value = _state.value.copy(step = Step.Scanning(mode))
    }

    /** Declining never traps the user: the transfer stays available without a check. */
    fun onConsentDeclined(mode: ScanMode) {
        _state.value = when (mode) {
            ScanMode.PAYMENT -> _state.value.copy(
                step = Step.Confirm,
                decision = null,
                cameraDeclined = true,
            )
            ScanMode.BASELINE -> _state.value.copy(step = Step.SendForm)
        }
    }

    fun onCameraPermissionDenied(mode: ScanMode) {
        _state.value = _state.value.copy(
            step = Step.ScanProblem(
                mode = mode,
                message = "PauseBeforePay needs camera access to read pulse and " +
                    "breathing. You can still send without the check.",
                canRetry = false,
            ),
        )
    }

    fun onPlainLanguageOptInChange(enabled: Boolean) {
        store.plainLanguageOptIn = enabled
        _state.value = _state.value.copy(plainLanguageOptIn = enabled)
    }

    // ---- Scanning ----------------------------------------------------------

    fun onPreviewSurfaceReady(provider: Preview.SurfaceProvider?) {
        engine.setPreviewSurfaceProvider(provider)
    }

    /** Called by the scan screen once its preview surface exists. */
    fun startScan(mode: ScanMode) {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            val summary = engine.runMeasurement(mode)
            when (mode) {
                ScanMode.BASELINE -> finishBaseline(summary)
                ScanMode.PAYMENT -> finishPaymentScan(summary)
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _state.value = _state.value.copy(step = Step.SendForm)
    }

    private fun finishPaymentScan(summary: ScanSummary) {
        val failure = summary.failure
        if (failure != null) {
            _state.value = _state.value.copy(
                step = Step.ScanProblem(ScanMode.PAYMENT, failure, canRetry = true),
            )
            return
        }

        val decision = DecisionEngine.decide(summary, _state.value.baseline)
        _state.value = _state.value.copy(
            decision = decision,
            plainLanguage = null,
            step = if (decision.verdict == Verdict.ELEVATED) Step.Pause else Step.Confirm,
        )

        if (decision.verdict == Verdict.ELEVATED &&
            _state.value.plainLanguageOptIn &&
            PlainLanguage.available
        ) {
            viewModelScope.launch {
                val sentence = PlainLanguage.reword(decision)
                if (sentence != null) {
                    _state.value = _state.value.copy(plainLanguage = sentence)
                }
            }
        }
    }

    private fun finishBaseline(summary: ScanSummary) {
        val failure = summary.failure
        if (failure != null) {
            _state.value = _state.value.copy(
                step = Step.ScanProblem(ScanMode.BASELINE, failure, canRetry = true),
            )
            return
        }
        if (summary.pulseSamples < Thresholds.BASELINE_MIN_PULSE_SAMPLES ||
            summary.pulseBpm == null
        ) {
            _state.value = _state.value.copy(
                step = Step.ScanProblem(
                    mode = ScanMode.BASELINE,
                    message = "That scan produced ${summary.pulseSamples} good pulse " +
                        "readings and we need at least " +
                        "${Thresholds.BASELINE_MIN_PULSE_SAMPLES}. " +
                        DecisionEngine.guidanceFor(summary.dominantIssue),
                    canRetry = true,
                ),
            )
            return
        }

        // Rebuild the baseline from the summary the engine returned.
        val baseline = Baseline(
            restingPulseBpm = summary.pulseBpm,
            pulseMad = 0.0,
            pulseSamples = summary.pulseSamples,
            restingBreathingBrpm = summary.breathingBrpm
                ?.takeIf { summary.breathingSamples >= Thresholds.BASELINE_MIN_BREATHING_SAMPLES },
            breathingMad = null,
            breathingSamples = summary.breathingSamples,
            recordedAtEpochMillis = System.currentTimeMillis(),
        )
        store.save(baseline)
        _state.value = _state.value.copy(baseline = baseline, step = Step.BaselineSaved(baseline))
    }

    fun deleteBaseline() {
        store.clear()
        _state.value = _state.value.copy(baseline = null)
    }

    // ---- Pause screen ------------------------------------------------------

    fun onRescan() {
        _state.value = _state.value.copy(step = Step.Scanning(ScanMode.PAYMENT))
    }

    /** Always available, always enabled. Non-negotiable. */
    fun onSendAnyway() {
        _state.value = _state.value.copy(sentAnyway = true, step = Step.Confirm)
    }

    fun onCancel() {
        _state.value = _state.value.copy(step = Step.SendForm)
    }

    // ---- Confirm / success -------------------------------------------------

    fun onConfirmSend() {
        _state.value = _state.value.copy(step = Step.Success)
    }

    fun onDone() {
        _state.value = UiState(
            baseline = _state.value.baseline,
            plainLanguageOptIn = _state.value.plainLanguageOptIn,
            apiKeyConfigured = _state.value.apiKeyConfigured,
            amount = _state.value.amount,
            recipient = _state.value.recipient,
        )
    }

    fun retry(mode: ScanMode) {
        _state.value = _state.value.copy(step = Step.Scanning(mode))
    }

    fun backToForm() {
        _state.value = _state.value.copy(step = Step.SendForm)
    }

    /** Lets the user proceed after declining or failing the camera check. */
    fun continueWithoutCheck() {
        _state.value = _state.value.copy(
            step = Step.Confirm,
            decision = null,
            cameraDeclined = true,
        )
    }
}
