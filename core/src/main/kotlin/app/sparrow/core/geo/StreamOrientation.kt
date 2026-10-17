package app.sparrow.core.geo

/**
 * How the sensor image is turned so the stream is upright for the way the phone is held.
 *
 * @property rotationDeg clockwise rotation applied to the sensor image (0, 90, 180, 270)
 * @property width encoded width; [height] encoded height
 */
data class StreamOrientation(val rotationDeg: Int, val width: Int, val height: Int, private val sensorOrientationDeg: Int) {
    /** What [CameraPose.fromDevice] needs for the encoded image: the rotation still missing to be upright. */
    val remainingSensorRotationDeg: Int get() = ((sensorOrientationDeg - rotationDeg) % 360 + 360) % 360

    val swapsAxes: Boolean get() = rotationDeg % 180 != 0

    companion object {
        /**
         * @param sensorOrientationDeg Camera2 SENSOR_ORIENTATION
         * @param displayRotationDeg display rotation from the natural orientation (Surface.ROTATION_* in degrees)
         * @param sensorWidth width of the camera buffer, in sensor orientation; [sensorHeight] likewise
         */
        fun of(
            sensorOrientationDeg: Int,
            displayRotationDeg: Int,
            facing: LensFacing,
            sensorWidth: Int,
            sensorHeight: Int,
        ): StreamOrientation {
            // Camera2's standard formula for an upright image at the current display rotation.
            val r = if (facing == LensFacing.FRONT) (sensorOrientationDeg + displayRotationDeg) % 360
            else (sensorOrientationDeg - displayRotationDeg + 360) % 360
            val swap = r % 180 != 0
            return StreamOrientation(
                rotationDeg = r,
                width = if (swap) sensorHeight else sensorWidth,
                height = if (swap) sensorWidth else sensorHeight,
                sensorOrientationDeg = sensorOrientationDeg,
            )
        }
    }
}
