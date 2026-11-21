package com.pausebeforepay.llm

import android.util.Log
import com.pausebeforepay.BuildConfig
import com.pausebeforepay.signal.Decision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Optional cosmetic rewording of the pause message.
 *
 * Three rules this file must never break:
 *  1. It cannot change a verdict. It receives a finished [Decision] and returns
 *     a string. [com.pausebeforepay.signal.DecisionEngine] has already decided.
 *  2. It is off unless the user explicitly opts in, because it is the one thing
 *     in the app that would send a measurement off the device.
 *  3. It must fail silently. No endpoint, no key, a timeout, a bad response --
 *     all return null, and the caller shows the deterministic sentence that the
 *     decision engine already produced.
 *
 * The endpoint is assumed to be OpenAI-compatible (base URL ending in /v1,
 * POST /chat/completions), which is what the configured Gemma host looks like.
 */
object PlainLanguage {

    val available: Boolean
        get() = BuildConfig.GEMMA_BASE_URL.isNotBlank() && BuildConfig.GEMMA_MODEL.isNotBlank()

    /** Host shown on the consent screen, so the user knows where it would go. */
    val endpointHost: String?
        get() = runCatching { URL(BuildConfig.GEMMA_BASE_URL).host }.getOrNull()

    /**
     * @return one short sentence, or null if anything at all went wrong.
     */
    suspend fun reword(decision: Decision): String? {
        if (!available) return null
        return withTimeoutOrNull(TIMEOUT_MILLIS) {
            withContext(Dispatchers.IO) { request(decision) }
        }
    }

    private fun request(decision: Decision): String? {
        try {
            val url = URL(BuildConfig.GEMMA_BASE_URL.trimEnd('/') + "/chat/completions")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MILLIS.toInt()
                readTimeout = TIMEOUT_MILLIS.toInt()
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (BuildConfig.GEMMA_API_KEY.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer " + BuildConfig.GEMMA_API_KEY)
                }
            }

            val body = JSONObject().apply {
                put("model", BuildConfig.GEMMA_MODEL)
                put("temperature", 0.2)
                put("max_tokens", 60)
                put(
                    "messages",
                    JSONArray().apply {
                        put(
                            JSONObject()
                                .put("role", "system")
                                .put("content", SYSTEM_PROMPT),
                        )
                        put(
                            JSONObject()
                                .put("role", "user")
                                .put("content", userPrompt(decision)),
                        )
                    },
                )
            }

            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "Phrasing endpoint returned ${connection.responseCode}")
                connection.disconnect()
                return null
            }

            val text = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            connection.disconnect()

            val content = JSONObject(text)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null

            return sanitize(content)
        } catch (t: Throwable) {
            Log.w(TAG, "Phrasing endpoint unreachable; using the built-in sentence", t)
            return null
        }
    }

    /**
     * Only the two numbers are sent. No name, no amount, no recipient, no
     * device identifier, no history.
     */
    private fun userPrompt(decision: Decision): String {
        val pulse = decision.pulseBpm?.roundToInt()
        val delta = decision.pulseDeltaBpm?.roundToInt()
        val breathing = decision.breathingBrpm?.roundToInt()
        return buildString {
            append("Verdict: ").append(decision.verdict.name).append('.')
            if (pulse != null) append(" Pulse: ").append(pulse).append(" bpm.")
            if (delta != null) append(" Difference from this person's resting pulse: ")
                .append(delta).append(" bpm.")
            if (breathing != null) append(" Breathing: ").append(breathing)
                .append(" breaths per minute.")
            when (decision.faceTense) {
                true -> append(" Facial expression: looked tense for most of the check.")
                false -> append(" Facial expression: looked settled.")
                null -> Unit
            }
        }
    }

    /** Keeps the model to one sentence, and never lets it sound clinical. */
    private fun sanitize(raw: String): String? {
        val oneLine = raw.replace(Regex("\\s+"), " ").trim().removeSurrounding("\"")
        if (oneLine.length > MAX_CHARS) return null
        val lowered = oneLine.lowercase()
        if (BANNED.any { lowered.contains(it) }) return null
        return oneLine
    }

    private const val TAG = "PlainLanguage"
    private const val TIMEOUT_MILLIS = 4_000L
    private const val MAX_CHARS = 180

    private const val SYSTEM_PROMPT =
        "You turn camera readings into one short, calm sentence for a payment app, " +
            "spoken to the person who was just measured. " +
            "You may say they seem rushed, hurried or tense, because that is what the " +
            "readings describe. " +
            "Describe only what was measured, in everyday language, and hedge it: " +
            "\"seems\", not \"is\". " +
            "Do not diagnose anything, do not mention illness or any medical condition, " +
            "do not mention fraud, scams, theft or anyone pressuring them, " +
            "do not give advice and do not tell them what to do about their payment. " +
            "Reply with one sentence and nothing else."

    /**
     * A refusal list, not a safety net. The app's copy rules forbid clinical and
     * fraud language; anything that slips through here is dropped and the
     * deterministic sentence is shown instead.
     */
    private val BANNED = listOf(
        "diagnos", "disease", "condition", "symptom", "medical", "doctor",
        "scam", "fraud", "coerc", "forced", "stolen", "anxiety", "panic",
        "hypertension", "arrhythm", "treatment",
    )
}
