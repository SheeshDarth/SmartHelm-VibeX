package com.smarthelm.mobile.alert

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.smarthelm.mobile.R
import com.smarthelm.mobile.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Manages all alert output: a sustained alarm (sound + vibration) and an SMS to the fleet
 * manager / emergency contact sent from the driver's own SIM.
 *
 * Alarm design (robust while another app — e.g. Google Maps — is in the foreground):
 *   - A looping MediaPlayer on the ALARM stream so it keeps buzzing the whole time the
 *     rider is drowsy and stops the instant they recover (proper alarm, not a one-shot).
 *   - Requests transient audio focus so the alarm ducks Maps' navigation voice and routes
 *     to the active output, including a Bluetooth helmet headset.
 *   - Raising the ALARM volume is isolated in its own try/catch: under Do Not Disturb /
 *     Driving Mode that call throws a SecurityException, and it must NOT abort playback.
 *     The ALARM stream still sounds under DND, so the alarm is heard regardless.
 *
 * SMS path:
 *   trigger() → (after 15 s sustained, if SMS is enabled) sendViaSim() → SmsManager.
 *
 * trigger()/clear() are invoked from the single camera-analysis thread, so start/stop
 * ordering is deterministic; the actual audio work runs on a dedicated single-thread
 * executor so it never blocks frame analysis and start/stop can't race.
 */
class AlertManager(private val context: Context) {

    companion object {
        private const val TAG                  = "AlertManager"
        private const val SMS_INITIAL_DELAY_MS = 15_000L          // 15 s of sustained alert before first SMS
        private const val SMS_COOLDOWN_MS      = 5 * 60 * 1_000L  // then at most once every 5 min
    }

    private val scope         = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var alertActive  = false
    @Volatile private var lastSmsMs    = 0L
    @Volatile private var alertStartMs = 0L   // when the current alert episode began
    @Volatile private var alarmPlaying = false
    @Volatile private var lastLocation: Pair<Double, Double>? = null

    private val vibrator     = context.getSystemService(Vibrator::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val alarmAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var mediaPlayer:  MediaPlayer?      = null
    private var focusRequest: AudioFocusRequest? = null

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    fun trigger() {
        alertActive = true
        startAlarm()

        // ── SMS from the phone's SIM ─────────────────────────────────
        val now = SystemClock.elapsedRealtime()
        if (alertStartMs == 0L) alertStartMs = now
        val sinceStart = now - alertStartMs
        val sinceLast  = now - lastSmsMs
        // First SMS fires after 15 s of sustained alerting; repeats at most once every 5 min.
        if (Prefs.isSmsEnabled(context) &&
            sinceStart >= SMS_INITIAL_DELAY_MS && (lastSmsMs == 0L || sinceLast >= SMS_COOLDOWN_MS)) {
            val emergency = Prefs.getEmergencyContact(context).trim()
            val fleetMgr  = Prefs.getFleetManagerPhone(context).trim()
            val recipients = buildSet {
                if (emergency.isNotBlank()) add(emergency)
                if (fleetMgr.isNotBlank())  add(fleetMgr)
            }
            if (recipients.isEmpty()) {
                Log.w(TAG, "SMS skipped: no recipients configured")
            } else {
                lastSmsMs = now
                val riderName = Prefs.getRiderName(context).ifBlank { "the rider" }
                val loc = lastLocation
                scope.launch { sendViaSim(recipients.toList(), riderName, loc) }
            }
        }
    }

    fun clear() {
        alertActive  = false
        alertStartMs = 0L   // reset so the next episode gets a fresh 15-second window
        stopAlarm()
    }

    fun isActive() = alertActive

    /** Latest GPS fix, included in the alert SMS as a map link. */
    fun setLastLocation(lat: Double, lng: Double) { lastLocation = Pair(lat, lng) }

    fun release() {
        stopAlarm()
        scope.cancel()
        audioExecutor.shutdown()
    }

    // ------------------------------------------------------------------
    // Alarm — looping sound + vibration, audio-focus aware, DND-safe
    // ------------------------------------------------------------------

    private fun startAlarm() {
        if (alarmPlaying) return        // already buzzing — trigger() is called every frame
        alarmPlaying = true
        audioExecutor.execute {
            requestAlarmFocus()

            // Raising ALARM volume can throw SecurityException under Do Not Disturb /
            // Driving Mode. Isolate it — the alarm stream still plays, just not louder.
            try {
                val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 7
                audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't raise alarm volume (DND / driving mode?): ${e.message}")
            }

            // Looping alarm sound on the ALARM stream — sounds even on silent/DND.
            try {
                val afd = context.resources.openRawResourceFd(R.raw.alert)
                val mp  = MediaPlayer()
                mp.setAudioAttributes(alarmAttributes)
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                mp.isLooping = true
                mp.setVolume(1f, 1f)
                mp.prepare()
                mp.start()
                mediaPlayer = mp
                Log.i(TAG, "Alarm started")
            } catch (e: Exception) {
                Log.e(TAG, "Alarm playback failed", e)
                FirebaseCrashlytics.getInstance().recordException(e)
            }

            // Repeating buzz (repeat from index 0) until stopAlarm() cancels it.
            try {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 250), 0))
            } catch (e: Exception) {
                Log.w(TAG, "Vibrate failed: ${e.message}")
            }
        }
    }

    private fun stopAlarm() {
        if (!alarmPlaying) return
        alarmPlaying = false
        audioExecutor.execute {
            try {
                mediaPlayer?.let { if (it.isPlaying) it.stop(); it.release() }
            } catch (e: Exception) {
                Log.w(TAG, "Alarm stop failed: ${e.message}")
            }
            mediaPlayer = null
            try { vibrator?.cancel() } catch (_: Exception) {}
            abandonAlarmFocus()
        }
    }

    private fun requestAlarmFocus() {
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(alarmAttributes)
                .setOnAudioFocusChangeListener { /* alarms are insistent — ignore changes */ }
                .build()
            focusRequest = req
            audioManager?.requestAudioFocus(req)
        } catch (e: Exception) {
            Log.w(TAG, "Audio focus request failed: ${e.message}")
        }
    }

    private fun abandonAlarmFocus() {
        try { focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) } } catch (_: Exception) {}
        focusRequest = null
    }

    // ------------------------------------------------------------------
    // SMS from the driver's own SIM
    // ------------------------------------------------------------------

    /**
     * Sends the alert through Android's SmsManager on the phone's own SIM. No gateway or
     * credentials are involved, and it works on a cellular signal even without mobile data.
     * Without SEND_SMS permission it only logs: the alarm and dashboard alert still work.
     */
    private fun sendViaSim(numbers: List<String>, riderName: String, location: Pair<Double, Double>?) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "SMS skipped: SEND_SMS permission not granted")
            return
        }
        val sms = context.getSystemService(SmsManager::class.java)
        if (sms == null) {
            Log.w(TAG, "SMS skipped: no SmsManager on this device")
            return
        }
        val message = buildAlertSms(riderName, location)
        for (phone in numbers) {
            try {
                sms.sendMultipartTextMessage(phone, null, sms.divideMessage(message), null, null)
                Log.i(TAG, "Alert SMS handed to the SIM for $phone")
            } catch (e: Exception) {
                Log.w(TAG, "Alert SMS failed for $phone: ${e.message}")
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }
}

/** Alert text. Kept out of the class so it can be unit-tested without Android. */
internal fun buildAlertSms(riderName: String, location: Pair<Double, Double>?): String {
    val base = "SmartHelm alert: $riderName is showing signs of drowsiness while driving. " +
               "Please call them now."
    return if (location == null) base
           else base + " Last location: https://maps.google.com/?q=" +
                String.format(java.util.Locale.US, "%.5f,%.5f", location.first, location.second)
}
