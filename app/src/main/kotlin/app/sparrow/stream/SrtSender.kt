package app.sparrow.stream

import app.sparrow.core.config.Destination
import app.sparrow.core.config.SrtMode
import app.sparrow.core.srt.Backoff
import app.sparrow.core.srt.LinkSample
import app.sparrow.log.AppLog
import io.github.thibaultbee.srtdroid.core.enums.SockOpt
import io.github.thibaultbee.srtdroid.core.enums.Transtype
import io.github.thibaultbee.srtdroid.core.models.SrtSocket
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Sends 1316-byte TS payloads over SRT in live mode, as caller or listener (SRT-1, SRT-2), and
 * reconnects with backoff without touching capture (SRT-3). Data produced while disconnected is
 * dropped: live video must not arrive late.
 */
class SrtSender(
    private val destination: Destination,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(state: LinkState)
        fun onConnected()
        fun onStats(sample: LinkSample, sendKbps: Double)
    }

    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CHUNKS)
    private val backoff = Backoff()
    private val wake = Object()
    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var socket: SrtSocket? = null
    @Volatile private var serverSocket: SrtSocket? = null
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ run() }, "srt-sender").apply { start() }
    }

    fun stop() {
        running = false
        closeSockets()
        synchronized(wake) { wake.notifyAll() }
        thread?.join(3000)
    }

    /** Queues one payload; drops the oldest when the queue is full or the link is down. */
    fun send(chunk: ByteArray) {
        if (!connected) return
        if (!queue.offer(chunk)) {
            queue.poll()
            queue.offer(chunk)
        }
    }

    /** Network changed (BG-4): drop the current connection and retry immediately. */
    fun onNetworkChanged() {
        backoff.reset()
        if (connected) {
            AppLog.i("Network changed; reconnecting SRT")
            closeSockets()
        }
        synchronized(wake) { wake.notifyAll() }
    }

    private fun run() {
        while (running) {
            try {
                val s = open()
                socket = s
                connected = true
                queue.clear()
                backoff.reset()
                listener.onState(LinkState.Live)
                listener.onConnected()
                AppLog.i("SRT connected to ${s.peerName}")
                pump(s)
            } catch (e: Exception) {
                if (!running) break
                connected = false
                closeSockets()
                val delay = backoff.nextDelayMs()
                val reason = e.message ?: e.javaClass.simpleName
                AppLog.w("SRT link down ($reason); retry in $delay ms")
                listener.onState(LinkState.Reconnecting(delay, reason))
                synchronized(wake) { if (running) wake.wait(delay) }
            }
        }
        connected = false
        closeSockets()
    }

    private fun configure(s: SrtSocket) {
        s.setSockFlag(SockOpt.TRANSTYPE, Transtype.LIVE)
        s.setSockFlag(SockOpt.LATENCY, destination.latencyMs)
        s.setSockFlag(SockOpt.PAYLOADSIZE, PAYLOAD_SIZE)
        s.setSockFlag(SockOpt.CONNTIMEO, 3000)
        s.setSockFlag(SockOpt.PEERIDLETIMEO, 4000)
        if (destination.passphrase.isNotEmpty()) {
            s.setSockFlag(SockOpt.PASSPHRASE, destination.passphrase)
            if (destination.keyLength != 0) s.setSockFlag(SockOpt.PBKEYLEN, destination.keyLength)
        }
        if (destination.streamId.isNotEmpty()) s.setSockFlag(SockOpt.STREAMID, destination.streamId)
    }

    private fun open(): SrtSocket = when (destination.mode) {
        SrtMode.CALLER -> {
            listener.onState(LinkState.Connecting)
            SrtSocket().also {
                configure(it)
                it.connect(InetSocketAddress(destination.host, destination.port))
            }
        }
        SrtMode.LISTENER -> {
            listener.onState(LinkState.Listening)
            val server = SrtSocket()
            serverSocket = server
            configure(server)
            server.reuseAddress = true
            server.bind(InetSocketAddress(destination.host.ifBlank { "0.0.0.0" }, destination.port))
            server.listen(1)
            val accepted = server.accept()
            val client = accepted.first
            AppLog.i("SRT listener accepted ${accepted.second}")
            serverSocket = null
            runCatching { server.close() }
            client
        }
    }

    private fun pump(s: SrtSocket) {
        var lastStats = System.nanoTime()
        while (running) {
            val chunk = queue.poll(200, TimeUnit.MILLISECONDS)
            if (chunk != null) s.send(chunk)
            val now = System.nanoTime()
            if (now - lastStats >= 1_000_000_000L) {
                val elapsed = (now - lastStats) / 1e9
                lastStats = now
                val st = s.bstats(true)
                listener.onStats(
                    LinkSample(
                        packetsSent = st.pktSent,
                        packetsLost = st.pktSndLoss,
                        packetsRetransmitted = st.pktRetrans,
                        packetsDropped = st.pktSndDrop,
                        rttMs = st.msRTT,
                        sendBufferMs = st.msSndBuf,
                    ),
                    sendKbps = st.byteSent * 8 / 1000.0 / elapsed,
                )
            }
            if (!s.isConnected) throw java.io.IOException("connection lost")
        }
    }

    private fun closeSockets() {
        socket?.let { runCatching { it.close() } }
        socket = null
        serverSocket?.let { runCatching { it.close() } }
        serverSocket = null
    }

    private companion object {
        const val PAYLOAD_SIZE = 1316
        /** About 3 s at 4 Mbit/s; beyond that the data is too old for a live receiver. */
        const val QUEUE_CHUNKS = 1200
    }
}
