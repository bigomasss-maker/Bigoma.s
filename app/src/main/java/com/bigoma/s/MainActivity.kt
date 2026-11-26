package com.bigoma.s

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
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
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
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

data class Place(val name: String, val lat: Double, val lon: Double)

class MainActivity : AppCompatActivity() {

    companion object {
        // Map y'amabarabara n'amazu (vector) ya OpenFreeMap
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        const val MAX_ZOOM = 14.0
        // Amategeko ya OpenFreeMap: tiles 3000 na 50 MB kuri buri gukuramo.
        const val MAX_TILES = 1500L
        const val MAX_BYTES = 45_000_000L
        const val EMPTY_FC = "{\"type\":\"FeatureCollection\",\"features\":[]}"
        val HOME: LatLng = LatLng(-3.3822, 29.3644) // Bujumbura
    }

    private lateinit var mapView: MapView
    private lateinit var tvStatus: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnAccount: TextView
    private lateinit var navPanel: View
    private lateinit var tvNavTitle: TextView
    private lateinit var tvNavSteps: TextView
    private lateinit var scrollSteps: View
    private lateinit var btnRoute: Button
    private lateinit var btnCancelNav: Button
    private lateinit var http: OkHttpClient
    private var map: MapLibreMap? = null
    private var satelliteOn = false
    private var destination: LatLng? = null
    private var locationActive = false
    private var pendingAfterPermission: (() -> Unit)? = null
    private val ui = Handler(Looper.getMainLooper())

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val next = pendingAfterPermission
            pendingAfterPermission = null
            if (granted) {
                if (next != null) next() else showMyLocation()
            }
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

        // Ibyifuzo 4 gusa icyarimwe. App yiyita izina ryihariye n'aho kuyisanga
        // (ibyo Nominatim na OpenFreeMap bisaba).
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
                            "BigomaS/0.9 (Android map app; +https://github.com/bigomasss-maker/Bigoma.s)"
                        )
                        .build()
                )
            }
            .build()

        // MapLibre igomba gutangira mbere ya byose
        MapLibre.getInstance(this)
        HttpRequestUtil.setOkHttpClient(http)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        etSearch = findViewById(R.id.etSearch)
        btnAccount = findViewById(R.id.btnAccount)
        navPanel = findViewById(R.id.navPanel)
        tvNavTitle = findViewById(R.id.tvNavTitle)
        tvNavSteps = findViewById(R.id.tvNavSteps)
        scrollSteps = findViewById(R.id.scrollSteps)
        btnRoute = findViewById(R.id.btnRoute)
        btnCancelNav = findViewById(R.id.btnCancelNav)
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            m.cameraPosition = CameraPosition.Builder()
                .target(HOME)
                .zoom(1.5)
                .build()
            // Kanda igihe kirekire ku ikarita kugira ngo uhitemo aho ugiye
            m.addOnMapLongClickListener { point ->
                setDestination(point, null)
                true
            }
            loadStyle(m)
        }

        findViewById<Button>(R.id.btnSatellite).setOnClickListener { toggleSatellite() }
        findViewById<Button>(R.id.btnLocate).setOnClickListener { requestLocation() }
        findViewById<Button>(R.id.btnDownload).setOnClickListener { startDownload() }
        btnRoute.setOnClickListener { startRoute() }
        btnCancelNav.setOnClickListener { cancelNav() }

        // Gushakisha: wandika hanyuma ukande Enter / ikirahure kuri keyboard
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

        // Konti: akagati kerekana inyuguti ya mbere y'izina
        updateAvatar()
        btnAccount.setOnClickListener { editProfile() }
    }

    private fun show(text: String) {
        tvStatus.text = text
        tvStatus.visibility = View.VISIBLE
    }

    // ---------- Konti (izina rigumye muri telefone gusa) ----------

    private fun profileName(): String {
        return getSharedPreferences("profile", MODE_PRIVATE).getString("name", "") ?: ""
    }

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

    // ---------- Gushakisha ahantu (bisaba internet) ----------
    // Banza Nominatim (OpenStreetMap); nimba nta kintu itanze, tugerageza Photon.
    // Iyo hari ibisubizo byinshi, urahitamo muri urutonde.

    private fun searchPlace(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        show("Gushakisha...")
        searchNominatim(q) { results, err1 ->
            if (results.isNotEmpty()) {
                showResults(results)
            } else {
                searchPhoton(q) { results2, err2 ->
                    if (results2.isNotEmpty()) {
                        showResults(results2)
                    } else {
                        show(err2 ?: err1 ?: "Nta kintu cabonetse. Gerageza: izina ry'umuhanda + umujyi.")
                    }
                }
            }
        }
    }

    // Iyo ikarita yegereye (zoom 8+), dukunda ibisubizo biri hafi aho urimo
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
                out.add(
                    Place(
                        o.optString("display_name", ""),
                        o.getString("lat").toDouble(),
                        o.getString("lon").toDouble()
                    )
                )
            }
        } catch (ex: Exception) {
            // nta kintu
        }
        return out
    }

    private fun parsePhoton(body: String): List<Place> {
        val out = ArrayList<Place>()
        try {
            val feats = JSONObject(body).getJSONArray("features")
            for (i in 0 until feats.length()) {
                val f = feats.getJSONObject(i)
                val c = f.getJSONObject("geometry").getJSONArray("coordinates")
                out.add(
                    Place(
                        photonName(f.getJSONObject("properties")),
                        c.getDouble(1),
                        c.getDouble(0)
                    )
                )
            }
        } catch (ex: Exception) {
            // nta kintu
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

    private fun goTo(p: Place) {
        val m = map ?: return
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), 15.0), 1500)
        setDestination(LatLng(p.lat, p.lon), p.name.take(80))
    }

    // ---------- Kuyobora (online, binyuze kuri OSRM) ----------

    private fun setGeo(id: String, json: String) {
        val src = map?.style?.getSourceAs<GeoJsonSource>(id)
        src?.setGeoJson(json)
    }

    private fun pointJson(p: LatLng): String {
        return String.format(
            Locale.US,
            "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Point\",\"coordinates\":[%.6f,%.6f]}}",
            p.longitude, p.latitude
        )
    }

    private fun addRouteLayers(style: Style) {
        style.addSource(GeoJsonSource("route-src", EMPTY_FC))
        style.addSource(GeoJsonSource("dest-src", EMPTY_FC))
        style.addLayer(
            LineLayer("route-layer", "route-src").withProperties(
                PropertyFactory.lineColor("#1A73E8"),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        style.addLayer(
            CircleLayer("dest-layer", "dest-src").withProperties(
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleColor("#D93025"),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f)
            )
        )
    }

    private fun setDestination(p: LatLng, label: String?) {
        destination = p
        setGeo("route-src", EMPTY_FC)
        setGeo("dest-src", pointJson(p))
        navPanel.visibility = View.VISIBLE
        tvNavTitle.text = label
            ?: String.format(Locale.US, "Aho wahisemo (%.4f, %.4f)", p.latitude, p.longitude)
        scrollSteps.visibility = View.GONE
        btnRoute.visibility = View.VISIBLE
        btnRoute.isEnabled = true
    }

    private fun cancelNav() {
        destination = null
        setGeo("route-src", EMPTY_FC)
        setGeo("dest-src", EMPTY_FC)
        navPanel.visibility = View.GONE
        tvStatus.visibility = View.GONE
    }

    private fun startRoute() {
        val dest = destination ?: return
        btnRoute.isEnabled = false
        show("Gushaka aho uri (GPS)...")
        whenLocationReady { from -> fetchRoute(from, dest) }
    }

    private fun routeFailed(msg: String) {
        btnRoute.isEnabled = true
        show(msg)
    }

    private fun whenLocationReady(onReady: (LatLng) -> Unit) {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingAfterPermission = { whenLocationReady(onReady) }
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        activateLocation(false)
        pollLocation(onReady, 0)
    }

    private fun pollLocation(onReady: (LatLng) -> Unit, attempt: Int) {
        val loc: android.location.Location? = try {
            map?.locationComponent?.lastKnownLocation
        } catch (ex: Exception) {
            null
        }
        if (loc != null) {
            onReady(LatLng(loc.latitude, loc.longitude))
            return
        }
        if (attempt >= 15) {
            routeFailed("GPS ntiraboneka. Fungura Location, uve hanze, hanyuma ugerageze.")
            return
        }
        ui.postDelayed({ pollLocation(onReady, attempt + 1) }, 1500)
    }

    private fun fetchRoute(from: LatLng, to: LatLng) {
        show("Gushaka inzira...")
        val url = String.format(
            Locale.US,
            "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true",
            from.longitude, from.latitude, to.longitude, to.latitude
        )
        http.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { routeFailed("Nta internet. Kuyobora (online) bisaba internet.") }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.use { if (it.isSuccessful) it.body?.string() else null }
                runOnUiThread { handleRoute(body) }
            }
        })
    }

    private fun handleRoute(body: String?) {
        btnRoute.isEnabled = true
        val m = map ?: return
        if (body == null) {
            show("Inzira ntiyabonetse. Ongera ugerageze.")
            return
        }
        try {
            val root = JSONObject(body)
            if (root.optString("code") != "Ok") {
                show("Nta nzira yabonetse hagati y'aho uri n'aho ugiye.")
                return
            }
            val route = root.getJSONArray("routes").getJSONObject(0)
            val geometry = route.getJSONObject("geometry")
            setGeo(
                "route-src",
                "{\"type\":\"Feature\",\"properties\":{},\"geometry\":" + geometry.toString() + "}"
            )

            val lines = ArrayList<String>()
            val legs = route.getJSONArray("legs")
            var n = 1
            for (l in 0 until legs.length()) {
                val steps = legs.getJSONObject(l).getJSONArray("steps")
                for (i in 0 until steps.length()) {
                    val s = steps.getJSONObject(i)
                    val man = s.getJSONObject("maneuver")
                    val text = stepText(
                        man.optString("type"),
                        man.optString("modifier"),
                        s.optString("name"),
                        s.optDouble("distance", 0.0)
                    )
                    lines.add("$n. $text")
                    n++
                }
            }

            tvNavTitle.text = formatDist(route.getDouble("distance")) + "  ·  " +
                formatDur(route.getDouble("duration"))
            tvNavSteps.text = lines.joinToString("\n")
            scrollSteps.visibility = View.VISIBLE
            btnRoute.visibility = View.GONE
            tvStatus.visibility = View.GONE

            try {
                val coords = geometry.getJSONArray("coordinates")
                val b = LatLngBounds.Builder()
                for (i in 0 until coords.length()) {
                    val c = coords.getJSONArray(i)
                    b.include(LatLng(c.getDouble(1), c.getDouble(0)))
                }
                m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 120), 1200)
            } catch (ex: Exception) {
                // niba kwerekana inzira yose binaniwe, ntacyo bitwaye
            }
        } catch (ex: Exception) {
            show("Inzira ntishobotse gusomwa.")
        }
    }

    private fun stepText(type: String, modifier: String, name: String, dist: Double): String {
        val road = if (name.isNotEmpty()) " kuri $name" else ""
        val dir = when (modifier) {
            "left" -> "ibumoso"
            "right" -> "iburyo"
            "slight left" -> "ibumoso gato"
            "slight right" -> "iburyo gato"
            "sharp left" -> "ibumoso cane"
            "sharp right" -> "iburyo cane"
            else -> ""
        }
        val action = when (type) {
            "depart" -> "Tangura$road"
            "arrive" -> "Wageze iyo ugiye"
            "roundabout", "rotary", "roundabout turn" -> "Injira mu kizunguruka$road"
            else -> when {
                modifier == "uturn" -> "Subira inyuma$road"
                dir.isNotEmpty() -> "Hindukira $dir$road"
                else -> "Komeza imbere$road"
            }
        }
        return if (type == "arrive") action else action + " (" + formatDist(dist) + ")"
    }

    private fun formatDist(meters: Double): String {
        return if (meters >= 1000.0) {
            String.format(Locale.US, "%.1f km", meters / 1000.0)
        } else {
            "${meters.toInt()} m"
        }
    }

    private fun formatDur(seconds: Double): String {
        val min = maxOf(1, (seconds / 60.0).toInt())
        return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    }

    // ---------- Style ----------
    // Dukura style ya OpenFreeMap, tukayibika muri telefone (kugira ngo ikore offline).

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
                // niba byanze, dukoresha style isanzwe
            }
        }
        m.setStyle(builder) { style ->
            addSatellite(style)
            addRouteLayers(style)
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
        if (ok) {
            showMyLocation()
        } else {
            pendingAfterPermission = null
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun showMyLocation() {
        activateLocation(true)
    }

    @Suppress("MissingPermission")
    private fun activateLocation(track: Boolean) {
        val m = map ?: return
        val style = m.style ?: return
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
    override fun onDestroy() { super.onDestroy(); if (::mapView.isInitialized) mapView.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::mapView.isInitialized) mapView.onSaveInstanceState(outState)
    }
}
