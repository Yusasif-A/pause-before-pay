package com.pausebeforepay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Standard page: dark background, safe insets, 20 dp gutter, scrollable. */
@Composable
fun Screen(
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = Modifier
        .fillMaxSize()
        .background(Pbp.Background)
        .safeDrawingPadding()
        .padding(horizontal = 20.dp, vertical = 16.dp)
    Column(
        modifier = if (scrollable) {
            base.verticalScroll(rememberScrollState()).then(modifier)
        } else {
            base.then(modifier)
        },
        verticalArrangement = Arrangement.Top,
    ) {
        content()
    }
}

@Composable
fun ScreenTitle(text: String, subtitle: String? = null) {
    Text(text, style = MaterialTheme.typography.headlineLarge, color = Pbp.OnSurface)
    if (subtitle != null) {
        Spacer(Modifier.height(8.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Pbp.Muted)
    }
    Spacer(Modifier.height(20.dp))
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Pbp.Accent,
            contentColor = Color(0xFF07251A),
            disabledContainerColor = Pbp.SurfaceHigh,
            disabledContentColor = Pbp.Muted,
        ),
        modifier = modifier.fillMaxWidth().height(60.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Pbp.OnSurface),
        modifier = modifier.fillMaxWidth().height(58.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.fillMaxWidth().height(52.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Pbp.Muted)
    }
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Pbp.Surface, RoundedCornerShape(20.dp))
            .then(
                if (accent != null) {
                    Modifier.border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                } else {
                    Modifier
                },
            )
            .padding(18.dp),
    ) {
        content()
    }
}

/** A big live number with its label, used for pulse and breathing. */
@Composable
fun MetricTile(
    label: String,
    value: String,
    unit: String,
    accent: Color,
    note: String? = null,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier, accent = accent) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Pbp.Muted)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.displaySmall, color = accent)
            Spacer(Modifier.size(6.dp))
            Text(
                unit,
                style = MaterialTheme.typography.labelMedium,
                color = Pbp.Muted,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (note != null) {
            Spacer(Modifier.height(4.dp))
            Text(note, style = MaterialTheme.typography.labelMedium, color = Pbp.Muted)
        }
    }
}

/** A labelled chip, used for validation state and scan progress. */
@Composable
fun Chip(text: String, accent: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(accent.copy(alpha = 0.16f), CircleShape)
            .border(1.dp, accent.copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Pbp.OnSurface)
    }
}

@Composable
fun BulletLine(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("•  ", style = MaterialTheme.typography.bodyLarge, color = Pbp.Accent)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = Pbp.OnSurface,
            textAlign = TextAlign.Start,
        )
    }
}
