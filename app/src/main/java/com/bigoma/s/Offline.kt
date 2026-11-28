package com.bigoma.s

import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

// Gukuramo agace ukakoresha nta internet

internal fun MainActivity.startDownload() {
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

internal fun MainActivity.observe(region: OfflineRegion) {
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

internal fun MainActivity.countTiles(b: LatLngBounds, minZ: Int, maxZ: Int): Long {
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

internal fun MainActivity.lonToTile(lon: Double, n: Int): Int =
    floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)

internal fun MainActivity.latToTile(lat: Double, n: Int): Int {
    val rad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
    return floor((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n)
        .toInt().coerceIn(0, n - 1)
}

// ---------- Aho ndi (GPS) ----------
