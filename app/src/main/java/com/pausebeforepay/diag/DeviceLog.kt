package com.pausebeforepay.diag

import android.os.Build
import android.os.SystemClock

/**
 * A diagnostic log the app keeps for itself, in memory.
 *
 * The first version of this shelled out to `logcat`. That returned nothing on
 * the only phone available, which is allowed: an app may read its own log, but
 * nothing obliges a device to let it, and some builds decline. A log that only
 * works on some phones is no use when there is exactly one phone.
 *
 * So the app writes its own. It cannot capture the SDK's internal logging this
 * way, only what this code observes, which turns out to be the part that
 * matters: which metrics arrive, how often, and what the SDK says when it
 * refuses to produce one.
 *
 * Nothing here leaves the device on its own.
 */
object DeviceLog {

    private const val MAX_LINES = 600

    private val lines = ArrayDeque<String>()
    private var epoch = SystemClock.elapsedRealtime()

    /** Starts a fresh log, so one scan's lines are not buried under the last. */
    @Synchronized
    fun beginSection(title: String) {
        if (lines.size > MAX_LINES / 2) lines.clear()
        epoch = SystemClock.elapsedRealtime()
        add("--- $title ---")
    }

    @Synchronized
    fun add(line: String) {
        val at = (SystemClock.elapsedRealtime() - epoch) / 1000.0
        lines.addLast(String.format("%6.2fs  %s", at, line))
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): String = buildString {
        appendLine("PauseBeforePay diagnostics")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android: ${Build.VERSION.RELEASE} (api ${Build.VERSION.SDK_INT})")
        appendLine()
        if (lines.isEmpty()) {
            appendLine("Nothing recorded yet. Run a camera check, then come back here")
            appendLine("without closing the app in between.")
        } else {
            lines.forEach { appendLine(it) }
        }
    }

    @Synchronized
    fun clear() {
        lines.clear()
    }
}
