package io.github.avikulin.thud

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Kiosk home / launcher activity.
 *
 * Declared as the device HOME so the base Android launcher never shows: this
 * activity is a full-screen, immersive Kodiak backdrop ("MackTrack"), and the
 * HUD is drawn on top of it by [HUDService] as an overlay. It intentionally does
 * NOT finish — it stays as the backdrop. Tapping the backdrop re-shows the HUD
 * (e.g. after the user closed it or returned from another app via Home).
 */
class MainActivity : AppCompatActivity() {

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            checkNotificationPermissionAndShow()
        } else {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Start HUD regardless of notification permission result
        // The notification is nice-to-have but not required
        showHud()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersive()
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.kioskRoot)?.setOnClickListener { showHud() }
        checkOverlayPermissionAndShow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing Home returns here; keep the HUD up and bars hidden.
        enterImmersive()
        if (Settings.canDrawOverlays(this)) showHud()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun checkOverlayPermissionAndShow() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            overlayPermissionLauncher.launch(intent)
            return
        }
        checkNotificationPermissionAndShow()
    }

    private fun checkNotificationPermissionAndShow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        showHud()
    }

    /** Start (or re-show) the HUD overlay. Does not finish — this stays as the backdrop. */
    private fun showHud() {
        val intent = Intent(this, HUDService::class.java).apply {
            action = HUDService.ACTION_SHOW_HUD
        }
        startForegroundService(intent)
    }
}
