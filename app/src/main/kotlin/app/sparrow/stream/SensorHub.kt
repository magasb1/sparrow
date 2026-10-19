package app.sparrow.stream

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import app.sparrow.core.geo.CameraPose
import app.sparrow.core.geo.LensFacing
import app.sparrow.core.geo.Mat3
import app.sparrow.core.geo.Vec3
import app.sparrow.core.klv.PositionFix
import app.sparrow.log.AppLog
import kotlin.math.cos
import kotlin.math.sin

/**
 * GNSS position and device orientation on the capture clock (elapsedRealtime, µs).
 *
 * Only the GPS provider is used: a network location is not a GNSS fix (KLV-6). MSL altitude comes
 * from Location (API 34+) or from NMEA GGA sentences on older releases.
 */
class SensorHub(private val context: Context) {
    private val thread = HandlerThread("sensors")
    private val handler by lazy { Handler(thread.looper) }
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val locationManager = context.getSystemService(LocationManager::class.java)

    @Volatile var latestFix: PositionFix? = null
        private set
    @Volatile private var latestAccuracy: Float? = null
    @Volatile private var declinationDeg = 0.0
    @Volatile private var ggaMsl: Pair<Long, Double>? = null // (captureUs, metres)

    /** UTC µs minus capture-clock µs. */
    @Volatile var utcOffsetUs: Long = systemUtcOffset()
        private set
    @Volatile var usingGnssTime = false
        private set

    /** ~2.5 s at 100 Hz: covers stabilisation and encoder latency before a frame is stamped. */
    private val poses = arrayOfNulls<Pair<Long, FloatArray>>(256)
    private var poseIndex = 0
    private val rotation = FloatArray(9)

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            synchronized(poses) {
                poses[poseIndex] = event.timestamp / 1000 to rotation.copyOf()
                poseIndex = (poseIndex + 1) % poses.size
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private val locationListener = LocationListener { onLocation(it) }

    private val nmeaListener = OnNmeaMessageListener { message, _ -> parseGga(message) }

    private val altitudeConverter by lazy { if (Build.VERSION.SDK_INT >= 34) AltitudeConverter() else null }

    @SuppressLint("MissingPermission")
    fun start() {
        thread.start()
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor == null) {
            AppLog.w("No rotation vector sensor: attitude tags will be omitted")
        } else {
            sensorManager.registerListener(sensorListener, rotationSensor, 10_000, handler)
        }
        runCatching {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, locationListener, thread.looper)
            locationManager.addNmeaListener(nmeaListener, handler)
        }.onFailure { AppLog.w("GNSS unavailable", it) }
    }

    fun stop() {
        if (!thread.isAlive) return
        sensorManager.unregisterListener(sensorListener)
        runCatching {
            locationManager.removeUpdates(locationListener)
            locationManager.removeNmeaListener(nmeaListener)
        }
        thread.quitSafely()
    }

    private fun onLocation(location: Location) {
        val captureUs = location.elapsedRealtimeNanos / 1000
        if (Build.VERSION.SDK_INT >= 34 && !location.hasMslAltitude() && location.hasAltitude()) {
            runCatching { altitudeConverter?.addMslAltitudeToLocation(context, location) }
        }
        val msl = when {
            Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude() -> location.mslAltitudeMeters
            else -> ggaMsl?.takeIf { captureUs - it.first < 2_000_000 }?.second
        }
        latestFix = PositionFix(
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMsl = msl,
            ellipsoidHeight = if (location.hasAltitude()) location.altitude else null,
            captureUs = captureUs,
        )
        latestAccuracy = if (location.hasAccuracy()) location.accuracy else null
        // GNSS-derived UTC for the Precision Time Stamp.
        utcOffsetUs = location.time * 1000 - captureUs
        usingGnssTime = true
        declinationDeg = GeomagneticField(
            location.latitude.toFloat(), location.longitude.toFloat(), location.altitude.toFloat(), location.time,
        ).declination.toDouble()
    }

    /** $xxGGA: field 6 = fix quality, field 9 = altitude above MSL in metres. */
    private fun parseGga(sentence: String) {
        if (sentence.length < 7 || sentence.substring(3, 6) != "GGA") return
        val f = sentence.substringBefore('*').split(',')
        if (f.size < 10 || (f[6].toIntOrNull() ?: 0) == 0) return
        f[9].toDoubleOrNull()?.let { ggaMsl = SystemClock.elapsedRealtimeNanos() / 1000 to it }
    }

    fun gnssStatus(nowUs: Long): GnssStatus {
        val fix = latestFix
        val age = fix?.let { (nowUs - it.captureUs) / 1000 }
        return GnssStatus(
            hasFix = age != null && age < FIX_TIMEOUT_MS,
            accuracyM = latestAccuracy,
            ageMs = age,
            usingGnssTime = usingGnssTime,
        )
    }

    fun utcMicros(captureUs: Long): Long {
        if (!usingGnssTime || (latestFix?.let { captureUs - it.captureUs > 60_000_000 } == true)) {
            // GNSS time is stale: fall back to the network-corrected system clock (KLV-6).
            utcOffsetUs = systemUtcOffset()
            usingGnssTime = false
        }
        return captureUs + utcOffsetUs
    }

    /** Camera pose at [captureUs], from the nearest orientation sample, true-north referenced. */
    fun poseAt(captureUs: Long, facing: LensFacing, sensorOrientation: Int): CameraPose? {
        val nearest = synchronized(poses) {
            poses.filterNotNull().minByOrNull { kotlin.math.abs(it.first - captureUs) }
        } ?: return null
        // A far-off sample is a different pose; omitting attitude beats sending a wrong one.
        if (kotlin.math.abs(nearest.first - captureUs) > 25_000) return null
        val pose = CameraPose.fromDevice(Mat3.of(nearest.second), facing, sensorOrientation)
        return CameraPose(rotateAzimuth(pose.forward, declinationDeg), rotateAzimuth(pose.up, declinationDeg))
    }

    /** Rotates an ENU vector clockwise (seen from above) by [deg]: magnetic → true north. */
    private fun rotateAzimuth(v: Vec3, deg: Double): Vec3 {
        val d = Math.toRadians(deg)
        return Vec3(v.x * cos(d) + v.y * sin(d), -v.x * sin(d) + v.y * cos(d), v.z)
    }

    companion object {
        const val FIX_TIMEOUT_MS = 3000L

        fun systemUtcOffset(): Long = System.currentTimeMillis() * 1000 - SystemClock.elapsedRealtimeNanos() / 1000

        fun nowUs(): Long = SystemClock.elapsedRealtimeNanos() / 1000
    }
}
