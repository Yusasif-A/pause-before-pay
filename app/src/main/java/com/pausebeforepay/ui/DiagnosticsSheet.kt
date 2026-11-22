package com.pausebeforepay.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pausebeforepay.diag.DeviceLog

/**
 * Shows this app's own log, on the phone.
 *
 * It exists because the only test hardware is a phone with no cable to a
 * computer, and a camera problem that only happens on the device cannot be
 * diagnosed from a screenshot of a progress bar.
 */
@Composable
fun DiagnosticsSheet(onClose: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(DeviceLog.snapshot()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Pbp.Background)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        ScreenTitle(
            "Diagnostics",
            "The app's own log. Run a camera check first, then come back here.",
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(
                "Share",
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "PauseBeforePay diagnostics")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(send, "Share diagnostics"))
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            SecondaryButton(
                "Refresh",
                onClick = { text = DeviceLog.snapshot() },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                ),
                color = Pbp.OnSurface,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }

        Spacer(Modifier.height(12.dp))
        QuietButton("Close", onClick = onClose)
    }
}
