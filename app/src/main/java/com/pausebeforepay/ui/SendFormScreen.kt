package com.pausebeforepay.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pausebeforepay.signal.Baseline
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
fun SendFormScreen(
    amount: String,
    recipient: String,
    baseline: Baseline?,
    apiKeyConfigured: Boolean,
    onAmountChange: (String) -> Unit,
    onRecipientChange: (String) -> Unit,
    onContinue: () -> Unit,
    onOpenBaseline: () -> Unit,
) {
    Screen {
        ScreenTitle(
            "Send money",
            "A mock transfer. No bank is connected and no money moves.",
        )

        OutlinedTextField(
            value = amount,
            onValueChange = onAmountChange,
            label = { Text("Amount") },
            prefix = { Text("NGN ") },
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineSmall,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
            ),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = recipient,
            onValueChange = onRecipientChange,
            label = { Text("Recipient") },
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineSmall,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))

        Card(accent = if (baseline == null) Pbp.Warn else Pbp.Accent) {
            Text(
                if (baseline == null) "No baseline saved" else "Baseline saved",
                style = MaterialTheme.typography.titleLarge,
                color = Pbp.OnSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                baselineSummary(baseline),
                style = MaterialTheme.typography.bodyMedium,
                color = Pbp.Muted,
            )
            Spacer(Modifier.height(14.dp))
            SecondaryButton(
                if (baseline == null) "Set up your baseline" else "Baseline settings",
                onClick = onOpenBaseline,
            )
        }

        if (!apiKeyConfigured) {
            Spacer(Modifier.height(16.dp))
            Card(accent = Pbp.Alert) {
                Text(
                    "No Presage API key in this build",
                    style = MaterialTheme.typography.titleLarge,
                    color = Pbp.Alert,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add PRESAGE_API_KEY to local.properties and rebuild. The camera " +
                        "check cannot run without it, but the rest of the flow still works.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Pbp.Muted,
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        PrimaryButton("Continue to confirm", onClick = onContinue)
        Spacer(Modifier.height(16.dp))
    }
}

private fun baselineSummary(baseline: Baseline?): String {
    if (baseline == null) {
        return "Without a baseline, the check compares your reading against general " +
            "thresholds instead of your own resting pulse. One 60-second scan fixes that."
    }
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM)
        .format(Date(baseline.recordedAtEpochMillis))
    val breathing = baseline.restingBreathingBrpm
    val breathingPart = if (breathing != null) {
        ", resting breathing ${breathing.roundToInt()} breaths/min"
    } else {
        ", breathing not captured"
    }
    return "Resting pulse ${baseline.restingPulseBpm.roundToInt()} bpm" +
        breathingPart + ". Recorded " + date + "."
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Pbp.OnSurface,
    unfocusedTextColor = Pbp.OnSurface,
    focusedBorderColor = Pbp.Accent,
    unfocusedBorderColor = Pbp.Muted,
    focusedLabelColor = Pbp.Accent,
    unfocusedLabelColor = Pbp.Muted,
    cursorColor = Pbp.Accent,
    focusedPrefixColor = Pbp.Muted,
    unfocusedPrefixColor = Pbp.Muted,
)
