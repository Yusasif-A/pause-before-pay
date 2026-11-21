---
title: "PauseBeforePay: reading your pulse before you confirm a transfer"
published: false
tags: android, kotlin, ai, showdev
---

*This is a submission for the [Presage SmartSpectra SDK Challenge](https://dev.to/challenges/presage-2026-11-04).*

## What I built

**PauseBeforePay** is a mock money-transfer app with one extra step. Before you
confirm, it reads your pulse, your breathing and whether your face looks tense,
from the front camera. If you look rushed *compared with your own resting
rates*, it says so in plain words, shows you the amount and the recipient again,
and offers a guided breath instead of sending straight away.

> **You seem rushed and tense**
> Confirm you actually want to pay.
> `Pulse 92 bpm · +19` `Face tense`
> **NGN 25,000** to **Ada Obi**
> Is the amount right? Is this the right person? Is anyone telling you to send this?

You can always send anyway. That button is on the pause screen, above the fold,
and it is never disabled.

No bank is connected. No money moves. It is a demo of one interaction: the
moment between deciding to send and actually sending.

### The idea

Transfer confirmation screens are designed to be frictionless, which is
wonderful until the moment you are being hurried. Someone is on the phone
telling you to send now. You are late. You are angry. The interface is identical
in all of those states, because it has no idea what state you are in.

A camera-based vitals SDK gives the app one weak signal about that: your pulse
is 19 bpm above your own resting rate, right now, at the confirm screen.

That is a terrible basis for blocking a payment, and a reasonable basis for
asking a question.

---

## The flow

```
Send form → Consent → 25s camera scan → Calm?     → Confirm unlocks → Success
                                      → Elevated? → Pause screen
                                                    ├── Send anyway → Confirm
                                                    ├── Check again → scan
                                                    └── Cancel
```

Seven screens, native Kotlin, Jetpack Compose, one `Step` state machine. The
SmartSpectra Android SDK does the sensing; everything else is plain Android.

---

## How the signals drive the UI

### Three signals, one request line

The default metric request is **breathing only**. Pulse and expressions both
have to be asked for:

```kotlin
sdk.config.requestedMetrics =
    SmartSpectraConfig.breathingMetrics +
        SmartSpectraConfig.cardioMetrics +
        listOf(MetricType.EXPRESSIONS)
```

I asked for `EXPRESSIONS` on its own rather than the whole `faceMetrics` bundle.
Landmarks, blinking and talking would mean sensing more than the decision needs,
and "we collect it because the SDK offers it" is not a reason.

One thing worth stating plainly, because people assume otherwise: this is
expression reading, **not face recognition**. The SDK reports that a face looks
angry or neutral. It has no idea whose face it is and cannot verify identity.

### Request cardio, or your pulse stays empty

The one configuration mistake worth warning about. The Android SDK's default
metric request is **breathing only**:

```kotlin
sdk.config.requestedMetrics =
    SmartSpectraConfig.breathingMetrics + SmartSpectraConfig.cardioMetrics
```

Leave out `cardioMetrics` and `cardio.pulseRateList` stays empty forever, with
no error to tell you why.

### Let the SDK tell you which samples are good

My first instinct was to invent a quality metric — average the confidences, pick
a cutoff, call it good. That was unnecessary. Every sample carries a `stable`
flag, and the docs say exactly what it means: `stable` marks confidence at or
above the SDK's published accuracy standard, which is ±3 bpm for pulse
(confidence ≥ 40) and ±1 breath/min for breathing (confidence ≥ 45).

So the gate became: use a sample if the SDK says it is stable.

```kotlin
fun onPulseSample(value: Double, stable: Boolean, timestampMicros: Long, elapsedSeconds: Double) {
    if (timestampMicros <= lastPulseTimestamp) return      // the stream can repeat samples
    lastPulseTimestamp = timestampMicros
    if (value !in PLAUSIBLE_PULSE_BPM) return
    lastPulseSeen = value                                  // shown live even if unusable
    if (elapsedSeconds < mode.warmupSeconds) return
    if (!stable) return
    if (currentValidation in PULSE_BLOCKERS) return
    pulse += value
}
```

Note the ordering: a sample can be shown on screen and still not count towards
the decision. The challenge checklist asks for live numbers, and users want to
see something happening, but "visible" and "trustworthy enough to gate a
payment" are different bars.

### Validation codes are UI copy, not just logs

`sdk.validationStatus` emits a `ValidationCode` plus a human-readable `hint`:
`TOO_DARK`, `FACE_TOO_FAR`, `CHEST_NOT_VISIBLE`, `EXCESSIVE_MOTION`,
`FRAME_RATE_TOO_LOW`, and more. That stream is the whole of the app's
"improve your signal" guidance — it drives the chip and the sentence under the
preview while the scan runs, and when a scan fails, the *most frequent* code
seen becomes the retry advice.

It also taught me not to over-gate. Requiring a bare `OK` throughout a scan
starves it: real scans drift in and out of perfect framing constantly. So the
blocker list is per metric. `CHEST_NOT_VISIBLE` ruins a breathing reading and
tells you nothing about a pulse reading, so it blocks one and not the other.

### Timing is published — use it

The quickstart documents when each metric actually arrives:

| Metric | First value | Confident value |
|---|---|---|
| Pulse rate | ~12 s | ~12 s (it is a 12-second average) |
| Breathing rate | ~10 s | ~30 s (confidence is 0 before) |
| HRV | ~30 s | ~60 s |

This reshaped the app. My original plan was a 20-second scan discarding the
first 8 seconds. But nothing exists before 12 seconds, so that plan had a
12-second warm-up whether I liked it or not. The scan became: 12 s warm-up,
earliest finish at 25 s, hard cap at 40 s, and it ends as soon as it has six
stable pulse samples past the warm-up.

It also means **breathing barely participates at payment time**. Thirty seconds
to confident breathing is longer than the whole scan. Rather than hide that, the
app degrades honestly: breathing is captured during the 60-second calibration,
the payment-time decision usually rests on pulse alone, and the README says so.

---

## Comparing you against yourself

A pulse of 85 bpm is calm for one person and rushed for another. Absolute
thresholds would make this app annoying for anyone with a naturally quick
resting rate and useless for anyone with a slow one.

So there is a one-time **60-second calibration scan**. It discards the first
15 seconds, keeps stable samples, and stores the **median** pulse and breathing
plus a spread measure, a sample count and a date. Median, not mean: one garbage
sample should not shift a stored baseline.

```
ELEVATED if (scanPulse - restingPulse)           >= 12 bpm
      or (scanBreathing - restingBreathing)      >= 5 breaths/min
      or (tense face reads / all face reads)     >= 0.55
```

Three independent triggers, any one of which offers the pause. The face earns
its place by catching what the pulse rules miss: someone frightened whose heart
rate has not moved yet. It also fires when the pulse was too noisy to use, since
a clearly-read face is a reading rather than a failed one.

The face is also the noisiest of the three, and the code says so. Concentrating
reads as scowling; squinting at a bright screen reads as angry. That is why the
tense share sits above half, why it needs at least ten confident samples, and
why a face with too few samples is recorded as *unjudged* rather than *settled* —
"we did not look" and "we looked and you were fine" are different facts and the
app must not blur them.

Twelve bpm is four times the SDK's own ±3 bpm error band for a stable sample.
That is deliberate — it should mean "clearly above", not "measurably above".

With no baseline saved, it falls back to absolute thresholds (100 bpm,
20 breaths/min) and says on screen that it is doing so. It is a blunter rule and
the user deserves to know which one ran.

---

## The part I was most careful about

### The decision is arithmetic, and it is tested

The app has a language-model feature. It is not allowed anywhere near the
verdict.

`DecisionEngine.decide(summary, baseline)` is a pure function over two data
classes, with every threshold a named constant in one file. The face trigger is
counting and a comparison, same as the others — adding a third signal did not
mean adding a model. Same numbers in,
same verdict out. No network, no weights, no randomness. It is covered by JVM
unit tests, including the boundary cases (79.9 bpm over a 68 bpm baseline is
calm; 80.0 is elevated) and one test that asserts no generated sentence ever
contains "diagnos", "scam", "fraud", "coerc" or "stress".

This matters because the thing being gated is somebody's money. A reviewer
should be able to read one file and know what the app will do.

### Where the LLM is allowed

Exactly one place: rewording the pause screen's sentence. It is off by default,
sends only the two numbers (no amount, no recipient, no identifier), has a
4-second timeout, is filtered for clinical and fraud vocabulary, and falls back
silently to the sentence the decision engine already wrote. If the endpoint is
down you cannot tell.

### It never blocks

`LOW_CONFIDENCE` is treated as not-elevated. Declining the camera leads to a
confirm screen that works. A failed scan offers "Continue without the check".
The only state where Confirm is held is a clear `ELEVATED` reading, and that
screen has `Send anyway` on it.

An app that can hard-stop your money on a heart-rate reading is a worse app than
one that can't.

---

## Consent and data handling

The consent screen shows before the camera ever starts, for both kinds of scan.
It states, in plain words:

- **What** is measured: pulse rate, breathing rate, and whether the face looks
  tense or settled — with the explicit note that the app reads how a face looks,
  never whose face it is.
- **Why**: only to decide whether to offer a pause.
- **Where**: on the device, inside the SDK. Presage's docs are explicit that
  metric computation is on-device and the backend is contacted only to
  authenticate the API key and validate the subscription. Internet is needed for
  that check — the screen says so rather than implying the phone is offline.
- **SDK telemetry**: `enableTelemetry` defaults to **true**. The app sets it to
  `false` and says that it has. (Presage documents that telemetry never contains
  raw frames or measured vitals, which is worth knowing either way.)
- **Retention**: a payment-time reading is discarded. Only the baseline summary
  persists, in `EncryptedSharedPreferences`, until you delete it. **Delete my
  baseline** exists and actually wipes it.

And what it will not claim: no diagnosis, no "scam detected", no "this person is
being coerced", no automatic redirection of money. SmartSpectra SDK metrics are
for general wellness and informational purposes only and are not FDA-cleared.
Presage's separate Vital Signs Monitor product *is* FDA-cleared — the SDK is not,
and borrowing that clearance in a write-up would be dishonest.

---

## What I would do next

- **Tune the thresholds on real people.** 12 bpm and 5 breaths/min are
  reasoned-about guesses, not findings.
- **Use HRV.** The SDK already provides RMSSD, and it is arguably a better
  arousal signal than raw rate — but it needs ~60 s, which does not fit a
  payment flow. A background baseline refresh might.
- **Re-scan during the breath pacer** rather than after it, so the pacer's
  effect is visible live.
- **Scan quality coaching before the scan starts**, using `validationStatus`
  while the preview is up but the measurement has not begun.

---

## Try it

Source: *[your repo link]*

```bash
git clone <repo> && cd pause-before-pay
# put PRESAGE_API_KEY=... in local.properties
./gradlew :app:installDebug
```

Needs Android 9+ and a real phone — the SDK wants a real face in a real camera.
Get your free key from <https://physiology.presagetech.com>.

To see the elevated path on purpose: twenty squats, then open the app.
