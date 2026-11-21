package com.pausebeforepay.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pausebeforepay.sdk.LiveReading
import com.pausebeforepay.signal.DecisionEngine
import com.pausebeforepay.signal.ScanMode
import com.pausebeforepay.signal.Thresholds
import com.presagetech.smartspectra.ValidationCode
import kotlin.math.roundToInt

/**
 * The measurement screen.
 *
 * Two things it must do, both scored by the challenge checklist: show the live
 * camera preview, and show the live pulse and breathing numbers as they arrive
 * rather than hiding them behind a spinner.
 */
@Composable
fun ScanScreen(
    mode: ScanMode,
    live: LiveReading,
    onPreviewSurfaceReady: (Preview.SurfaceProvider?) -> Unit,
    onStart: () -> Unit,
    onPermissionDenied: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var surfaceReady by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (!granted) onPermissionDenied()
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // If the app leaves the foreground mid-scan, drop the measurement rather
    // than keep the camera open behind a permission dialog or another app.
    // ON_STOP, not ON_PAUSE: the permission dialog pauses this activity.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { onCancel() }

    // The SDK needs the preview surface before start(), so the measurement only
    // begins once both the permission and the surface are in place.
    LaunchedEffect(permissionGranted, surfaceReady) {
        if (permissionGranted && surfaceReady) onStart()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Pbp.Background)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            when (mode) {
                ScanMode.BASELINE -> "Setting up your baseline"
                ScanMode.PAYMENT -> "Quick camera check"
            },
            style = MaterialTheme.typography.headlineSmall,
            color = Pbp.OnSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Keep your face and upper chest in the picture. Sit still, in good light.",
            style = MaterialTheme.typography.bodyMedium,
            color = Pbp.Muted,
        )
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Pbp.Surface),
        ) {
            if (permissionGranted) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            contentDescription = "Front camera preview"
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            onPreviewSurfaceReady(surfaceProvider)
                            surfaceReady = true
                        }
                    },
                )
            } else {
                Text(
                    "Waiting for camera permission",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Pbp.Muted,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }

        DisposableEffect(Unit) {
            onDispose { onPreviewSurfaceReady(null) }
        }

        Spacer(Modifier.height(14.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            MetricTile(
                label = "Pulse",
                value = live.pulseBpm?.roundToInt()?.toString() ?: "--",
                unit = "bpm",
                accent = Pbp.Pulse,
                note = sampleNote(live.acceptedPulseSamples),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            MetricTile(
                label = "Breathing",
                value = live.breathingBrpm?.roundToInt()?.toString() ?: "--",
                unit = "breaths/min",
                accent = Pbp.Breath,
                note = sampleNote(live.acceptedBreathingSamples),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(10.dp))

        FaceRow(live)

        Spacer(Modifier.height(14.dp))

        val progress = if (live.targetSeconds > 0) {
            (live.elapsedSeconds.toFloat() / live.targetSeconds).coerceIn(0f, 1f)
        } else {
            0f
        }

        LinearProgressIndicator(
            progress = { progress },
            color = Pbp.Accent,
            trackColor = Pbp.SurfaceHigh,
            modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${live.elapsedSeconds}s of about ${live.targetSeconds}s" +
                if (!live.pastWarmup) "  ·  warming up" else "",
            style = MaterialTheme.typography.labelMedium,
            color = Pbp.Muted,
        )

        Spacer(Modifier.height(14.dp))
        GuidanceCard(live)

        Spacer(Modifier.height(14.dp))
        QuietButton("Cancel", onClick = onCancel)
    }
}

/**
 * The live face reading. Shown as plain words rather than the raw model label,
 * and only once enough confident samples have arrived to mean anything.
 */
@Composable
private fun FaceRow(live: LiveReading) {
    val share = live.tenseShare
    val enough = live.acceptedExpressionSamples >= Thresholds.MIN_EXPRESSION_SAMPLES
    val tense = enough && share != null && share >= Thresholds.TENSE_SHARE
    // "no reading yet" rather than "reading", which looked like a working state
    // and made the face seem to succeed while pulse and breathing failed. It
    // means exactly what "--" means on the metric tiles: nothing has arrived.
    val label = when {
        live.expression == null -> "Face: no reading yet"
        !enough -> "Face: reading (${live.acceptedExpressionSamples}/" +
            "${Thresholds.MIN_EXPRESSION_SAMPLES})"
        tense -> "Face: tense"
        else -> "Face: settled"
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Chip(text = label, accent = if (tense) Pbp.Warn else Pbp.Muted)
        if (enough) {
            Spacer(Modifier.width(8.dp))
            Chip(
                text = "${live.acceptedExpressionSamples} face reads",
                accent = Pbp.Muted,
            )
        }
    }
}

@Composable
private fun GuidanceCard(live: LiveReading) {
    // An SDK error outranks framing guidance. Telling someone to "keep still"
    // while the real problem is an unauthorised metric wastes their time.
    val error = live.errorText
    if (error != null) {
        Card(accent = Pbp.Warn) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Chip(text = "sdk problem", accent = Pbp.Warn)
            }
            Spacer(Modifier.height(10.dp))
            Text(error, style = MaterialTheme.typography.bodyMedium, color = Pbp.OnSurface)
        }
        return
    }

    val code = live.validation
    val good = code == null || code == ValidationCode.OK || code == ValidationCode.CAMERA_TUNING
    Card(accent = if (good) Pbp.Accent else Pbp.Warn) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Chip(
                text = code?.name?.replace('_', ' ')?.lowercase() ?: "starting",
                accent = if (good) Pbp.Accent else Pbp.Warn,
            )
            live.processing?.let {
                Spacer(Modifier.width(8.dp))
                Chip(text = it.name.lowercase(), accent = Pbp.Muted)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                code == null -> "Opening the camera."
                code == ValidationCode.CAMERA_TUNING -> "The camera is adjusting. Hold still."
                code == ValidationCode.OK -> "Signal looks good. Keep still."
                else -> live.validationHint ?: DecisionEngine.guidanceFor(code)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Pbp.OnSurface,
        )
        // Only appears when something is wrong: past warm-up with the validation
        // stream happy and still no pulse value. It says whether the metrics
        // stream is silent or delivering empty payloads, which look the same on
        // screen and have completely different causes.
        if (live.pastWarmup && live.pulseBpm == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "No pulse value yet. Metrics payloads: ${live.payloads} " +
                    "(cardio ${live.cardioPayloads}, breathing ${live.breathingPayloads}, " +
                    "face ${live.facePayloads}).",
                style = MaterialTheme.typography.labelMedium,
                color = Pbp.Muted,
            )
        }
    }
}

private fun sampleNote(count: Int): String =
    if (count == 0) "no steady readings yet" else "$count steady readings"
