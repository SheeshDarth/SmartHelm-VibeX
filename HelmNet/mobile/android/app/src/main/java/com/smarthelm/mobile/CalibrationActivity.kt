package com.smarthelm.mobile

import android.content.Intent
import android.os.SystemClock
import com.smarthelm.mobile.service.DetectionService
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.smarthelm.mobile.databinding.ActivityCalibrationBinding
import com.smarthelm.mobile.detection.EyeDetector
import com.smarthelm.mobile.util.Prefs
import java.util.concurrent.Executors

/**
 * Guides the rider through a 100-frame EAR calibration.
 *
 * Shows a live PreviewView so the rider can align their face before starting.
 * Collects EAR values → computes baseline → saves earOpen = baseline * 0.80,
 * earClosed = baseline * 0.65 to Prefs.
 */
class CalibrationActivity : AppCompatActivity() {

    companion object {
        private const val TAG            = "Calibration"
        private const val FRAMES_NEEDED  = 100
        private const val NO_FACE_HINT_MS = 3_000L
    }

    private lateinit var binding: ActivityCalibrationBinding
    private lateinit var eyeDetector: EyeDetector
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val earSamples  = mutableListOf<Float>()
    private var collecting  = false
    private var lastFaceMs  = 0L
    private var hintShown   = false

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        eyeDetector = EyeDetector(this)

        binding.btnStartCalibration.setOnClickListener {
            if (!collecting) startCollection()
        }
    }

    override fun onResume() {
        super.onResume()
        startCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        eyeDetector.release()
    }

    // ------------------------------------------------------------------
    // Camera
    // ------------------------------------------------------------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val cameraProvider = future.get()

                // Preview — shows the rider's face so they can align
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(binding.previewView.surfaceProvider)

                // Analysis — runs EAR collection when collecting=true
                val resSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(
                        Size(480, 360),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )).build()

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(cameraExecutor, ::calibrateFrame)

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                toast("Camera failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ------------------------------------------------------------------
    // Calibration logic
    // ------------------------------------------------------------------

    private fun startCollection() {
        earSamples.clear()
        collecting = true
        binding.btnStartCalibration.isEnabled = false
        binding.progressBar.visibility        = View.VISIBLE
        binding.tvInstructions.text           = "Keep your eyes open naturally…"
        lastFaceMs = SystemClock.elapsedRealtime()
        hintShown  = false
    }

    private fun calibrateFrame(imageProxy: ImageProxy) {
        try {
            if (!collecting) return

            val tsMs  = imageProxy.imageInfo.timestamp / 1_000_000L
            val mpImage = BitmapImageBuilder(imageProxy.toBitmap()).build()

            val result = eyeDetector.process(mpImage, tsMs)

            if (!result.faceDetected) {
                // Tell the driver why nothing is happening instead of sitting at 0 %.
                if (!hintShown && SystemClock.elapsedRealtime() - lastFaceMs > NO_FACE_HINT_MS) {
                    hintShown = true
                    runOnUiThread {
                        binding.tvInstructions.text =
                            "Can't see your face. Hold the phone at eye level, in good light, " +
                            "with your whole face in the picture."
                    }
                }
            } else {
                lastFaceMs = SystemClock.elapsedRealtime()
                if (hintShown) {
                    hintShown = false
                    runOnUiThread { binding.tvInstructions.text = "Keep your eyes open naturally…" }
                }
            }

            if (result.faceDetected && result.earSmoothed > 0.1f) {
                earSamples.add(result.earSmoothed)

                val progress = (earSamples.size * 100 / FRAMES_NEEDED)
                runOnUiThread {
                    binding.progressBar.progress = progress
                    binding.tvProgress.text      = "${earSamples.size}/$FRAMES_NEEDED frames"
                }

                if (earSamples.size >= FRAMES_NEEDED) {
                    collecting = false
                    finishCalibration()
                }
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun finishCalibration() {
        if (earSamples.isEmpty()) {
            runOnUiThread {
                toast("Calibration failed — no face detected. Try again.")
                binding.btnStartCalibration.isEnabled = true
                binding.progressBar.visibility        = View.GONE
            }
            return
        }

        val baseline = earSamples.average().toFloat()
        val earOpen   = baseline * 0.80f
        val earClosed = baseline * 0.65f

        Prefs.setEarThresholds(this, earOpen, earClosed)
        Log.i(TAG, "Calibration done: baseline=$baseline open=$earOpen closed=$earClosed")

        runOnUiThread {
            binding.tvInstructions.text = "Calibration complete!\n" +
                "Open threshold: ${"%.3f".format(earOpen)}\n" +
                "Closed threshold: ${"%.3f".format(earClosed)}"
            binding.progressBar.visibility = View.GONE
            binding.btnDone.visibility     = View.VISIBLE
            binding.btnDone.text           = "Start detection"
        }

        // First run: calibration was opened by Start, so go straight into detection.
        binding.btnDone.setOnClickListener {
            if (!Prefs.isDetectionRunning(this)) {
                startForegroundService(Intent(this, DetectionService::class.java))
            }
            finish()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
