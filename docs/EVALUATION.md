# SmartHelm — detection evaluation protocol

Goal: put **three real numbers** on the slides, measured on the detection logic the app actually
ships, with an honest description of how they were obtained.

| Number | Definition |
|---|---|
| **Recall** | drowsy episodes caught ÷ drowsy episodes labelled |
| **False alarms per hour** | alert onsets outside any drowsy episode ÷ hours of non-drowsy footage |
| **Median alert latency** | seconds from the labelled start of an episode to the alert switching on |

Rules: never invent, round up, or cherry-pick a number. If the dataset floor below is not met,
report the real N and say so on the slide.

## How the harness works

```
video clip ──► HelmNet/eval/extract_features.py ──► <clip>.features.csv
               (MediaPipe FaceLandmarker, same model,                │
                settings, landmarks and EAR/MAR/pitch                ▼
                formulas as EyeDetector.kt)           ReplayEvaluationTest (JVM unit test)
                                                      EarClassifier → PerclosTracker → FatigueScorer
                                                      (the app's real Kotlin code, recorded timestamps)
                                                                     │
                                         labels.csv ────────────────►│──► report.md
```

The decision logic (eye-state thresholds, PERCLOS window, fatigue score, hysteresis, 700 ms
confirmation, yawn gate, microsleep override) is **not** re-implemented anywhere: the replay calls
the same Kotlin classes the app uses. Only the per-frame feature extraction runs in Python, because
MediaPipe's Android API cannot run on a plain JVM.

## Dataset floor

- At least **20 simulated drowsy episodes** and **30 minutes of non-drowsy footage**.
- At least **3 lighting conditions**.
- Only footage of yourself or of volunteers who gave consent. Keep a short signed consent note per
  volunteer **outside** the repository. Do not commit any video.

## Scenarios to record

Mount the phone where it will sit in the cab (dashboard holder, front camera facing the driver),
record with the phone's own camera app at 30 fps, and keep each clip to 1–3 minutes.

| Group | Scenarios |
|---|---|
| Drowsy (label DROWSY) | slow eye closure held 2–4 s; repeated heavy blinks building to a closure; head drop with eyes closing |
| Must NOT alarm | normal blinking; a big yawn (eyes may close briefly); mirror and shoulder checks; glancing down at the phone/meter; talking; squinting into sun |
| Conditions | daylight, dusk, **night with oncoming headlights**, backlit window, **sunglasses**, glasses, different mount angles/distances |

## Labelling

For each drowsy episode note when the eyes start to close and when they reopen, in milliseconds
from the start of the video (play it frame by frame in VLC or similar). Write them to `labels.csv`
using `HelmNet/eval/labels.example.csv` as the template. Clips with no DROWSY rows count entirely
as non-drowsy footage.

## Running it

```bash
# 1. features (Python 3.10+, pip install mediapipe opencv-python)
python HelmNet/eval/extract_features.py clips/*.mp4 \
    --model HelmNet/mobile/android/app/src/main/assets/face_landmarker.task \
    --out-dir eval-run --max-fps 15
cp labels.csv eval-run/

# 2. replay through the app's logic (from HelmNet/mobile/android)
SMARTHELM_EVAL_DIR=/abs/path/to/eval-run ./gradlew testDebugUnitTest --tests "*ReplayEvaluationTest*"
# report: eval-run/report.md
```

- `--max-fps`: set it to the rate the phone actually processes frames (check the app's inference
  rate in a debug build). Different rates change PERCLOS slightly.
- `--rotate 90|180|270` if MediaPipe finds the face in under 80 % of frames because of orientation.
- If you calibrated your eyes in the app, pass the same thresholds:
  `SMARTHELM_EAR_OPEN=0.27 SMARTHELM_EAR_CLOSED=0.21` (defaults: 0.25 / 0.20).

## Known limitations (put these on the slide too)

- **Simulated** drowsiness by healthy volunteers is not real fatigue.
- Small N; no claim about the general population.
- Features come from MediaPipe's Python runtime on recorded video, not the phone's live camera
  pipeline; landmark output can differ slightly between runtimes and with video compression.
- Speed-gating is off in the replay (speed unknown), so the stationary-vehicle suppression is not
  measured here; it is covered by `FatigueScorerTest`.
- Episodes are scored with a 0.5 s early tolerance and 3 s late grace around each labelled window.
