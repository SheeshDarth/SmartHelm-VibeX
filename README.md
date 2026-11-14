# SmartHelm

Driver-fatigue detection from an ordinary Android phone, with a live fleet-manager dashboard.
The phone's front camera watches the driver's eyes with MediaPipe, fires an on-device alarm when
drowsiness is detected, and streams a privacy-reduced status to a dashboard so a fleet manager can
see who needs attention. No extra hardware is required.

> **Scope of this repository's working product: the Android app + the web fleet dashboard.**
> A Raspberry Pi smart-helmet variant exists in the code but is **paused** (no hardware available)
> and is not part of the demo. See [Status](#status--whats-built-and-whats-not).

---

## Status — what's built and what's not

| Area | State |
|---|---|
| Android driver app (CameraX + MediaPipe FaceLandmarker, eye-closure/PERCLOS + fatigue scoring, on-device alarm + vibration, GPS/trip, Firestore reporting, SMS alert) | **Built** |
| Fleet-manager web dashboard (phone-OTP login, fleet code, live rider tiles, map, eye-strip view, alert notifications) | **Built** |
| Firestore security rules (owner/fleet-scoped) with emulator tests | **Built** — 21 tests in `HelmNet/fleet-dashboard/rules-tests` |
| Detection accuracy (recall, false alarms/hour, alert latency) | **Not yet measured — no accuracy figures are published.** |
| Raspberry Pi helmet, ESP32-CAM, MAX30102 heart-rate/SpO2, SIM800L GSM fallback | **Paused / planned — not built or tested in the current cycle** |
| Hindi / regional-language alerts | Planned |

Not verified: behaviour on a real moving vehicle, night driving, sunglasses, and phone-mount angles.

---

## How it works

```
┌───────────────────────────────────┐
│   Driver phone (Android app)      │
│   Front camera → MediaPipe        │
│   Eye closure + PERCLOS + fatigue │
│   score → on-device alarm         │
│   OTP checked on device, then an  │
│   anonymous Firebase session      │
└───────────────┬───────────────────┘
                │ eye state, PERCLOS, fatigue score, GPS,
                │ 180×72 eye-strip JPEG (no full face)
                ▼
        Firebase Firestore  riders/{deviceId}   (owner-only; see Security)
                │
                ▼
   ┌─────────────────────────────┐        SMS alert after a sustained
   │   Fleet dashboard (browser) │        alarm: sent by the app straight
   │   manager signs in by phone │        to Twilio (see Credentials)
   │   OTP; sees ONLY their fleet│
   └─────────────────────────────┘
```

Drivers join a fleet by entering the manager's 6-character fleet code (derived from the manager's
phone number) during app setup.

---

## Repository structure

```
HelmNet/
├── mobile/android/          # Android app (Kotlin)
│   └── app/src/main/java/com/smarthelm/mobile/
│       ├── detection/       # EyeDetector, PerclosTracker, FatigueScorer, Yawn/Blink/HeadNod trackers
│       ├── cloud/           # FirestoreReporter
│       ├── alert/           # AlertManager — alarm, vibration, SMS via Twilio
│       ├── service/         # DetectionService — foreground service
│       └── overlay/         # OverlayManager — floating widget
├── fleet-dashboard/         # Web fleet-manager dashboard
│   ├── index.html           # the dashboard
│   ├── firestore.rules      # security rules
│   ├── rules-tests/         # Firestore emulator tests for the rules
│   └── functions/           # OPTIONAL Cloud Function (MSG91 SMS backup — not the primary path)
└── smarthelm/               # PAUSED: Raspberry Pi helmet backend + ESP32-CAM firmware
```

---

## Quick start

### Android app

1. Download `face_landmarker.task` (~3.6 MB) from
   [MediaPipe Models](https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task)
   → place it at `HelmNet/mobile/android/app/src/main/assets/`.
2. Copy `google-services.json.example` → `google-services.json` and fill in your Firebase project.
3. Copy `local.properties.example` → `local.properties`. Leave the `TWILIO_*` fields empty unless
   you want real SMS (see [Credentials](#credentials-and-security-notes)).
4. Build: `.\gradlew.bat assembleDebug` (from `HelmNet/mobile/android`).

### Fleet dashboard

```bash
cd HelmNet/fleet-dashboard
cp firebase.config.example.js firebase.config.js   # fill in your Firebase web config
firebase serve                                       # or: firebase deploy --only hosting
```

Managers sign in with Firebase Phone Auth, so enable the Phone provider in your Firebase project.

### Firestore rules tests

```bash
cd HelmNet/fleet-dashboard/rules-tests
npm install
npm test        # needs the Firebase CLI and Java 21+ (the emulator runs the rules)
```

On some Windows builds JDK 25 fails to start the emulator with "Unable to establish loopback
connection"; setting `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/Windows/Temp` works around it.

Other tests: `FatigueScorerTest.kt` (Android unit test) and `smarthelm/tests/test_perclos.py`.

---

## Privacy

What stays on the phone:
- Raw video frames and full face images
- The full face-landmark mesh

What is sent to Firestore:
- Eye state (OPEN / CLOSED), PERCLOS %, fatigue score and level
- A small eye-region JPEG strip (180×72 px) and a few eye-landmark points
- GPS location, speed, trip state, and alert events (timestamp + type)
- Rider name, phone number, fleet code, and the fleet's emergency-contact number

This design minimises personal data; it has **not** had a legal review, so no formal data-protection
compliance claim is made.

---

## Credentials and security notes

- **Twilio credentials are compiled into the APK.** `app/build.gradle.kts` copies `TWILIO_*` from
  `local.properties` into `BuildConfig` and the app calls Twilio directly. Anyone who unzips an APK
  built with real values can read them. **Any APK shared outside your own devices must be built with
  the `TWILIO_*` fields empty** (SMS is then skipped). Moving SMS into a server-side function is the
  proper fix and is not done yet.
- **Rider login:** a phone-number OTP is generated and checked **on the device**, then the app signs
  in to Firebase anonymously. In debug builds only, the OTP `000000` is accepted. This is a
  convenience for development, not strong authentication.
- **Firestore rules:** a rider document can be read/written only by the anonymous uid that created
  it (`ownerUid`); a manager can read only riders whose `managerId` is a fleet they own (verified
  phone login). Listing all riders or all fleets is denied. Known gaps that rules alone cannot fix:
  a fleet code can be squatted before its real manager registers it, and a rider can put any phone
  number in the emergency-contact field. Both need server-side checks.
- **Deploying the rules** requires the new app build and dashboard first: older app builds do not
  write `ownerUid` and will be denied until reinstalled.
- The optional Cloud Function in `fleet-dashboard/functions` (MSG91) is **not** the primary SMS path
  and must not be deployed alongside a Twilio-enabled app unless duplicate SMS are acceptable.

---

## Hardware track (paused)

An earlier version targeted a Raspberry Pi smart helmet. The code is kept under `HelmNet/smarthelm/`
for reference, but the hardware is not currently available, so none of it is tested or demonstrated.

| Component | Intended purpose |
|---|---|
| Raspberry Pi 4 + Pi Camera | Compute + driver camera |
| ESP32-CAM × 2 | Road-facing cameras |
| MAX30102 | Heart rate + SpO2 |
| SIM800L | GSM SMS fallback |

---

## License

MIT — see [LICENSE](LICENSE)
