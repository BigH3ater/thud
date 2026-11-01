package io.github.avikulin.thud.util

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Optional kdk-lab Home Assistant integration.
 *
 * Reads an internal config file (populated via `run-as`) and, when a webhook URL is present,
 * POSTs small JSON events (active profile, run start, run end) to a Home Assistant webhook so HA
 * can auto-attribute the workout to the active profile. thud has no MQTT and the broker is not
 * reachable from the console, so an HA webhook over HTTPS is used instead (no auth token needed).
 *
 * Config file: {internalFilesDir}/kdk-ha.conf — simple key=value lines, e.g.
 *     webhook_url=https://homeassistant.kmkdp.com/api/webhook/<id>
 *
 * If the file is absent or webhook_url is empty, the integration is DISABLED (silent no-op).
 * No URL/secret is hardcoded in source. All network work runs on a background daemon thread with
 * short timeouts, and every exception is swallowed so a network/TLS failure can never block or
 * crash the workout. The webhook URL (which contains a secret id) is never logged.
 */
object KdkHa {
    private const val TAG = "KdkHa"
    private const val CONFIG_FILE = "kdk-ha.conf"
    private const val KEY_WEBHOOK_URL = "webhook_url"

    @Volatile private var webhookUrl: String? = null

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Loads kdk-ha.conf from internal storage. Safe to call repeatedly (e.g. on each service
     * (re)start). Any failure disables the integration rather than propagating.
     */
    @Synchronized
    fun init(context: Context) {
        try {
            val conf = File(context.filesDir, CONFIG_FILE)
            if (!conf.exists()) {
                webhookUrl = null
                Log.i(TAG, "No $CONFIG_FILE in ${context.filesDir.absolutePath}; HA integration disabled")
                return
            }
            var url: String? = null
            conf.readLines().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val idx = line.indexOf('=')
                if (idx <= 0) return@forEach
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (key == KEY_WEBHOOK_URL) url = value
            }
            webhookUrl = url?.takeIf { it.isNotEmpty() }
            if (webhookUrl != null) {
                Log.i(TAG, "HA integration ENABLED")
            } else {
                Log.i(TAG, "HA integration DISABLED ($KEY_WEBHOOK_URL missing or empty)")
            }
        } catch (t: Throwable) {
            webhookUrl = null
            Log.w(TAG, "Failed to read $CONFIG_FILE; HA integration disabled", t)
        }
    }

    /** Active-profile change. Payload: {"event":"profile","profile":"<name>"} */
    fun emitProfile(profile: String) {
        post(JSONObject().apply {
            put("event", "profile")
            put("profile", profile)
        })
    }

    /** Run started. Payload: {"event":"run_start","profile":"<name>"} */
    fun emitRunStart(profile: String) {
        post(JSONObject().apply {
            put("event", "run_start")
            put("profile", profile)
        })
    }

    /**
     * Run ended / FIT exported.
     * Payload: {"event":"run_end","profile":"<name>","distance_m":<int?>,"duration_s":<int?>}
     * distance_m / duration_s are omitted when null.
     */
    fun emitRunEnd(profile: String, distanceMeters: Int?, durationSeconds: Int?) {
        post(JSONObject().apply {
            put("event", "run_end")
            put("profile", profile)
            if (distanceMeters != null) put("distance_m", distanceMeters)
            if (durationSeconds != null) put("duration_s", durationSeconds)
        })
    }

    private fun post(payload: JSONObject) {
        val url = webhookUrl
        if (url.isNullOrEmpty()) return // disabled: silent no-op
        val bodyText = payload.toString()
        val event = payload.optString("event")
        Thread({
            try {
                val request = Request.Builder()
                    .url(url)
                    .post(bodyText.toRequestBody(jsonMediaType))
                    .build()
                client.newCall(request).execute().use { resp ->
                    Log.d(TAG, "Posted '$event' -> HTTP ${resp.code}")
                }
            } catch (t: Throwable) {
                // Silent by design: network/TLS failures must never affect the workout.
                Log.d(TAG, "HA post '$event' failed (ignored): ${t.javaClass.simpleName}")
            }
        }, "kdk-ha-post").apply { isDaemon = true }.start()
    }
}
