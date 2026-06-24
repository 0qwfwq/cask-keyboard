package com.example.cask.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

/**
 * A keyboard (an [android.inputmethodservice.InputMethodService]) has no Activity, so it can't ask
 * for the runtime mic permission itself. When the user first tries voice typing without it,
 * [com.example.cask.CaskKeyboardService] launches this tiny, invisible activity to run the standard
 * permission prompt, then it finishes immediately. After granting, the user just holds the spacebar
 * again to dictate.
 */
class VoicePermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Toast.makeText(
            this,
            if (granted) "Microphone enabled — hold the spacebar to dictate."
            else "Voice typing needs microphone access.",
            Toast.LENGTH_SHORT,
        ).show()
        finish()
    }

    /** Convenience for the keyboard service: an intent that launches this prompt from a non-activity. */
    companion object {
        private const val REQUEST_CODE = 7321

        fun intent(context: android.content.Context): Intent =
            Intent(context, VoicePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
