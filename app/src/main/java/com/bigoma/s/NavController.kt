
package com.bigoma.s

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.IOException
import java.util.Locale

class NavController(
    private val act: AppCompatActivity,
    private val http: OkHttpClient,
    private val map: () -> MapLibreMap?,
    private val ensureLocation: (() -> Unit) -> Unit,
    private val enablePuck: () -> Unit,
    private val onNavigating: (Boolean) -> Unit
) {
    private var destination: LatLng? = null
    private var destinationName: String? = null
    private var routeDialog: AlertDialog? = null
    private var request: Call? = null

    fun setDestination(point: LatLng, label: String?) {
        destination = point
        destinationName = label

        ensureLocation {
            enablePuck()
            findCurrentLocation()
        }
    }

    private fun findCurrentLocation() {
        if (!hasLocationPermission()) {
            permissionDenied()
            return
        }

        val manager = act.getSystemService(
            Context.LOCATION_SERVICE
        ) as LocationManager

        var best: Location? = null

        try {
            for (provider in manager.getProviders(true)) {
                val location = manager.getLastKnownLocation(provider)
                if (location != null &&
                    (best == null || location.accuracy < best.accuracy)
                ) {
                    best = location
                }
            }
        } catch (e: SecurityException) {
            permissionDenied()
            return
        }

        if (best == null) {
            Toast.makeText(
                act,
                "GPS ntirabona aho uri. Fungura Location hanyuma ugerageze.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val target = destination ?: return
        getRoute(
            LatLng(best.latitude, best.longitude),
            target
        )
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            act,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                act,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getRoute(start: LatLng, end: LatLng) {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("router.project-osrm.org")
            .addPathSegments("route/v1/driving")
            .addPathSegment(
                String.format(
                    Locale.US,
                    "%.6f,%.6f;%.6f,%.6f",
                    start.longitude,
                    start.latitude,
                    end.longitude,
                    end.latitude
                )
            )
            .addQueryParameter("overview", "full")
            .addQueryParameter("geometries", "geojson")
            .addQueryParameter("steps", "true")
            .build()

        request?.cancel()

        request = http.newCall(
            Request.Builder().url(url).build()
        )

        request?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                act.runOnUiThread {
                    if (!call.isCanceled()) {
                        Toast.makeText(
                            act,
                            "Ntivyashobotse kuronka inzira. Suzuma internet.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.use {
                    if (it.isSuccessful) it.body?.string() else null
                }

                act.runOnUiThread {
                    if (call.isCanceled()) return@runOnUiThread

                    if (body == null) {
                        Toast.makeText(
                            act,
                            "Server y'inzira ntiyishuye neza.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@runOnUiThread
                    }

                    try {
                        displayRoute(body, start, end)
                    } catch (e: Exception) {
                        Toast.makeText(
                            act,
                            "Ikosa mu gusoma inzira.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })
    }

    private fun displayRoute(
        body: String,
        start: LatLng,
        end: LatLng
    ) {
        val root = JSONObject(body)

        if (root.optString("code") != "Ok") {
            Toast.makeText(
                act,
                "Nta nzira yabonetse hagati y'aha hantu.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val routes = root.getJSONArray("routes")
        if (routes.length() == 0) return

        val route = routes.getJSONObject(0)
        val geometry = route.getJSONObject("geometry")

        val feature = JSONObject()
            .put("type", "Feature")
            .put("properties", JSONObject())
            .put("geometry", geometry)

        val featureCollection = JSONObject()
            .put("type", "FeatureCollection")
            .put("features", JSONArray().put(feature))

        val destinationFeature = JSONObject()
            .put("type", "Feature")
            .put("properties", JSONObject())
            .put(
                "geometry",
                JSONObject()
                    .put("type", "Point")
                    .put(
                        "coordinates",
                        JSONArray()
                            .put(end.longitude)
                            .put(end.latitude)
                    )
            )

        val destinationCollection = JSONObject()
            .put("type", "FeatureCollection")
            .put("features", JSONArray().put(destinationFeature))

        map()?.style?.let { style ->
            style.getSourceAs<GeoJsonSource>("route-src")
                ?.setGeoJson(featureCollection.toString())

            style.getSourceAs<GeoJsonSource>("dest-src")
                ?.setGeoJson(destinationCollection.toString())
        }

        val distance = route.optDouble("distance", 0.0)
        val duration = route.optDouble("duration", 0.0)

        val stepsText = StringBuilder()
        val legs = route.optJSONArray("legs")

        if (legs != null && legs.length() > 0) {
            val steps = legs.getJSONObject(0)
                .optJSONArray("steps")

            if (steps != null) {
                for (i in 0 until minOf(steps.length(), 8)) {
                    val step = steps.getJSONObject(i)
                    val maneuver = step.optJSONObject("maneuver")
                    val instruction = maneuver?.optString(
                        "instruction", ""
                    ) ?: ""

                    if (instruction.isNotBlank()) {
                        stepsText.append("• ")
                            .append(instruction)
                            .append("\n")
                    }
                }
            }
        }

        val distanceText = if (distance >= 1000) {
            String.format(Locale.US, "%.1f km", distance / 1000)
        } else {
            "${distance.toInt()} m"
        }

        val minutes = (duration / 60).toInt()

        showRouteDialog(
            "Aho ugiye: ${destinationName ?: "Aho wahisemwo"}\n\n" +
                "Intera: $distanceText\n" +
                "Igihe cagereranyijwe: $minutes min\n\n" +
                stepsText.toString()
        )
    }

    private fun showRouteDialog(message: String) {
        routeDialog?.dismiss()

        onNavigating(true)

        routeDialog = AlertDialog.Builder(act)
            .setTitle("BIGOMA MAPS — Inzira")
            .setMessage(message)
            .setPositiveButton("Hagarika inzira") { _, _ ->
                cancel()
            }
            .setNegativeButton("Funga", null)
            .create()

        routeDialog?.setOnDismissListener {
            onNavigating(false)
        }

        routeDialog?.show()
    }

    fun permissionDenied() {
        Toast.makeText(
            act,
            "Ukeneye kwemerera BIGOMA MAPS gukoresha Location.",
            Toast.LENGTH_LONG
        ).show()
    }

    fun cancel() {
        request?.cancel()
        request = null

        routeDialog?.setOnDismissListener(null)
        routeDialog?.dismiss()
        routeDialog = null

        map()?.style?.let { style ->
            style.getSourceAs<GeoJsonSource>("route-src")
                ?.setGeoJson(MainActivity.EMPTY_FC)

            style.getSourceAs<GeoJsonSource>("dest-src")
                ?.setGeoJson(MainActivity.EMPTY_FC)
        }

        destination = null
        destinationName = null
        onNavigating(false)
    }
}
