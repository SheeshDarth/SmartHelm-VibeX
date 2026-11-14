package com.smarthelm.mobile.eval

import com.smarthelm.mobile.detection.DetectionResult
import com.smarthelm.mobile.detection.EarClassifier
import com.smarthelm.mobile.detection.FatigueScorer
import com.smarthelm.mobile.detection.PerclosTracker
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** One frame of features written by `HelmNet/eval/extract_features.py`. */
data class FrameFeatures(
    val tMs: Long,
    val face: Boolean,
    val earSmoothed: Float,
    val mar: Float,
    val headPitch: Float
)

/** A hand-labelled interval of a clip. Only label == "DROWSY" is scored as a drowsy event. */
data class LabelWindow(val clip: String, val startMs: Long, val endMs: Long, val label: String)

/** Result of replaying one clip: when the app's alert switched on (rising edges). */
data class ClipReplay(val clip: String, val durationMs: Long, val alertOnsetsMs: List<Long>)

/**
 * Replays extracted video features through the app's REAL decision logic
 * ([EarClassifier] → [PerclosTracker] → [FatigueScorer]) with the recorded timestamps,
 * exactly as DetectionService wires them per camera frame.
 */
object ReplayEngine {

    /**
     * @param speedKmph -1 = unknown speed, i.e. no speed-gating (the app's behaviour without a GPS
     *   fix). Clips are not recorded while driving, so gating would otherwise hide every alert.
     */
    fun replay(
        clip: String,
        frames: List<FrameFeatures>,
        earOpen: Float = EarClassifier.DEFAULT_EAR_OPEN,
        earClosed: Float = EarClassifier.DEFAULT_EAR_CLOSED,
        speedKmph: Float = -1f
    ): ClipReplay {
        val perclos = PerclosTracker()
        val scorer = FatigueScorer()
        val onsets = mutableListOf<Long>()
        var prev = false
        for (f in frames) {
            val det = if (f.face) {
                val (state, conf) = EarClassifier.classify(f.earSmoothed, earOpen, earClosed)
                DetectionResult(
                    eyeState = state, confidence = conf, earSmoothed = f.earSmoothed,
                    faceDetected = true, mar = f.mar, headPitch = f.headPitch
                )
            } else {
                // Same as EyeDetector when MediaPipe finds no face.
                DetectionResult(eyeState = "UNKNOWN", confidence = 0f, earSmoothed = 0f, faceDetected = false)
            }
            val p = perclos.update(det.eyeState, f.tMs)
            val r = scorer.update(det, p, speedKmph, f.tMs)
            if (r.alertActive && !prev) onsets += f.tMs
            prev = r.alertActive
        }
        val duration = if (frames.isEmpty()) 0L else frames.last().tMs - frames.first().tMs
        return ClipReplay(clip, duration, onsets)
    }

    /** Reads a `<clip>.features.csv` file (header: t_ms,face,ear_avg,ear_smoothed,mar,head_pitch). */
    fun readFeatures(file: File): List<FrameFeatures> {
        val lines = file.readLines().filter { it.isNotBlank() }
        require(lines.isNotEmpty()) { "empty features file: $file" }
        val header = lines.first().split(',').map { it.trim() }
        fun col(name: String) = header.indexOf(name).also { require(it >= 0) { "missing column '$name' in $file" } }
        val iT = col("t_ms"); val iFace = col("face"); val iEar = col("ear_smoothed")
        val iMar = col("mar"); val iPitch = col("head_pitch")
        return lines.drop(1).map { line ->
            val c = line.split(',')
            FrameFeatures(
                tMs = c[iT].trim().toLong(),
                face = c[iFace].trim() == "1",
                earSmoothed = c[iEar].trim().toFloatOrNull() ?: 0f,
                mar = c[iMar].trim().toFloatOrNull() ?: 0f,
                headPitch = c[iPitch].trim().toFloatOrNull() ?: 0f
            )
        }
    }

    /** Reads `labels.csv` (clip,t_start_ms,t_end_ms,label). Skips '#' comments and rows with no label. */
    fun readLabels(file: File): List<LabelWindow> =
        file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("clip,") }
            .mapNotNull { line ->
                val c = line.split(',').map { it.trim() }
                if (c.size < 4 || c[3].isEmpty() || c[1].isEmpty() || c[2].isEmpty()) null
                else LabelWindow(c[0], c[1].toLong(), c[2].toLong(), c[3].uppercase())
            }
}

/** Headline numbers for the deck. All three come from the same replay; nothing is estimated. */
data class EvalReport(
    val clips: Int,
    val totalMinutes: Double,
    val drowsyEvents: Int,
    val detectedEvents: Int,
    val falseAlarms: Int,
    val nonDrowsyHours: Double,
    val latenciesMs: List<Long>
) {
    val recall: Double get() = if (drowsyEvents == 0) Double.NaN else detectedEvents.toDouble() / drowsyEvents
    val falseAlarmsPerHour: Double get() = if (nonDrowsyHours <= 0.0) Double.NaN else falseAlarms / nonDrowsyHours
    val medianLatencyMs: Long? get() = latenciesMs.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    fun toMarkdown(): String = buildString {
        appendLine("# SmartHelm replay evaluation")
        appendLine()
        appendLine("| Metric | Value |")
        appendLine("|---|---|")
        appendLine("| Clips | $clips (${"%.1f".format(totalMinutes)} min) |")
        appendLine("| Drowsy events detected (recall) | $detectedEvents / $drowsyEvents" +
            (if (drowsyEvents > 0) " (${"%.0f".format(recall * 100)}%)" else "") + " |")
        appendLine("| False alarms | $falseAlarms in ${"%.2f".format(nonDrowsyHours)} h of non-drowsy footage" +
            (if (nonDrowsyHours > 0) " (${"%.1f".format(falseAlarmsPerHour)} per hour)" else "") + " |")
        appendLine("| Median alert latency | " + (medianLatencyMs?.let { "${"%.2f".format(it / 1000.0)} s" } ?: "n/a") + " |")
        appendLine()
        appendLine("Simulated drowsiness on a small set of clips; not a clinical or real-fatigue validation.")
    }
}

object EvalMetrics {
    /** An alert up to this long after a drowsy window ends still counts as catching it. */
    const val LATE_GRACE_MS = 3_000L
    /** Tolerance for labels placed slightly late (alert just before the labelled start). */
    const val EARLY_TOLERANCE_MS = 500L

    fun evaluate(
        replays: List<ClipReplay>,
        labels: List<LabelWindow>,
        lateGraceMs: Long = LATE_GRACE_MS,
        earlyToleranceMs: Long = EARLY_TOLERANCE_MS
    ): EvalReport {
        var drowsy = 0; var detected = 0; var fa = 0
        var nonDrowsyMs = 0L; var totalMs = 0L
        val latencies = mutableListOf<Long>()
        for (r in replays) {
            totalMs += r.durationMs
            val windows = labels.filter { it.clip == r.clip && it.label == "DROWSY" }
            val zones = windows.map { (it.startMs - earlyToleranceMs) to (it.endMs + lateGraceMs) }
            for (w in windows) {
                drowsy++
                val hit = r.alertOnsetsMs.firstOrNull { it >= w.startMs - earlyToleranceMs && it <= w.endMs + lateGraceMs }
                if (hit != null) { detected++; latencies += max(0L, hit - w.startMs) }
            }
            fa += r.alertOnsetsMs.count { t -> zones.none { (a, b) -> t in a..b } }
            val covered = zones.sumOf { (a, b) -> max(0L, min(b, r.durationMs) - max(a, 0L)) }
            nonDrowsyMs += max(0L, r.durationMs - covered)
        }
        return EvalReport(
            clips = replays.size,
            totalMinutes = totalMs / 60_000.0,
            drowsyEvents = drowsy,
            detectedEvents = detected,
            falseAlarms = fa,
            nonDrowsyHours = nonDrowsyMs / 3_600_000.0,
            latenciesMs = latencies
        )
    }
}
