package com.pausebeforepay.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pausebeforepay.signal.Baseline
import com.pausebeforepay.signal.ScanMode
import com.pausebeforepay.signal.Thresholds
import kotlin.math.roundToInt

@Composable
fun SuccessScreen(amount: String, recipient: String, onDone: () -> Unit) {
    Screen {
        Spacer(Modifier.height(40.dp))
        ScreenTitle("Transfer submitted")

        Card(accent = Pbp.Accent) {
            Text(
                "NGN " + formatAmount(amount),
                style = MaterialTheme.typography.displaySmall,
                color = Pbp.Accent,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "to " + recipient.ifBlank { "Unnamed recipient" },
                style = MaterialTheme.typography.headlineSmall,
                color = Pbp.OnSurface,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "This is a mock transfer. No bank, card or payment service was " +
                    "contacted and no money moved.",
                style = MaterialTheme.typography.bodyMedium,
                color = Pbp.Muted,
            )
        }

        Spacer(Modifier.height(28.dp))
        PrimaryButton("Done", onClick = onDone)
    }
}

/** Explains the calibration scan, and is also where a baseline gets deleted. */
@Composable
fun BaselineIntroScreen(
    baseline: Baseline?,
    onStart: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    Screen {
        ScreenTitle(
            "Your baseline",
            "One ${Thresholds.BASELINE_SCAN_SECONDS}-second scan, so the check " +
                "compares you against yourself.",
        )

        Card {
            Text("Why bother", style = MaterialTheme.typography.titleLarge, color = Pbp.OnSurface)
            Spacer(Modifier.height(10.dp))
            BulletLine(
                "Resting pulse differs a lot between people. 85 bpm is calm for one " +
                    "person and rushed for another.",
            )
            BulletLine(
                "With a baseline saved, the payment-time check only needs about " +
                    "${Thresholds.SCAN_TARGET_SECONDS} seconds instead of a minute.",
            )
            BulletLine(
                "Without one, the app falls back to general thresholds " +
                    "(${Thresholds.ABSOLUTE_PULSE_BPM.roundToInt()} bpm or " +
                    "${Thresholds.ABSOLUTE_BREATHING_BRPM.roundToInt()} breaths/min), " +
                    "which are much blunter.",
            )
        }

        Spacer(Modifier.height(14.dp))

        Card {
            Text("How to sit", style = MaterialTheme.typography.titleLarge, color = Pbp.OnSurface)
            Spacer(Modifier.height(10.dp))
            BulletLine("Sit down, somewhere well lit, and get comfortable first.")
            BulletLine("Hold the phone so your face and upper chest are both in the picture.")
            BulletLine("Stay still and breathe normally for the whole minute.")
        }

        if (baseline != null) {
            Spacer(Modifier.height(14.dp))
            Card(accent = Pbp.Accent) {
                Text(
                    "Saved baseline",
                    style = MaterialTheme.typography.titleLarge,
                    color = Pbp.OnSurface,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    describe(baseline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Pbp.Muted,
                )
                Spacer(Modifier.height(14.dp))
                SecondaryButton("Delete my baseline", onClick = onDelete)
            }
        }

        Spacer(Modifier.height(26.dp))
        PrimaryButton(
            if (baseline == null) "Start the setup scan" else "Record it again",
            onClick = onStart,
        )
        Spacer(Modifier.height(10.dp))
        QuietButton("Back", onClick = onBack)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun BaselineSavedScreen(baseline: Baseline, onDone: () -> Unit) {
    Screen {
        Spacer(Modifier.height(20.dp))
        ScreenTitle("Baseline saved")

        Card(accent = Pbp.Accent) {
            Text(
                "${baseline.restingPulseBpm.roundToInt()} bpm",
                style = MaterialTheme.typography.displaySmall,
                color = Pbp.Pulse,
            )
            Text(
                "resting pulse (median of ${baseline.pulseSamples} steady readings)",
                style = MaterialTheme.typography.labelMedium,
                color = Pbp.Muted,
            )
            Spacer(Modifier.height(16.dp))
            val breathing = baseline.restingBreathingBrpm
            if (breathing != null) {
                Text(
                    "${breathing.roundToInt()} breaths/min",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Pbp.Breath,
                )
                Text(
                    "resting breathing (median of ${baseline.breathingSamples} steady readings)",
                    style = MaterialTheme.typography.labelMedium,
                    color = Pbp.Muted,
                )
            } else {
                Text(
                    "Breathing rate did not settle enough to store this time, so the " +
                        "check will use pulse only. You can record the baseline again " +
                        "with your upper chest more clearly in frame.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Pbp.Muted,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Card {
            Text(
                "Stored encrypted on this phone. Nothing was uploaded. You can delete " +
                    "it at any time from Baseline settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = Pbp.Muted,
            )
        }

        Spacer(Modifier.height(26.dp))
        PrimaryButton("Done", onClick = onDone)
    }
}

/** Shown when the SDK failed, permission was refused, or signal was too poor. */
@Composable
fun ScanProblemScreen(
    mode: ScanMode,
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onContinueWithoutCheck: () -> Unit,
    onBack: () -> Unit,
) {
    Screen {
        Spacer(Modifier.height(20.dp))
        ScreenTitle(
            when (mode) {
                ScanMode.BASELINE -> "Setup scan did not finish"
                ScanMode.PAYMENT -> "The check could not run"
            },
        )

        Card(accent = Pbp.Warn) {
            Text(message, style = MaterialTheme.typography.bodyLarge, color = Pbp.OnSurface)
        }

        Spacer(Modifier.height(26.dp))
        if (canRetry) {
            PrimaryButton("Try again", onClick = onRetry)
            Spacer(Modifier.height(10.dp))
        }
        if (mode == ScanMode.PAYMENT) {
            SecondaryButton("Continue without the check", onClick = onContinueWithoutCheck)
            Spacer(Modifier.height(10.dp))
        }
        QuietButton("Back", onClick = onBack)
        Spacer(Modifier.height(16.dp))
    }
}

private fun describe(baseline: Baseline): String {
    val breathing = baseline.restingBreathingBrpm
    return buildString {
        append("Resting pulse ")
        append(baseline.restingPulseBpm.roundToInt())
        append(" bpm, spread ")
        append(baseline.pulseMad.roundToInt())
        append(" bpm, from ")
        append(baseline.pulseSamples)
        append(" steady readings.")
        if (breathing != null) {
            append(" Resting breathing ")
            append(breathing.roundToInt())
            append(" breaths/min from ")
            append(baseline.breathingSamples)
            append(" readings.")
        } else {
            append(" No breathing baseline stored.")
        }
    }
}
