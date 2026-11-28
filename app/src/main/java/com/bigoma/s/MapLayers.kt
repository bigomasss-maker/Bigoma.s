package com.bigoma.s

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.FillLayer
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

// Ikarita: style, satellite, imirongo y'aho n'inzira, ikimenyetso c'ahantu

internal fun MainActivity.setGeo(id: String, json: String) {
    val src = map?.style?.getSourceAs<GeoJsonSource>(id)
    src?.setGeoJson(json)
}

internal fun MainActivity.pointJson(p: LatLng): String {
    return String.format(
        Locale.US,
        "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Point\",\"coordinates\":[%.6f,%.6f]}}",
        p.longitude, p.latitude
    )
}

internal fun MainActivity.addAreaLayers(style: Style) {
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

internal fun MainActivity.addRouteLayers(style: Style) {
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

// Ikimenyetso c'ahantu (pin) gishushanyijwe n'app ubwayo
internal fun MainActivity.makePinBitmap(): android.graphics.Bitmap {
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

internal fun MainActivity.loadStyle(m: MapLibreMap) {
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

internal fun MainActivity.applyStyle(m: MapLibreMap, json: String?) {
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
        addAreaLayers(style)
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

internal fun MainActivity.addSatellite(style: Style) {
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

internal fun MainActivity.toggleSatellite() {
    val style = map?.style ?: return
    satelliteOn = !satelliteOn
    style.getLayer("sat-layer")?.setProperties(
        PropertyFactory.visibility(if (satelliteOn) Property.VISIBLE else Property.NONE)
    )
}

// ---------- Gukuramo agace (offline) ----------
