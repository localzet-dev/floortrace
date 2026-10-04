package com.localzet.floortrace.positioning.rtt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.util.Locale

class WifiRttEngine(
    context: Context,
    private val listener: (State) -> Unit,
) {
    data class State(
        val supported: Boolean,
        val available: Boolean,
        val configuredAnchors: Int,
        val rangedAnchors: Int = 0,
        val solution: RttSolution? = null,
        val error: String? = null,
    )

    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val hasRttFeature = Build.VERSION.SDK_INT >= 28 &&
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
    private val rttManager: WifiRttManager? = if (hasRttFeature) {
        appContext.getSystemService(WifiRttManager::class.java)
    } else null
    private val handler = Handler(Looper.getMainLooper())
    private val anchors = loadAnchors()
    private val supported = hasRttFeature && rttManager != null
    private var running = false
    private var requestInFlight = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            rangeOnce()
            handler.postDelayed(this, 5_000L)
        }
    }

    fun start() {
        if (running) return
        running = true
        listener(State(supported, isAvailable(), anchors.size))
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    private fun rangeOnce() {
        if (!supported || requestInFlight || anchors.isEmpty()) return
        if (!hasPermissions()) {
            listener(State(supported, isAvailable(), anchors.size, error = "Wi-Fi RTT permission missing"))
            return
        }
        val manager = rttManager ?: return
        if (!manager.isAvailable) {
            listener(State(supported, false, anchors.size))
            return
        }

        // ScanResult retrieval still requires fine location on current Android versions.
        val anchorByBssid = anchors.associateBy { normalizeBssid(it.bssid) }
        val peers = runCatching { wifiManager.scanResults }
            .getOrDefault(emptyList())
            .filter { it.is80211mcResponder && normalizeBssid(it.BSSID) in anchorByBssid }
            .take(10)
        if (peers.size < 3) {
            runCatching { wifiManager.startScan() }
            listener(State(supported, true, anchors.size, rangedAnchors = peers.size))
            return
        }

        val request = RangingRequest.Builder().addAccessPoints(peers).build()
        requestInFlight = true
        try {
            manager.startRanging(request, appContext.mainExecutor, object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) {
                    requestInFlight = false
                    listener(State(supported, manager.isAvailable, anchors.size, error = "RTT failure $code"))
                }

                override fun onRangingResults(results: MutableList<RangingResult>) {
                    requestInFlight = false
                    val ranges = results.mapNotNull { result ->
                        if (result.status != RangingResult.STATUS_SUCCESS) return@mapNotNull null
                        val anchor = anchorByBssid[normalizeBssid(result.macAddress.toString())] ?: return@mapNotNull null
                        RttRange(
                            anchor = anchor,
                            distanceMeters = result.distanceMm / 1_000.0,
                            stdDevMeters = result.distanceStdDevMm.coerceAtLeast(250) / 1_000.0,
                        )
                    }
                    val solution = RttTrilateration.solve(ranges)
                    listener(State(supported, manager.isAvailable, anchors.size, ranges.size, solution))
                }
            })
        } catch (error: SecurityException) {
            requestInFlight = false
            listener(State(supported, manager.isAvailable, anchors.size, error = error.message))
        } catch (error: Throwable) {
            requestInFlight = false
            listener(State(supported, manager.isAvailable, anchors.size, error = error.message))
        }
    }

    private fun isAvailable(): Boolean = supported && (rttManager?.isAvailable == true)

    private fun hasPermissions(): Boolean {
        val fine = appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val nearby = Build.VERSION.SDK_INT < 33 ||
            appContext.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        return fine && nearby
    }

    private fun loadAnchors(): List<RttAnchor> = runCatching {
        val text = appContext.assets.open("anchors.json").bufferedReader().use { it.readText() }
        val array = JSONArray(text)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val bssid = item.optString("bssid").trim()
                if (bssid.isBlank()) continue
                add(
                    RttAnchor(
                        bssid = bssid,
                        lat = item.getDouble("lat"),
                        lon = item.getDouble("lon"),
                        floor = item.optInt("floor").takeIf { item.has("floor") },
                        room = item.optString("room").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun normalizeBssid(value: String): String = value.trim().uppercase(Locale.US)
}
