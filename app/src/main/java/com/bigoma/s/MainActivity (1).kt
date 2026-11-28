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
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.module.http.HttpRequestUtil

class MainActivity : AppCompatActivity() {


    internal lateinit var mapView: MapView
    internal lateinit var tvStatus: TextView
    internal lateinit var etSearch: EditText
    internal lateinit var btnAccount: TextView
    internal lateinit var navPanel: View
    internal lateinit var tvNavTitle: TextView
    internal lateinit var tvNavSteps: TextView
    internal lateinit var scrollSteps: View
    internal lateinit var btnRoute: Button
    internal lateinit var btnCancelNav: Button
    internal lateinit var http: OkHttpClient
    internal var map: MapLibreMap? = null
    internal var satelliteOn = false
    internal var destination: LatLng? = null
    internal var locationActive = false
    internal var pendingAfterPermission: (() -> Unit)? = null
    internal val ui = Handler(Looper.getMainLooper())

    // Kuyobora
    internal var mode = Mode.CAR
    internal val modeViews = HashMap<Mode, TextView>()
    internal lateinit var banner: TextView
    internal lateinit var btnStart: Button
    internal lateinit var searchBarView: View
    internal var navSteps: List<NavStep> = emptyList()
    internal var routePts: List<LatLng> = emptyList()
    internal var routeDist = 0.0
    internal var routeDur = 0.0
    internal var routeShown = false
    internal var navActive = false
    internal var nextIdx = 1
    internal var minToNext = Double.MAX_VALUE
    internal var offCount = 0
    internal var lastReroute = 0L
    internal var arrived = false
    internal val navTick = object : Runnable {
        override fun run() {
            if (!navActive) return
            navUpdate()
            ui.postDelayed(this, 1000)
        }
    }

    internal val permissionLauncher =
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
                            "BigomaMaps/0.14 (Android map app; +https://github.com/bigomasss-maker/Bigoma.s)"
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
        searchBarView = findViewById(R.id.searchBar)
        buildNavUi()

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
    internal fun bannerReady(): Boolean = ::banner.isInitialized
    internal fun show(text: String) {
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
    // 1) Nominatim, 2) Photon, 3) niba hari nimero y'inzu itabonetse,
    // dushakisha umuhanda gusa.

    internal fun requestLocation() {
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

    internal fun showMyLocation() {
        activateLocation(true)
    }

    @Suppress("MissingPermission")
    internal fun activateLocation(track: Boolean) {
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
    override fun onDestroy() { ui.removeCallbacksAndMessages(null); super.onDestroy(); if (::mapView.isInitialized) mapView.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::mapView.isInitialized) mapView.onSaveInstanceState(outState)
    }
}
}
