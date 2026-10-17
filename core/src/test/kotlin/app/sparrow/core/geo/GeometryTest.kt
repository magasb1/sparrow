package app.sparrow.core.geo

import app.sparrow.core.klv.ComposerSettings
import app.sparrow.core.klv.LatLon
import app.sparrow.core.klv.MetadataComposer
import app.sparrow.core.klv.PositionFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt

class GeometryTest {
    private val origin = LatLon(60.0, 10.0)
    private val metresPerDegLat = 111_412.0 // WGS84 meridian arc at 60°N

    @Test
    fun `45 degree depression lands one height away`() {
        val pose = CameraPose.fromAngles(azimuthDeg = 0.0, elevationDeg = -45.0, rollDeg = 0.0)
        val fp = FrameGeometry.footprint(origin, 110.0, 10.0, pose, 60.0, 40.0)
        val center = fp.center!!
        assertEquals(100.0, (center.lat - origin.lat) * metresPerDegLat, 0.5)
        assertEquals(origin.lon, center.lon, 1e-9)
        assertEquals(100 * sqrt(2.0), fp.slantRange!!, 1e-6)
        val c = fp.corners!!
        // Upper corners are farther away than lower ones; left is west of right.
        assertEquals(true, c[0].lat > c[3].lat)
        assertEquals(true, c[0].lon < c[1].lon)
        assertEquals(true, c[3].lon < c[2].lon)
    }

    @Test
    fun `looking east moves the frame center east`() {
        val pose = CameraPose.fromAngles(90.0, -30.0, 0.0)
        val center = FrameGeometry.footprint(origin, 100.0, 0.0, pose, 60.0, 40.0).center!!
        assertEquals(origin.lat, center.lat, 1e-9)
        assertEquals(true, center.lon > origin.lon)
    }

    @Test
    fun `above the horizon yields no footprint`() {
        val pose = CameraPose.fromAngles(0.0, 5.0, 0.0)
        val fp = FrameGeometry.footprint(origin, 100.0, 0.0, pose, 60.0, 40.0)
        assertNull(fp.center)
        assertNull(fp.slantRange)
        assertNull(fp.corners)
    }

    @Test
    fun `upper corners above horizon drop corners but keep center`() {
        val pose = CameraPose.fromAngles(0.0, -10.0, 0.0)
        val fp = FrameGeometry.footprint(origin, 100.0, 0.0, pose, 60.0, 40.0)
        assertNotNull(fp.center)
        assertNull(fp.corners)
    }

    @Test
    fun `angles round trip through pose`() {
        val pose = CameraPose.fromAngles(237.0, -12.5, 17.0)
        assertEquals(237.0, pose.azimuthDeg, 1e-9)
        assertEquals(-12.5, pose.elevationDeg, 1e-9)
        assertEquals(17.0, pose.rollDeg, 1e-9)
    }

    @Test
    fun `phone held upright in portrait facing north, back camera`() {
        // Device Y (top of phone) is up, the screen faces south, so device -Z points north.
        // Device → ENU: x_dev → East, y_dev → Up, z_dev → South.
        val r = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0))
        val pose = CameraPose.fromDevice(r, LensFacing.BACK, sensorOrientationDeg = 90)
        assertEquals(0.0, pose.azimuthDeg, 1e-9)
        assertEquals(0.0, pose.elevationDeg, 1e-9)
        // The sensor's top row points to the device's right (east): the landscape image is
        // rolled 90° clockwise relative to an upright one.
        assertEquals(90.0, pose.rollDeg, 1e-9)
    }

    @Test
    fun `phone in landscape with its top to the left gives a level image`() {
        // Device X points up, Y points west, Z points south.
        val r = Mat3(doubleArrayOf(0.0, -1.0, 0.0, 0.0, 0.0, -1.0, 1.0, 0.0, 0.0))
        val pose = CameraPose.fromDevice(r, LensFacing.BACK, sensorOrientationDeg = 90)
        assertEquals(0.0, pose.azimuthDeg, 1e-9)
        assertEquals(0.0, pose.rollDeg, 1e-9)
    }

    @Test
    fun `field of view from focal length`() {
        assertEquals(90.0, FrameGeometry.fieldOfView(8.0, 4.0), 1e-9)
        assertEquals(53.13010235, FrameGeometry.fieldOfView(8.0, 4.0, zoomRatio = 2.0), 1e-6)
    }

    @Test
    fun `composer omits position without a fresh fix`() {
        val composer = MetadataComposer(ComposerSettings(maxFixAgeUs = 1_000_000))
        val pose = CameraPose.fromAngles(0.0, -45.0, 0.0)
        val fix = PositionFix(60.0, 10.0, 100.0, 140.0, captureUs = 0)
        val stale = composer.compose(1, captureUs = 2_000_000, fix = fix, pose = pose, horizontalFovDeg = 60.0, verticalFovDeg = 40.0)
        assertNull(stale.sensorLatitude)
        assertNull(stale.frameCenter)
        assertNotNull(stale.platformHeading)

        val fresh = composer.compose(1, captureUs = 500_000, fix = fix, pose = pose, horizontalFovDeg = 60.0, verticalFovDeg = 40.0)
        assertEquals(60.0, fresh.sensorLatitude!!, 0.0)
        assertEquals(-45.0, fresh.sensorRelativeElevation!!, 1e-9)
        assertNotNull(fresh.frameCenter)
        assertEquals(0.0, fresh.frameCenterElevation!!, 0.0)
    }
}

class StreamOrientationTest {
    @Test
    fun `back camera at the four display rotations`() {
        // Typical back camera: sensor orientation 90, landscape buffer 1280x720.
        val portrait = StreamOrientation.of(90, 0, LensFacing.BACK, 1280, 720)
        assertEquals(90, portrait.rotationDeg)
        assertEquals(720 to 1280, portrait.width to portrait.height)
        assertEquals(0, portrait.remainingSensorRotationDeg)

        val landscape = StreamOrientation.of(90, 90, LensFacing.BACK, 1280, 720)
        assertEquals(0, landscape.rotationDeg)
        assertEquals(1280 to 720, landscape.width to landscape.height)
        assertEquals(90, landscape.remainingSensorRotationDeg)

        assertEquals(180, StreamOrientation.of(90, 270, LensFacing.BACK, 1280, 720).rotationDeg)
        assertEquals(270, StreamOrientation.of(90, 180, LensFacing.BACK, 1280, 720).rotationDeg)
    }

    @Test
    fun `portrait stream of a phone held upright facing north is level`() {
        // Same device pose as the portrait test above; after rotating the image 90° it is upright.
        val r = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0))
        val o = StreamOrientation.of(90, 0, LensFacing.BACK, 1280, 720)
        val pose = CameraPose.fromDevice(r, LensFacing.BACK, o.remainingSensorRotationDeg)
        assertEquals(0.0, pose.rollDeg, 1e-9)
        assertEquals(0.0, pose.azimuthDeg, 1e-9)
    }

    @Test
    fun `front camera uses the mirrored formula`() {
        assertEquals(270, StreamOrientation.of(270, 0, LensFacing.FRONT, 1280, 720).rotationDeg)
        assertEquals(0, StreamOrientation.of(270, 90, LensFacing.FRONT, 1280, 720).rotationDeg)
    }
}
