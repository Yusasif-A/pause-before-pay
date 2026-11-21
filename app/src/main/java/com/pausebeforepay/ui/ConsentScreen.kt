package com.pausebeforepay.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pausebeforepay.signal.ScanMode
import com.pausebeforepay.signal.Thresholds

/**
 * Shown before the camera is ever started, for both the calibration scan and
 * the payment-time scan. Declining is a real option that leads somewhere sane.
 *
 * Every factual claim here is checkable against the SmartSpectra docs:
 * metrics are computed on the device, the backend is contacted only to
 * authenticate the API key and validate the subscription, SDK telemetry
 * defaults to on and is switched off by this app, and SDK metrics are for
 * general wellness and informational purposes only.
 */
@Composable
fun ConsentScreen(
    mode: ScanMode,
    plainLanguageAvailable: Boolean,
    plainLanguageOptIn: Boolean,
    plainLanguageHost: String?,
    sdkVersion: String,
    onOptInChange: (Boolean) -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val seconds = when (mode) {
        ScanMode.BASELINE -> Thresholds.BASELINE_SCAN_SECONDS
        ScanMode.PAYMENT -> Thresholds.SCAN_TARGET_SECONDS
    }

    Screen {
        ScreenTitle(
            "Before the camera starts",
            when (mode) {
                ScanMode.BASELINE ->
                    "This is the one-time setup scan. It takes about $seconds seconds."
                ScanMode.PAYMENT ->
                    "A short camera check, about $seconds seconds, before you confirm."
            },
        )

        Card {
            Text("What is measured", style = MaterialTheme.typography.titleLarge, color = Pbp.OnSurface)
            Spacer(Modifier.height(10.dp))
            BulletLine("Your pulse rate and breathing rate, read from the front camera.")
            BulletLine(
                "Whether your face looks tense or settled, from the camera's " +
                    "expression reading. The app reads how a face looks, never " +
                    "whose face it is — there is no face recognition here, and it " +
                    "cannot identify you.",
            )
            BulletLine("Nothing else. No recording is kept, shown later, or uploaded.")
        }

        Spacer(Modifier.height(14.dp))

        Card {
            Text("Why", style = MaterialTheme.typography.titleLarge, color = Pbp.OnSurface)
            Spacer(Modifier.height(10.dp))
            BulletLine(
                "Only to decide whether to offer you a short pause before you " +
                    "confirm a transfer.",
            )
            BulletLine(
                "You can always send anyway. The app never cancels, redirects or " +
                    "blocks a transfer on its own.",
            )
        }

        Spacer(Modifier.height(14.dp))

        Card {
            Text(
                "Where the processing happens",
                style = MaterialTheme.typography.titleLarge,
                color = Pbp.OnSurface,
            )
            Spacer(Modifier.height(10.dp))
            BulletLine(
                "On this phone, inside the Presage SmartSpectra SDK " +
                    "(version $sdkVersion). Camera frames are not sent anywhere.",
            )
            BulletLine(
                "The SDK does need the internet once per measurement, to check that " +
                    "its API key and subscription are valid. That check carries no " +
                    "frames and no measurements.",
            )
            BulletLine(
                "The SDK can also send its own diagnostic reports, and that is on by " +
                    "default. This app switches it off. Those reports never contained " +
                    "camera frames or vital signs in any case.",
            )
        }

        Spacer(Modifier.height(14.dp))

        Card {
            Text("What is kept", style = MaterialTheme.typography.titleLarge, color = Pbp.OnSurface)
            Spacer(Modifier.height(10.dp))
            when (mode) {
                ScanMode.BASELINE -> {
                    BulletLine(
                        "This scan saves a summary: your median resting pulse and " +
                            "breathing rate, how spread out the readings were, how many " +
                            "readings there were, and the date.",
                    )
                    BulletLine(
                        "That summary is stored encrypted on this phone only, until you " +
                            "delete it. You can delete it at any time from Baseline settings.",
                    )
                }
                ScanMode.PAYMENT -> {
                    BulletLine(
                        "Nothing. A payment-time reading is used for the decision on the " +
                            "next screen and then discarded when you leave the flow.",
                    )
                    BulletLine(
                        "The only thing this app ever stores is your baseline summary, " +
                            "which you can delete from Baseline settings.",
                    )
                }
            }
        }

        if (plainLanguageAvailable) {
            Spacer(Modifier.height(14.dp))
            Card(accent = if (plainLanguageOptIn) Pbp.Warn else null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Plain-language wording",
                        style = MaterialTheme.typography.titleLarge,
                        color = Pbp.OnSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = plainLanguageOptIn,
                        onCheckedChange = onOptInChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Pbp.Accent,
                            checkedTrackColor = Pbp.AccentPressed,
                        ),
                    )
                }
                Spacer(Modifier.height(10.dp))
                BulletLine(
                    "Off by default. When on, the two numbers alone (pulse and " +
                        "breathing) are sent to " + (plainLanguageHost ?: "a configured " +
                        "language model endpoint") + " to word the pause message.",
                )
                BulletLine(
                    "It never changes the decision. The calm-or-elevated call is made by " +
                        "fixed arithmetic on this phone, with or without this switch.",
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Card {
            Text("Not a medical device", style = MaterialTheme.typography.titleLarge, color = Pbp.Warn)
            Spacer(Modifier.height(10.dp))
            Text(
                "SmartSpectra SDK metrics are offered for general wellness and " +
                    "informational purposes only. They have not been cleared by the FDA " +
                    "and may not be used for medical diagnosis or treatment. This app " +
                    "makes no health claim, and it cannot tell why your pulse is what it is.",
                style = MaterialTheme.typography.bodyMedium,
                color = Pbp.Muted,
            )
        }

        Spacer(Modifier.height(26.dp))
        PrimaryButton("I understand, start the camera", onClick = onAccept)
        Spacer(Modifier.height(10.dp))
        SecondaryButton(
            when (mode) {
                ScanMode.BASELINE -> "Not now"
                ScanMode.PAYMENT -> "Decline and skip the check"
            },
            onClick = onDecline,
        )
        Spacer(Modifier.height(16.dp))
    }
}
