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
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
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
        // Dukoresha imipaka mito kugira ngo tubungabunge umutekano.
        const val MAX_TILES = 1500L
        const val MAX_BYTES = 45_000_000L
    }

    private lateinit var mapView: MapView
    private lateinit var tvStatus: TextView
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
        val client = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply {
                maxRequests = 4
                maxRequestsPerHost = 4
            })
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "BigomaS/0.2 (Android offline map app)")
                        .build()
                )
            }
            .build()

        // MapLibre igomba gutangira mbere ya byose
        MapLibre.getInstance(this)
        HttpRequestUtil.setOkHttpClient(client)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            m.cameraPosition = CameraPosition.Builder()
                .target(LatLng(-3.3822, 29.3644))
                .zoom(12.0)
                .build()
            m.setStyle(STYLE_URL) { style ->
                // Satellite (online gusa), ihishwa hejuru ya map
                val tiles = TileSet(
                    "2.2.0",
                    "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
                )
                style.addSource(RasterSource("sat", tiles, 256))
                val layer = RasterLayer("sat-layer", "sat")
                layer.setProperties(PropertyFactory.visibility(Property.NONE))
                style.addLayer(layer)
            }
        }

        findViewById<Button>(R.id.btnSatellite).setOnClickListener { toggleSatellite() }
        findViewById<Button>(R.id.btnLocate).setOnClickListener { requestLocation() }
        findViewById<Button>(R.id.btnDownload).setOnClickListener { startDownload() }
    }

    private fun show(text: String) {
        tvStatus.text = text
        tvStatus.visibility = View.VISIBLE
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
