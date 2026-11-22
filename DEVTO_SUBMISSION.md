*This is a submission for the [Presage SmartSpectra SDK Challenge](https://dev.to/challenges/presage-2026-11-04)*

## What I Built

**PauseBeforePay** is a mock money-transfer app with one extra step.

Before you confirm a transfer, the front camera reads your pulse rate, your breathing rate, and whether your face looks tense. If you look rushed *compared with your own resting rates*, the app does not block anything. It offers you a pause:

> **You seem rushed and tense**
> Confirm you actually want to pay.
> `Pulse 92 bpm · +19`  `Face tense`
>
> **Check this before you send**
> **NGN 25,000** to **Ada Obi**
> Is the amount right? Is this the right person? Is anyone telling you to send this?

Then it shows you the amount and the recipient again, offers a three-breath pacer if you want it, and puts **Send anyway** right there on the screen — always visible, never disabled.

**This is not a bank app, and it is not trying to be one.** It is a component designed to drop into one — the send form and confirm screen here exist only to give the pause somewhere to live. No bank is connected and no money moves.

### Why that moment

In 2025 Nigerians lost **₦25.85 billion** to electronic payment fraud across **67,518 reported cases** ([NIBSS, via the Nigeria Electronic Fraud Forum](https://techeconomy.ng/nibss-digital-payment-fraud-drops-51-to-%E2%82%A625-85b-in-2025)). NIBSS named the single biggest technique, and it was not hacking — it was **social engineering**. Somebody talks you into sending the money yourself.

The other failure costs just as much and gets less attention: the wrong account number. In Nigeria a completed transfer is **not automatically reversible**. The CBN's process starts with notifying your own bank, and if the recipient has spent it and will not give it back, recovery can take a court order.

Both failures happen in the same half second — the moment you tap confirm without really reading the screen, because you are rushed, or because somebody is standing over you. And the confirm screen looks identical in every one of those cases, because it has no idea what state you are in.

A camera-based vitals SDK gives the app one weak signal about that state. That is a terrible basis for blocking a payment, and a reasonable basis for asking a question.

## Demo

{% embed https://youtu.be/9dLP9h82PW8 %}

The video walks through the real app on a physical Android phone: the send form, the consent screen, the live camera check with the SDK's own validation feedback, how a pulse is actually read from light, the decision rule, and the pause screen.

## Code

**<https://github.com/Yusasif-A/pause-before-pay>**

Setup is in the README: requirements, where to get a free Presage API key, where to put it (`local.properties`, surfaced through `BuildConfig` — never committed), and how to build and install. There is also `docs/SDK_NOTES.md`, which records everything I verified about the SDK the hard way, including the places the published docs and the shipped AAR disagree.

```bash
git clone https://github.com/Yusasif-A/pause-before-pay.git
cd pause-before-pay
# add PRESAGE_API_KEY=... to local.properties
./gradlew :app:installDebug
```

Needs a real phone — Android 9+, a real face, and real light.

## How It Responds to Human Signals

**Signals used: pulse rate, breathing rate, and facial expressions.**

### Ask for exactly what you read

The one configuration trap worth warning anyone about: the Android SDK's default metric request is **breathing only**. Leave out cardio and `cardio.pulseRateList` stays empty forever, with no error to tell you why.

The obvious fix is to request the convenience bundles. I did that first, and then decompiled the shipped AAR to see what they actually expand to:

```
breathingMetrics = [CHEST_BREATHING, ABDOMEN_BREATHING, BREATHING_RATE]
cardioMetrics    = [PULSE_RATE, ARTERIAL_PRESSURE_TRACE, HRV]
faceMetrics      = [FACE_LANDMARKS, BLINKING, TALKING, EXPRESSIONS]
```

That is **seven metrics** for an app that reads three. Four of them this app never touches, and each is a separate thing an API key may or may not be entitled to — the SDK has a dedicated `METRIC_NOT_AUTHORIZED` error code, so entitlements are real and per-metric. So the app asks for precisely what it uses:

```kotlin
sdk.config.requestedMetrics = buildList {
    add(MetricType.PULSE_RATE)
    add(MetricType.BREATHING_RATE)
    if (requestExpressions) add(MetricType.EXPRESSIONS)
}
```

Smaller request, fewer ways to fail, and the consent screen's claim about what is sensed stays literally true.

### Observe before you start

```kotlin
// metricsFlow is a SharedFlow with no replay. A collector registered after
// start() silently misses whatever was emitted in between.
observers += launch(work) { sdk.metricsFlow.collect { ... } }
observers += launch(work) { sdk.validationStatus.asFlow().collect { ... } }
observers += launch(work) { sdk.processingStatus.asFlow().collect { ... } }
observers += launch(work) { sdk.error.asFlow().collect { ... } }

sdk.start()   // only now
```

### Let the SDK tell you which samples are good

My first instinct was to invent a quality metric — average the confidences, pick a cutoff, call it good. Unnecessary. Every sample carries a `stable` flag, and the docs say exactly what it means: confidence at or above the published accuracy standard, which is ±3 bpm for pulse and ±1 breath/min for breathing.

```kotlin
fun onPulseSample(value: Double, stable: Boolean, timestampMicros: Long, elapsedSeconds: Double) {
    if (timestampMicros <= lastPulseTimestamp) return   // the stream can repeat samples
    lastPulseTimestamp = timestampMicros
    if (value !in PLAUSIBLE_PULSE_BPM) return
    lastPulseSeen = value                               // shown live even if unusable
    if (elapsedSeconds < mode.warmupSeconds) return
    if (!stable) return
    if (currentValidation in PULSE_BLOCKERS) return
    pulse += value
}
```

Note the ordering: a sample can be shown on screen and still not count toward the decision. "Visible" and "trustworthy enough to gate a payment" are different bars.

### Validation codes are UI copy, not log noise

`sdk.validationStatus` emits a `ValidationCode` plus a human-readable `hint`. That stream *is* the app's coaching: it drives the chip and the sentence under the preview in real time, and when a scan fails, the most frequent code becomes the retry advice.

It also taught me not to over-gate. Requiring a bare `OK` throughout starves real scans, because real scans drift in and out of perfect framing constantly. So the blocker list is **per metric**: `CHEST_NOT_VISIBLE` ruins a breathing reading and tells you nothing about a pulse reading, so it blocks one and not the other.

### Timing is published — use it

| Metric | First value | Confident |
|---|---|---|
| Pulse | ~12 s | ~12 s (it *is* a 12-second average) |
| Breathing | ~10 s | ~30 s (confidence is 0 before) |

This reshaped the app. My original plan was a 20-second scan discarding the first 8 seconds — but nothing exists before 12 seconds, so that plan had a 12-second warm-up whether I liked it or not. It also means breathing barely participates at payment time, and rather than hide that, the app degrades honestly and the README says so.

### Get off the main thread

The bug that cost me the most, and the most useful thing I learned. Everything ran on the main thread at first. On a Redmi Note 11 the device log said:

```
Slow Binder: BpBinder transact took 793ms  ICameraDeviceUser
PerfMonitor doFrame : time=1858ms
```

Parsing eight expression scores at 30 Hz and recomposing the screen just as often was sitting *in front of* the camera's own binder calls. The SDK saw holes in the frame timestamps and reported `TIMESTAMP_GAP` — which it must, because a pulse cannot be read from an unevenly sampled signal.

It also explained a symptom that had confused me for two days: **expressions kept working while pulse never did.** Expressions are computed per frame and survive a dropped frame. Pulse needs a continuous window, so a single gap ruins it and it starts over.

Collectors and the progress tick now run on one background thread. `start()` and `stop()` stay on main, where CameraX binds its use cases.

### The decision is arithmetic, and it is tested

The app has an optional language-model feature. It is not allowed anywhere near the verdict.

```
ELEVATED if (scanPulse - restingPulse)       >= 12 bpm
      or (scanBreathing - restingBreathing)  >= 5 breaths/min
      or (tense face reads / all face reads) >= 0.55
```

Three independent triggers, any one of which offers the pause. A one-time 60-second calibration stores your own resting rates as a **median** (not a mean — one garbage sample should not move a stored baseline), so the app compares you against yourself rather than a textbook number. With no baseline it falls back to absolute thresholds and says on screen that it is doing so.

The face earns its place by catching what the pulse rules miss: someone frightened whose heart rate has not moved yet. It is also the noisiest signal, which is why its threshold sits above half, needs at least ten confident samples, and records a face with too few samples as **unjudged** rather than **settled** — "we did not look" and "we looked and you were fine" are different facts.

`DecisionEngine.decide()` is a pure function over two data classes with every threshold a named constant in one file. **41 JVM unit tests**, including boundary cases (79.9 bpm over a 68 bpm baseline is calm; 80.0 is elevated) and one test asserting no generated sentence ever contains "diagnos", "scam", "fraud", "coerc" or "stress".

### It never blocks

`LOW_CONFIDENCE` is treated as not-elevated. Declining the camera leads to a confirm screen that works. A failed scan offers "Continue without the check". The only state where Confirm is held is a clear `ELEVATED` reading, and that screen has `Send anyway` on it.

An app that can hard-stop your money on a heart-rate reading is a worse app than one that cannot.

### One honest note

On my test phone, in evening light, the camera could not sustain the frame rate the SDK needs, so the measurement in the recording does not complete. The app reports that plainly — *"Reading was unclear. Nothing is being held up."* — and lets the transfer through with Confirm enabled.

I would rather show that than fake a number. The degradation path is the part of this build I am most glad works, because it is the behaviour that matters most when this idea meets a real phone in bad light.

## Prize Categories

**Best Thoughtful Real-World Use.**

₦25.85 billion, 67,518 cases, and social engineering as the number one technique. Authorised push payment fraud works by manufacturing urgency — "send it now, the account will close", "your money is at risk, move it immediately". Every bank's answer so far is a static warning screen that everyone taps past, because it appears identically whether you are relaxed or being rushed by someone on the phone.

This is for people at the moment of a transfer they may regret: the ones sending to a wrong account number because they did not re-read it, the ones pushed into a payment by someone standing over them, the older relative on a call with a "bank official".

It is deliberately built as a **component, not a product**. A bank already owns the send form, the confirm screen and the ledger. What it does not have is any read on the person's state at the moment of confirmation, and that is the only thing this adds.

Designing it to be usable for those people meant three deliberate constraints:

- **It must never block.** The person who most needs the pause is also the person who most needs to be able to ignore it. `Send anyway` is above the fold and always enabled.
- **It must explain, not report.** The pause leads with *"You seem rushed and tense"*, not *"92 bpm"*. Numbers stay as evidence underneath.
- **It must not lie about what it knows.** No diagnosis, no "scam detected", no claim to identify anyone. The app reads how a face looks, never whose face it is.

For a bank, this is a few hundred milliseconds between tapping send and the money leaving. It needs no new hardware — every phone already has a front camera — and it fits beside existing confirmation screens rather than replacing them.

## Consent and Data Handling

**Consent is asked before the camera ever starts**, for both the calibration scan and the payment-time check, on a screen that states in plain words:

- **What is measured** — pulse rate, breathing rate, and whether the face looks tense or settled. The screen says explicitly that the app reads *how a face looks, never whose face it is*: there is **no face recognition**, and it cannot identify anyone.
- **Why** — only to decide whether to offer a pause.
- **Where** — on the device, inside the SDK. Presage's docs are explicit that metric computation is on-device and the backend is contacted only to authenticate the API key and validate the subscription. Internet is needed for that one check, and the screen says so rather than implying the phone is offline.
- **Android camera permission** is requested through the normal system dialog; declining leads to a confirm screen that works normally.

**SDK telemetry:** `enableTelemetry` defaults to **true**. The app sets it to `false` and says on the consent screen that it has.

**Retention:** a payment-time reading is used for the decision on the next screen and then discarded. The only thing that persists is the baseline summary — two medians, two spread values, two sample counts and a date — stored in `EncryptedSharedPreferences`. **Delete my baseline** exists and actually wipes it. No video, no frames, no images are ever recorded, shown later, or uploaded.

**Optional AI service:** there is one opt-in, off-by-default feature that rewords the pause sentence using an external Gemma endpoint. When on, it sends **only the two rounded numbers** — no amount, no recipient, no identifier, no image. It has a 4-second timeout, is filtered for clinical and fraud vocabulary, and falls back silently to the sentence the decision engine already wrote. It never changes the verdict: the calm-or-elevated call is fixed arithmetic on the phone, with or without the switch.

**What it will not claim:** no diagnosis, no "scam detected", no "this person is being coerced", no automatic redirection of money. SmartSpectra SDK metrics are for general wellness and informational purposes only and are **not FDA-cleared**. (Presage's separate Vital Signs Monitor product *is* cleared — the SDK is not, and borrowing that clearance in a write-up would be dishonest.)

## Agent Session

<!-- Upload the transcript at https://dev.to/agent_sessions/new, then embed the link here.
     Remember to hit "Make Public" or judges cannot open it. -->
