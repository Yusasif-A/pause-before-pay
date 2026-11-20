# SmartSpectra Android — verified facts and corrections

Checked against the live docs on 2026-11-20
(`smartspectra.presagetech.com`, SDK `com.presagetech:smartspectra:3.5.0`).
This file exists so the next person does not have to re-derive any of it, and so
the places where the build brief turned out to be stale are written down rather
than silently fixed.

## Corrections to the build brief

| Brief said | Actually |
|---|---|
| Validation codes look like `kOk`, `kTooDark` | Kotlin enum `ValidationCode` with `OK`, `TOO_DARK`, `TOO_BRIGHT`, `NO_FACE_FOUND`, `MULTIPLE_FACES_FOUND`, `FACE_NOT_CENTERED`, `FACE_SIZE_OUT_OF_RANGE`, `FACE_TOO_CLOSE`, `FACE_TOO_FAR`, `FACE_TOO_HIGH`, `FACE_TOO_LOW`, `FACE_NOT_FORWARD`, `CHEST_NOT_VISIBLE`, `CAMERA_TUNING`, `FRAME_RATE_TOO_LOW`, `EXCESSIVE_MOTION`. `ValidationStatus` is a data class of `code` **and a human-readable `hint`** — the hint is used directly in the UI. |
| Android telemetry toggle unconfirmed | `SmartSpectraConfig.enableTelemetry` exists and **defaults to `true`**. The app sets it to `false`. Telemetry never includes raw frames or measured vitals; readiness reports may contain free-text diagnostics. |
| Warm-up time before the first value is unstated | It is published: pulse first value ≈12 s (and is a 12-second average), breathing rate ≈10 s but **confidence 0 until ≈30 s**, HRV ≈30 s / confident ≈60 s. |
| Observe metrics via `sdk.metrics` LiveData | `sdk.metricsFlow: SharedFlow<Metrics>` also exists and is **lossless**; LiveData is keep-latest and can drop emissions under main-thread congestion. This app uses `metricsFlow`. |
| Quality gating = collect only while validation is OK | Samples carry their own `stable: Boolean`, documented as "confidence at or above the published accuracy standard" (≥40 for pulse = ±3 bpm, ≥45 for breathing = ±1 br/min). That is the primary gate here; validation codes are applied per metric on top. |

## Deliberate deviations from the brief's §5 numbers

All constants live in
[`Thresholds.kt`](../app/src/main/java/com/pausebeforepay/signal/Thresholds.kt).

| Brief | Built | Why |
|---|---|---|
| Payment scan 20 s target / 35 s cap, discard 8 s | 25 s target / 40 s cap, discard 12 s | Nothing exists before ~12 s, so an 8 s discard is fictional. A 20 s scan would leave ~8 s of pulse samples; 25 s leaves ~13 s. |
| Payment scan requires ≥8 valid pulse samples | ≥6 | Pulse sample cadence is not published. Six keeps the scan from failing honest users; the scan extends to the 40 s cap if it has not reached six. |
| Baseline requires ≥20 valid samples | ≥12 pulse, ≥6 breathing (breathing optional) | A 60 s scan with a 15 s discard has ~45 s of pulse. Breathing is only confident from ~30 s, so demanding it would fail most calibrations; a pulse-only baseline is accepted and the breathing rule is then never applied. |
| Collect only while `validationStatus == OK` | Per-metric blocker sets | A bare `OK` requirement starves real scans. `CHEST_NOT_VISIBLE` and `FACE_NOT_FORWARD` block breathing only; `CAMERA_TUNING` and `FACE_NOT_CENTERED` block neither. |

## API shape used here

```kotlin
val sdk = SmartSpectraSdk.shared

sdk.config.apiKey = BuildConfig.PRESAGE_API_KEY
sdk.config.imageOutputEnabled = false        // we render via PreviewView
sdk.config.enableTelemetry = false           // defaults to true
sdk.config.requestedMetrics =
    SmartSpectraConfig.breathingMetrics + SmartSpectraConfig.cardioMetrics
sdk.config.previewSurfaceProvider = previewView.surfaceProvider
sdk.useCamera(CameraSelection.Front)

sdk.start()      // suspend; throws SmartSpectraException
sdk.stop()       // suspend

sdk.metricsFlow          // SharedFlow<Metrics>, lossless
sdk.validationStatus     // LiveData<ValidationStatus?>  (code + hint)
sdk.error                // LiveData<SmartSpectraError?> (code, message, retryable)
sdk.processingStatus     // LiveData<ProcessingStatus>
```

Reading samples — note the proto field names and that `value` is a `Float`:

```kotlin
metrics.cardio.pulseRateList     // List<MeasurementWithConfidence>
metrics.breathing.rateList       // List<MeasurementWithConfidence>
// MeasurementWithConfidence: value: Float, stable: Boolean,
//                            confidence: Float (0..100), timestamp: Long (micros since epoch)
```

`requestedMetrics` must not include `BREATHING_AMPLITUDE`, `APNEA`,
`RESPIRATORY_LINE_LENGTH`, `BASELINE` or `INHALE_EXHALE_RATIO` — they are
Unavailable and `start()` fails with `CONFIGURATION_FAILED`.

## Toolchain

The docs require `minSdk` 28, `compileSdk` 36.1+, AGP 8.10.1+ (or an AGP 9.x
release if the project uses Gradle 9), Kotlin 2.2.x. This project uses
Gradle 8.14.3 + AGP 8.13.2 + Kotlin 2.2.21, and reaches the installed
`android-36.1` platform with:

```kotlin
compileSdk = 36
compileSdkMinor = 1
```

## Things not used, and why

- **`sdk.requestInsight(text)` / the `Insight` stream** — the SDK's own LLM
  analysis of the measured vitals. Not used: it would send vitals to a server,
  which contradicts the consent screen's claim that measurements stay on the
  device. The app's optional phrasing feature is separately opt-in and sends only
  two rounded numbers.
- **HRV** (`cardio.hrvList`) — needs ~60 s to become confident, which does not
  fit a payment-time scan. Requested as part of `cardioMetrics` but ignored.
- **`checkDeviceReadiness()`** — useful diagnostics, but with telemetry off its
  report is local only, and the validation stream already drives the UI.
- **`faceMetrics`** — expressions and landmarks are not needed and would widen
  what the app senses for no benefit to the decision.

## Reference

- Quick start / full sample: <https://smartspectra.presagetech.com/docs/android/option-1-api-key.md>
- API reference: <https://smartspectra.presagetech.com/docs/android/api-reference.md>
- Metrics config: <https://smartspectra.presagetech.com/docs/android/metrics.md>
- Payload schema: <https://smartspectra.presagetech.com/docs/data-types.md>
- Telemetry and privacy: <https://smartspectra.presagetech.com/docs/telemetry-and-privacy.md>
- Whole corpus: <https://smartspectra.presagetech.com/llms-full.txt>
- Agent skill: <https://smartspectra.presagetech.com/skill.md>
- Hosted MCP server: <https://mcp.presagetech.com/mcp>
