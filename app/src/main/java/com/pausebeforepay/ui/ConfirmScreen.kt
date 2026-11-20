package com.pausebeforepay.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pausebeforepay.signal.Basis
import com.pausebeforepay.signal.Decision
import com.pausebeforepay.signal.Verdict
import kotlin.math.roundToInt

@Composable
fun ConfirmScreen(
    amount: String,
    recipient: String,
    decision: Decision?,
    sentAnyway: Boolean,
    cameraDeclined: Boolean,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Screen {
        ScreenTitle("Confirm transfer")

        Card {
            Text("Amount", style = MaterialTheme.typography.labelMedium, color = Pbp.Muted)
            Spacer(Modifier.height(4.dp))
            Text(
                "NGN " + formatAmount(amount),
                style = MaterialTheme.typography.displaySmall,
                color = Pbp.OnSurface,
            )
            Spacer(Modifier.height(16.dp))
            Text("To", style = MaterialTheme.typography.labelMedium, color = Pbp.Muted)
            Spacer(Modifier.height(4.dp))
            Text(
                recipient.ifBlank { "Unnamed recipient" },
                style = MaterialTheme.typography.headlineSmall,
                color = Pbp.OnSurface,
            )
        }

        Spacer(Modifier.height(16.dp))

        val accent = when {
            cameraDeclined -> Pbp.Muted
            decision == null -> Pbp.Muted
            decision.verdict == Verdict.CALM -> Pbp.Accent
            decision.verdict == Verdict.ELEVATED -> Pbp.Warn
            else -> Pbp.Muted
        }

        Card(accent = accent) {
            Text(
                when {
                    cameraDeclined -> "No camera check"
                    decision == null -> "No reading"
                    sentAnyway && decision.verdict == Verdict.ELEVATED -> "Sending anyway"
                    decision.verdict == Verdict.CALM -> "Camera check passed"
                    decision.verdict == Verdict.ELEVATED -> "Still elevated"
                    else -> "Reading was unclear"
                },
                style = MaterialTheme.typography.titleLarge,
                color = accent,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    cameraDeclined ->
                        "You skipped the camera check, so nothing was measured. " +
                            "The transfer is yours to confirm."
                    decision == null -> "No measurement was taken."
                    else -> decision.explanation
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Pbp.OnSurface,
            )

            val guidance = decision?.guidance
            if (guidance != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    guidance,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Pbp.Muted,
                )
            }

            if (decision != null && decision.verdict != Verdict.LOW_CONFIDENCE) {
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    MetricTile(
                        label = "Pulse",
                        value = decision.pulseBpm?.roundToInt()?.toString() ?: "--",
                        unit = "bpm",
                        accent = Pbp.Pulse,
                        note = deltaNote(decision.pulseDeltaBpm, "bpm"),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    MetricTile(
                        label = "Breathing",
                        value = decision.breathingBrpm?.roundToInt()?.toString() ?: "--",
                        unit = "breaths/min",
                        accent = Pbp.Breath,
                        note = deltaNote(decision.breathingDeltaBrpm, "breaths/min"),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (decision?.basis == Basis.ABSOLUTE_FALLBACK) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Compared against general thresholds, because no baseline is saved.",
                    style = MaterialTheme.typography.labelMedium,
                    color = Pbp.Muted,
                )
            }
        }

        Spacer(Modifier.height(26.dp))

        PrimaryButton("Confirm send", onClick = onConfirm, enabled = confirmEnabled)
        if (!confirmEnabled) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Confirm is held while the reading is elevated. Go back to the pause " +
                    "screen to breathe, check again, or send anyway.",
                style = MaterialTheme.typography.bodyMedium,
                color = Pbp.Muted,
            )
        }
        Spacer(Modifier.height(10.dp))
        QuietButton("Cancel", onClick = onCancel)
        Spacer(Modifier.height(16.dp))
    }
}

private fun deltaNote(delta: Double?, unit: String): String? {
    if (delta == null) return null
    val rounded = delta.roundToInt()
    return when {
        rounded > 0 -> "+$rounded $unit vs your baseline"
        rounded < 0 -> "$rounded $unit vs your baseline"
        else -> "same as your baseline"
    }
}

/** Groups digits for readability: 25000 -> 25,000. */
internal fun formatAmount(raw: String): String {
    val digits = raw.filter { it.isDigit() }.ifEmpty { return "0" }
    return digits.reversed().chunked(3).joinToString(",").reversed()
}
