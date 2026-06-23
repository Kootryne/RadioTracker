package com.kootryne.radiotracker

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONArray
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val MIN_FM_MHZ = 87.5
private const val MAX_FM_MHZ = 108.0
private const val LOCATION_REQUEST_CODE = 42
private const val MIN_RANGE_PERCENT = 25
private const val MAX_RANGE_PERCENT = 250
private const val DEFAULT_RANGE_PERCENT = 100

class MainActivity : Activity() {
    private lateinit var spectrumView: SpectrumView
    private lateinit var statusText: TextView
    private lateinit var rangeText: TextView
    private lateinit var stationListText: TextView
    private lateinit var locationManager: LocationManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val metadataExecutor = Executors.newSingleThreadExecutor()
    private var allStations: List<RadioStation> = emptyList()
    private var visibleStations: List<RadioStation> = emptyList()
    private var rangePercent = DEFAULT_RANGE_PERCENT
    private var lastLatitude = 59.3293
    private var lastLongitude = 18.0686
    private var lastLocationStatus = "Using Stockholm fallback"

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            try {
                locationManager.removeUpdates(this)
            } catch (_: SecurityException) {
            }
            showStationsForLocation(location.latitude, location.longitude, "Using GPS location")
        }

        @Deprecated("Deprecated in Android framework")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        allStations = StationRepository.load(this)
        setContentView(buildUi())
        findLocationAndScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        metadataExecutor.shutdownNow()
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: SecurityException) {
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 13, 20))
            setPadding(dp(16), dp(18), dp(16), dp(16))
        }

        val title = TextView(this).apply {
            text = "RadioTracker"
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(title, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        statusText = TextView(this).apply {
            text = "Getting location..."
            setTextColor(Color.rgb(170, 183, 199))
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(10))
        }
        root.addView(statusText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        spectrumView = SpectrumView(this)
        root.addView(spectrumView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.2f))

        rangeText = TextView(this).apply {
            setTextColor(Color.rgb(225, 231, 240))
            textSize = 14f
            setPadding(0, dp(8), 0, dp(2))
        }
        root.addView(rangeText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        updateRangeText()

        val rangeSlider = SeekBar(this).apply {
            max = MAX_RANGE_PERCENT - MIN_RANGE_PERCENT
            progress = DEFAULT_RANGE_PERCENT - MIN_RANGE_PERCENT
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    rangePercent = (MIN_RANGE_PERCENT + progress).coerceIn(MIN_RANGE_PERCENT, MAX_RANGE_PERCENT)
                    updateRangeText()
                    showStationsForLocation(lastLatitude, lastLongitude, lastLocationStatus, reloadMetadata = false)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    showStationsForLocation(lastLatitude, lastLongitude, lastLocationStatus, reloadMetadata = true)
                }
            })
        }
        root.addView(rangeSlider, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val rescanButton = Button(this).apply {
            text = "Rescan current position"
            setOnClickListener { findLocationAndScan() }
        }
        root.addView(rescanButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        stationListText = TextView(this).apply {
            setTextColor(Color.rgb(225, 231, 240))
            textSize = 15f
            setPadding(0, dp(12), 0, 0)
            text = "No stations loaded yet."
        }
        val scrollView = ScrollView(this).apply { addView(stationListText) }
        root.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        return root
    }

    private fun findLocationAndScan() {
        if (!hasLocationPermission()) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_REQUEST_CODE
            )
            return
        }

        statusText.text = "Looking for your phone location..."
        val lastLocation = getBestLastKnownLocation()
        if (lastLocation != null) {
            showStationsForLocation(lastLocation.latitude, lastLocation.longitude, "Using last known location")
        } else {
            showStationsForLocation(59.3293, 18.0686, "No GPS fix yet, using Stockholm as fallback")
        }

        requestFreshLocationUpdate()
    }

    private fun requestFreshLocationUpdate() {
        if (!hasLocationPermission()) return
        try {
            val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            providers.forEach { provider ->
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, 0L, 0f, locationListener)
                }
            }
            mainHandler.postDelayed({
                try {
                    locationManager.removeUpdates(locationListener)
                } catch (_: SecurityException) {
                }
            }, 12_000L)
        } catch (_: SecurityException) {
            statusText.text = "Location permission was blocked."
        }
    }

    private fun getBestLastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null
        return try {
            val gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            listOfNotNull(gps, network).maxByOrNull { it.time }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_REQUEST_CODE && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            findLocationAndScan()
        } else {
            showStationsForLocation(59.3293, 18.0686, "Location denied, using Stockholm fallback")
        }
    }

    private fun showStationsForLocation(
        latitude: Double,
        longitude: Double,
        status: String,
        reloadMetadata: Boolean = true
    ) {
        lastLatitude = latitude
        lastLongitude = longitude
        lastLocationStatus = status

        val oldMetadata = visibleStations.associateBy(
            keySelector = { stationKey(it) },
            valueTransform = { it.nowPlaying }
        )
        val multiplier = rangePercent / 100.0

        visibleStations = allStations
            .map { station ->
                val distance = distanceKm(latitude, longitude, station.latitude, station.longitude)
                val existingText = oldMetadata[stationKey(station)] ?: "Loading live metadata..."
                station.copy(distanceKm = distance, nowPlaying = existingText)
            }
            .filter { it.distanceKm <= it.rangeKm * multiplier }
            .sortedBy { it.frequencyMhz }

        statusText.text = "$status • ${visibleStations.size} likely FM stations nearby"
        updateStationViews()
        if (reloadMetadata) loadNowPlayingMetadata()
    }

    private fun updateRangeText() {
        if (::rangeText.isInitialized) {
            rangeText.text = "Range threshold: $rangePercent% • lower = stricter, higher = catches weaker/farther stations"
        }
    }

    private fun updateStationViews() {
        spectrumView.stations = visibleStations
        spectrumView.invalidate()

        stationListText.text = if (visibleStations.isEmpty()) {
            "No stations found near this position with the current range threshold. Try increasing the range slider, or add more transmitters in app/src/main/assets/stations_se.json."
        } else {
            visibleStations.joinToString(separator = "\n\n") { station ->
                val distanceText = "${(station.distanceKm * 10.0).roundToInt() / 10.0} km away"
                val effectiveRange = "threshold ${(station.rangeKm * rangePercent / 100.0 * 10.0).roundToInt() / 10.0} km"
                "${station.frequencyMhz} MHz — ${station.name}\n${station.nowPlaying}\n$distanceText • $effectiveRange"
            }
        }
    }

    private fun loadNowPlayingMetadata() {
        val snapshot = visibleStations
        metadataExecutor.submit {
            snapshot.forEach { station ->
                val text = if (station.streamUrl.isNullOrBlank()) {
                    "No public song metadata source configured"
                } else {
                    fetchIcyStreamTitle(station.streamUrl) ?: "Live song metadata not found"
                }

                runOnUiThread {
                    visibleStations = visibleStations.map { current ->
                        if (current.name == station.name && current.frequencyMhz == station.frequencyMhz) {
                            current.copy(nowPlaying = text)
                        } else {
                            current
                        }
                    }
                    updateStationViews()
                }
            }
        }
    }

    private fun fetchIcyStreamTitle(streamUrl: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(streamUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 7_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Icy-MetaData", "1")
            connection.setRequestProperty("User-Agent", "RadioTracker Android")

            val metadataInterval = connection.getHeaderFieldInt("icy-metaint", -1)
            if (metadataInterval <= 0) return null

            BufferedInputStream(connection.inputStream).use { input ->
                var remaining = metadataInterval
                while (remaining > 0) {
                    val skipped = input.skip(remaining.toLong()).toInt()
                    if (skipped > 0) {
                        remaining -= skipped
                    } else {
                        if (input.read() == -1) return null
                        remaining -= 1
                    }
                }

                val metadataLengthByte = input.read()
                if (metadataLengthByte <= 0) return null
                val metadataLength = metadataLengthByte * 16
                val buffer = ByteArray(metadataLength)
                var offset = 0
                while (offset < metadataLength) {
                    val read = input.read(buffer, offset, metadataLength - offset)
                    if (read == -1) break
                    offset += read
                }

                val metadata = String(buffer, 0, offset, Charsets.UTF_8)
                Regex("StreamTitle='([^']*)'").find(metadata)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun stationKey(station: RadioStation): String = "${station.name}|${station.frequencyMhz}"
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

private object StationRepository {
    fun load(context: Context): List<RadioStation> {
        val jsonText = context.assets.open("stations_se.json").bufferedReader().use { it.readText() }
        val jsonArray = JSONArray(jsonText)
        return buildList {
            for (index in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(index)
                add(
                    RadioStation(
                        name = item.getString("name"),
                        frequencyMhz = item.getDouble("frequencyMhz"),
                        latitude = item.getDouble("latitude"),
                        longitude = item.getDouble("longitude"),
                        rangeKm = item.getDouble("rangeKm"),
                        streamUrl = item.optString("streamUrl").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }
}

data class RadioStation(
    val name: String,
    val frequencyMhz: Double,
    val latitude: Double,
    val longitude: Double,
    val rangeKm: Double,
    val streamUrl: String?,
    val distanceKm: Double = 0.0,
    val nowPlaying: String = "Waiting for metadata"
)

private class SpectrumView(context: Context) : View(context) {
    var stations: List<RadioStation> = emptyList()

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(112, 129, 148)
        strokeWidth = 3f
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(76, 93, 112)
        strokeWidth = 2f
    }
    private val stationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(100, 210, 255)
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(12f)
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(178, 190, 205)
        textSize = sp(10f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(11, 18, 29))

        val left = dp(20).toFloat()
        val right = width - dp(20).toFloat()
        val axisY = height - dp(64).toFloat()
        val top = dp(28).toFloat()

        canvas.drawLine(left, axisY, right, axisY, axisPaint)

        for (mhz in 88..108 step 2) {
            val x = xForFrequency(mhz.toDouble(), left, right)
            canvas.drawLine(x, axisY - dp(10), x, axisY + dp(10), tickPaint)
            canvas.drawText("$mhz", x - dp(10), axisY + dp(30), smallTextPaint)
        }
        canvas.drawText("FM ${MIN_FM_MHZ}–${MAX_FM_MHZ} MHz", left, height - dp(18).toFloat(), smallTextPaint)

        stations.forEachIndexed { index, station ->
            val x = xForFrequency(station.frequencyMhz, left, right)
            val labelLevel = index % 5
            val labelY = top + labelLevel * dp(48)

            canvas.drawLine(x, axisY, x, labelY + dp(24), stationPaint)
            canvas.drawCircle(x, axisY, dp(5).toFloat(), stationPaint)
            canvas.drawCircle(x, labelY + dp(24), dp(5).toFloat(), stationPaint)

            val labelX = (x - dp(54)).coerceIn(left, right - dp(112))
            canvas.drawText("${station.frequencyMhz} ${station.name}", labelX, labelY, textPaint)
            canvas.drawText(station.nowPlaying.take(38), labelX, labelY + dp(17), smallTextPaint)
        }
    }

    private fun xForFrequency(frequencyMhz: Double, left: Float, right: Float): Float {
        val clamped = frequencyMhz.coerceIn(MIN_FM_MHZ, MAX_FM_MHZ)
        val percent = (clamped - MIN_FM_MHZ) / (MAX_FM_MHZ - MIN_FM_MHZ)
        return (left + percent * (right - left)).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}

private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadiusKm = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val rLat1 = Math.toRadians(lat1)
    val rLat2 = Math.toRadians(lat2)

    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(rLat1) * cos(rLat2) * sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return earthRadiusKm * c
}
