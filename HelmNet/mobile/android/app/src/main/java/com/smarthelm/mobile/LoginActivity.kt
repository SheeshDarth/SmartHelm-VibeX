package com.smarthelm.mobile

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.smarthelm.mobile.util.Prefs

/**
 * Entry point. Drivers do not log in: the app opens an anonymous Firebase session (which the
 * Firestore rules bind the driver's document to) and goes straight to the one-screen sign-up,
 * or to the main screen once sign-up is done.
 *
 * There is deliberately no OTP: it needed SMS-gateway credentials inside the APK and a
 * verified number, which made the app impossible to try. The driver's name and phone are
 * self-declared; the fleet code is what links a driver to a manager.
 */
class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) proceed() else signIn(auth)
    }

    private fun signIn(auth: FirebaseAuth) {
        auth.signInAnonymously()
            .addOnSuccessListener { proceed() }
            .addOnFailureListener { e ->
                AlertDialog.Builder(this)
                    .setTitle("Can't reach SmartHelm")
                    .setMessage("Check your internet connection and try again.\n\n(${e.message})")
                    .setCancelable(false)
                    .setPositiveButton("Try again") { _, _ -> signIn(auth) }
                    .setNegativeButton("Close") { _, _ -> finish() }
                    .show()
            }
    }

    private fun proceed() {
        startActivity(
            if (!Prefs.isSetupComplete(this)) Intent(this, SetupActivity::class.java)
            else Intent(this, MainActivity::class.java)
        )
        finish()
    }
}
