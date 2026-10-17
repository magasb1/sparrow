package app.sparrow.core.ts

import app.sparrow.core.klv.St0601Decoder
import app.sparrow.core.klv.St0601Encoder
import app.sparrow.core.klv.St0601Test
import app.sparrow.core.util.hexToBytes
import app.sparrow.core.util.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TsMuxerTest {
    private class Capture {
        val chunks = ArrayList<ByteArray>()
        val stream = ByteArrayOutputStream()
        val chunker = TsChunker { chunks.add(it); stream.write(it) }
    }

    private fun idr(size: Int) = byteArrayOf(0, 0, 0, 1, 0x65) + ByteArray(size) { (it % 251).toByte() }
    private fun pFrame(size: Int) = byteArrayOf(0, 0, 0, 1, 0x41) + ByteArray(size) { 7 }

    /** 10 s at 30 fps with 10 Hz KLV; the muxer is ticked every 10 ms. */
    private fun run(config: TsConfig, capture: Capture) {
        val muxer = TsMuxer(config, capture.chunker)
        val assembler = AccessUnitAssembler(config.videoCodec)
        assembler.setCodecConfig("0000000167640028ACD940780227E5C04400000303".hexToBytes() + "0000000168EBE3CB22C0".hexToBytes())
        val klv = St0601Encoder()
        val start = 5_000_000_000L
        var nextFrame = start
        var nextKlv = start
        var frame = 0
        for (t in start until start + 10_000_000 step 10_000) {
            if (t >= nextFrame) {
                val key = frame % 30 == 0
                val au = assembler.assemble(if (key) idr(9000) else pFrame(1500), key)
                muxer.writeVideo(nextFrame, au, key, nowUs = t)
                nextFrame += 33_333
                frame++
            }
            if (t >= nextKlv) {
                val m = St0601Test.fullSample().copy(timestampUtcMicros = nextKlv)
                muxer.writeKlv(nextKlv, klv.encode(m), nowUs = t)
                nextKlv += 100_000
            }
            muxer.tick(t)
        }
        muxer.flush()
    }

    @Test
    fun `PAT matches reference bytes`() {
        val cap = Capture()
        run(TsConfig(), cap)
        val demux = TsDemuxer().apply { psiPids = setOf(0, 0x1000); feed(cap.stream.toByteArray().let { it.copyOf(it.size / 188 * 188) }) }
        val pat = demux.sections.first { it.first == 0 }.second
        assertEquals("00B00D0001C100000001F0002AB104B2", pat.toHex())
        val pmt = demux.sections.first { it.first == 0x1000 }.second
        assertEquals(Crc32Mpeg.compute(pmt, 0, pmt.size - 4), pmt.takeLast(4).fold(0) { a, b -> (a shl 8) or (b.toInt() and 0xFF) })
    }

    @Test
    fun `payloads are 7 packets and stream is continuous`() {
        val cap = Capture()
        run(TsConfig(), cap)
        assertTrue(cap.chunks.dropLast(1).all { it.size == 1316 })
        val bytes = cap.stream.toByteArray()
        val demux = TsDemuxer().apply { psiPids = setOf(0, 0x1000); feed(bytes); finish() }
        assertEquals(0, demux.continuityErrors)
    }

    @Test
    fun `sync KLV carriage with PTS and metadata descriptors`() {
        val cap = Capture()
        run(TsConfig(klvCarriage = KlvCarriage.SYNC), cap)
        val demux = TsDemuxer().apply { psiPids = setOf(0, 0x1000); feed(cap.stream.toByteArray()); finish() }
        val pmt = demux.sections.first { it.first == 0x1000 }.second.toHex()
        assertTrue("metadata_pointer_descriptor", pmt.contains("250FFFFF4B4C5641FF4B4C5641001F0001"))
        assertTrue("KLV stream entry", pmt.contains("15E101F01A"))
        assertTrue("metadata_descriptor", pmt.contains("260DFFFF4B4C5641FF4B4C5641000F"))

        val klv = demux.pesPackets.filter { it.pid == 0x0101 }
        assertEquals(100, klv.size)
        klv.forEachIndexed { i, pes ->
            assertEquals(0xFC, pes.streamId)
            assertEquals(i and 0xFF, pes.payload[1].toInt() and 0xFF)
            val decoded = St0601Decoder.decode(pes.payload, offset = 5)
            assertTrue(decoded.checksumValid)
        }
        // KLV PTS uses the same clock as video: sample n at 100 ms intervals.
        assertEquals(9000L, klv[1].pts90k!! - klv[0].pts90k!!)
        val video = demux.pesPackets.filter { it.pid == 0x0100 }
        assertEquals(klv[0].pts90k, video[0].pts90k)
    }

    @Test
    fun `async KLV uses private stream with registration descriptor`() {
        val cap = Capture()
        run(TsConfig(klvCarriage = KlvCarriage.ASYNC), cap)
        val demux = TsDemuxer().apply { psiPids = setOf(0, 0x1000); feed(cap.stream.toByteArray()); finish() }
        val pmt = demux.sections.first { it.first == 0x1000 }.second.toHex()
        assertTrue(pmt.contains("06E101F006" + "05044B4C5641"))
        val klv = demux.pesPackets.filter { it.pid == 0x0101 }
        assertTrue(klv.all { it.streamId == 0xBD && it.pts90k == null })
        assertTrue(St0601Decoder.decode(klv[0].payload).checksumValid)
    }

    @Test
    fun `PCR at most 40 ms apart and PSI at most 100 ms apart`() {
        val cap = Capture()
        run(TsConfig(), cap)
        val bytes = cap.stream.toByteArray()
        val demux = TsDemuxer().apply { psiPids = setOf(0, 0x1000); feed(bytes) }
        val gaps = demux.pcrs.zipWithNext { a, b -> (b - a) / 27_000.0 }
        assertTrue("max PCR gap ${gaps.max()} ms", gaps.max() <= 40.0)
        assertTrue(demux.pcrs.size > 250)

        // PSI spacing measured against the PCR timeline.
        var lastPcr = 0L
        val patTimes = ArrayList<Long>()
        for (off in bytes.indices step 188) {
            val one = TsDemuxer().apply { psiPids = setOf(0, 0x1000); feed(bytes.copyOfRange(off, off + 188)) }
            one.pcrs.firstOrNull()?.let { lastPcr = it }
            if (lastPcr > 0 && one.sections.any { it.first == 0 }) patTimes.add(lastPcr)
        }
        assertTrue(patTimes.size > 100)
        val psiGaps = patTimes.zipWithNext { a, b -> (b - a) / 27_000.0 }
        assertTrue("max PSI gap ${psiGaps.max()} ms", psiGaps.max() <= 100.0)
    }

    @Test
    fun `SPS and PPS precede every IDR and keyframes are flagged`() {
        val cap = Capture()
        run(TsConfig(), cap)
        val demux = TsDemuxer().apply { psiPids = setOf(0, 0x1000); feed(cap.stream.toByteArray()); finish() }
        val video = demux.pesPackets.filter { it.pid == 0x0100 }
        assertEquals(300, video.size)
        for ((i, pes) in video.withIndex()) {
            val types = AnnexB.split(pes.payload, VideoCodec.H264).map { it.type }
            assertEquals(9, types.first())
            if (i % 30 == 0) {
                assertEquals(listOf(9, 7, 8, 5), types)
                assertTrue(pes.randomAccess)
            } else {
                assertEquals(listOf(9, 1), types)
            }
        }
        assertArrayEquals(idr(9000).copyOfRange(4, 9005), video[0].payload.copyOfRange(video[0].payload.size - 9001, video[0].payload.size))
    }
}
