package com.example.cask

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.example.cask.engine.PersonalStore
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * Host / setup activity. The Flutter UI lives here and talks to the platform over [CHANNEL] to:
 *  - open the system "On-screen keyboard" settings (where the user enables Cask),
 *  - show the keyboard picker (to make Cask the active keyboard),
 *  - report whether Cask is currently enabled / selected.
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.example.cask/keyboard"
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "openImeSettings" -> {
                        startActivity(
                            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        result.success(true)
                    }
                    "showImePicker" -> {
                        imm().showInputMethodPicker()
                        result.success(true)
                    }
                    "isKeyboardEnabled" -> result.success(isKeyboardEnabled())
                    "isKeyboardSelected" -> result.success(isKeyboardSelected())
                    "ingestCorpus" -> {
                        // "Personalise from your own writing": fold the supplied text into the on-device
                        // personal model (vocabulary + bi/trigram context). Heaviest-first off the UI
                        // thread; the keyboard reads the updated store next time it (re)starts.
                        val text = call.argument<String>("text").orEmpty()
                        if (text.isBlank()) {
                            result.success(0)
                        } else {
                            Thread {
                                val n = runCatching { PersonalStore.load(this).ingest(text) }.getOrDefault(0)
                                runOnUiThread { result.success(n) }
                            }.start()
                        }
                    }
                    "getHaptics" -> {
                        val p = getSharedPreferences(Haptics.PREFS, Context.MODE_PRIVATE)
                        result.success(
                            mapOf(
                                "enabled" to p.getBoolean(Haptics.KEY_ENABLED, Haptics.DEFAULT_ENABLED),
                                "strength" to p.getInt(Haptics.KEY_STRENGTH, Haptics.DEFAULT_STRENGTH),
                            )
                        )
                    }
                    "setHaptics" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: Haptics.DEFAULT_ENABLED
                        val strength = call.argument<Int>("strength") ?: Haptics.DEFAULT_STRENGTH
                        getSharedPreferences(Haptics.PREFS, Context.MODE_PRIVATE).edit()
                            .putBoolean(Haptics.KEY_ENABLED, enabled)
                            .putInt(Haptics.KEY_STRENGTH, strength.coerceIn(0, 100))
                            .apply()
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun imm() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    /** True once the user has ticked Cask in the on-screen keyboard settings. */
    private fun isKeyboardEnabled(): Boolean =
        imm().enabledInputMethodList.any { it.packageName == packageName }

    /** True when Cask is the currently active keyboard. */
    private fun isKeyboardSelected(): Boolean {
        val current = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
        return current?.startsWith(packageName) == true
    }
}
