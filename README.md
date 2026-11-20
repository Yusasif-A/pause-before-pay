# PauseBeforePay

**A mock transfer demo, not a real payment app.** No bank, card or payment
service is connected. Nothing in this repository moves money.

Before you confirm a transfer, PauseBeforePay reads your pulse and breathing
from the front camera using the [Presage SmartSpectra SDK][sdk]. If you look
rushed *compared with your own resting rates*, it offers a short pause and a
guided breath. You can always send anyway.

Built for the [Presage SmartSpectra SDK Challenge][challenge].

---

## What it does

```
Send form  →  Consent  →  20-40s camera scan  →  Calm?    → Confirm → Success
                                              →  Elevated? → Pause screen
                                                             ├── Send anyway  → Confirm
                                                             ├── Check again  → scan
                                                             └── Cancel
```

- **Live numbers on screen.** Pulse and breathing appear as the SDK produces
  them, with a count of how many readings were steady enough to use.
- **Your own baseline.** One 60-second calibration scan stores your resting
  pulse and breathing. The payment-time check then compares you against
  yourself, not against a population average.
- **Deterministic decision.** Calm or elevated is decided by fixed arithmetic in
  [`Decision.kt`](app/src/main/java/com/pausebeforepay/signal/Decision.kt), with
  every threshold named in
  [`Thresholds.kt`](app/src/main/java/com/pausebeforepay/signal/Thresholds.kt).
  No model, no language model, no randomness. It is unit-tested.
- **Never a hard block.** `Send anyway` is on the pause screen, above the fold,
  always enabled. A poor reading never blocks. Declining the camera never blocks.

## What it does not do

It does not diagnose anything, detect scams, detect coercion, redirect money, or
block fraud. It cannot tell *why* your pulse is what it is. SmartSpectra SDK
metrics are offered for general wellness and informational purposes only; they
are **not FDA-cleared** and may not be used for medical diagnosis or treatment.

(Presage's separate *SmartSpectra Vital Signs Monitor* product is FDA-cleared.
The SDK this app uses is not, and this app claims nothing from that clearance.)

---

## Running it

### 1. Requirements

| Thing | Version |
|---|---|
| Android phone | API 28 (Android 9) or newer, with a front camera |
| Android Studio | any version shipping JDK 17+ (the wrapper uses Gradle 8.14.3) |
| Android SDK platform | 36.1 (`compileSdk = 36`, `compileSdkMinor = 1`) |
| Build tools | 36.1.0 or newer |

The project builds from the command line as long as `JAVA_HOME` points at a
JDK 17 or newer — Android Studio's bundled JBR works:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # Git Bash
```

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # PowerShell
```

### 2. Get a Presage API key

1. Register at the Presage developer admin portal:
   <https://physiology.presagetech.com/auth/register>
2. Confirm your email, then log in at `/auth/login`.
3. Copy the API key from your portal page.

The SDK computes metrics on the device. It contacts Presage's backend only to
authenticate that key and validate the subscription, so the phone needs an
internet connection for the check — not for the measurement.

### 3. Put the key in `local.properties`

`local.properties` is gitignored. Create or edit it in the repository root:

```properties
sdk.dir=C:/Users/you/AppData/Local/Android/Sdk
PRESAGE_API_KEY=your_key_here
```

The key is read at build time into `BuildConfig.PRESAGE_API_KEY`. It is never
hard-coded in a source file. If it is missing, the app still builds and runs and
says plainly on the send screen that no key is configured.

Optional, and off unless you turn it on in the app — see
[Optional plain-language wording](#optional-plain-language-wording):

```properties
GEMMA_BASE_URL=https://your-host/v1
GEMMA_MODEL=google/gemma-4-E26B-it
GEMMA_API_KEY=your_endpoint_key
```

`.env.example` lists the same names if you prefer to keep them in a `.env` and
copy them across.

### 4. Build and install

```bash
./gradlew :app:assembleDebug          # APK at app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # the decision-logic tests
adb devices -l                        # check the phone is attached first
./gradlew :app:installDebug
```

Or open the project in Android Studio and run it on a physical device. The
emulator is not useful here: the SDK needs a real face in a real camera.

### 5. First run

1. Tap **Set up your baseline** and sit through the 60-second scan, still and
   well lit. Without this the app falls back to blunt absolute thresholds.
2. Go back, enter an amount, tap **Continue to confirm**.
3. Accept the consent screen, then hold still for the short scan.
4. To see the elevated path on purpose: do something that raises your pulse
   by 12 bpm or more — a flight of stairs, twenty squats — then run the check.

---

## How the decision works

Two measurement contexts, one rule set. Everything below is in
[`Thresholds.kt`](app/src/main/java/com/pausebeforepay/signal/Thresholds.kt).

### Which samples count

A sample is used only if all of these hold:

1. It arrived **after the warm-up window** (15 s for calibration, 12 s for a
   payment scan). The SDK produces no pulse value before about 12 s anyway, and
   its first value is a 12-second average.
2. The SDK marked it **`stable`**. That is the SDK's own verdict that confidence
   has reached its published accuracy standard — ≥40 for pulse (±3 bpm) and ≥45
   for breathing (±1 breath/min). It is a far better quality gate than anything
   this app could invent.
3. The current **`ValidationCode` is not a blocker** for that metric. Darkness,
   motion and a missing face block both. A chest out of frame blocks breathing
   only, because it says nothing about pulse.
4. The value is **physiologically plausible** (30–220 bpm, 4–45 breaths/min).

Accepted samples are reduced with a **median**, not a mean, so one garbage
reading cannot move the result.

### The verdict

| Condition | Verdict |
|---|---|
| Fewer than 6 accepted pulse samples | `LOW_CONFIDENCE` → never blocks; shows guidance from the most frequent validation code |
| Baseline saved, and scan pulse ≥ resting + **12 bpm** | `ELEVATED` |
| Baseline saved, and scan breathing ≥ resting + **5 breaths/min** | `ELEVATED` |
| No baseline, and pulse ≥ **100 bpm** or breathing ≥ **20 breaths/min** | `ELEVATED` |
| otherwise | `CALM` |

12 bpm is four times the SDK's published ±3 bpm error band for a stable sample,
so it means "clearly above", not "measurably above". These numbers are
heuristics: they are starting points for a demo, not clinical thresholds, and
they are in one file precisely so they can be argued with and tuned.

A payment scan runs for at least 25 s, finishes as soon as it has 6 good pulse
samples after that, and gives up at 40 s.

---

## Consent and data

The consent screen appears **before the camera ever starts**, for both kinds of
scan, and declining leads somewhere sane rather than trapping you.

**What is measured:** pulse rate and breathing rate, from the front camera.

**Why:** only to decide whether to offer a short pause before you confirm.

**Where it is processed:** on the phone, inside the SDK. Camera frames are not
sent anywhere. The SDK contacts Presage once per measurement to validate its API
key and subscription; that request carries no frames and no measurements.

**SDK telemetry:** the SDK can send its own session summaries and device
readiness diagnostics, and this **defaults to on**. This app sets
`enableTelemetry = false`. Presage documents that telemetry never includes raw
frames or measured vitals in any case.

**What is kept:** a payment-time reading is used for the decision and then
discarded. The only thing stored is the baseline summary — two medians, two
spread values, two sample counts and a date — held in
`EncryptedSharedPreferences` on the device until you delete it.
**Delete my baseline** is on the Baseline settings screen and wipes it
immediately.

### Optional plain-language wording

The pause screen's sentence is generated by code. If an OpenAI-compatible
endpoint is configured, a switch on the consent screen will reword that one
sentence — and only that sentence.

It is **off by default**, it sends **only the two numbers** (no amount, no
recipient, no identifier), it **cannot change the verdict**, and if the endpoint
is missing, slow or malformed the app silently shows the built-in sentence. See
[`PlainLanguage.kt`](app/src/main/java/com/pausebeforepay/llm/PlainLanguage.kt).

---

## Project layout

```
app/src/main/java/com/pausebeforepay/
  MainActivity.kt           Activity; everything else is Compose
  PauseBeforePayApp.kt      The flow, as one Step state machine
  PaymentViewModel.kt       State, and the only place screens change
  signal/
    Thresholds.kt           Every tunable number, in one place
    Decision.kt             The deterministic verdict (unit-tested)
    SampleWindow.kt         Warm-up, stability and validation gating
    Stats.kt                Median and MAD
    Baseline.kt             What gets stored
    ScanSummary.kt          What one measurement produced
  sdk/MeasurementEngine.kt  All SmartSpectra SDK contact
  data/BaselineStore.kt     EncryptedSharedPreferences
  llm/PlainLanguage.kt      Optional, cosmetic, opt-in
  ui/                       Compose screens
app/src/test/               JVM tests for the decision and the sample gating
```

## Known limits

- **Breathing rarely contributes at payment time.** Breathing-rate confidence is
  zero for roughly the first 30 s, so a 25 s scan usually has no stable
  breathing samples and the decision rests on pulse. Breathing does get captured
  during the 60 s calibration. This is a property of the measurement, not a bug,
  and the app degrades to pulse-only rather than pretending otherwise.
- **Thresholds are untuned heuristics.** 12 bpm and 5 breaths/min were chosen to
  mean "clearly above". Real tuning needs readings from many people.
- **A raised pulse has many causes.** Stairs, caffeine, heat, a cold room, a
  phone held at an awkward angle. The app treats it only as a reason to offer a
  pause, never as evidence of anything.
- **The debug APK is about 124 MB.** The SmartSpectra native engine ships for
  four ABIs and nothing is stripped or minified in a debug build. Installing via
  `./gradlew :app:installDebug` over USB is unaffected.
- **`EncryptedSharedPreferences` is deprecated** in `androidx.security:security-crypto:1.1.0`
  and the build warns about it. It still works on API 28+ and is the least
  surprising place to keep two medians; the storage is isolated behind
  [`BaselineStore`](app/src/main/java/com/pausebeforepay/data/BaselineStore.kt)
  if it needs replacing.
- **Not verified on hardware yet.** The project builds and its 29 unit tests
  pass, but no phone was attached when it was written, so the camera path has
  not been run end to end. Check `adb devices -l` first; expect to tune the
  thresholds once you see real readings.

## Licence and credits

Uses the [Presage SmartSpectra Android SDK][sdk] (`com.presagetech:smartspectra`).
SmartSpectra is a product of Presage Technologies; this app is an independent
demo and is not affiliated with or endorsed by Presage.

[sdk]: https://smartspectra.presagetech.com/docs/android
[challenge]: https://dev.to/challenges/presage-2026-11-04
