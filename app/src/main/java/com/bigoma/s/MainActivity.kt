package com.bigoma.s

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var satelliteOn = false

    private val styleJson = """
    {
      "version": 8,
      "sources": {
        "osm": {
          "type": "raster",
          "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
          "tileSize": 256,
          "maxzoom": 19
        },
        "sat": {
          "type": "raster",
          "tiles": ["https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"],
          "tileSize": 256,
          "maxzoom": 19
        }
      },
      "layers": [
        { "id": "osm", "type": "raster", "source": "osm" },
        { "id": "sat", "type": "raster", "source": "sat", "layout": { "visibility": "none" } }
      ]
    }
    """.trimIndent()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) showMyLocation()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            m.cameraPosition = CameraPosition.Builder()
                .target(LatLng(-3.3822, 29.3644))
                .zoom(12.0)
                .build()
            m.setStyle(Style.Builder().fromJson(styleJson))
        }

        findViewById<Button>(R.id.btnSatellite).setOnClickListener { toggleSatellite() }
        findViewById<Button>(R.id.btnLocate).setOnClickListener { requestLocation() }
    }

    private fun toggleSatellite() {
        val style = map?.style ?: return
        satelliteOn = !satelliteOn
        style.getLayer("sat")?.setProperties(
            PropertyFactory.visibility(if (satelliteOn) Property.VISIBLE else Property.NONE)
        )
    }

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

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { super.onDestroy(); mapView.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
