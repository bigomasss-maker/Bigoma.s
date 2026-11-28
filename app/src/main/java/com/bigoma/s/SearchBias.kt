package com.bigoma.s

import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import java.io.IOException
import java.util.Locale

// Gushakisha ahantu (Nominatim, Photon) no kwerekana ibisubizo

internal fun MainActivity.searchPlace(query: String) {
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

// Iyo ikarita yegereye (zoom 8+), dukunda ibisubizo biri hafi aho urimo
internal fun MainActivity.searchBias(): LatLngBounds? {
    val m = map ?: return null
    if (m.cameraPosition.zoom < 8.0) return null
    return m.projection.visibleRegion.latLngBounds
}

internal fun MainActivity.getText(url: HttpUrl, done: (String?, String?) -> Unit) {
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

internal fun MainActivity.searchNominatim(q: String, done: (List<Place>, String?) -> Unit) {
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

internal fun MainActivity.searchPhoton(q: String, done: (List<Place>, String?) -> Unit) {
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

internal fun MainActivity.parseNominatim(body: String): List<Place> {
    val out = ArrayList<Place>()
    try {
        val arr = JSONArray(body)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            var box: DoubleArray? = null
            val bb = o.optJSONArray("boundingbox")
            if (bb != null && bb.length() == 4) {
                // Nominatim: [south, north, west, east]
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
                ) {
                    geo = g.toString()
                }
            }
            out.add(
                Place(
                    o.optString("display_name", ""),
                    o.getString("lat").toDouble(),
                    o.getString("lon").toDouble(),
                    box,
                    geo
                )
            )
        }
    } catch (ex: Exception) {
        // nta kintu
    }
    // Ibisubizo bifise umurongo w'ahantu (Polygon) bibanza
    return out.sortedByDescending {
        val g = it.geo
        if (g != null && g.contains("Polygon")) 1 else 0
    }
}

internal fun MainActivity.parsePhoton(body: String): List<Place> {
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
                // Photon: [minLon, maxLat, maxLon, minLat]
                box = doubleArrayOf(
                    ex.getDouble(3),
                    ex.getDouble(0),
                    ex.getDouble(1),
                    ex.getDouble(2)
                )
            }
            out.add(Place(photonName(props), c.getDouble(1), c.getDouble(0), box))
        }
    } catch (ex: Exception) {
        // nta kintu
    }
    return out
}

internal fun MainActivity.photonName(p: JSONObject): String {
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

internal fun MainActivity.showResults(list: List<Place>) {
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

// Niba ahantu ari hanini (umujyi, umuhanda, akarere), twerekana hose.
// Niba ari akadomo (inzu), twegera zoom 15.
internal fun MainActivity.goTo(p: Place) {
    val m = map ?: return
    val bx = p.bbox
    var fitted = false
    if (bx != null && (bx[2] - bx[0] > 0.004 || bx[3] - bx[1] > 0.004)) {
        try {
            val b = LatLngBounds.Builder()
                .include(LatLng(bx[0], bx[1]))
                .include(LatLng(bx[2], bx[3]))
                .build()
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 100), 1500)
            fitted = true
        } catch (ex: Exception) {
            fitted = false
        }
    }
    if (!fitted) {
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), 15.0), 1500)
    }
    setDestination(LatLng(p.lat, p.lon), p.name.take(80))
    // Erekana umurongo n'ibara ry'ahantu hose (umujyi, province, commune...)
    if (p.geo != null) {
        setGeo("area-src", "{\"type\":\"Feature\",\"properties\":{},\"geometry\":" + p.geo + "}")
    } else if (bx != null && (bx[2] - bx[0] > 0.004 || bx[3] - bx[1] > 0.004)) {
        // Nta murongo nyawo muri OSM: twerekana agasanduku k'ahantu
        val ring = String.format(
            Locale.US,
            "[[%.6f,%.6f],[%.6f,%.6f],[%.6f,%.6f],[%.6f,%.6f],[%.6f,%.6f]]",
            bx[1], bx[0], bx[3], bx[0], bx[3], bx[2], bx[1], bx[2], bx[1], bx[0]
        )
        setGeo(
            "area-src",
            "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[" + ring + "]}}"
        )
        Toast.makeText(
            this,
            "Umurongo nyawo ntiboneka muri OpenStreetMap. Herekanywe agasanduku k'ahantu.",
            Toast.LENGTH_LONG
        ).show()
    }
}

// ---------- Kuyobora (online, binyuze kuri OSRM) ----------
