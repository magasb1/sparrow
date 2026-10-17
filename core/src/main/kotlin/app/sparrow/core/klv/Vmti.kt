package app.sparrow.core.klv

import app.sparrow.core.util.ByteSink

/** One reported target, in pixels of the encoded frame. */
data class VmtiTarget(
    /** Track id, stable for the life of the track (≥ 1). */
    val id: Int,
    /** Bounding box, 0-based pixel coordinates, inclusive. */
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** 0–100. */
    val confidence: Int,
    /** Number of earlier detections of this target. */
    val history: Int,
    /** Detected class, e.g. "person". */
    val className: String?,
    /** Ground position of the target's base, when the geometry allows. */
    val location: LatLon? = null,
    /** Metres above the WGS84 ellipsoid. */
    val locationHae: Double? = null,
)

data class VmtiFrame(
    val frameWidth: Int,
    val frameHeight: Int,
    val horizontalFov: Double?,
    val verticalFov: Double?,
    /** Targets detected in this frame, including any not reported. */
    val totalTargets: Int,
    val targets: List<VmtiTarget>,
    val systemName: String = "Sparrow RF-DETR",
    val sourceSensor: String = "EO",
)

/**
 * MISB ST 0903 VMTI Local Set, as embedded in ST 0601 Tag 74 (no Universal Key or checksum).
 *
 * Tags written: 3 system name, 4 version, 5 total targets, 6 reported targets, 8/9 frame size,
 * 10 source sensor, 11/12 FOV, 101 VTarget series. Each VTarget pack carries 1 centroid, 2/3
 * bounding box, 5 confidence, 6 history, 17 location and 102 VObject (ontology class).
 */
object Vmti {
    const val VERSION = 6
    const val ONTOLOGY = "https://cocodataset.org/#explore"

    private val FOV = Imapb(0.0, 180.0, 2)
    val LATITUDE = Imapb(-90.0, 90.0, 4)
    val LONGITUDE = Imapb(-180.0, 180.0, 4)
    val HAE = Imapb(-900.0, 19000.0, 3)

    /** ST 0903 pixel number: column + (row − 1) × width, both 1-based. */
    fun pixelNumber(x: Int, y: Int, width: Int): Long = (x + 1).toLong() + y.toLong() * width

    fun pixelFromNumber(number: Long, width: Int): Pair<Int, Int> = ((number - 1) % width).toInt() to ((number - 1) / width).toInt()

    fun encode(frame: VmtiFrame): ByteArray {
        val out = ByteSink(256)
        text(out, 3, frame.systemName)
        item(out, 4) { it.u16(VERSION) }
        uint(out, 5, frame.totalTargets.toLong())
        uint(out, 6, frame.targets.size.toLong())
        uint(out, 8, frame.frameWidth.toLong())
        uint(out, 9, frame.frameHeight.toLong())
        text(out, 10, frame.sourceSensor)
        frame.horizontalFov?.let { v -> item(out, 11) { FOV.write(it, v) } }
        frame.verticalFov?.let { v -> item(out, 12) { FOV.write(it, v) } }
        if (frame.targets.isNotEmpty()) {
            item(out, 101) { series ->
                for (t in frame.targets) {
                    val pack = targetPack(t, frame.frameWidth, frame.frameHeight)
                    Ber.writeLength(series, pack.size)
                    series.bytes(pack)
                }
            }
        }
        return out.toByteArray()
    }

    private fun targetPack(t: VmtiTarget, width: Int, height: Int): ByteArray {
        val p = ByteSink(96)
        Ber.writeOid(p, t.id)
        val l = t.left.coerceIn(0, width - 1)
        val r = t.right.coerceIn(l, width - 1)
        val top = t.top.coerceIn(0, height - 1)
        val b = t.bottom.coerceIn(top, height - 1)
        uint(p, 1, pixelNumber((l + r) / 2, (top + b) / 2, width))
        uint(p, 2, pixelNumber(l, top, width))
        uint(p, 3, pixelNumber(r, b, width))
        item(p, 5) { it.u8(t.confidence.coerceIn(0, 100)) }
        item(p, 6) { it.u16(t.history.coerceIn(0, 0xFFFF)) }
        t.location?.let { loc ->
            item(p, 17) { lp ->
                LATITUDE.write(lp, loc.lat)
                LONGITUDE.write(lp, loc.lon)
                HAE.write(lp, t.locationHae ?: 0.0)
            }
        }
        t.className?.let { name ->
            item(p, 102) { obj ->
                text(obj, 1, ONTOLOGY)
                text(obj, 2, name)
            }
        }
        return p.toByteArray()
    }

    private inline fun item(out: ByteSink, tag: Int, body: (ByteSink) -> Unit) {
        val value = ByteSink(64).also(body).toByteArray()
        Ber.writeOid(out, tag)
        Ber.writeLength(out, value.size)
        out.bytes(value)
    }

    private fun uint(out: ByteSink, tag: Int, value: Long) {
        var n = 1
        while (n < 8 && value ushr (8 * n) != 0L) n++
        item(out, tag) { it.uN(value, n) }
    }

    private fun text(out: ByteSink, tag: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8).let { if (it.size > 127) it.copyOf(127) else it }
        item(out, tag) { it.bytes(bytes) }
    }

    // --- decoding (inspector, tests) ---------------------------------------------------------

    fun decode(data: ByteArray): VmtiFrame {
        var width = 0
        var height = 0
        var hfov: Double? = null
        var vfov: Double? = null
        var total = 0
        var system = ""
        var sensor = ""
        val targets = ArrayList<VmtiTarget>()
        forEachItem(data, 0, data.size) { tag, off, len ->
            when (tag) {
                3 -> system = String(data, off, len, Charsets.UTF_8)
                5 -> total = readUint(data, off, len).toInt()
                8 -> width = readUint(data, off, len).toInt()
                9 -> height = readUint(data, off, len).toInt()
                10 -> sensor = String(data, off, len, Charsets.UTF_8)
                11 -> hfov = FOV.read(data, off)
                12 -> vfov = FOV.read(data, off)
                101 -> {
                    var pos = off
                    while (pos < off + len) {
                        val (packLen, lb) = Ber.readLength(data, pos)
                        targets.add(decodeTarget(data, pos + lb, packLen, width))
                        pos += lb + packLen
                    }
                }
            }
        }
        return VmtiFrame(width, height, hfov, vfov, total, targets, system, sensor)
    }

    private fun decodeTarget(data: ByteArray, start: Int, length: Int, width: Int): VmtiTarget {
        val (id, idLen) = Ber.readOid(data, start)
        var tl = 1L
        var br = 1L
        var conf = 0
        var history = 0
        var cls: String? = null
        var loc: LatLon? = null
        var hae: Double? = null
        forEachItem(data, start + idLen, start + length) { tag, off, len ->
            when (tag) {
                2 -> tl = readUint(data, off, len)
                3 -> br = readUint(data, off, len)
                5 -> conf = readUint(data, off, len).toInt()
                6 -> history = readUint(data, off, len).toInt()
                17 -> {
                    loc = LatLon(LATITUDE.read(data, off), LONGITUDE.read(data, off + 4))
                    hae = HAE.read(data, off + 8)
                }
                102 -> forEachItem(data, off, off + len) { t, o, l -> if (t == 2) cls = String(data, o, l, Charsets.UTF_8) }
            }
        }
        val (l, t) = pixelFromNumber(tl, width)
        val (r, b) = pixelFromNumber(br, width)
        return VmtiTarget(id, l, t, r, b, conf, history, cls, loc, hae)
    }

    private inline fun forEachItem(data: ByteArray, start: Int, end: Int, f: (tag: Int, off: Int, len: Int) -> Unit) {
        var pos = start
        while (pos < end) {
            val (tag, tb) = Ber.readOid(data, pos)
            val (len, lb) = Ber.readLength(data, pos + tb)
            f(tag, pos + tb + lb, len)
            pos += tb + lb + len
        }
    }

    private fun readUint(data: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        for (i in 0 until len) v = (v shl 8) or (data[off + i].toLong() and 0xFF)
        return v
    }
}
