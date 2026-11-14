#!/usr/bin/env python3
"""Extract per-frame eye/mouth/head features from video clips, mirroring the Android app.

Mirrors HelmNet/mobile/android/app/src/main/java/com/smarthelm/mobile/detection/EyeDetector.kt:
  * MediaPipe FaceLandmarker (face_landmarker.task), VIDEO mode, 1 face, all confidences 0.4
  * EAR on the same 6-point eye indices in pixel space, averaged over both eyes
  * 3-frame rolling mean of EAR (the buffer is kept across no-face frames, like the app)
  * MAR (inner lips 13/14 over mouth corners 61/291) and the head-pitch proxy (nose 1 vs eye line)

Eye-state classification and all timing/decision logic are NOT done here. They run in the app's
own Kotlin code (ReplayEvaluationTest), so the evaluation measures the shipped decision logic.

Usage:
  python extract_features.py clip1.mp4 clip2.mp4 --model path/to/face_landmarker.task
Writes <clip>.features.csv next to each video (or into --out-dir).
Columns: t_ms,face,ear_avg,ear_smoothed,mar,head_pitch   (t_ms = ms from the start of the video)
"""
import argparse
import csv
import math
import sys
from collections import deque
from pathlib import Path

import cv2
import mediapipe as mp

LEFT_EYE = (362, 385, 387, 263, 373, 380)
RIGHT_EYE = (33, 160, 158, 133, 153, 144)
LIP_TOP, LIP_BOTTOM, MOUTH_L, MOUTH_R = 13, 14, 61, 291
NOSE_TIP, EYE_OUT_L, EYE_OUT_R = 1, 33, 263
EAR_SMOOTHING_WINDOW = 3
ROTATIONS = {0: None, 90: cv2.ROTATE_90_CLOCKWISE, 180: cv2.ROTATE_180, 270: cv2.ROTATE_90_COUNTERCLOCKWISE}


def _pt(lm, i, w, h):
    return lm[i].x * w, lm[i].y * h


def _dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def ear(lm, idx, w, h):
    p = [_pt(lm, i, w, h) for i in idx]
    return (_dist(p[1], p[5]) + _dist(p[2], p[4])) / (2 * _dist(p[0], p[3]) + 1e-6)


def mar(lm, w, h):
    return _dist(_pt(lm, LIP_TOP, w, h), _pt(lm, LIP_BOTTOM, w, h)) / (
        _dist(_pt(lm, MOUTH_L, w, h), _pt(lm, MOUTH_R, w, h)) + 1e-6)


def head_pitch(lm, w, h):
    nose_y = lm[NOSE_TIP].y * h
    eye_line_y = (lm[EYE_OUT_L].y + lm[EYE_OUT_R].y) / 2 * h
    span = math.hypot((lm[EYE_OUT_R].x - lm[EYE_OUT_L].x) * w, (lm[EYE_OUT_R].y - lm[EYE_OUT_L].y) * h)
    return (nose_y - eye_line_y) / (span + 1e-6)


def extract(video: Path, model: Path, out: Path, max_fps: float | None, rotate: int) -> dict:
    options = mp.tasks.vision.FaceLandmarkerOptions(
        base_options=mp.tasks.BaseOptions(model_asset_path=str(model)),
        running_mode=mp.tasks.vision.RunningMode.VIDEO,
        num_faces=1,
        min_face_detection_confidence=0.4,
        min_face_presence_confidence=0.4,
        min_tracking_confidence=0.4,
    )
    cap = cv2.VideoCapture(str(video))
    if not cap.isOpened():
        raise SystemExit(f"cannot open video: {video}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    min_gap_ms = 1000.0 / max_fps if max_fps else 0.0

    buf = deque(maxlen=EAR_SMOOTHING_WINDOW)
    rows = faces = 0
    idx = 0
    last_t = -1
    last_kept = -1e12
    with mp.tasks.vision.FaceLandmarker.create_from_options(options) as landmarker, \
            open(out, "w", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["t_ms", "face", "ear_avg", "ear_smoothed", "mar", "head_pitch"])
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            # Presentation timestamp when the backend provides it (phone videos are often
            # variable-frame-rate); otherwise frame index / fps. Must be strictly increasing.
            pos = cap.get(cv2.CAP_PROP_POS_MSEC)
            t = int(round(pos)) if pos and pos > 0 else int(round(idx * 1000.0 / fps))
            idx += 1
            if t <= last_t:
                t = last_t + 1
            last_t = t
            if min_gap_ms and t - last_kept < min_gap_ms:
                continue
            last_kept = t

            if ROTATIONS.get(rotate) is not None:
                frame = cv2.rotate(frame, ROTATIONS[rotate])
            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            h, w = rgb.shape[:2]
            result = landmarker.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb), t)
            if result.face_landmarks:
                lm = result.face_landmarks[0]
                e = (ear(lm, LEFT_EYE, w, h) + ear(lm, RIGHT_EYE, w, h)) / 2
                buf.append(e)
                es = sum(buf) / len(buf)
                writer.writerow([t, 1, f"{e:.5f}", f"{es:.5f}", f"{mar(lm, w, h):.5f}", f"{head_pitch(lm, w, h):.5f}"])
                faces += 1
            else:
                writer.writerow([t, 0, "", "0", "0", "0"])
            rows += 1
    cap.release()
    return {"frames": rows, "face_frames": faces, "duration_s": last_t / 1000.0, "source_fps": fps}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("videos", nargs="+", type=Path)
    ap.add_argument("--model", type=Path, required=True, help="path to face_landmarker.task (the app's model)")
    ap.add_argument("--out-dir", type=Path, help="where to write <clip>.features.csv (default: next to each video)")
    ap.add_argument("--max-fps", type=float, help="drop frames to at most this rate (match the phone's processing rate)")
    ap.add_argument("--rotate", type=int, choices=sorted(ROTATIONS), default=0, help="rotate frames clockwise first")
    args = ap.parse_args()

    if not args.model.is_file():
        print(f"model not found: {args.model}", file=sys.stderr)
        return 2
    for video in args.videos:
        out_dir = args.out_dir or video.parent
        out_dir.mkdir(parents=True, exist_ok=True)
        out = out_dir / f"{video.stem}.features.csv"
        stats = extract(video, args.model, out, args.max_fps, args.rotate)
        pct = 100.0 * stats["face_frames"] / stats["frames"] if stats["frames"] else 0.0
        print(f"{video.name}: {stats['frames']} frames, face in {pct:.0f}%, "
              f"{stats['duration_s']:.1f} s @ {stats['source_fps']:.1f} fps -> {out}")
        if pct < 80:
            print(f"  WARNING: face found in only {pct:.0f}% of frames - check framing/lighting/--rotate",
                  file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
