package com.kootryne.radiotracker

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
    private lateinit var stationListContainer: LinearLayout
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
            setBackgroundColor(Color.rgb(6, 10, 18))
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        header.addView(titleColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f))

        val title = TextView(this).apply {
            text = "RadioTracker"
            setTextColor(Color.WHITE)
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
        }
        titleColumn.addView(title, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        statusText = TextView(this).apply {
            text = "Getting location..."
            setTextColor(Color.rgb(158, 174, 194))
            textSize = 13f
            setPadding(0, dp(2), 0, 0)
        }
        titleColumn.addView(statusText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(Color.rgb(13, 24, 38), Color.rgb(31, 48, 68), 18)
        }
        header.addView(controls, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.85f))

        rangeText = TextView(this).apply {
            setTextColor(Color.rgb(225, 234, 245))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        }
        controls.addView(rangeText, LinearLayout.LayoutParams(dp(170), LinearLayout.LayoutParams.WRAP_CONTENT))
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
        controls.addView(rangeSlider, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val rescanButton = Button(this).apply {
            text = "Rescan"
            setOnClickListener { findLocationAndScan() }
        }
        controls.addView(rescanButton, LinearLayout.LayoutParams(dp(112), LinearLayout.LayoutParams.WRAP_CONTENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val leftPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBackground(Color.rgb(10, 18, 30), Color.rgb(35, 56, 82), 22)
        }
        content.addView(leftPanel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.7f).apply {
            setMargins(0, 0, dp(14), 0)
        })

        val spectrumHeader = TextView(this).apply {
            text = "FM spectrum 87.5–108.0 MHz"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        }
        leftPanel.addView(spectrumHeader, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val spectrumSubHeader = TextView(this).apply {
            text = "Numbers on the graph match the station cards on the right."
            setTextColor(Color.rgb(149, 166, 188))
            textSize = 12f
            setPadding(0, dp(2), 0, dp(8))
        }
        leftPanel.addView(spectrumSubHeader, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        spectrumView = SpectrumView(this)
        leftPanel.addView(spectrumView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val rightPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBackground(Color.rgb(10, 18, 30), Color.rgb(35, 56, 82), 22)
        }
        content.addView(rightPanel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        val stationTitle = TextView(this).apply {
            text = "Stations"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        }
        rightPanel.addView(stationTitle, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val stationHint = TextView(this).apply {
            text = "Nearby, sorted by frequency"
            setTextColor(Color.rgb(149, 166, 188))
            textSize = 12f
            setPadding(0, dp(2), 0, dp(8))
        }
        rightPanel.addView(stationHint, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        stationListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scrollView = ScrollView(this).apply { addView(stationListContainer) }
        rightPanel.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

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

        statusText.text = "$status • ${visibleStations.size} nearby stations"
        updateStationViews()
        if (reloadMetadata) loadNowPlayingMetadata()
    }

    private fun updateRangeText() {
        if (::rangeText.isInitialized) {
            rangeText.text = "Range $rangePercent%"
        }
    }

    private fun updateStationViews() {
        spectrumView.stations = visibleStations
        spectrumView.invalidate()

        stationListContainer.removeAllViews()
        if (visibleStations.isEmpty()) {
            stationListContainer.addView(stationCardText("No stations found\nIncrease range, or add more transmitters to the JSON database.", muted = true))
            return
        }

        visibleStations.forEachIndexed { index, station ->
            val distanceText = "${formatOneDecimal(station.distanceKm)} km away"
            val effectiveRange = "${formatOneDecimal(station.rangeKm * rangePercent / 100.0)} km threshold"
            val cardText = buildString {
                append("${index + 1}. ${station.frequencyMhz} MHz  •  ${station.name}\n")
                append(station.nowPlaying.ifBlank { "Waiting for metadata" })
                append("\n$distanceText  •  $effectiveRange")
            }
            stationListContainer.addView(stationCardText(cardText, muted = false))
        }
    }

    private fun stationCardText(text: String, muted: Boolean): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(if (muted) Color.rgb(150, 165, 185) else Color.rgb(232, 239, 249))
            textSize = 13.5f
            setLineSpacing(0f, 1.08f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(
                fill = if (muted) Color.rgb(12, 22, 36) else Color.rgb(15, 28, 45),
                stroke = if (muted) Color.rgb(35, 50, 70) else Color.rgb(42, 72, 102),
                radiusDp = 16
            )
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(10))
            }
        }
    }

    private fun loadNowPlayingMetadata() {
        val snapshot = visibleStations
        metadataExecutor.submit {
            snapshot.forEach { station ->
                val text = fetchNowPlaying(station)

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

    private fun fetchNowPlaying(station: RadioStation): String {
        val metadataText = station.metadataUrl?.let { fetchMetadataUrlNowPlaying(it) }
        if (!metadataText.isNullOrBlank()) return metadataText

        val knownPlaylistText = fetchKnownPlaylistNowPlaying(station)
        if (!knownPlaylistText.isNullOrBlank()) return knownPlaylistText

        val directIcyText = station.streamUrl?.let { fetchIcyStreamTitle(it) }
        if (!directIcyText.isNullOrBlank() && !looksLikeProgramOnly(directIcyText, station)) return directIcyText

        val directoryText = fetchRadioBrowserNowPlaying(station)
        if (!directoryText.isNullOrBlank() && !looksLikeProgramOnly(directoryText, station)) return directoryText

        return if (station.name.contains("NRJ", ignoreCase = true)) {
            "NRJ found • playlist/stream did not expose a song right now"
        } else if (station.name.contains("Mix Megapol", ignoreCase = true)) {
            "Mix Megapol found • playlist/stream did not expose a song right now"
        } else if (station.metadataUrl.isNullOrBlank() && station.streamUrl.isNullOrBlank() && station.streamSearchName.isNullOrBlank()) {
            "No public song metadata source configured"
        } else {
            "No song metadata found"
        }
    }

    private fun fetchKnownPlaylistNowPlaying(station: RadioStation): String? {
        val playlistUrl = when {
            station.name.contains("NRJ", ignoreCase = true) -> "https://onlineradiobox.com/se/nrj/playlist/"
            station.name.contains("Mix Megapol", ignoreCase = true) -> "https://onlineradiobox.com/se/mixmegapol/playlist/"
            else -> null
        } ?: return null

        return fetchOnlineRadioBoxNowPlaying(playlistUrl)
    }

    private fun fetchMetadataUrlNowPlaying(metadataUrl: String): String? {
        return try {
            val text = fetchText(metadataUrl) ?: return null
            if (metadataUrl.contains("onlineradiobox.com", ignoreCase = true) || text.trimStart().startsWith("<")) {
                return extractOnlineRadioBoxNowPlaying(text)
            }

            val root = JSONObject(text)
            extractSongFromJson(root)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchOnlineRadioBoxNowPlaying(playlistUrl: String): String? {
        val htmlText = fetchText(playlistUrl) ?: return null
        return extractOnlineRadioBoxNowPlaying(htmlText)
    }

    private fun extractOnlineRadioBoxNowPlaying(htmlText: String): String? {
        val markerIndex = listOf(
            "track list for the past",
            "Show by radio station time",
            "playlist stores",
            "playlist"
        ).mapNotNull { marker ->
            htmlText.indexOf(marker, ignoreCase = true).takeIf { it >= 0 }
        }.minOrNull() ?: 0

        val searchArea = htmlText.substring(markerIndex.coerceIn(0, htmlText.length))
        val plainText = htmlToPlainText(searchArea)
            .replace(Regex("\\s+"), " ")
            .trim()

        val liveIndex = plainText.indexOf("Live ", ignoreCase = true)
        if (liveIndex < 0) return null

        val afterLive = plainText.substring(liveIndex + "Live ".length).trim()
        if (afterLive.isBlank()) return null

        val nextTime = Regex("\\s\\d{1,2}:\\d{2}\\s").find(afterLive)
        val endIndex = nextTime?.range?.first ?: afterLive.length.coerceAtMost(180)
        return cleanTrackText(afterLive.substring(0, endIndex))
    }

    private fun htmlToPlainText(htmlText: String): String {
        return Html.fromHtml(htmlText, Html.FROM_HTML_MODE_LEGACY).toString()
    }

    private fun cleanTrackText(text: String): String? {
        val cleaned = text
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('-', '•', '|')
            .trim()

        if (cleaned.length < 3) return null

        val lower = cleaned.lowercase()
        if (lower.contains("ad break")) return null
        if (lower.contains("inga låtar hittades")) return null
        if (lower.contains("no song")) return null
        if (lower.startsWith("show by radio station time")) return null
        if (lower.startsWith("listen on apple music")) return null
        if (lower.startsWith("install the free")) return null

        return cleaned
    }

    private fun looksLikeProgramOnly(text: String, station: RadioStation): Boolean {
        val lower = text.lowercase().trim()
        return when {
            station.name.contains("NRJ", ignoreCase = true) -> lower == "nrj" || lower.contains("hit story") || lower.contains("hitstory") || lower.contains("hit music only")
            station.name.contains("Mix Megapol", ignoreCase = true) -> lower == "mix megapol" || lower == "mix megapol live"
            else -> false
        }
    }

    private fun extractSongFromJson(jsonObject: JSONObject): String? {
        val directSong = jsonObject.optJSONObject("song")
        if (directSong != null) {
            parseSongObject(directSong)?.let { return it }
        }

        val playlist = jsonObject.optJSONObject("playlist")
        if (playlist != null) {
            extractSongFromJson(playlist)?.let { return it }
        }

        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val child = jsonObject.optJSONObject(key) ?: continue
            parseSongObject(child)?.let { return it }
        }

        return null
    }

    private fun parseSongObject(song: JSONObject): String? {
        val title = song.optString("title").trim()
        val artist = song.optString("artist").trim()
        return when {
            artist.isNotBlank() && title.isNotBlank() -> "$artist — $title"
            title.isNotBlank() -> title
            artist.isNotBlank() -> artist
            else -> null
        }
    }

    private fun fetchRadioBrowserNowPlaying(station: RadioStation): String? {
        var streamWasFound = false

        for (searchName in stationSearchNames(station)) {
            val encodedName = URLEncoder.encode(searchName, "UTF-8")
            val searchUrl = "https://de1.api.radio-browser.info/json/stations/search?name=$encodedName&countrycode=SE&hidebroken=true&limit=10&order=clickcount&reverse=true"

            try {
                val jsonText = fetchText(searchUrl) ?: continue
                val results = JSONArray(jsonText)
                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    val name = item.optString("name", "")
                    if (station.name.contains("NRJ", ignoreCase = true) && !name.contains("NRJ", ignoreCase = true)) {
                        continue
                    }

                    val urlResolved = item.optString("url_resolved", "").trim()
                    val url = item.optString("url", "").trim()
                    val streamUrl = urlResolved.ifBlank { url }
                    if (streamUrl.isBlank()) continue

                    streamWasFound = true
                    val song = fetchIcyStreamTitle(streamUrl)
                    if (!song.isNullOrBlank()) return song
                }
            } catch (_: Exception) {
            }
        }

        return if (streamWasFound && station.name.contains("NRJ", ignoreCase = true)) {
            "NRJ live station found • song title not exposed by stream"
        } else {
            null
        }
    }

    private fun stationSearchNames(station: RadioStation): List<String> {
        if (station.name.contains("NRJ", ignoreCase = true)) {
            return listOf("NRJ", "NRJ Sweden", "NRJ Sverige", "NRJ Hit Music Only")
        }

        val base = station.streamSearchName ?: station.name
            .replace(" Stockholm", "")
            .replace(" Hörby", "")
            .replace(" Malmö", "")
            .replace(" Uppsala", "")
            .replace(" Östhammar", "")
            .replace(" seed", "")
            .trim()
        return listOf(base).filter { it.isNotBlank() }.distinct()
    }

    private fun fetchText(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 7_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json, text/html, text/plain, */*")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; RadioTracker) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36")

            val code = connection.responseCode
            if (code !in 200..299) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
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

    private fun roundedBackground(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), stroke)
        }
    }

    private fun stationKey(station: RadioStation): String = "${station.name}|${station.frequencyMhz}"
    private fun formatOneDecimal(value: Double): String = "${(value * 10.0).roundToInt() / 10.0}"
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
                        streamUrl = item.optString("streamUrl", "").takeIf { it.isNotBlank() },
                        metadataUrl = item.optString("metadataUrl", "").takeIf { it.isNotBlank() },
                        streamSearchName = item.optString("streamSearchName", "").takeIf { it.isNotBlank() }
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
    val metadataUrl: String?,
    val streamSearchName: String?,
    val distanceKm: Double = 0.0,
    val nowPlaying: String = "Waiting for metadata"
)

private class SpectrumView(context: Context) : View(context) {
    var stations: List<RadioStation> = emptyList()

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(115, 135, 160)
        strokeWidth = 3f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(35, 55, 78)
        strokeWidth = 1.5f
    }
    private val stationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(94, 213, 255)
        strokeWidth = 4f
    }
    private val markerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(94, 213, 255)
        style = Paint.Style.FILL
    }
    private val markerNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(7, 12, 20)
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val tickTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(176, 191, 211)
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }
    private val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(145, 162, 184)
        textSize = sp(10f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(9, 17, 29))

        val left = dp(28).toFloat()
        val right = width - dp(28).toFloat()
        val axisY = height - dp(46).toFloat()
        val top = dp(26).toFloat()

        for (mhz in 88..108 step 2) {
            val x = xForFrequency(mhz.toDouble(), left, right)
            canvas.drawLine(x, top, x, axisY + dp(8), gridPaint)
            canvas.drawLine(x, axisY - dp(8), x, axisY + dp(8), axisPaint)
            canvas.drawText("$mhz", x, axisY + dp(28), tickTextPaint)
        }

        canvas.drawLine(left, axisY, right, axisY, axisPaint)
        canvas.drawText("FM ${MIN_FM_MHZ}–${MAX_FM_MHZ} MHz", left, height - dp(12).toFloat(), footerPaint)

        stations.forEachIndexed { index, station ->
            val x = xForFrequency(station.frequencyMhz, left, right)
            val stagger = index % 5
            val markerY = top + dp(18) + stagger * dp(26)
            val radius = dp(10).toFloat()

            canvas.drawLine(x, axisY, x, markerY, stationPaint)
            canvas.drawCircle(x, axisY, dp(5).toFloat(), markerFillPaint)
            canvas.drawCircle(x, markerY, radius, markerFillPaint)
            canvas.drawText("${index + 1}", x, markerY + dp(4), markerNumberPaint)
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
