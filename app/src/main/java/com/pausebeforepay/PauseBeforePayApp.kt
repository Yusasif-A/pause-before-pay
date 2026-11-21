package com.pausebeforepay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pausebeforepay.signal.ScanMode
import com.pausebeforepay.ui.BaselineIntroScreen
import com.pausebeforepay.ui.BaselineSavedScreen
import com.pausebeforepay.ui.ConfirmScreen
import com.pausebeforepay.ui.ConsentScreen
import com.pausebeforepay.ui.PauseBeforePayTheme
import com.pausebeforepay.ui.PauseScreen
import com.pausebeforepay.ui.ScanProblemScreen
import com.pausebeforepay.ui.ScanScreen
import com.pausebeforepay.ui.SendFormScreen
import com.pausebeforepay.ui.SuccessScreen

/**
 * The whole flow, driven by one [Step]. No navigation library: the path through
 * this app is short and linear, and a single state value makes it obvious which
 * screens can reach the Confirm button and under what conditions.
 */
@Composable
fun PauseBeforePayApp(vm: PaymentViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()

    PauseBeforePayTheme {
        when (val step = state.step) {
            Step.SendForm -> SendFormScreen(
                amount = state.amount,
                recipient = state.recipient,
                baseline = state.baseline,
                apiKeyConfigured = state.apiKeyConfigured,
                onAmountChange = vm::onAmountChange,
                onRecipientChange = vm::onRecipientChange,
                onContinue = vm::onContinueToConfirm,
                onOpenBaseline = vm::onOpenBaselineSetup,
            )

            Step.BaselineIntro -> BaselineIntroScreen(
                baseline = state.baseline,
                onStart = { vm.onConsentRequested(ScanMode.BASELINE) },
                onDelete = vm::deleteBaseline,
                onBack = vm::backToForm,
            )

            is Step.Consent -> ConsentScreen(
                mode = step.mode,
                plainLanguageAvailable = vm.plainLanguageAvailable,
                plainLanguageOptIn = state.plainLanguageOptIn,
                plainLanguageHost = vm.plainLanguageHost,
                sdkVersion = vm.sdkVersion,
                onOptInChange = vm::onPlainLanguageOptInChange,
                onAccept = { vm.onConsentAccepted(step.mode) },
                onDecline = { vm.onConsentDeclined(step.mode) },
            )

            is Step.Scanning -> ScanScreen(
                mode = step.mode,
                live = live,
                onPreviewSurfaceReady = vm::onPreviewSurfaceReady,
                onStart = { vm.startScan(step.mode) },
                onPermissionDenied = { vm.onCameraPermissionDenied(step.mode) },
                onCancel = vm::cancelScan,
            )

            is Step.ScanProblem -> ScanProblemScreen(
                mode = step.mode,
                message = step.message,
                canRetry = step.canRetry,
                onRetry = { vm.retry(step.mode) },
                onContinueWithoutCheck = vm::continueWithoutCheck,
                onBack = vm::backToForm,
            )

            is Step.BaselineSaved -> BaselineSavedScreen(
                baseline = step.baseline,
                onDone = vm::backToForm,
            )

            Step.Pause -> {
                val decision = state.decision
                if (decision == null) {
                    // Unreachable in practice; falling back beats a blank screen.
                    LaunchedEffect(Unit) { vm.backToForm() }
                } else {
                    PauseScreen(
                        amount = state.amount,
                        recipient = state.recipient,
                        decision = decision,
                        plainLanguage = state.plainLanguage,
                        onRescan = vm::onRescan,
                        onSendAnyway = vm::onSendAnyway,
                        onCancel = vm::onCancel,
                    )
                }
            }

            Step.Confirm -> ConfirmScreen(
                amount = state.amount,
                recipient = state.recipient,
                decision = state.decision,
                sentAnyway = state.sentAnyway,
                cameraDeclined = state.cameraDeclined,
                confirmEnabled = state.confirmEnabled,
                onConfirm = vm::onConfirmSend,
                onCancel = vm::onCancel,
            )

            Step.Success -> SuccessScreen(
                amount = state.amount,
                recipient = state.recipient,
                onDone = vm::onDone,
            )
        }
    }
}
