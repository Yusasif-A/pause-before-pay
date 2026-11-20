package com.pausebeforepay.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pausebeforepay.signal.Decision
import com.pausebeforepay.signal.Thresholds
import kotlinx.coroutines.delay

/**
 * The pause. Not a block.
 *
 * Three ways out of this screen and all of them work: breathe and re-scan, send
 * anyway, or cancel. "Send anyway" is visible without scrolling and is never
 * disabled -- an app that can hold up someone's money on a camera reading has no
 * business being the final word.
 */
@Composable
fun PauseScreen(
    decision: Decision,
    plainLanguage: String?,
    onRescan: () -> Unit,
    onSendAnyway: () -> Unit,
    onCancel: () -> Unit,
) {
    Screen {
        ScreenTitle(
            "You seem rushed",
            "Take a short pause before sending?",
        )

        Card(accent = Pbp.Warn) {
            Text(
                plainLanguage ?: decision.explanation,
                style = MaterialTheme.typography.bodyLarge,
                color = Pbp.OnSurface,
            )
            if (plainLanguage != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Wording from a language model. The decision itself was made on " +
                        "this phone by fixed arithmetic.",
                    style = MaterialTheme.typography.labelMedium,
                    color = Pbp.Muted,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // The lock, shown rather than implied: Confirm is visibly present and
        // visibly disabled while the reading is elevated. It unlocks on the
        // Confirm screen after a calm re-scan, or immediately via Send anyway.
        PrimaryButton("Confirm send", onClick = {}, enabled = false)
        Spacer(Modifier.height(8.dp))
        Text(
            "Held while the reading is elevated. Breathe and check again to " +
                "unlock it, or send anyway.",
            style = MaterialTheme.typography.bodyMedium,
            color = Pbp.Muted,
        )

        Spacer(Modifier.height(16.dp))

        SecondaryButton("Send anyway", onClick = onSendAnyway)

        Spacer(Modifier.height(24.dp))

        BreathPacer()

        Spacer(Modifier.height(20.dp))
        SecondaryButton("Check again", onClick = onRescan)
        Spacer(Modifier.height(10.dp))
        QuietButton("Cancel this transfer", onClick = onCancel)
        Spacer(Modifier.height(16.dp))
    }
}

private enum class Phase(val label: String) {
    IDLE("Tap to start"),
    INHALE("Breathe in"),
    EXHALE("Breathe out"),
    DONE("Done"),
}

/**
 * Three breaths, four seconds in and six out, with a circle that grows and
 * shrinks at the same pace. Roughly thirty seconds in total.
 */
@Composable
private fun BreathPacer() {
    var phase by remember { mutableStateOf(Phase.IDLE) }
    var breath by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }

    val target = when (phase) {
        Phase.INHALE -> 1f
        Phase.EXHALE -> 0.35f
        else -> 0.55f
    }
    val durationMillis = when (phase) {
        Phase.INHALE -> Thresholds.PACER_INHALE_SECONDS * 1000
        Phase.EXHALE -> Thresholds.PACER_EXHALE_SECONDS * 1000
        else -> 600
    }
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = durationMillis),
        label = "breath",
    )

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        for (index in 1..Thresholds.PACER_BREATHS) {
            breath = index
            phase = Phase.INHALE
            delay(Thresholds.PACER_INHALE_SECONDS * 1000L)
            phase = Phase.EXHALE
            delay(Thresholds.PACER_EXHALE_SECONDS * 1000L)
        }
        phase = Phase.DONE
        running = false
    }

    Card {
        Text(
            "Guided breaths",
            style = MaterialTheme.typography.titleLarge,
            color = Pbp.OnSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${Thresholds.PACER_BREATHS} breaths · " +
                "${Thresholds.PACER_INHALE_SECONDS}s in, " +
                "${Thresholds.PACER_EXHALE_SECONDS}s out",
            style = MaterialTheme.typography.labelMedium,
            color = Pbp.Muted,
        )
        Spacer(Modifier.height(16.dp))

        Box(
            modifier = Modifier.fillMaxWidth().height(200.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(200.dp)) {
                val maxRadius = size.minDimension / 2f
                drawCircle(
                    color = Pbp.SurfaceHigh,
                    radius = maxRadius,
                )
                drawCircle(
                    color = Pbp.Accent.copy(alpha = 0.35f),
                    radius = maxRadius * scale,
                )
            }
            Text(
                when (phase) {
                    Phase.IDLE -> Phase.IDLE.label
                    Phase.DONE -> "Done"
                    else -> "${phase.label}\n$breath of ${Thresholds.PACER_BREATHS}"
                },
                style = MaterialTheme.typography.titleLarge,
                color = Pbp.OnSurface,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(12.dp))
        SecondaryButton(
            when (phase) {
                Phase.IDLE -> "Start the breaths"
                Phase.DONE -> "Again"
                else -> "Breathing..."
            },
            enabled = !running,
            onClick = {
                breath = 0
                running = true
            },
        )
        if (phase == Phase.DONE) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Ready? Check again below, or send anyway.",
                style = MaterialTheme.typography.bodyMedium,
                color = Pbp.Muted,
            )
        }
    }
}
