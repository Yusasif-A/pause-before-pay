package com.pausebeforepay.diag

import android.os.Build
import android.os.Process
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Reads this app's own log output, on the phone, with no computer attached.
 *
 * Android stopped letting an app read other apps' logs years ago, but an app
 * can still read its own, and the SmartSpectra SDK logs into our process. That
 * makes this the only way to see what the SDK is complaining about when the
 * only hardware available is the phone in your hand.
 *
 * Nothing here is sent anywhere. The text is rendered on screen, and goes
 * further only if the person taps Share and chooses where it goes.
 */
object DeviceLog {

    /** Lines kept. Enough for a whole scan, small enough to scroll and to share. */
    private const val MAX_LINES = 400

    /** Tags worth keeping. Everything else in our process is noise. */
    private val INTERESTING = listOf(
        "MeasurementEngine",
        "SmartSpectra",
        "Presage",
        "presage",
        "CameraX",
        "Camera2",
        "libpresage",
        "physiology",
    )

    /**
     * Returns the interesting part of this process's log, newest last, or an
     * explanation if the log could not be read.
     */
    fun capture(): String {
        val raw = runCatching { readOwnLog() }
            .getOrElse { return "Could not read the log on this device: ${it.message}" }

        if (raw.isEmpty()) {
            return "The log came back empty. Run a camera check first, then open " +
                "this screen again without closing the app in between."
        }

        val kept = raw.filter { line -> INTERESTING.any { line.contains(it) } }
        val body = if (kept.isEmpty()) raw.takeLast(MAX_LINES) else kept.takeLast(MAX_LINES)

        return buildString {
            appendLine(header())
            appendLine()
            body.forEach { appendLine(it) }
        }
    }

    private fun header(): String = buildString {
        appendLine("PauseBeforePay diagnostics")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android: ${Build.VERSION.RELEASE} (api ${Build.VERSION.SDK_INT})")
        append("lines below are this app's own log, newest last")
    }

    private fun readOwnLog(): List<String> {
        // --pid limits output to this process. Without it the command returns
        // nothing useful on a modern device anyway, since an app may only read
        // its own log.
        val process = ProcessBuilder(
            "logcat",
            "-d",
            "-v",
            "time",
            "--pid=${Process.myPid()}",
        ).redirectErrorStream(true).start()

        val lines = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.readLines()
        }
        runCatching { process.waitFor() }
        return lines
    }
}
