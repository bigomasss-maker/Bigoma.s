package com.bigoma.s

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import java.io.File
import java.io.IOException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

class MainActivity : AppCompatActivity() {

    companion object {
        // Map y'amabarabara n'amazu (vector) ya OpenFreeMap
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        const val MAX_ZOOM = 14.0
        // Amategeko ya OpenFreeMap: tiles 3000 na 50 MB kuri buri gukuramo.
        const val MAX_TILES = 1500L
        const val MAX_BYTES = 45_000_000L
        val HOME: LatLng = LatLng(-3.3822, 29.3644) // Bujumbura
    }

    private lateinit var mapView: MapView
    private lateinit var tvStatus: TextView
    private lateinit var http: OkHttpClient
    private var map: MapLibreMap? = null
    private var satelliteOn = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) showMyLocation()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Kwerekana ikosa niba app yaraciitse ubushize
        val prefs = getSharedPreferences("crash", MODE_PRIVATE)
        val lastCrash = prefs.getString("trace", null)
        if (lastCrash != null) {
            prefs.edit().remove("trace").apply()
            val tv = TextView(this)
            tv.text = "IKOSA:\n\n" + lastCrash
            tv.textSize = 11f
            tv.setPadding(24, 80, 24, 24)
            tv.setTextIsSelectable(true)
            setContentView(android.widget.ScrollView(this).apply { addView(tv) })
            return
        }
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            prefs.edit().putString("trace", android.util.Log.getStackTraceString(e)).commit()
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        // Ibyifuzo 4 gusa icyarimwe, kandi app yiyita izina ryihariye
        http = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply {
                maxRequests = 4
                maxRequestsPerHost = 4
            })
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "BigomaS/0.3 (Android offline map app)")
                        .build()
                )
            }
            .build()

        // MapLibre igomba gutangira mbere ya byose
        MapLibre.getInstance(this)
        HttpRequestUtil.setOkHttpClient(http)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            // Gutangirira ku isi yose (globe)
            m.cameraPosition = CameraPosition.Builder()
                .target(HOME)
                .zoom(1.5)
                .build()
            loadStyle(m)
        }

        findViewById<Button>(R.id.btnSatellite).setOnClickListener { toggleSatellite() }
        findViewById<Button>(R.id.btnLocate).setOnClickListener { requestLocation() }
        findViewById<Button>(R.id.btnDownload).setOnClickListener { startDownload() }
    }

    private fun show(text: String) {
        tvStatus.text = text
        tvStatus.visibility = View.VISIBLE
    }

    // ---------- Style (globe) ----------
    // Dukura style ya OpenFreeMap, tukayibika muri telefone (kugira ngo ikore offline),
    // hanyuma tukongeramo "projection: globe".

    private fun loadStyle(m: MapLibreMap) {
        val cache = File(filesDir, "style_liberty.json")
        http.newCall(Request.Builder().url(STYLE_URL).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val cached = if (cache.exists()) cache.readText() else null
                runOnUiThread { applyStyle(m, cached) }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.use { if (it.isSuccessful) it.body.string() else null }
                if (body != null) {
                    try { cache.writeText(body) } catch (ex: Exception) {}
                }
                val json = body ?: (if (cache.exists()) cache.readText() else null)
                runOnUiThread { applyStyle(m, json) }
            }
        })
    }

    private fun applyStyle(m: MapLibreMap, json: String?) {
        var builder = Style.Builder().fromUri(STYLE_URL)
        if (json != null) {
            try {
                val withGlobe = JSONObject(json)
                    .put("projection", JSONObject().put("type", "globe"))
                    .toString()
                builder = Style.Builder().fromJson(withGlobe)
            } catch (ex: Exception) {
                // niba byanze, dukoresha style isanzwe
            }
        }
        m.setStyle(builder) { style ->
            addSatellite(style)
            // Kuva ku isi ujya ku mujyi
            m.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(HOME).zoom(12.0).build()
                ),
                5000
            )
        }
    }

    private fun addSatellite(style: Style) {
        val tiles = TileSet(
            "2.2.0",
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        )
        style.addSource(RasterSource("sat", tiles, 256))
        val layer = RasterLayer("sat-layer", "sat")
        layer.setProperties(PropertyFactory.visibility(Property.NONE))
        style.addLayer(layer)
        satelliteOn = false
    }

    private fun toggleSatellite() {
        val style = map?.style ?: return
        satelliteOn = !satelliteOn
        style.getLayer("sat-layer")?.setProperties(
            PropertyFactory.visibility(if (satelliteOn) Property.VISIBLE else Property.NONE)
        )
    }

    // ---------- Gukuramo agace (offline) ----------

    private fun startDownload() {
        val m = map ?: return
        val bounds = m.projection.visibleRegion.latLngBounds
        val maxZ = MAX_ZOOM
        val minZ = floor(m.cameraPosition.zoom).coerceIn(0.0, maxZ)

        val count = countTiles(bounds, minZ.toInt(), maxZ.toInt())
        if (count > MAX_TILES) {
            show("Agace ni kanini cyane ($count tiles). Egera map hanyuma ugerageze.")
            return
        }

        show("Gutangira ($count tiles)...")
        val definition = OfflineTilePyramidRegionDefinition(
            STYLE_URL, bounds, minZ, maxZ, resources.displayMetrics.density
        )
        val metadata = "Agace ${System.currentTimeMillis()}".toByteArray()

        OfflineManager.getInstance(this).createOfflineRegion(
            definition, metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    observe(offlineRegion)
                }

                override fun onError(error: String) {
                    show("Ikosa: $error")
                }
            })
    }

    private fun observe(region: OfflineRegion) {
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                val mb = status.completedResourceSize / 1_000_000.0
                if (status.completedResourceSize > MAX_BYTES) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() {}
                        override fun onError(error: String) {}
                    })
                    show("Agace ni kanini cyane (> 45 MB). Egera map ugerageze.")
                    return
                }
                if (status.isComplete) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    show("Karangiye ✓ (%.1f MB). Ubu ushobora gukoresha nta internet.".format(mb))
                } else {
                    val required = status.requiredResourceCount
                    val pct = if (required > 0)
                        (100.0 * status.completedResourceCount / required).toInt() else 0
                    show("Gukuramo: $pct%% (%.1f MB)".format(mb))
                }
            }

            override fun onError(error: OfflineRegionError) {
                show("Ikosa: ${error.reason} ${error.message}")
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                show("Tiles ni nyinshi cyane ($limit)")
            }
        })
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    private fun countTiles(b: LatLngBounds, minZ: Int, maxZ: Int): Long {
        var total = 0L
        for (z in minZ..maxZ) {
            val n = 1 shl z
            val x1 = lonToTile(b.longitudeWest, n)
            val x2 = lonToTile(b.longitudeEast, n)
            val y1 = latToTile(b.latitudeNorth, n)
            val y2 = latToTile(b.latitudeSouth, n)
            total += (x2 - x1 + 1L) * (y2 - y1 + 1L)
        }
        return total
    }

    private fun lonToTile(lon: Double, n: Int): Int =
        floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)

    private fun latToTile(lat: Double, n: Int): Int {
        val rad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return floor((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n)
            .toInt().coerceIn(0, n - 1)
    }

    // ---------- Aho ndi (GPS) ----------

    private fun requestLocation() {
        val ok = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (ok) showMyLocation() else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @Suppress("MissingPermission")
    private fun showMyLocation() {
        val m = map ?: return
        val style = m.style ?: return
        m.locationComponent.apply {
            activateLocationComponent(
                LocationComponentActivationOptions.builder(this@MainActivity, style).build()
            )
            isLocationComponentEnabled = true
            cameraMode = CameraMode.TRACKING
            zoomWhileTracking(16.0)
        }
    }

    override fun onStart() { super.onStart(); if (::mapView.isInitialized) mapView.onStart() }
    override fun onResume() { super.onResume(); if (::mapView.isInitialized) mapView.onResume() }
    override fun onPause() { super.onPause(); if (::mapView.isInitialized) mapView.onPause() }
    override fun onStop() { super.onStop(); if (::mapView.isInitialized) mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); if (::mapView.isInitialized) mapView.onLowMemory() }
    override fun onDestroy() { super.onDestroy(); if (::mapView.isInitialized) mapView.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::mapView.isInitialized) mapView.onSaveInstanceState(outState)
    }
}
