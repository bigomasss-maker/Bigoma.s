package com.bigoma.s

import org.maplibre.android.geometry.LatLng

// bbox = [south, west, north, east] (ubugari bw'ahantu, bishobora kuba null)
data class Place(val name: String, val lat: Double, val lon: Double, val bbox: DoubleArray? = null, val geo: String? = null)

// Intambwe imwe y'inzira: aho uhindukira (lat/lon), ico ukora, intera ikurikira
class NavStep(
    val type: String,
    val modifier: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val dist: Double
)

// Uburyo bwo kugenda
enum class Mode(val path: String, val label: String, val offRoute: Double) {
    WALK("routed-foot", "🚶 Amaguru", 30.0),
    BIKE("routed-bike", "🚲 Igare", 40.0),
    MOTO("routed-car", "🏍 Ipikipiki", 60.0),
    CAR("routed-car", "🚗 Imodoka", 60.0)
}

// Ibisanzwe by'app
// Map y'amabarabara n'amazu (vector) ya OpenFreeMap
const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
const val MAX_ZOOM = 14.0
// Amategeko ya OpenFreeMap: tiles 3000 na 50 MB kuri buri gukuramo.
const val MAX_TILES = 1500L
const val MAX_BYTES = 45_000_000L
const val EMPTY_FC = "{\"type\":\"FeatureCollection\",\"features\":[]}"
val HOME: LatLng = LatLng(-3.3822, 29.3644) // Bujumbura
