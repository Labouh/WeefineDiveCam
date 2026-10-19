package org.zorro.weefinedivecam

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.ExifInterface
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Geotags dive photos. GPS can't reach the phone underwater but works through the housing at
 * the surface, so photos taken during a dive are queued and positioned afterwards, by time,
 * between the entry fix (last one before descending) and the exit fix (first good one after
 * surfacing). With only one of the two, that one is used.
 *
 * The GPS is never left on: one fix every [SURFACE_FIX_INTERVAL] at the surface, off underwater,
 * then on continuously after surfacing only until an accurate exit fix is found.
 *
 * Not thread-safe: every public method must be called on the main thread.
 */
class DiveGeotagger(
    private val context: Context,
    private val scope: CoroutineScope,
    private val currentDepth: () -> Double?, // null while the housing is disconnected
) {
    private data class Fix(val latitude: Double, val longitude: Double, val time: Long)
    private data class PendingPhoto(val uri: Uri, val time: Long, val depth: Double)

    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val prefs = context.getSharedPreferences("geotag", Context.MODE_PRIVATE)

    private var lastFix: Fix? = null
    private var entryFix: Fix? = null
    private val pending = mutableListOf<PendingPhoto>()

    private var diving = false
    private var lastUnderwaterAt = 0L
    private var lastFixAttemptAt = 0L
    private var singleFixRequest: CancellationSignal? = null
    private var exitSeek: LocationListener? = null

    init {
        restore()
        scope.launch {
            while (true) {
                tick()
                delay(1.seconds)
            }
        }
    }

    /** Most recent fix if still close enough in time to describe where we are, for videos. */
    val recentLocation: Location?
        get() = lastFix?.takeIf { System.currentTimeMillis() - it.time <= ENTRY_MAX_AGE.inWholeMilliseconds }
            ?.let { fix ->
                Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = fix.latitude
                    longitude = fix.longitude
                    time = fix.time
                }
            }

    /** Queues freshly saved files (original and corrected copy of one shot) for geotagging. */
    fun addPhotos(uris: List<Uri>, takenAt: Long, depth: Double) {
        uris.forEach { pending += PendingPhoto(it, takenAt, depth) }
        persist()
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val depth = currentDepth()
        // Unknown while the housing is disconnected: keep the current phase
        if (depth != null) {
            if (depth > 0.0) {
                lastUnderwaterAt = now
                if (!diving) startDive(now)
            } else if (diving && now - lastUnderwaterAt >= SURFACED_AFTER.inWholeMilliseconds) {
                // The housing reports 0 m in the first metre or so: require a while at 0 before trusting it
                diving = false
                startExitSeek()
            }
        }

        if (!diving && exitSeek == null && singleFixRequest == null &&
            now - lastFixAttemptAt >= SURFACE_FIX_INTERVAL.inWholeMilliseconds
        ) {
            requestSingleFix(now)
        }
    }

    private fun startDive(now: Long) {
        diving = true
        singleFixRequest?.cancel()
        singleFixRequest = null
        stopExitSeek()
        entryFix = lastFix?.takeIf { now - it.time <= ENTRY_MAX_AGE.inWholeMilliseconds }
        persist()
    }

    @SuppressLint("MissingPermission")
    private fun requestSingleFix(now: Long) {
        lastFixAttemptAt = now
        if (!hasLocationPermission()) return
        val signal = CancellationSignal()
        singleFixRequest = signal
        try {
            locationManager.getCurrentLocation(LocationManager.GPS_PROVIDER, signal, ContextCompat.getMainExecutor(context)) { location ->
                if (singleFixRequest === signal) singleFixRequest = null
                if (location != null && location.accuracy <= SURFACE_FIX_ACCURACY_M) onFix(location, exit = false)
            }
        } catch (e: Exception) {
            singleFixRequest = null
            Log.w(TAG, "GPS fix request failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startExitSeek() {
        if (!hasLocationPermission()) return
        val startedAt = System.currentTimeMillis()
        var best: Location? = null
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (best == null || location.accuracy < best!!.accuracy) best = location
                val timedOut = System.currentTimeMillis() - startedAt >= EXIT_SEEK_TIMEOUT.inWholeMilliseconds
                if (location.accuracy <= EXIT_FIX_ACCURACY_M || timedOut) {
                    stopExitSeek()
                    best?.takeIf { it.accuracy <= SURFACE_FIX_ACCURACY_M }?.let { onFix(it, exit = true) }
                }
            }
        }
        exitSeek = listener
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        } catch (e: Exception) {
            exitSeek = null
            Log.w(TAG, "GPS updates request failed", e)
        }
        // Give up after the timeout even if no update ever arrives; the periodic fixes take over
        scope.launch {
            delay(EXIT_SEEK_TIMEOUT)
            if (exitSeek === listener) stopExitSeek()
        }
    }

    private fun stopExitSeek() {
        exitSeek?.let { locationManager.removeUpdates(it) }
        exitSeek = null
        lastFixAttemptAt = System.currentTimeMillis()
    }

    private fun onFix(location: Location, exit: Boolean) {
        if (diving) return
        val fix = Fix(location.latitude, location.longitude, System.currentTimeMillis())
        lastFix = fix
        if (pending.isNotEmpty()) tagPending(fix)
        if (exit) entryFix = null
        persist()
    }

    private fun tagPending(fix: Fix) {
        val entry = entryFix
        val photos = pending.toList()
        pending.clear()

        scope.launch(Dispatchers.IO) {
            photos.forEach { photo ->
                val (lat, lon) = if (entry != null && photo.time in entry.time..fix.time && fix.time > entry.time) {
                    val f = (photo.time - entry.time).toDouble() / (fix.time - entry.time)
                    (entry.latitude + (fix.latitude - entry.latitude) * f) to
                        (entry.longitude + (fix.longitude - entry.longitude) * f)
                } else {
                    fix.latitude to fix.longitude
                }
                try {
                    writeGps(photo.uri, lat, lon, photo.depth)
                } catch (e: Exception) {
                    Log.w(TAG, "Geotagging ${photo.uri} failed", e) // Deleted in the meantime, most likely
                }
            }
        }
    }

    private fun writeGps(uri: Uri, latitude: Double, longitude: Double, depth: Double) {
        checkNotNull(context.contentResolver.openFileDescriptor(uri, "rw")).use { pfd ->
            ExifInterface(pfd.fileDescriptor).apply {
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, toExifDms(latitude))
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (latitude >= 0) "N" else "S")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, toExifDms(longitude))
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (longitude >= 0) "E" else "W")
                // Already written at capture; kept for photos saved by older versions
                depthAsAltitude(depth).forEach { (tag, value) -> setAttribute(tag, value) }
                saveAttributes()
            }
        }
    }

    /** EXIF rational degrees/minutes/seconds, e.g. "43/1,17/1,30123/1000". */
    private fun toExifDms(coordinate: Double): String {
        val abs = kotlin.math.abs(coordinate)
        val degrees = abs.toInt()
        val minutesFull = (abs - degrees) * 60
        val minutes = minutesFull.toInt()
        val millisOfSecond = ((minutesFull - minutes) * 60 * 1000).roundToInt()
        return "$degrees/1,$minutes/1,$millisOfSecond/1000"
    }

    private fun hasLocationPermission() =
        PermissionChecker.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PermissionChecker.PERMISSION_GRANTED

    // Pending photos and the entry fix survive an app restart between the dive and the exit fix
    private fun persist() {
        prefs.edit()
            .putStringSet(KEY_PENDING, pending.map { "${it.time}|${it.depth}|${it.uri}" }.toSet())
            .putString(KEY_ENTRY, entryFix?.let { "${it.latitude}|${it.longitude}|${it.time}" })
            .apply()
    }

    private fun restore() {
        prefs.getStringSet(KEY_PENDING, emptySet())!!.forEach { line ->
            val parts = line.split("|", limit = 3)
            if (parts.size == 3) pending += PendingPhoto(Uri.parse(parts[2]), parts[0].toLong(), parts[1].toDouble())
        }
        prefs.getString(KEY_ENTRY, null)?.split("|")?.takeIf { it.size == 3 }?.let {
            entryFix = Fix(it[0].toDouble(), it[1].toDouble(), it[2].toLong())
        }
    }

    fun release() {
        singleFixRequest?.cancel()
        stopExitSeek()
    }

    private companion object {
        const val TAG = "DiveGeotagger"
        const val KEY_PENDING = "pending"
        const val KEY_ENTRY = "entry"

        val SURFACE_FIX_INTERVAL = 2.minutes
        val SURFACED_AFTER = 30.seconds
        val EXIT_SEEK_TIMEOUT = 3.minutes
        /** An entry fix older than this probably wasn't taken at the dive site. */
        val ENTRY_MAX_AGE = 10.minutes

        const val SURFACE_FIX_ACCURACY_M = 50f
        const val EXIT_FIX_ACCURACY_M = 20f
    }
}
