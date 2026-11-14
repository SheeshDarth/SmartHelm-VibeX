package com.smarthelm.mobile.detection

/**
 * Pure EAR → eye-state classification, shared by [EyeDetector] (live camera) and the offline
 * replay evaluation (`app/src/test/.../eval`), so the evaluation measures exactly the app's logic.
 * No Android dependencies.
 *
 * Thresholds come from [com.smarthelm.mobile.util.Prefs] in the app (per-user calibration);
 * [DEFAULT_EAR_OPEN]/[DEFAULT_EAR_CLOSED] mirror the uncalibrated Prefs defaults.
 */
object EarClassifier {
    const val DEFAULT_EAR_OPEN   = 0.25f
    const val DEFAULT_EAR_CLOSED = 0.20f

    /**
     * @return (eyeState, confidence): "OPEN" above [earOpen], "CLOSED" below [earClosed],
     *   otherwise "UNKNOWN" (a squint never counts as closed).
     */
    fun classify(ear: Float, earOpen: Float, earClosed: Float): Pair<String, Float> = when {
        ear > earOpen   -> Pair("OPEN",   minOf(1f, 0.5f + (ear - earOpen) / 0.15f))
        ear < earClosed -> Pair("CLOSED", minOf(1f, 0.5f + (earClosed - ear) / 0.15f))
        else            -> Pair("UNKNOWN", 0f)
    }
}
