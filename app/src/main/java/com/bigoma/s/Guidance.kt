package com.bigoma.s

import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sqrt

// Kuyobora (2): kuyobora igihe ugenda, kongera gushaka inzira, amabwiriza

internal fun MainActivity.startNavigation() {
    if (navSteps.isEmpty()) return
    navActive = true
    arrived = false
    nextIdx = if (navSteps.size > 1) 1 else 0
    minToNext = Double.MAX_VALUE
    offCount = 0
    lastReroute = System.currentTimeMillis()
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    searchBarView.visibility = View.GONE
    scrollSteps.visibility = View.GONE
    btnStart.visibility = View.GONE
    btnRoute.visibility = View.GONE
    (navPanel as LinearLayout).getChildAt(1).visibility = View.GONE
    val lpS = tvStatus.layoutParams as FrameLayout.LayoutParams
    lpS.topMargin = dp(130)
    tvStatus.layoutParams = lpS
    banner.visibility = View.VISIBLE
    banner.text = "Gutangura..."
    try {
        activateLocation(false)
        val lc = map?.locationComponent
        if (lc != null) {
            lc.renderMode = RenderMode.GPS
            lc.cameraMode = CameraMode.TRACKING_GPS
            lc.zoomWhileTracking(17.0)
        }
    } catch (ex: Exception) {
        // GPS itaraboneka: tuzakomeza kugerageza
    }
    ui.removeCallbacks(navTick)
    ui.post(navTick)
}

internal fun MainActivity.stopNavigation() {
    if (!bannerReady()) return
    val wasActive = navActive
    navActive = false
    ui.removeCallbacks(navTick)
    banner.visibility = View.GONE
    searchBarView.visibility = View.VISIBLE
    (navPanel as LinearLayout).getChildAt(1).visibility = View.VISIBLE
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    val lpS = tvStatus.layoutParams as FrameLayout.LayoutParams
    lpS.topMargin = dp(80)
    tvStatus.layoutParams = lpS
    if (wasActive) {
        try {
            val lc = map?.locationComponent
            if (lc != null && locationActive) {
                lc.cameraMode = CameraMode.NONE
                lc.renderMode = RenderMode.NORMAL
            }
        } catch (ex: Exception) {
            // nta kintu
        }
    }
}

internal fun MainActivity.navUpdate() {
    if (navSteps.isEmpty()) return
    val loc: android.location.Location? = try {
        map?.locationComponent?.lastKnownLocation
    } catch (ex: Exception) {
        null
    }
    if (loc == null) {
        banner.text = "Gushaka GPS..."
        return
    }
    val pos = LatLng(loc.latitude, loc.longitude)
    val last = navSteps.size - 1

    // Kujya imbere ku ntambwe ikurikira iyo uyigezeho cangwa wayirenze
    var guard = 0
    while (nextIdx < last && guard < 5) {
        guard++
        val st = navSteps[nextIdx]
        val d = pos.distanceTo(LatLng(st.lat, st.lon))
        if (d < minToNext) minToNext = d
        val reached = d < 25.0
        val passed = minToNext < 60.0 && d > minToNext + 30.0
        if (reached || passed) {
            nextIdx++
            minToNext = Double.MAX_VALUE
        } else {
            break
        }
    }

    val st = navSteps[nextIdx]
    val dNext = pos.distanceTo(LatLng(st.lat, st.lon))
    if (dNext < minToNext) minToNext = dNext

    // Wageze
    if (nextIdx == last && dNext < 30.0) {
        if (!arrived) {
            arrived = true
            banner.text = "🏁 Wageze iyo ugiye"
            tvNavTitle.text = "Wageze ✓"
            ui.postDelayed({ if (arrived) stopNavigation() }, 6000)
        }
        return
    }

    // Ico ugiye gukora
    banner.text = stepArrow(st) + "  " + formatDist(dNext) + "\n" + stepAction(st)

    // Intera isigaye n'igihe
    var rem = dNext
    for (k in nextIdx until navSteps.size) rem += navSteps[k].dist
    val eta = if (routeDist > 0.0) routeDur * (rem / routeDist) else 0.0
    tvNavTitle.text = mode.label + "  ·  " + formatDist(rem) + "  ·  " + formatDur(eta)

    // Niba wavuye mu nzira: dushaka indi
    val off = distToRoute(pos)
    if (off > mode.offRoute) offCount++ else offCount = 0
    val now = System.currentTimeMillis()
    if (offCount >= 4 && now - lastReroute > 15000) {
        lastReroute = now
        offCount = 0
        banner.text = "Gushaka inzira nshasha..."
        val dest = destination
        if (dest != null) fetchRoute(pos, dest)
    }
}

// Intera iri hagati y'aho uri n'umurongo w'inzira (muri metero)
internal fun MainActivity.distToRoute(p: LatLng): Double {
    val pts = routePts
    if (pts.size < 2) return 0.0
    val kx = 111320.0 * cos(Math.toRadians(p.latitude))
    val ky = 110540.0
    var best = Double.MAX_VALUE
    for (i in 0 until pts.size - 1) {
        val ax = (pts[i].longitude - p.longitude) * kx
        val ay = (pts[i].latitude - p.latitude) * ky
        val bx = (pts[i + 1].longitude - p.longitude) * kx
        val by = (pts[i + 1].latitude - p.latitude) * ky
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = if (len2 == 0.0) 0.0 else (-ax * dx - ay * dy) / len2
        if (t < 0.0) t = 0.0
        if (t > 1.0) t = 1.0
        val cx = ax + t * dx
        val cy = ay + t * dy
        val d = sqrt(cx * cx + cy * cy)
        if (d < best) best = d
    }
    return best
}

internal fun MainActivity.stepArrow(st: NavStep): String {
    if (st.type == "arrive") return "🏁"
    if (st.type == "roundabout" || st.type == "rotary" || st.type == "roundabout turn") return "⟳"
    return when (st.modifier) {
        "left" -> "↰"
        "right" -> "↱"
        "slight left" -> "↖"
        "slight right" -> "↗"
        "sharp left" -> "↙"
        "sharp right" -> "↘"
        "uturn" -> "↩"
        else -> "↑"
    }
}

internal fun MainActivity.stepAction(st: NavStep): String {
    val road = if (st.name.isNotEmpty()) " kuri ${st.name}" else ""
    val dir = when (st.modifier) {
        "left" -> "ibumoso"
        "right" -> "iburyo"
        "slight left" -> "ibumoso gato"
        "slight right" -> "iburyo gato"
        "sharp left" -> "ibumoso cane"
        "sharp right" -> "iburyo cane"
        else -> ""
    }
    return when (st.type) {
        "depart" -> "Tangura$road"
        "arrive" -> "Wageze iyo ugiye"
        "roundabout", "rotary", "roundabout turn" -> "Injira mu kizunguruka$road"
        else -> when {
            st.modifier == "uturn" -> "Subira inyuma$road"
            dir.isNotEmpty() -> "Hindukira $dir$road"
            else -> "Komeza imbere$road"
        }
    }
}

internal fun MainActivity.stepText(st: NavStep): String {
    val a = stepAction(st)
    return if (st.type == "arrive") a else a + " (" + formatDist(st.dist) + ")"
}

internal fun MainActivity.formatDist(meters: Double): String {
    return if (meters >= 1000.0) {
        String.format(Locale.US, "%.1f km", meters / 1000.0)
    } else {
        "${meters.toInt()} m"
    }
}

internal fun MainActivity.formatDur(seconds: Double): String {
    val min = maxOf(1, (seconds / 60.0).toInt())
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}
