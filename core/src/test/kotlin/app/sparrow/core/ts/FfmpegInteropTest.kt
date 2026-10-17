package app.sparrow.core.ts

import app.sparrow.core.klv.Ber
import app.sparrow.core.klv.St0601
import app.sparrow.core.klv.St0601Decoder
import app.sparrow.core.klv.St0601Encoder
import app.sparrow.core.klv.St0601Test
import app.sparrow.core.klv.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Feeds FFmpeg-encoded H.264 through the muxer and checks that FFmpeg's own demuxer finds the
 * video and KLV streams and decodes both cleanly. Skipped when ffmpeg is not installed.
 */
class FfmpegInteropTest {
    private fun exec(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0) { "${cmd.joinToString(" ")} failed:\n$out" }
        return out
    }

    private fun available(tool: String) =
        runCatching { ProcessBuilder(tool, "-version").start().waitFor() == 0 }.getOrDefault(false)

    @Test
    fun `ffmpeg demuxes video and sync KLV`() = roundTrip(KlvCarriage.SYNC)

    @Test
    fun `ffmpeg demuxes video and async KLV`() = roundTrip(KlvCarriage.ASYNC)

    private fun roundTrip(carriage: KlvCarriage) {
        assumeTrue(available("ffmpeg") && available("ffprobe"))
        val dir = createTempDir()
        val h264 = File(dir, "in.h264")
        exec(
            "ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=640x360:rate=30", "-t", "4",
            "-c:v", "libx264", "-bf", "0", "-g", "30", "-pix_fmt", "yuv420p", "-f", "h264", "-y", h264.path,
        )

        // Emulate MediaCodec: parameter sets arrive once as codec config, frames without them.
        val es = h264.readBytes()
        val nals = AnnexB.split(es, VideoCodec.H264)
        val assembler = AccessUnitAssembler(VideoCodec.H264)
        val config = nals.filter { it.type == 7 || it.type == 8 }.take(2)
        assembler.setCodecConfig(config.fold(ByteArray(0)) { a, n -> a + START + es.copyOfRange(n.offset, n.offset + n.length) })
        val frames = nals.filter { it.type == 1 || it.type == 5 }

        val ts = File(dir, "out.ts")
        val out = ts.outputStream()
        val muxer = TsMuxer(TsConfig(klvCarriage = carriage), TsChunker { out.write(it) })
        val encoder = St0601Encoder()
        val start = 10_000_000L
        var klvCount = 0
        for ((i, nal) in frames.withIndex()) {
            val t = start + i * 33_333L
            val frame = START + es.copyOfRange(nal.offset, nal.offset + nal.length)
            muxer.writeVideo(t, assembler.assemble(frame, nal.type == 5), nal.type == 5, nowUs = t + 20_000)
            if (i % 3 == 0) {
                muxer.writeKlv(t, encoder.encode(St0601Test.fullSample().copy(timestampUtcMicros = t)), nowUs = t + 20_000)
                klvCount++
            }
            muxer.tick(t + 25_000)
        }
        muxer.flush()
        out.close()

        val probe = exec("ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name", "-of", "csv=p=0", ts.path)
        assertTrue(probe, probe.lines().any { it.startsWith("h264,video") })
        assertTrue(probe, probe.lines().any { it.startsWith("klv,data") })

        // Video decodes without errors.
        val decodeLog = exec("ffmpeg", "-v", "error", "-i", ts.path, "-map", "0:v", "-f", "null", "-")
        assertEquals("", decodeLog.trim())

        // KLV comes back byte-exact and valid.
        val klvFile = File(dir, "out.klv")
        exec("ffmpeg", "-v", "error", "-i", ts.path, "-map", "0:d", "-c", "copy", "-f", "data", "-y", klvFile.path)
        // FFmpeg <= 6.1 strips a 5-byte metadata AU header from every KLV stream, even async
        // ones that carry none (libavformat/mpegts.c); put the lost key bytes back.
        val dropped = if (carriage == KlvCarriage.ASYNC) 5 else 0
        val klv = klvFile.readBytes()
        var pos = 0
        var count = 0
        while (pos < klv.size) {
            val (len, lb) = Ber.readLength(klv, pos + St0601.UNIVERSAL_KEY.size - dropped)
            val end = pos + St0601.UNIVERSAL_KEY.size - dropped + lb + len
            val packet = St0601.UNIVERSAL_KEY.copyOf(dropped) + klv.copyOfRange(pos, end)
            val decoded = St0601Decoder.decode(packet)
            assertTrue(decoded.checksumValid)
            assertTrue(decoded.has(Tag.SENSOR_LATITUDE))
            pos = end
            count++
        }
        assertEquals(klvCount, count)
        dir.deleteRecursively()
    }

    private companion object {
        val START = byteArrayOf(0, 0, 0, 1)
    }
}
