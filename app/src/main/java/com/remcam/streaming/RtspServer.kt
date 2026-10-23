package com.remcam.streaming

import android.util.Log
import java.io.BufferedReader
import java.io.EOFException
import java.io.InputStreamReader
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Minimal RTSP/RTP server for a single H.264 track.
 *
 * Supports interleaved TCP and UDP transports, and optional HTTP Basic
 * authentication (401 challenge) or anonymous access.
 */
class RtspServer(
    private val port: Int,
    private val hostAddress: String,
    private val streamPath: String = "/camera",
    private val authEnabled: Boolean = false,
    private val authUsername: String = "",
    private val authPassword: String = ""
) {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val clients = CopyOnWriteArrayList<ClientConnection>()
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var streamInfo: StreamInfo? = null

    @Throws(BindException::class)
    fun start() {
        if (running.get()) return
        serverSocket = ServerSocket(port).also { it.reuseAddress = true }
        running.set(true)
        Log.i(
            TAG_RTSP_SERVER,
            "RTSP server started on rtsp://$hostAddress:$port$streamPath (auth=${
                if (authEnabled) "basic" else "none"
            })"
        )
        executor.execute(::acceptLoop)
    }

    fun stop() {
        running.set(false)
        clients.forEach { it.close() }
        clients.clear()
        runCatching { serverSocket?.close() }
        executor.shutdownNow()
        Log.i(TAG_RTSP_SERVER, "RTSP server stopped")
    }

    fun updateStreamInfo(
        width: Int,
        height: Int,
        fps: Int,
        sps: ByteArray,
        pps: ByteArray
    ) {
        streamInfo = StreamInfo(width, height, fps, sps, pps)
        Log.i(TAG_VIDEO_ENCODER, "Stream info updated: ${width}x$height @ ${fps}fps")
    }

    fun dispatchSample(
        accessUnit: ByteArray,
        presentationTimeUs: Long,
        isKeyFrame: Boolean
    ) {
        val info = streamInfo ?: return
        val timestamp = ((presentationTimeUs * 90L) / 1000L).coerceAtLeast(0L)
        clients.forEach { client ->
            if (!client.isReadyToStream()) return@forEach
            runCatching {
                client.sendAccessUnit(
                    accessUnit = accessUnit,
                    timestamp = timestamp,
                    isKeyFrame = isKeyFrame,
                    sps = info.sps,
                    pps = info.pps
                )
            }.onFailure { error ->
                Log.w(TAG_CLIENT_CONNECTION, "Streaming error for ${client.clientLabel}: ${error.message}", error)
                client.close()
                clients.remove(client)
            }
        }
    }

    private fun acceptLoop() {
        val socket = serverSocket ?: return
        while (running.get()) {
            try {
                val clientSocket = socket.accept()
                val client = ClientConnection(clientSocket)
                clients += client
                Log.i(TAG_CLIENT_CONNECTION, "Client connected: ${client.clientLabel}")
                executor.execute {
                    client.handleRequests()
                    clients.remove(client)
                }
            } catch (_: SocketException) {
                if (!running.get()) return
            } catch (error: Exception) {
                Log.e(TAG_RTSP_SERVER, "Accept loop error: ${error.message}", error)
                if (!running.get()) return
            }
        }
    }

    private inner class ClientConnection(private val socket: Socket) {
        private val outputLock = Any()
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        private val output = socket.getOutputStream()
        private val sessionId = UUID.randomUUID().toString()
        private val ssrc = Random.nextInt().toLong() and 0xffffffffL
        private var cSeq: String = "1"
        private var sequenceNumber = Random.nextInt(0, 65_535)
        private var transportSession: TransportSession? = null

        @Volatile
        private var closed = false

        @Volatile
        private var playing = false

        val clientLabel: String = "${socket.inetAddress.hostAddress}:${socket.port}"
        private val serverAddress: String =
            (socket.localAddress as? Inet4Address)?.hostAddress ?: hostAddress

        fun handleRequests() {
            socket.tcpNoDelay = true
            socket.sendBufferSize = 64 * 1024
            socket.soTimeout = 0
            try {
                while (running.get() && !socket.isClosed && !closed) {
                    val request = readRequest() ?: break
                    logRequest(request)
                    if (!isAuthorized(request)) {
                        respond(
                            request = request,
                            status = "401 Unauthorized",
                            headers = listOf("WWW-Authenticate: Basic realm=\"RemCam\"")
                        )
                        continue
                    }
                    when (request.method) {
                        "OPTIONS" -> respond(
                            request = request,
                            status = "200 OK",
                            headers = listOf("Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER")
                        )

                        "DESCRIBE" -> handleDescribe(request)
                        "SETUP" -> handleSetup(request)
                        "PLAY" -> handlePlay(request)
                        "GET_PARAMETER" -> respondWithSession(request, "200 OK")
                        "TEARDOWN" -> {
                            respondWithSession(request, "200 OK")
                            break
                        }

                        else -> respond(request, "405 Method Not Allowed")
                    }
                }
            } catch (_: EOFException) {
                Log.i(TAG_CLIENT_CONNECTION, "Client disconnected: $clientLabel")
            } catch (_: SocketException) {
                Log.i(TAG_CLIENT_CONNECTION, "Socket closed for client: $clientLabel")
            } catch (error: Exception) {
                Log.e(TAG_CLIENT_CONNECTION, "RTSP request loop failed for $clientLabel: ${error.message}", error)
            } finally {
                close()
            }
        }

        fun isReadyToStream(): Boolean = playing && transportSession != null && !closed

        private fun isAuthorized(request: RtspRequest): Boolean {
            if (!authEnabled) return true
            val header = request.headers["authorization"] ?: return false
            if (!header.startsWith("Basic ", ignoreCase = true)) return false
            val decoded = runCatching {
                String(
                    Base64.getDecoder().decode(header.substring(6).trim()),
                    StandardCharsets.UTF_8
                )
            }.getOrNull() ?: return false
            val separator = decoded.indexOf(':')
            if (separator < 0) return false
            val providedUsername = decoded.substring(0, separator)
            val providedPassword = decoded.substring(separator + 1)
            return MessageDigest.isEqual(
                providedUsername.toByteArray(StandardCharsets.UTF_8),
                authUsername.toByteArray(StandardCharsets.UTF_8)
            ) && MessageDigest.isEqual(
                providedPassword.toByteArray(StandardCharsets.UTF_8),
                authPassword.toByteArray(StandardCharsets.UTF_8)
            )
        }

        fun sendAccessUnit(
            accessUnit: ByteArray,
            timestamp: Long,
            isKeyFrame: Boolean,
            sps: ByteArray,
            pps: ByteArray
        ) {
            val transport = transportSession ?: return
            val nalUnits = splitNalUnits(accessUnit)
            if (nalUnits.isEmpty()) return
            if (isKeyFrame) {
                sendNalUnit(transport, sps, timestamp, marker = false)
                sendNalUnit(transport, pps, timestamp, marker = false)
            }
            nalUnits.forEachIndexed { index, nalUnit ->
                sendNalUnit(
                    transport = transport,
                    nalUnit = nalUnit,
                    timestamp = timestamp,
                    marker = index == nalUnits.lastIndex
                )
            }
        }

        private fun handleDescribe(request: RtspRequest) {
            val info = streamInfo
            if (info == null) {
                respond(request, "503 Service Unavailable")
                return
            }
            if (!matchesTrack(request.uri)) {
                respond(request, "404 Not Found")
                return
            }
            val sdp = buildSdp(info)
            val body = sdp.toByteArray(StandardCharsets.UTF_8)
            respond(
                request = request,
                status = "200 OK",
                headers = listOf(
                    "Content-Base: ${normalizedUrl()}/",
                    "Content-Type: application/sdp",
                    "Content-Length: ${body.size}"
                ),
                body = body
            )
        }

        private fun handleSetup(request: RtspRequest) {
            if (!matchesTrack(request.uri)) {
                respond(request, "404 Not Found")
                return
            }

            val requestedTransport = request.headers["transport"]
            if (requestedTransport.isNullOrBlank()) {
                respond(request, "461 Unsupported Transport")
                return
            }

            val parsed = parseTransport(requestedTransport)
            if (parsed == null) {
                respond(request, "461 Unsupported Transport")
                return
            }

            val session = when (parsed.mode) {
                TransportMode.TCP -> {
                    val existing = transportSession as? TcpTransportSession
                    existing ?: TcpTransportSession(
                        rtpChannel = parsed.rtpChannel ?: 0,
                        rtcpChannel = parsed.rtcpChannel ?: 1,
                        sender = ::sendInterleavedPacket
                    )
                }

                TransportMode.UDP -> {
                    val clientRtpPort = parsed.clientRtpPort
                    val clientRtcpPort = parsed.clientRtcpPort
                    if (clientRtpPort == null || clientRtcpPort == null) {
                        respond(request, "461 Unsupported Transport")
                        return
                    }
                    val existing = transportSession as? UdpTransportSession
                    existing?.takeIf {
                        it.clientRtpPort == clientRtpPort && it.clientRtcpPort == clientRtcpPort
                    } ?: UdpTransportSession(
                        clientAddress = socket.inetAddress.hostAddress
                            ?: throw IllegalStateException("Client IP missing"),
                        clientRtpPort = clientRtpPort,
                        clientRtcpPort = clientRtcpPort
                    )
                }
            }

            transportSession = session
            playing = false
            respond(
                request = request,
                status = "200 OK",
                headers = listOf(
                    session.transportHeader(),
                    "Session: $sessionId"
                )
            )
        }

        private fun handlePlay(request: RtspRequest) {
            if (transportSession == null) {
                respond(request, "454 Session Not Found")
                return
            }
            playing = true
            respond(
                request = request,
                status = "200 OK",
                headers = listOf(
                    "Session: $sessionId",
                    "RTP-Info: url=${normalizedTrackUrl()};seq=$sequenceNumber;rtptime=0"
                )
            )
        }

        private fun respondWithSession(request: RtspRequest, status: String) {
            val headers = buildList {
                if (transportSession != null) add("Session: $sessionId")
            }
            respond(request, status, headers)
        }

        private fun sendNalUnit(
            transport: TransportSession,
            nalUnit: ByteArray,
            timestamp: Long,
            marker: Boolean
        ) {
            val payloadLimit = 1_400
            if (nalUnit.size <= payloadLimit) {
                transport.sendRtpPacket(buildRtpPacket(nalUnit, timestamp, marker))
                return
            }

            val nalHeader = nalUnit[0].toInt() and 0xFF
            val fuIndicator = (nalHeader and 0xE0) or 28
            val nalType = nalHeader and 0x1F
            var offset = 1
            while (offset < nalUnit.size) {
                val remaining = nalUnit.size - offset
                val chunkSize = remaining.coerceAtMost(payloadLimit - 2)
                val start = offset == 1
                val end = offset + chunkSize >= nalUnit.size
                val fuHeader = (if (start) 0x80 else 0x00) or
                    (if (end) 0x40 else 0x00) or
                    nalType
                val payload = ByteArray(chunkSize + 2)
                payload[0] = fuIndicator.toByte()
                payload[1] = fuHeader.toByte()
                System.arraycopy(nalUnit, offset, payload, 2, chunkSize)
                transport.sendRtpPacket(buildRtpPacket(payload, timestamp, marker && end))
                offset += chunkSize
            }
        }

        private fun buildRtpPacket(payload: ByteArray, timestamp: Long, marker: Boolean): ByteArray {
            val packet = ByteArray(12 + payload.size)
            packet[0] = 0x80.toByte()
            packet[1] = ((if (marker) 0x80 else 0x00) or 96).toByte()
            packet[2] = (sequenceNumber shr 8).toByte()
            packet[3] = sequenceNumber.toByte()
            sequenceNumber = (sequenceNumber + 1) and 0xFFFF
            packet[4] = (timestamp shr 24).toByte()
            packet[5] = (timestamp shr 16).toByte()
            packet[6] = (timestamp shr 8).toByte()
            packet[7] = timestamp.toByte()
            packet[8] = (ssrc shr 24).toByte()
            packet[9] = (ssrc shr 16).toByte()
            packet[10] = (ssrc shr 8).toByte()
            packet[11] = ssrc.toByte()
            System.arraycopy(payload, 0, packet, 12, payload.size)
            return packet
        }

        private fun sendInterleavedPacket(channel: Int, packet: ByteArray) {
            synchronized(outputLock) {
                output.write('$'.code)
                output.write(channel)
                val length = packet.size
                output.write((length shr 8) and 0xFF)
                output.write(length and 0xFF)
                output.write(packet)
                output.flush()
            }
        }

        private fun buildSdp(info: StreamInfo): String {
            val spsB64 = Base64.getEncoder().encodeToString(info.sps)
            val ppsB64 = Base64.getEncoder().encodeToString(info.pps)
            val profileLevelId = info.sps
                .takeIf { it.size >= 4 }
                ?.let {
                    "%02X%02X%02X".format(
                        Locale.US,
                        it[1].toInt() and 0xFF,
                        it[2].toInt() and 0xFF,
                        it[3].toInt() and 0xFF
                    )
                } ?: "42E01F"

            return buildString {
                appendLine("v=0")
                appendLine("o=- 0 0 IN IP4 $serverAddress")
                appendLine("s=RemCam")
                appendLine("c=IN IP4 $serverAddress")
                appendLine("t=0 0")
                appendLine("a=control:*")
                appendLine("m=video 0 RTP/AVP 96")
                appendLine("a=rtpmap:96 H264/90000")
                appendLine("a=fmtp:96 packetization-mode=1;profile-level-id=$profileLevelId;sprop-parameter-sets=$spsB64,$ppsB64")
                appendLine("a=framerate:${info.fps}")
                appendLine("a=framesize:96 ${info.width}-${info.height}")
                appendLine("a=control:trackID=0")
            }
        }

        private fun readRequest(): RtspRequest? {
            val requestLine = reader.readLine() ?: return null
            if (requestLine.isBlank()) return null
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: throw EOFException("Unexpected EOF while reading RTSP headers")
                if (line.isBlank()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase(Locale.US)] =
                        line.substring(separator + 1).trim()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val buffer = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val read = reader.read(buffer, readTotal, contentLength - readTotal)
                    if (read == -1) break
                    readTotal += read
                }
                String(buffer, 0, readTotal)
            } else {
                null
            }
            val parts = requestLine.split(" ")
            val method = parts.firstOrNull()?.uppercase(Locale.US) ?: return null
            val uri = parts.getOrNull(1) ?: ""
            cSeq = headers["cseq"] ?: "1"
            return RtspRequest(
                method = method,
                uri = uri,
                headers = headers,
                body = body
            )
        }

        private fun logRequest(request: RtspRequest) {
            Log.i(
                TAG_RTSP_SERVER,
                "Request ${request.method} url=${request.uri} transport=${request.headers["transport"] ?: "none"} client=$clientLabel"
            )
        }

        private fun respond(
            request: RtspRequest,
            status: String,
            headers: List<String> = emptyList(),
            body: ByteArray? = null
        ) {
            val payload = buildString {
                append("RTSP/1.0 ").append(status).append("\r\n")
                append("CSeq: ").append(cSeq).append("\r\n")
                append("Server: RemCam\r\n")
                headers.forEach { append(it).append("\r\n") }
                append("\r\n")
            }.toByteArray(StandardCharsets.UTF_8)

            synchronized(outputLock) {
                output.write(payload)
                if (body != null) output.write(body)
                output.flush()
            }

            Log.i(
                TAG_RTSP_SERVER,
                "Response ${request.method} status=$status url=${request.uri} transport=${transportSession?.description ?: "none"} client=$clientLabel"
            )
        }

        private fun normalizedUrl(): String = "rtsp://$serverAddress:$port$streamPath"
        private fun normalizedTrackUrl(): String = "${normalizedUrl()}/trackID=0"

        private fun matchesTrack(uri: String): Boolean {
            return uri.endsWith(streamPath) || uri.endsWith("$streamPath/trackID=0")
        }

        fun close() {
            if (closed) return
            closed = true
            playing = false
            runCatching { transportSession?.close() }
            transportSession = null
            runCatching { socket.close() }
            Log.i(TAG_CLIENT_CONNECTION, "Client closed: $clientLabel")
        }
    }
}

private data class StreamInfo(
    val width: Int,
    val height: Int,
    val fps: Int,
    val sps: ByteArray,
    val pps: ByteArray
)

private data class RtspRequest(
    val method: String,
    val uri: String,
    val headers: Map<String, String>,
    val body: String?
)

private enum class TransportMode {
    TCP,
    UDP
}

private data class RequestedTransport(
    val mode: TransportMode,
    val rtpChannel: Int? = null,
    val rtcpChannel: Int? = null,
    val clientRtpPort: Int? = null,
    val clientRtcpPort: Int? = null
)

private sealed interface TransportSession {
    val description: String
    fun transportHeader(): String
    fun sendRtpPacket(packet: ByteArray)
    fun close()
}

private class TcpTransportSession(
    private val rtpChannel: Int,
    private val rtcpChannel: Int,
    private val sender: (Int, ByteArray) -> Unit
) : TransportSession {
    override val description: String = "tcp(interleaved=$rtpChannel-$rtcpChannel)"

    override fun transportHeader(): String =
        "Transport: RTP/AVP/TCP;unicast;interleaved=$rtpChannel-$rtcpChannel"

    override fun sendRtpPacket(packet: ByteArray) {
        sender(rtpChannel, packet)
    }

    override fun close() = Unit
}

private class UdpTransportSession(
    clientAddress: String,
    val clientRtpPort: Int,
    val clientRtcpPort: Int
) : TransportSession {
    private val rtpSocket = DatagramSocket()
    private val rtcpSocket = DatagramSocket()
    private val address = java.net.InetAddress.getByName(clientAddress)
    private val serverRtpPort = rtpSocket.localPort
    private val serverRtcpPort = rtcpSocket.localPort

    override val description: String =
        "udp(client=$clientRtpPort-$clientRtcpPort server=$serverRtpPort-$serverRtcpPort)"

    override fun transportHeader(): String =
        "Transport: RTP/AVP;unicast;client_port=$clientRtpPort-$clientRtcpPort;server_port=$serverRtpPort-$serverRtcpPort"

    override fun sendRtpPacket(packet: ByteArray) {
        rtpSocket.send(DatagramPacket(packet, packet.size, address, clientRtpPort))
    }

    override fun close() {
        runCatching { rtpSocket.close() }
        runCatching { rtcpSocket.close() }
    }
}

private fun parseTransport(header: String): RequestedTransport? {
    val lower = header.lowercase(Locale.US)
    return when {
        lower.contains("rtp/avp/tcp") -> {
            val channels = Regex("""interleaved=(\d+)-(\d+)""").find(lower)
            RequestedTransport(
                mode = TransportMode.TCP,
                rtpChannel = channels?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0,
                rtcpChannel = channels?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 1
            )
        }

        lower.contains("rtp/avp") -> {
            val ports = Regex("""client_port=(\d+)-(\d+)""").find(lower) ?: return null
            RequestedTransport(
                mode = TransportMode.UDP,
                clientRtpPort = ports.groupValues[1].toIntOrNull(),
                clientRtcpPort = ports.groupValues[2].toIntOrNull()
            )
        }

        else -> null
    }
}

private fun splitNalUnits(buffer: ByteArray): List<ByteArray> {
    if (buffer.isEmpty()) return emptyList()
    val nalUnits = mutableListOf<ByteArray>()
    if (buffer.size >= 4 && buffer[0] == 0.toByte() && buffer[1] == 0.toByte() &&
        ((buffer[2] == 0.toByte() && buffer[3] == 1.toByte()) || buffer[2] == 1.toByte())
    ) {
        var offset = findStartCode(buffer, 0)
        while (offset >= 0 && offset < buffer.size) {
            val startCodeLength = if (buffer[offset + 2] == 1.toByte()) 3 else 4
            val nalStart = offset + startCodeLength
            val nextOffset = findStartCode(buffer, nalStart)
            val nalEnd = if (nextOffset == -1) buffer.size else nextOffset
            if (nalStart < nalEnd) {
                nalUnits += buffer.copyOfRange(nalStart, nalEnd)
            }
            offset = nextOffset
        }
        return nalUnits
    }

    val wrapped = ByteBuffer.wrap(buffer)
    while (wrapped.remaining() > 4) {
        val length = wrapped.int
        if (length <= 0 || length > wrapped.remaining()) break
        val nalUnit = ByteArray(length)
        wrapped.get(nalUnit)
        nalUnits += nalUnit
    }
    return nalUnits
}

private fun findStartCode(buffer: ByteArray, offset: Int): Int {
    var index = offset
    while (index + 3 < buffer.size) {
        if (buffer[index] == 0.toByte() && buffer[index + 1] == 0.toByte()) {
            if (buffer[index + 2] == 1.toByte()) return index
            if (index + 3 < buffer.size && buffer[index + 2] == 0.toByte() && buffer[index + 3] == 1.toByte()) {
                return index
            }
        }
        index++
    }
    return -1
}

private const val TAG_RTSP_SERVER = "RTSP_SERVER"
private const val TAG_VIDEO_ENCODER = "VIDEO_ENCODER"
private const val TAG_CLIENT_CONNECTION = "CLIENT_CONNECTION"
