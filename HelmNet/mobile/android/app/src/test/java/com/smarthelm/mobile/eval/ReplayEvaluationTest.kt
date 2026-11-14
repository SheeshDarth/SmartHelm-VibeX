package com.smarthelm.mobile.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Accuracy harness for the shipped detection logic.
 *
 * The first three tests always run (synthetic data) and prove the harness itself is correct.
 * [replay_recorded_clips] runs only when SMARTHELM_EVAL_DIR points at a folder containing
 * `<clip>.features.csv` files (from HelmNet/eval/extract_features.py) and a `labels.csv`.
 * It prints the report and writes `report.md` into that folder. See docs/EVALUATION.md.
 */
class ReplayEvaluationTest {

    private val FRAME_MS = 33L   // ~30 fps

    /** EAR trace: eyes open at 0.30, short blinks, and one sustained closure [closeFrom, closeTo). */
    private fun syntheticFrames(totalMs: Long, closeFrom: Long, closeTo: Long): List<FrameFeatures> {
        val out = mutableListOf<FrameFeatures>()
        var t = 0L
        while (t <= totalMs) {
            val inClosure = t in closeFrom until closeTo
            val inBlink = (t % 4_000L) < 150L          // 150 ms blink every 4 s
            val ear = if (inClosure || inBlink) 0.10f else 0.30f
            out += FrameFeatures(tMs = t, face = true, earSmoothed = ear, mar = 0.05f, headPitch = 0.6f)
            t += FRAME_MS
        }
        return out
    }

    @Test fun metrics_count_hits_false_alarms_and_latency() {
        val replays = listOf(
            ClipReplay("a", 60_000, listOf(11_500L, 40_000L)),   // hit at 11.5 s, false alarm at 40 s
            ClipReplay("b", 60_000, emptyList())                  // missed event
        )
        val labels = listOf(
            LabelWindow("a", 10_000, 13_000, "DROWSY"),
            LabelWindow("b", 20_000, 23_000, "DROWSY")
        )
        val r = EvalMetrics.evaluate(replays, labels)
        assertEquals(2, r.drowsyEvents)
        assertEquals(1, r.detectedEvents)
        assertEquals(1, r.falseAlarms)
        assertEquals(listOf(1_500L), r.latenciesMs)
        // non-drowsy time = 2 × 60 s − 2 × (3 s window + 0.5 s early + 3 s late) = 107 s
        assertEquals(107.0 / 3600.0, r.nonDrowsyHours, 1e-9)
    }

    @Test fun replay_catches_sustained_closure_without_false_alarms() {
        val frames = syntheticFrames(totalMs = 40_000, closeFrom = 30_000, closeTo = 33_000)
        val replay = ReplayEngine.replay("synthetic", frames)
        val report = EvalMetrics.evaluate(
            listOf(replay), listOf(LabelWindow("synthetic", 30_000, 33_000, "DROWSY"))
        )
        assertEquals("the 3 s closure must be caught", 1, report.detectedEvents)
        assertEquals("blinks every 4 s must not alarm", 0, report.falseAlarms)
        val latency = report.latenciesMs.single()
        // FatigueScorer's microsleep override fires at 1.5 s of continuous closure
        assertTrue("latency $latency ms should be ~1.5 s", latency in 1_400L..1_700L)
    }

    @Test fun readers_parse_extractor_and_label_formats() {
        val dir = java.nio.file.Files.createTempDirectory("smarthelm-eval").toFile()
        try {
            val feat = File(dir, "clip1.features.csv").apply {
                writeText("t_ms,face,ear_avg,ear_smoothed,mar,head_pitch\n0,1,0.3,0.3,0.05,0.6\n33,0,,0,0,0\n")
            }
            val lab = File(dir, "labels.csv").apply {
                writeText("# comment\nclip,t_start_ms,t_end_ms,label\nclip1,1000,2000,drowsy\nclip2,,,\n")
            }
            val frames = ReplayEngine.readFeatures(feat)
            assertEquals(2, frames.size)
            assertTrue(frames[0].face && !frames[1].face)
            val labels = ReplayEngine.readLabels(lab)
            assertEquals(listOf(LabelWindow("clip1", 1000, 2000, "DROWSY")), labels)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun replay_recorded_clips() {
        val dirPath = System.getenv("SMARTHELM_EVAL_DIR")
        assumeTrue("set SMARTHELM_EVAL_DIR to run the real-clip evaluation", !dirPath.isNullOrBlank())
        val dir = File(dirPath!!)
        val featureFiles = dir.listFiles { f -> f.name.endsWith(".features.csv") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no *.features.csv in $dir", featureFiles.isNotEmpty())
        val labelsFile = File(dir, "labels.csv")
        val labels = if (labelsFile.exists()) ReplayEngine.readLabels(labelsFile) else emptyList()

        val earOpen = System.getenv("SMARTHELM_EAR_OPEN")?.toFloatOrNull()
            ?: com.smarthelm.mobile.detection.EarClassifier.DEFAULT_EAR_OPEN
        val earClosed = System.getenv("SMARTHELM_EAR_CLOSED")?.toFloatOrNull()
            ?: com.smarthelm.mobile.detection.EarClassifier.DEFAULT_EAR_CLOSED

        val replays = featureFiles.map { f ->
            ReplayEngine.replay(f.name.removeSuffix(".features.csv"), ReplayEngine.readFeatures(f), earOpen, earClosed)
        }
        val report = EvalMetrics.evaluate(replays, labels)
        val md = report.toMarkdown() +
            "\nThresholds: EAR open > $earOpen, closed < $earClosed; speed-gating off (speed unknown).\n" +
            "\nPer-clip alert onsets (ms):\n" +
            replays.joinToString("\n") { "- ${it.clip}: ${it.alertOnsetsMs}" } + "\n"
        File(dir, "report.md").writeText(md)
        println(md)
    }
}
