package com.bigoma.s

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import java.io.IOException
import java.util.Locale

// Kuyobora (1): amahitamwo y'ibinyabiziga, gushaka inzira (OSRM)

internal fun MainActivity.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

internal fun MainActivity.roundBg(color: String, radiusDp: Int): android.graphics.drawable.GradientDrawable {
    val g = android.graphics.drawable.GradientDrawable()
    g.setColor(android.graphics.Color.parseColor(color))
    g.cornerRadius = dp(radiusDp).toFloat()
    return g
}

// Dukora ibintu byo kuyobora muri code (nta layout nshasha dukeneye)
internal fun MainActivity.buildNavUi() {
    val panel = navPanel as LinearLayout

    // Amahitamwo y'ibinyabiziga
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.setPadding(0, dp(8), 0, 0)
    for (m in Mode.values()) {
        val b = TextView(this)
        b.text = m.label
        b.textSize = 12f
        b.gravity = Gravity.CENTER
        b.setPadding(dp(4), dp(9), dp(4), dp(9))
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(dp(2), 0, dp(2), 0)
        b.layoutParams = lp
        b.setOnClickListener { selectMode(m) }
        modeViews[m] = b
        row.addView(b)
    }
    panel.addView(row, 1)
    styleModes()

    // Buto "Tangura" (itangura kuyobora)
    btnStart = Button(this)
    btnStart.text = "Tangura"
    btnStart.visibility = View.GONE
    btnStart.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    btnStart.setOnClickListener { startNavigation() }
    val btnRow = panel.getChildAt(panel.childCount - 1) as LinearLayout
    btnRow.addView(btnStart, 1)

    // Akabaho ko hejuru kerekana ico ugiye gukora
    banner = TextView(this)
    banner.setTextColor(android.graphics.Color.WHITE)
    banner.textSize = 20f
    banner.typeface = android.graphics.Typeface.DEFAULT_BOLD
    banner.setPadding(dp(16), dp(12), dp(16), dp(12))
    banner.background = roundBg("#0F766E", 16)
    banner.elevation = dp(10).toFloat()
    banner.visibility = View.GONE
    val lp = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.TOP
    )
    lp.setMargins(dp(12), dp(12), dp(12), 0)
    (mapView.parent as FrameLayout).addView(banner, lp)
}

internal fun MainActivity.styleModes() {
    for ((m, v) in modeViews) {
        if (m == mode) {
            v.background = roundBg("#1A73E8", 18)
            v.setTextColor(android.graphics.Color.WHITE)
        } else {
            v.background = roundBg("#E8EAED", 18)
            v.setTextColor(android.graphics.Color.parseColor("#202124"))
        }
    }
}

internal fun MainActivity.selectMode(m: Mode) {
    mode = m
    styleModes()
    // Iyo inzira yari yerekanywe, tuyisubiramo n'ubwo buryo bushasha
    if (destination != null && routeShown && !navActive) startRoute()
}

internal fun MainActivity.setDestination(p: LatLng, label: String?) {
    stopNavigation()
    destination = p
    routeShown = false
    setGeo("area-src", EMPTY_FC)
    setGeo("route-src", EMPTY_FC)
    setGeo("dest-src", pointJson(p))
    navPanel.visibility = View.VISIBLE
    tvNavTitle.text = label
        ?: String.format(Locale.US, "Aho wahisemo (%.4f, %.4f)", p.latitude, p.longitude)
    scrollSteps.visibility = View.GONE
    btnRoute.visibility = View.VISIBLE
    btnRoute.isEnabled = true
    btnStart.visibility = View.GONE
}

internal fun MainActivity.cancelNav() {
    stopNavigation()
    destination = null
    routeShown = false
    setGeo("area-src", EMPTY_FC)
    setGeo("route-src", EMPTY_FC)
    setGeo("dest-src", EMPTY_FC)
    navPanel.visibility = View.GONE
    tvStatus.visibility = View.GONE
}

internal fun MainActivity.startRoute() {
    val dest = destination ?: return
    btnRoute.isEnabled = false
    show("Gushaka aho uri (GPS)...")
    whenLocationReady { from -> fetchRoute(from, dest) }
}

internal fun MainActivity.routeFailed(msg: String) {
    btnRoute.isEnabled = true
    show(msg)
}

internal fun MainActivity.whenLocationReady(onReady: (LatLng) -> Unit) {
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

internal fun MainActivity.pollLocation(onReady: (LatLng) -> Unit, attempt: Int) {
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

internal fun MainActivity.fetchRoute(from: LatLng, to: LatLng) {
    if (!navActive) show("Gushaka inzira...")
    val url = String.format(
        Locale.US,
        "https://routing.openstreetmap.de/%s/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true",
        mode.path, from.longitude, from.latitude, to.longitude, to.latitude
    )
    http.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            runOnUiThread { routeFailed("Nta internet. Kuyobora (online) bisaba internet.") }
        }

        override fun onResponse(call: Call, response: Response) {
            val code = response.code
            val body = response.use { if (it.isSuccessful) it.body?.string() else null }
            runOnUiThread {
                if (body == null) routeFailed("Inzira ntiyabonetse (HTTP $code).")
                else handleRoute(body)
            }
        }
    })
}

internal fun MainActivity.handleRoute(body: String?) {
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

        val steps2 = ArrayList<NavStep>()
        val lines = ArrayList<String>()
        val legs = route.getJSONArray("legs")
        var n = 1
        for (l in 0 until legs.length()) {
            val steps = legs.getJSONObject(l).getJSONArray("steps")
            for (i in 0 until steps.length()) {
                val s = steps.getJSONObject(i)
                val man = s.getJSONObject("maneuver")
                val loc = man.getJSONArray("location")
                val st = NavStep(
                    man.optString("type"),
                    man.optString("modifier"),
                    s.optString("name"),
                    loc.getDouble(1),
                    loc.getDouble(0),
                    s.optDouble("distance", 0.0)
                )
                steps2.add(st)
                lines.add("$n. " + stepText(st))
                n++
            }
        }

        val pts = ArrayList<LatLng>()
        val coords = geometry.getJSONArray("coordinates")
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            pts.add(LatLng(c.getDouble(1), c.getDouble(0)))
        }

        navSteps = steps2
        routePts = pts
        routeDist = route.getDouble("distance")
        routeDur = route.getDouble("duration")
        routeShown = true
        nextIdx = if (steps2.size > 1) 1 else 0
        minToNext = Double.MAX_VALUE
        offCount = 0

        // Iyo turi mu kuyobora (reroute), ntitwongera guhindura ecran
        if (navActive) return

        var foot = ""
        if (mode == Mode.MOTO) foot = "\n(Ipikipiki: inzira ni iy'imodoka.)"
        tvNavTitle.text = mode.label + "  ·  " + formatDist(routeDist) + "  ·  " + formatDur(routeDur)
        tvNavSteps.text = lines.joinToString("\n") +
            "\n\nAmakuru: © OpenStreetMap. Inzira: routing.openstreetmap.de" + foot
        scrollSteps.visibility = View.VISIBLE
        btnRoute.visibility = View.GONE
        btnStart.visibility = View.VISIBLE
        tvStatus.visibility = View.GONE

        try {
            val b = LatLngBounds.Builder()
            for (pt in pts) b.include(pt)
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 120), 1200)
        } catch (ex: Exception) {
            // niba kwerekana inzira yose binaniwe, ntacyo bitwaye
        }
    } catch (ex: Exception) {
        show("Inzira ntishobotse gusomwa.")
    }
}

// ---------- Kuyobora igihe ugenda ----------
