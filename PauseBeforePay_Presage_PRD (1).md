# PauseBeforePay — Build Document

**Product:** PauseBeforePay  
**Challenge:** Presage SmartSpectra SDK Challenge  
**One-line:** Before confirming a money transfer, use the camera to read pulse/breathing. If the person looks rushed, pause and offer a short breath check (or let them send anyway).

---

## 1. What to build

A small app (desktop or mobile) with a **mock transfer screen**:

1. User enters amount and recipient (fake data is fine).  
2. User taps **Continue to confirm**.  
3. App asks for **camera consent**.  
4. SmartSpectra runs a short scan (face + upper chest visible).  
5. App reads **live pulse** and/or **breathing rate**.  
6. If calm enough → enable **Confirm send**.  
7. If elevated → show pause screen with guided breaths; **Confirm** stays off until calm or user taps **Send anyway**.  
8. Show a mock **Transfer successful** screen.  

No real bank or payment API required.

---

## 2. User flow

```
Mock send form (amount + recipient)
        ↓
Camera consent
        ↓
Short scan (SmartSpectra)
        ↓
    ┌───┴───┐
    ↓       ↓
  Calm   Elevated
    ↓       ↓
Confirm   Pause screen:
enabled   • "You seem rushed"
          • Guided 3 breaths
          • [Send anyway] [Cancel]
              ↓
        Confirm send
              ↓
        Mock success
```

---

## 3. Screens to implement

| Screen | Purpose |
|--------|---------|
| **Send form** | Amount, recipient name, Continue |
| **Consent** | Explain camera use; Accept / Decline |
| **Scan** | Camera preview guidance; live pulse/breath when ready |
| **Pause** | Rushed message + breath pacer + Send anyway / Cancel |
| **Confirm** | Final Confirm send button (enabled only when allowed) |
| **Success** | Simple "Transfer submitted" mock |

Keep UI large and plain. English first; optional simple local-language strings later.

---

## 4. Signal rules

**Inputs from SmartSpectra**

- Pulse rate  
- Breathing rate (if available)  
- Signal confidence / quality  

**Logic (keep simple)**

1. Wait until pulse (and optionally breathing) has acceptable confidence.  
2. Take a short baseline after the signal stabilizes (about 15–30 seconds).  
3. Compare a later window to that baseline.  
4. **Elevated** if pulse is clearly above baseline, or breathing is clearly faster, with good confidence.  
5. **Low confidence** → do not treat as elevated; ask user to sit still, improve light, face camera.  
6. **Always** offer **Send anyway** on the pause screen.  

**Do not claim**

- Medical diagnosis  
- "Scam detected"  
- "Person is being forced"  
- Automatic redirect of money  

Copy example: *You seem rushed. Take a short pause before sending?*

---

## 5. Tech stack

Pick one path and stick to it.

### Option A — Desktop (often fastest for webcam demos)

- Electron or plain Node + local web UI  
- Package: `@smartspectra/node-sdk`  
- Docs: https://smartspectra.presagetech.com/docs/nodejs  

### Option B — Android

- Kotlin app  
- Official SmartSpectra Android SDK  
- Docs: https://smartspectra.presagetech.com/docs/android  

### Required for either path

| Item | Detail |
|------|--------|
| API key | Register via Presage; store in `.env` / `local.properties` — never commit the real key |
| Camera | Front camera; face and upper chest in frame |
| Internet | Needed for API key / SDK gating; metrics compute on device |
| Demo video | Must show live SmartSpectra numbers and Confirm lock/unlock |

General docs: https://smartspectra.presagetech.com/docs  
Product / signup: https://presagetech.com/

---

## 6. Consent and data (required)

Before the camera starts, show a clear screen:

- What is measured: pulse and/or breathing from the camera  
- Why: only to decide whether to offer a short pause before confirm  
- Video is used for sensing on the device; follow Presage telemetry policy in the docs  
- Prefer **no long-term storage** of vitals; if you store session stats, say how long and how to delete  
- User can decline camera and leave the flow  

Not allowed: covert capture, diagnosis, treatment claims.

---

## 7. Project structure (suggested)

```
pausebeforepay/
  README.md              # setup, how to run, where to put API key
  .env.example           # PRESAGE_API_KEY=
  src/ or app/           # UI + SmartSpectra integration
  docs/                  # optional screenshots
```

README must include:

1. How to get a Presage API key  
2. Install steps  
3. How to run  
4. That this is a mock transfer demo, not a real payment app  

---

## 8. Build checklist

- [ ] Presage developer account + API key  
- [ ] SDK installed and session starts with camera consent  
- [ ] Live pulse (and optional breathing) visible in UI  
- [ ] Baseline + elevated rule implemented  
- [ ] Confirm disabled when elevated  
- [ ] Pause screen with guided breaths  
- [ ] Send anyway always available  
- [ ] Mock success screen  
- [ ] No API key in git  
- [ ] Demo video: consent → scan → elevated path → confirm path  
- [ ] DEV post sections: what you built, how signals drive UI, consent/data  

---

## 9. Out of scope

- Real bank / OPay / Paystack integration  
- Auto-blocking with no override  
- Redirecting money to another account from heart rate  
- Full scam-text AI  
- Medical or legal claims  

---

## 10. Links

| Resource | URL |
|----------|-----|
| Challenge page | https://dev.to/challenges/presage-2026-11-04 |
| SmartSpectra docs | https://smartspectra.presagetech.com/docs |
| Node.js guide | https://smartspectra.presagetech.com/docs/nodejs |
| Android guide | https://smartspectra.presagetech.com/docs/android |
| Presage home | https://presagetech.com/ |

---

**This document is only about PauseBeforePay and what is needed to build it.**
