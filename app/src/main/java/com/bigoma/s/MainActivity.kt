package com.bigoma.s

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
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
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

// bbox = [south, west, north, east]
data class Place(val name: String, val lat: Double, val lon: Double, val bbox: DoubleArray? = null, val geo: String? = null)

class MainActivity : AppCompatActivity() {

    companion object {
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        const val MAX_ZOOM = 14.0
        const val MAX_TILES = 1500L
        const val MAX_BYTES = 45_000_000L
        const val EMPTY_FC = "{\"type\":\"FeatureCollection\",\"features\":[]}"
        val HOME: LatLng = LatLng(-3.3822, 29.3644) // Bujumbura
    }

    private lateinit var mapView: MapView
    private lateinit var tvStatus: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnAccount: TextView
    private lateinit var http: OkHttpClient
    private lateinit var nav: NavController
    private var map: MapLibreMap? = null
    private var satelliteOn = false
    private var locationActive = false
    private var navigating = false
    private var hasDest = false
    private var pendingAfterPermission: (() -> Unit)? = null

    // Ibyo bihishwa igihe kuyobora gutangiye
    private val hideWhileNav = ArrayList<View>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val next = pendingAfterPermission
            pendingAfterPermission = null
            if (granted) {
                if (next != null) next() else showMyLocation()
            } else {
                if (::nav.isInitialized) nav.permissionDenied()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        http = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply {
                maxRequests = 4
                maxRequestsPerHost = 4
            })
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header(
                            "User-Agent",
                            "BigomaMaps/0.14 (Android map app; +https://github.com/bigomasss-maker/Bigoma.s)"
                        )
                        .build()
                )
            }
            .build()

        MapLibre.getInstance(this)
        HttpRequestUtil.setOkHttpClient(http)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        etSearch = findViewById(R.id.etSearch)
        btnAccount = findViewById(R.id.btnAccount)
        mapView = findViewById(R.id.mapView)

        // Ibishaje bya layout ya kera (navPanel) ntibikenewe, NavUi ifite ibyayo
        findViewById<View>(R.id.navPanel).visibility = View.GONE

        val btnSat = findViewById<Button>(R.id.btnSatellite)
        val btnLoc = findViewById<Button>(R.id.btnLocate)
        val btnDl = findViewById<Button>(R.id.btnDownload)
        hideWhileNav.addAll(listOf(findViewById<View>(R.id.searchBar), btnSat, btnLoc, btnDl))

        // Umuyobozi wa navigation (inzira, GPS, ijwi, uburyo bwo gutambuka)
        nav = NavController(
            act = this,
            http = http,
            map = { map },
            ensureLocation = { next -> ensureLocationPermission(next) },
            enablePuck = { activateLocation(false) },
            onNavigating = { on -> setNavigating(on) }
        )

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            m.cameraPosition = CameraPosition.Builder().target(HOME).zoom(1.5).build()
            m.addOnMapLongClickListener { point ->
                chooseDestination(point, null)
                true
            }
            loadStyle(m)
        }

        btnSat.setOnClickListener { toggleSatellite() }
        btnLoc.setOnClickListener { requestLocation() }
        btnDl.setOnClickListener { startDownload() }

        etSearch.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                searchPlace(v.text.toString())
                true
            } else {
                false
            }
        }

        updateAvatar()
        btnAccount.setOnClickListener { editProfile() }

        // Back: niba urimo kuyoborwa cyangwa ufise aho ugiye, birahagarika
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (hasDest) {
                    hasDest = false
                    nav.cancel()
                    setGeo("area-src", EMPTY_FC)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun show(text: String) {
        tvStatus.text = text
        tvStatus.visibility = View.VISIBLE
    }

    private fun setNavigating(on: Boolean) {
        navigating = on
        val v = if (on) View.GONE else View.VISIBLE
        for (x in hideWhileNav) x.visibility = v
        if (on) tvStatus.visibility = View.GONE
    }

    // ---------- Uruhushya rwa Location ----------

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun ensureLocationPermission(next: () -> Unit) {
        if (hasLocationPermission()) {
            next()
        } else {
            pendingAfterPermission = next
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // ---------- Konti ----------

    private fun profileName(): String =
        getSharedPreferences("profile", MODE_PRIVATE).getString("name", "") ?: ""

    private fun updateAvatar() {
        val name = profileName().trim()
        val letter = if (name.isNotEmpty()) name[0].uppercaseChar() else 'B'
        btnAccount.text = letter.toString()
    }

    private fun editProfile() {
        val input = EditText(this)
        input.hint = "Izina ryawe"
        input.setText(profileName())
        AlertDialog.Builder(this)
            .setTitle("Konti yawe")
            .setMessage("Andika izina ryawe. Rizigamwa muri telefone gusa.")
            .setView(input)
            .setPositiveButton("Bika") { _, _ ->
                getSharedPreferences("profile", MODE_PRIVATE)
                    .edit().putString("name", input.text.toString().trim()).apply()
                updateAvatar()
            }
            .setNegativeButton("Hagarika", null)
            .show()
    }

    // ---------- Gushakisha ahantu ----------

    private fun searchPlace(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        show("Gushakisha...")
        searchNominatim(q) { r1, e1 ->
            if (r1.isNotEmpty()) {
                showResults(r1)
            } else {
                searchPhoton(q) { r2, e2 ->
                    if (r2.isNotEmpty()) {
                        showResults(r2)
                    } else {
                        val stripped = q.replace(Regex("^\\d+[A-Za-z]?\\s*,?\\s*"), "")
                        if (stripped != q && stripped.length > 2) {
                            searchNominatim(stripped) { r3, e3 ->
                                if (r3.isNotEmpty()) {
                                    Toast.makeText(
                                        this,
                                        "Nimero y'inzu ntiyabonetse. Hano ni umuhanda.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    showResults(r3)
                                } else {
                                    show(e3 ?: "Nta kintu cabonetse. Gerageza izina ry'umuhanda + umujyi.")
                                }
                            }
                        } else {
                            show(e2 ?: e1 ?: "Nta kintu cabonetse. Gerageza izina ry'umuhanda + umujyi.")
                        }
                    }
                }
            }
        }
    }

    private fun searchBias(): LatLngBounds? {
        val m = map ?: return null
        if (m.cameraPosition.zoom < 8.0) return null
        return m.projection.visibleRegion.latLngBounds
    }

    private fun getText(url: HttpUrl, done: (String?, String?) -> Unit) {
        http.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { done(null, "Nta internet. Gushakisha bisaba internet.") }
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val body = response.use { if (it.isSuccessful) it.body?.string() else null }
                runOnUiThread {
                    done(body, if (body == null) "Gushakisha vyanse (HTTP $code)." else null)
                }
            }
        })
    }

    private fun searchNominatim(q: String, done: (List<Place>, String?) -> Unit) {
        val b = HttpUrl.Builder()
            .scheme("https")
            .host("nominatim.openstreetmap.org")
            .addPathSegment("search")
            .addQueryParameter("q", q)
            .addQueryParameter("format", "json")
            .addQueryParameter("limit", "5")
            .addQueryParameter("dedupe", "0")
            .addQueryParameter("polygon_geojson", "1")
            .addQueryParameter("polygon_threshold", "0.0005")
        val bounds = searchBias()
        if (bounds != null) {
            b.addQueryParameter(
                "viewbox",
                String.format(
                    Locale.US, "%.5f,%.5f,%.5f,%.5f",
                    bounds.longitudeWest, bounds.latitudeNorth,
                    bounds.longitudeEast, bounds.latitudeSouth
                )
            )
        }
        getText(b.build()) { body, err ->
            done(if (body != null) parseNominatim(body) else emptyList(), err)
        }
    }

    private fun searchPhoton(q: String, done: (List<Place>, String?) -> Unit) {
        val b = HttpUrl.Builder()
            .scheme("https")
            .host("photon.komoot.io")
            .addPathSegment("api")
            .addQueryParameter("q", q)
            .addQueryParameter("limit", "5")
        val m = map
        if (m != null && m.cameraPosition.zoom >= 8.0) {
            val t = m.cameraPosition.target
            if (t != null) {
                b.addQueryParameter("lat", String.format(Locale.US, "%.5f", t.latitude))
                b.addQueryParameter("lon", String.format(Locale.US, "%.5f", t.longitude))
            }
        }
        getText(b.build()) { body, err ->
            done(if (body != null) parsePhoton(body) else emptyList(), err)
        }
    }

    private fun parseNominatim(body: String): List<Place> {
        val out = ArrayList<Place>()
        try {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                var box: DoubleArray? = null
                val bb = o.optJSONArray("boundingbox")
                if (bb != null && bb.length() == 4) {
                    box = doubleArrayOf(
                        bb.getString(0).toDouble(),
                        bb.getString(2).toDouble(),
                        bb.getString(1).toDouble(),
                        bb.getString(3).toDouble()
                    )
                }
                var geo: String? = null
                val g = o.optJSONObject("geojson")
                if (g != null) {
                    val t = g.optString("type")
                    if (t == "Polygon" || t == "MultiPolygon" ||
                        t == "LineString" || t == "MultiLineString"
                    ) geo = g.toString()
                }
                out.add(
                    Place(
                        o.optString("display_name", ""),
                        o.getString("lat").toDouble(),
                        o.getString("lon").toDouble(),
                        box, geo
                    )
                )
            }
        } catch (ex: Exception) {
        }
        return out.sortedByDescending {
            val g = it.geo
            if (g != null && g.contains("Polygon")) 1 else 0
        }
    }

    private fun parsePhoton(body: String): List<Place> {
        val out = ArrayList<Place>()
        try {
            val feats = JSONObject(body).getJSONArray("features")
            for (i in 0 until feats.length()) {
                val f = feats.getJSONObject(i)
                val c = f.getJSONObject("geometry").getJSONArray("coordinates")
                val props = f.getJSONObject("properties")
                var box: DoubleArray? = null
                val ex = props.optJSONArray("extent")
                if (ex != null && ex.length() == 4) {
                    box = doubleArrayOf(ex.getDouble(3), ex.getDouble(0), ex.getDouble(1), ex.getDouble(2))
                }
                out.add(Place(photonName(props), c.getDouble(1), c.getDouble(0), box))
            }
        } catch (ex: Exception) {
        }
        return out
    }

    private fun photonName(p: JSONObject): String {
        val parts = ArrayList<String>()
        val name = p.optString("name")
        if (name.isNotEmpty()) parts.add(name)
        val street = p.optString("street")
        val hn = p.optString("housenumber")
        if (street.isNotEmpty()) parts.add(if (hn.isNotEmpty()) "$hn $street" else street)
        for (k in listOf("district", "city", "state", "country")) {
            val v = p.optString(k)
            if (v.isNotEmpty() && !parts.contains(v)) parts.add(v)
        }
        return parts.joinToString(", ")
    }

    private fun showResults(list: List<Place>) {
        tvStatus.visibility = View.GONE
        if (list.size == 1) {
            goTo(list[0])
            return
        }
        val names = list.map { it.name.take(90) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Hitamo ahantu")
            .setItems(names) { _, which -> goTo(list[which]) }
            .setNegativeButton("Hagarika", null)
            .show()
    }

    // Kuhitamo aho ugiye (gushakisha cyangwa kanda igihe kirekire)
    private fun chooseDestination(p: LatLng, label: String?) {
        setGeo("area-src", EMPTY_FC)
        hasDest = true
        nav.setDestination(p, label)
    }

    private fun goTo(p: Place) {
        val m = map ?: return
        val bx = p.bbox
        val isArea = bx != null && (bx[2] - bx[0] > 0.004 || bx[3] - bx[1] > 0.004)
        hasDest = true
        nav.setDestination(LatLng(p.lat, p.lon), p.name.take(80))
        setGeo("area-src", EMPTY_FC)
        if (p.geo != null) {
            setGeo("area-src", "{\"type\":\"Feature\",\"properties\":{},\"geometry\":" + p.geo + "}")
        } else if (isArea && bx != null) {
            val ring = String.format(
                Locale.US,
                "[[%.6f,%.6f],[%.6f,%.6f],[%.6f,%.6f],[%.6f,%.6f],[%.6f,%.6f]]",
                bx[1], bx[0], bx[3], bx[0], bx[3], bx[2], bx[1], bx[2], bx[1], bx[0]
            )
            setGeo(
                "area-src",
                "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[" + ring + "]}}"
            )
        }
        // Niba nta nzira ishobora kuboneka (GPS ntirafungura), twegere ahantu
        if (!isArea) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), 15.0), 1200)
        }
    }

    // ---------- Ibice by'ikarita (layers) ----------

    private fun setGeo(id: String, json: String) {
        map?.style?.getSourceAs<GeoJsonSource>(id)?.setGeoJson(json)
    }

    private fun addAreaLayers(style: Style) {
        style.addSource(GeoJsonSource("area-src", EMPTY_FC))
        style.addLayer(
            FillLayer("area-fill", "area-src").withProperties(
                PropertyFactory.fillColor("#1A73E8"),
                PropertyFactory.fillOpacity(0.18f)
            )
        )
        style.addLayer(
            LineLayer("area-line", "area-src").withProperties(
                PropertyFactory.lineColor("#1A73E8"),
                PropertyFactory.lineWidth(3f),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
    }

    private fun addRouteLayers(style: Style) {
        style.addSource(GeoJsonSource("route-src", EMPTY_FC))
        style.addSource(GeoJsonSource("dest-src", EMPTY_FC))
        // Umurongo w'inzira ufite igicucu cyera hanze (nka Google Maps)
        style.addLayer(
            LineLayer("route-casing", "route-src").withProperties(
                PropertyFactory.lineColor("#FFFFFF"),
                PropertyFactory.lineWidth(10f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        style.addLayer(
            LineLayer("route-layer", "route-src").withProperties(
                PropertyFactory.lineColor("#1A73E8"),
                PropertyFactory.lineWidth(6.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        style.addImage("dest-pin", makePinBitmap())
        style.addLayer(
            SymbolLayer("dest-layer", "dest-src").withProperties(
                PropertyFactory.iconImage("dest-pin"),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        )
    }

    private fun makePinBitmap(): android.graphics.Bitmap {
        val d = resources.displayMetrics.density
        val w = (38 * d).toInt()
        val h = (52 * d).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val r = w / 2f - 3 * d
        val cx = w / 2f
        val cy = r + 3 * d
        val tri = android.graphics.Path()
        tri.moveTo(cx - r * 0.9f, cy + r * 0.45f)
        tri.lineTo(cx, h - 2 * d)
        tri.lineTo(cx + r * 0.9f, cy + r * 0.45f)
        tri.close()
        val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        stroke.style = android.graphics.Paint.Style.STROKE
        stroke.strokeWidth = 5 * d
        stroke.color = android.graphics.Color.WHITE
        stroke.strokeJoin = android.graphics.Paint.Join.ROUND
        c.drawCircle(cx, cy, r, stroke)
        c.drawPath(tri, stroke)
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        fill.style = android.graphics.Paint.Style.FILL
        fill.color = android.graphics.Color.parseColor("#0F766E")
        c.drawCircle(cx, cy, r, fill)
        c.drawPath(tri, fill)
        fill.color = android.graphics.Color.WHITE
        c.drawCircle(cx, cy, r * 0.42f, fill)
        return bmp
    }

    // ---------- Style ----------

    private fun loadStyle(m: MapLibreMap) {
        val cache = File(filesDir, "style_liberty.json")
        http.newCall(Request.Builder().url(STYLE_URL).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val cached = if (cache.exists()) cache.readText() else null
                runOnUiThread { applyStyle(m, cached) }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.use { if (it.isSuccessful) it.body?.string() else null }
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
            }
        }
        m.setStyle(builder) { style ->
            addSatellite(style)
            addAreaLayers(style)
            addRouteLayers(style)
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
                override fun onCreate(offlineRegion: OfflineRegion) { observe(offlineRegion) }
                override fun onError(error: String) { show("Ikosa: $error") }
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
        ensureLocationPermission { showMyLocation() }
    }

    private fun showMyLocation() {
        activateLocation(true)
    }

    @Suppress("MissingPermission")
    private fun activateLocation(track: Boolean) {
        val m = map ?: return
        val style = m.style ?: return
        if (!hasLocationPermission()) return
        val lc = m.locationComponent
        if (!locationActive) {
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(this, style).build()
            )
            lc.isLocationComponentEnabled = true
            locationActive = true
        }
        if (track) {
            lc.cameraMode = CameraMode.TRACKING
            lc.zoomWhileTracking(16.0)
        }
    }

    override fun onStart() { super.onStart(); if (::mapView.isInitialized) mapView.onStart() }
    override fun onResume() { super.onResume(); if (::mapView.isInitialized) mapView.onResume() }
    override fun onPause() { super.onPause(); if (::mapView.isInitialized) mapView.onPause() }
    override fun onStop() { super.onStop(); if (::mapView.isInitialized) mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); if (::mapView.isInitialized) mapView.onLowMemory() }
    override fun onDestroy() {
        super.onDestroy()
        if (::nav.isInitialized) nav.cancel()
        if (::mapView.isInitialized) mapView.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::mapView.isInitialized) mapView.onSaveInstanceState(outState)
    }
}
