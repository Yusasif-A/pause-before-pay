# PauseBeforePay — Build Brief (cold-start handoff)

**For:** a Claude Code session starting fresh inside `C:\Users\USER\Desktop\pause-before-pay`
**Written:** 2026-11-20
**Companion doc:** `PauseBeforePay_Presage_PRD (1).md` in this same repo — read it first, it is the product spec. This brief records the **decisions, verified facts and gotchas** established in a prior session so you don't re-derive them or repeat dead ends.

---

## 0. Read this before you touch anything

Three facts that will save you hours:

1. **There is no SmartSpectra browser SDK and no REST/upload API.** Do not attempt a PWA, a web app, or "record video in the browser and POST it somewhere". This was researched and ruled out (see §2). The only platforms Presage ships are Android/Kotlin, Swift/iOS, C++, and Node/Electron — all native, all on-device.
2. **The target is a native Kotlin Android APK.** Decided by the user. It must run on their physical Android phone.
3. **`.env` in this repo contains a real Presage API key and there is no `.gitignore` and no commits yet.** Your *first* action on the repo must be to write a `.gitignore` covering `.env` and `local.properties`. Do not `git add -A` before that. See §8.

---

## 1. What this is

**One-line:** before confirming a money transfer, use the phone's front camera to read pulse and breathing. If the person looks rushed compared to their own resting baseline, pause and offer a short breath check — but always let them send anyway.

It is a **mock** transfer demo for the [Presage SmartSpectra SDK Challenge](https://dev.to/challenges/presage-2026-11-04). No real bank, no real payment rail, no money moves.

---

## 2. Decisions already made (do not re-open)

| Decision | Choice | Why |
|---|---|---|
| Platform | **Native Kotlin Android app**, installed as an APK on the user's phone | User requires it to run on their phone. PWA/Chrome-install was their first preference but is **impossible**: no browser SDK, no WASM build, no cloud measurement endpoint. Confirmed against both `llms.txt` indexes and the full docs index. |
| Rejected: PWA + server | Rejected | Would need the phone to stream frames to a Node server running the SDK. Breaks the PRD's "sensing on the device" consent claim, needs a server live during the demo, and is the most fragile option. |
| Signal rule | **Stored personal baseline** | A one-time calibration scan saved locally, so the payment-time scan is short (~20s) instead of the 45s+ the PRD's in-flow baseline would cost. Falls back to absolute thresholds when no baseline exists. Full spec in §5. |
| Who decides calm vs elevated | **Deterministic code, never an LLM** | It gates someone's money. Must be auditable and reproducible. |
| LLM role | **Optional, cosmetic only** | Gemma may turn numbers into one plain sentence on the pause screen ("your pulse is 19 above your usual resting rate"). It must never influence the calm/elevated decision, and the app must work fully when it is unreachable. |
| Deliverables | **App + README + DEV.to post draft** | User explicitly did *not* ask for a demo-video script or a headless test harness. Don't build them unless asked. |

---

## 3. Verified environment (checked on this machine, 2026-11-20)

Everything needed is already installed. Do not install a JDK or Android SDK.

| Thing | Status |
|---|---|
| JDK | Android Studio JBR at `C:\Program Files\Android\Android Studio\jbr\bin\java.exe`. **`java` is NOT on PATH** — set `JAVA_HOME` to `C:\Program Files\Android\Android Studio\jbr` or use Studio's bundled Gradle. |
| Android SDK | `C:\Users\USER\AppData\Local\Android\Sdk` |
| Platforms | `android-35`, `android-36.1` ← 36.1 is exactly what the SDK needs |
| Build tools | `34.0.0`, `36.1.0`, `37.0.0` |
| adb | `1.0.41` (platform-tools 37.0.0) at `…\Sdk\platform-tools\adb.exe` |
| Gradle cache | `C:\Users\USER\.gradle` exists |
| Device | **No phone was connected** when checked. Run `adb devices -l` before trying to install. |
| Node | v24.14.0, npm 11.9.0 — not needed for the Android path |
| Git remote | `origin` → `https://github.com/Yusasif-A/pause-before-pay.git`, branch `main`, **zero commits** |

---

## 4. Verified SmartSpectra Android SDK facts

> **Source caveat:** these come from reading the official docs, not from a successful compile. Nothing here has been built yet. Verify against the live docs as you go, and treat any mismatch as the docs being right and this brief being stale.

### Dependency and build config

```kotlin
// build.gradle.kts (module)
implementation("com.presagetech:smartspectra:3.5.0")
```

Repositories: `mavenCentral()` and `google()`.

Requirements: `minSdk` **28**, `compileSdk` **36.1** or later, AGP **8.10.1**+, Kotlin **2.2.x**.

Manifest permissions: `android.permission.CAMERA` and `android.permission.INTERNET` (internet is needed to validate the API key, not to compute metrics).

### Auth

```kotlin
sdk.config.apiKey = API_KEY
```

The docs' sample hard-codes `const val API_KEY = "YOUR_API_KEY"`. **Do not hard-code it.** Put the key in `local.properties` and expose it via a `buildConfigField`, then read `BuildConfig.PRESAGE_API_KEY`. An OAuth alternative exists at `docs/android/option-2-oauth.md` if you'd rather avoid shipping a key in the APK at all — worth considering, but the API key path is fine for a challenge demo.

### Core API

- Singleton: `SmartSpectraSdk.shared`
- Lifecycle: `sdk.start()` / `sdk.stop()`, called from a coroutine
- Observables (LiveData): `sdk.metrics.observe(...)`, `sdk.processingStatus`, `sdk.validationStatus`, `sdk.error`
- Camera preview: `sdk.config.previewSurfaceProvider` wired to a CameraX `PreviewView`
- **No prebuilt screening UI component is documented.** The official sample builds its own cards, buttons and waveform views. You own all the UI.

### Reading values

```kotlin
val pulse     = metrics?.cardio?.pulseRateList?.lastOrNull()?.value
val breathing = metrics?.breathing?.rateList?.lastOrNull()?.value
```

**Gotcha:** these can be `null` between updates. Keep the last good value in your own state rather than rendering `null`.

Unlike the Node SDK (which hands back raw protobuf you must decode yourself), the Android `metrics` LiveData gives you decoded objects. No manual protobuf work.

### Requesting metrics — easy to get wrong

```kotlin
sdk.config.requestedMetrics =
    SmartSpectraConfig.breathingMetrics + SmartSpectraConfig.cardioMetrics
```

**The default is breathing only.** If you don't add `cardioMetrics`, `cardio.pulseRateList` stays empty and you will waste time debugging a "broken" pulse reading. Pulse rate and breathing rate together is explicitly supported.

### Validation status — this is your signal-quality channel

The PRD's "low confidence → ask the user to sit still / improve light" rule maps directly onto `sdk.validationStatus`. Codes seen in the docs include:

`kOk`, `kTooDark`, `kTooBright`, `kFaceNotCentered`, `kFaceTooFar`, `kExcessiveMotion`, `kFrameRateTooLow`

Confirm the exact Kotlin enum names against `docs/android/api-reference.md` — the list above was gathered partly from the Node docs, where the code names are shared but the type may differ.

### Measurement quality

- Docs suggest **30–60 seconds** of footage for rates to compute reliably; "a few seconds isn't" enough.
- Needs a well-lit, mostly still face. Front camera, face *and upper chest* in frame (chest matters for breathing).
- No numeric minimum fps or lighting threshold is published; rely on `validationStatus` instead of inventing your own quality metric.
- Warm-up time before the first value appears is **not stated** — measure it empirically and tune §5's warm-up discard accordingly.

### Docs worth reading (append `.md` to any URL for clean markdown)

- Quick start: https://smartspectra.presagetech.com/docs/android
- API key setup: https://smartspectra.presagetech.com/docs/android/option-1-api-key.md
- Metrics config: https://smartspectra.presagetech.com/docs/android/metrics.md
- API reference: https://smartspectra.presagetech.com/docs/android/api-reference.md
- Troubleshooting: https://smartspectra.presagetech.com/docs/android/troubleshooting.md
- Getting a good measurement: https://smartspectra.presagetech.com/docs/measurement-quality.md
- Data types / full schema: https://smartspectra.presagetech.com/docs/data-types.md
- Telemetry & privacy: https://smartspectra.presagetech.com/docs/telemetry-and-privacy.md
- **Everything in one file:** https://smartspectra.presagetech.com/llms-full.txt
- **Presage ships an agent skill:** https://smartspectra.presagetech.com/skill.md
- **Presage ships a hosted MCP server:** https://mcp.presagetech.com/mcp (API keys, app registration, plan usage, docs search)

The agent skill and MCP server are the fastest route to accurate API detail — prefer them over guessing.

### Telemetry

The Node SDK has `enableTelemetry` defaulting to **true**. Whether the Android SDK has an equivalent toggle, and its default, was **not confirmed**. Check `docs/telemetry-and-privacy.md` and the Android API reference — you need the answer to write an honest consent screen (§6).

---

## 5. Signal logic — full spec

Two measurement contexts. Keep all thresholds as named constants in one file so they're easy to tune and easy to explain in the DEV post.

### A. Baseline calibration (one-time, its own screen)

- Entry point: a "Set up your baseline" screen, reachable on first run and from settings.
- Run a **60s** measurement, sitting still, well lit.
- **Discard the first 15s** (warm-up / signal stabilisation).
- From the remaining ~45s, collect pulse and breathing samples **only while `validationStatus == kOk`**.
- Require a minimum valid-sample count (start at **20**); below that, fail honestly and offer a retry with the specific guidance from the validation code.
- Store the **median** pulse and median breathing (median, not mean — robust to the odd garbage sample), plus a spread measure (MAD or IQR), the sample count, and a timestamp.
- Persist in `EncryptedSharedPreferences` or DataStore. These are vitals; encrypt them.
- Provide a **"Delete my baseline"** button. The PRD's consent section requires you to state how long you keep data and how to delete it — so this must actually exist.

### B. Payment-time scan

- Target **20s**, hard cap **35s**.
- **Discard the first 8s** as warm-up.
- Collect samples where `validationStatus == kOk`.
- Require **≥ 8 valid pulse samples** to make a call. Otherwise → `LOW_CONFIDENCE`.
- `scanPulse` = median of valid pulse samples; `scanBreathing` = median of valid breathing samples.

### C. The decision

```
ELEVATED  if (scanPulse - restingPulse) >= 12 bpm
          OR (scanBreathing - restingBreathing) >= 5 breaths/min

CALM      otherwise

LOW_CONFIDENCE
          if too few valid samples
          → treat as NOT elevated (never block on bad signal)
          → show guidance derived from the most frequent validationStatus code
            (too dark / face not centred / face too far / hold still)
          → offer Retry and Send anyway

NO BASELINE YET
          → fall back to absolute thresholds:
            pulse >= 100 bpm OR breathing >= 20 breaths/min
```

The 12 bpm / 5 brpm figures are starting points chosen to mean "clearly above", per the PRD. Tune them once you have real readings from the device, and say in the DEV post that they're heuristics.

### D. Pause screen behaviour

- Copy: *"You seem rushed. Take a short pause before sending?"*
- Guided **3 breaths**, 4s inhale / 6s exhale ≈ 30s total, with a visual pacer.
- After the pacer, offer a **re-scan** (another 20s). If calm → unlock Confirm.
- **`Send anyway` is always visible and always enabled.** This is non-negotiable — it is in the PRD, and an app that can hard-block someone's money on a heart-rate reading is a bad app.
- `Cancel` returns to the send form.

---

## 6. Consent and data — mandatory, not decoration

Shown **before** the camera ever starts, with Accept / Decline, and Decline must cleanly exit the flow:

- **What is measured:** pulse and breathing, from the camera.
- **Why:** only to decide whether to offer a short pause before you confirm.
- **Where processing happens:** on the device, inside the SDK. (Presage's docs state metric computation is on-device and the backend is contacted only to authenticate the API key and validate the subscription. Internet is required for that auth step — say so.)
- **Retention:** state exactly what you keep. Recommended: keep only the baseline summary (two medians + spread + timestamp), keep no per-session vitals, and point at the Delete button.
- **Telemetry:** once you've confirmed whether the Android SDK sends aggregate telemetry and whether it can be disabled, say so plainly here.

### Never claim, in UI copy, the README, or the DEV post

- Medical diagnosis, treatment, or any clinical claim
- "Scam detected"
- "This person is being coerced/forced"
- Automatic redirection of money
- That the app blocks fraud

The SDK's own terms: metrics are **for general wellness and informational purposes only**, are **not FDA-cleared**, and **may not be used for medical diagnosis or treatment**. Note carefully: Presage's separate *SmartSpectra Vital Signs Monitor* product is FDA-cleared (510(k) K254169) — **the SDK is not**. Do not borrow that clearance in your write-up.

---

## 7. Screens to build

| Screen | Contents |
|---|---|
| Send form | Amount, recipient name (fake data fine), Continue to confirm |
| Baseline setup | Explain why, 60s calibration, result summary, Delete my baseline |
| Consent | §6 content, Accept / Decline |
| Scan | CameraX `PreviewView`, live pulse + breathing numbers, framing guidance driven by `validationStatus`, progress indicator |
| Pause | "You seem rushed" + breath pacer + Re-scan / Send anyway / Cancel |
| Confirm | Final Confirm send, enabled only when calm, sent-anyway, or low-confidence |
| Success | Mock "Transfer submitted" |

UI should be large and plain. English only — the user's related project is English-only by decision, so don't add localisation.

**Showing live numbers during the scan is a scored checklist item** in the PRD ("Live pulse (and optional breathing) visible in UI", "Demo video: must show live SmartSpectra numbers and Confirm lock/unlock"). Don't hide the readings behind a spinner.

---

## 8. Secrets and git hygiene — do this first

The repo currently holds `.env` with a **real** `PRESAGE_API_KEY` (the user confirmed they added it), plus `GEMMA_BASE_URL`, `GEMMA_MODEL`, `GEMMA_API_KEY`. There are **no commits and no `.gitignore`**, and `origin` points at a public GitHub repo.

Before any `git add`:

1. Write `.gitignore` covering at minimum: `.env`, `local.properties`, `.gradle/`, `build/`, `*.apk`, `*.keystore`, `*.jks`, `.idea/`, `local.properties`.
2. Add `.env.example` with empty values: `PRESAGE_API_KEY=`, and the Gemma keys if you use them.
3. Put the key in `local.properties` (already gitignored by convention) and surface it as a `buildConfigField`. Keep `.env` as the user's own store if they like, but it must stay untracked.
4. README must cover: how to get a Presage API key (register at https://physiology.presagetech.com — the developer admin portal), where to put it, install steps, how to run, and a clear statement that **this is a mock transfer demo, not a real payment app**.

### Commit messages

**Do not add any Claude attribution** — no `Co-Authored-By: Claude`, no "Generated with Claude Code" footer. This is a standing instruction from the user and it overrides the default attribution behaviour. Plain commit messages only.

---

## 9. Build checklist

- [ ] `.gitignore` + `.env.example` in place **before** the first commit
- [ ] Gradle project builds with `JAVA_HOME` pointed at Android Studio's JBR
- [ ] `com.presagetech:smartspectra:3.5.0` resolving, `compileSdk` 36.1, `minSdk` 28
- [ ] API key read from `local.properties` via `BuildConfig`, never hard-coded
- [ ] `requestedMetrics = breathingMetrics + cardioMetrics` (or pulse will be empty)
- [ ] Consent screen gates the camera; Decline exits cleanly
- [ ] CameraX preview visible, wired through `previewSurfaceProvider`
- [ ] Live pulse + breathing rendered during the scan
- [ ] `validationStatus` drives on-screen framing/lighting guidance
- [ ] Baseline calibration + encrypted storage + Delete my baseline
- [ ] Elevated rule per §5, thresholds as named constants
- [ ] Confirm disabled while elevated
- [ ] Pause screen with 3-breath pacer and re-scan
- [ ] Send anyway always available
- [ ] Mock success screen
- [ ] README per §8
- [ ] DEV.to post draft: what you built, how the signals drive the UI, consent and data handling
- [ ] Verify on the physical phone (`adb devices -l` first)

---

## 10. Out of scope

- Real bank / OPay / Paystack integration
- Auto-blocking with no override
- Redirecting money based on heart rate
- Scam-text AI
- Any medical or legal claim
- Demo-video script and headless CI harness (user declined both — ask before adding)

---

## 11. Open items to confirm with the user

1. **Challenge deadline** — unknown. The challenge page is https://dev.to/challenges/presage-2026-11-04; check the closing date before planning scope.
2. **Gemma** — is the plain-language sentence on the pause screen wanted, or should the app ship with no LLM at all? The keys are in `.env` either way. If used: decision logic stays deterministic, Gemma only phrases the result, and the app must degrade gracefully when it's unreachable. The `GEMMA_BASE_URL` looks like a self-hosted OpenAI-compatible endpoint — confirm the wire format before coding against it.
3. **Android SDK telemetry default** — needed for an honest consent screen (§6).
4. **Signing** — debug-signed APK for sideloading, or a release keystore? Debug is fine for a challenge demo.
5. **Phone's Android version** — must be API 28+. Confirm before building.
