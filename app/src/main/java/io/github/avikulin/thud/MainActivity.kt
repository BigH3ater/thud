package io.github.avikulin.thud

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import java.io.File

/**
 * Kiosk home / launcher activity.
 *
 * Declared as the device HOME so the base Android launcher never shows: this
 * activity is a full-screen, immersive Kodiak backdrop ("MackTrack") with a single
 * Apps button that opens a Watch/Listen/Read launcher. The HUD is drawn on top by
 * [HUDService] as an overlay. It intentionally does NOT finish — it stays as the
 * backdrop. Tapping empty backdrop re-shows the HUD.
 */
class MainActivity : AppCompatActivity() {

    // Default launch targets. Override per-install without a rebuild via
    // files/kdk-launcher.conf (lines "key=url:https://…" or "key=pkg:com.x.y").
    // The console's WAN is blocked at UniFi, so only self-hosted LAN apps are
    // offered (jellyfin/abs/calibre on kmkdp.com); the public streaming tiles were
    // removed because they can never reach the internet.
    private val launchDefaults = mapOf(
        "jellyfin" to "url:https://jellyfin.kmkdp.com",
        "abs" to "url:https://audiobookshelf.kmkdp.com",
        "calibre" to "url:https://calibre.kmkdp.com"
    )

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
        // Start HUD regardless of notification permission result (nice-to-have).
        showHud()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersive()
        setContentView(R.layout.activity_main)
        wireLauncher()
        wireControlBar()
        checkOverlayPermissionAndShow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing Home returns here; keep the HUD up and bars hidden.
        enterImmersive()
        findViewById<View>(R.id.launcherOverlay)?.visibility = View.GONE
        if (Settings.canDrawOverlays(this)) showHud()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
        // Returning to the MackTrack home re-shows the HUD (it is hidden while a
        // launched app is in the foreground so it doesn't cover the video).
        if (Settings.canDrawOverlays(this)) showHud()
    }

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ==================== Bottom control bar (kdk) ====================

    // Start / Stop / Resume / Previous. HUDService owns the GlassOS client and the
    // run lifecycle, so each button just sends it an action intent.
    private fun wireControlBar() {
        mapOf(
            R.id.btnCtlStart to HUDService.ACTION_KDK_START,
            R.id.btnCtlStop to HUDService.ACTION_KDK_STOP,
            R.id.btnCtlResume to HUDService.ACTION_KDK_RESUME,
            R.id.btnCtlPrevious to HUDService.ACTION_KDK_PREVIOUS,
        ).forEach { (id, action) ->
            findViewById<View>(id)?.setOnClickListener {
                startForegroundService(Intent(this, HUDService::class.java).setAction(action))
            }
        }
    }

    // ==================== Launcher ====================

    private fun wireLauncher() {
        val overlay = findViewById<View>(R.id.launcherOverlay)
        // Tap empty backdrop → re-show HUD (child buttons consume their own taps).
        findViewById<View>(R.id.kioskRoot)?.setOnClickListener { showHud() }
        findViewById<View>(R.id.appsButton)?.setOnClickListener { overlay?.visibility = View.VISIBLE }
        findViewById<View>(R.id.launcherClose)?.setOnClickListener { overlay?.visibility = View.GONE }

        val tiles = mapOf(
            R.id.appJellyfin to "jellyfin",
            R.id.appAbs to "abs",
            R.id.appCalibre to "calibre"
        )
        for ((id, key) in tiles) {
            findViewById<View>(id)?.setOnClickListener { launchApp(key) }
        }
    }

    private fun loadTargets(): Map<String, String> {
        val m = launchDefaults.toMutableMap()
        try {
            val f = File(filesDir, "kdk-launcher.conf")
            if (f.exists()) {
                f.readLines().forEach { raw ->
                    val line = raw.trim()
                    if (line.isNotEmpty() && !line.startsWith("#") && line.contains("=")) {
                        val k = line.substringBefore("=").trim()
                        val v = line.substringAfter("=").trim()
                        if (k.isNotEmpty() && v.isNotEmpty()) m[k] = v
                    }
                }
            }
        } catch (_: Exception) {
            // fall back to defaults
        }
        return m
    }

    private fun launchApp(key: String) {
        val target = loadTargets()[key]
        if (target != null) {
            try {
                val intent: Intent? = when {
                    target.startsWith("url:") ->
                        Intent(Intent.ACTION_VIEW, Uri.parse(target.removePrefix("url:")))
                    target.startsWith("pkg:") ->
                        packageManager.getLaunchIntentForPackage(target.removePrefix("pkg:"))
                            ?: throw ActivityNotFoundException(key)
                    else -> null
                }
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    hideHud()  // get the overlay out of the way so the app is full-screen
                    startActivity(intent)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    getString(R.string.launcher_not_installed, key),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        findViewById<View>(R.id.launcherOverlay)?.visibility = View.GONE
    }

    // ==================== Permissions / HUD ====================

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

    /** Hide the HUD overlay (e.g. while a launched app is in the foreground). */
    private fun hideHud() {
        val intent = Intent(this, HUDService::class.java).apply {
            action = HUDService.ACTION_HIDE_HUD
        }
        startForegroundService(intent)
    }
}
