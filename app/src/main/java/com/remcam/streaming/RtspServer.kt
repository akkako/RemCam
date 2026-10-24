package com.remcam.streaming

import com.remcam.VideoCodec

import android.util.Log
import java.io.BufferedInputStream
import java.io.EOFException
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
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
        codec: VideoCodec,
        width: Int,
        height: Int,
        fps: Int,
        vps: ByteArray?,
        sps: ByteArray,
        pps: ByteArray
    ) {
        streamInfo = StreamInfo(codec, width, height, fps, vps, sps, pps)
        Log.i(
            TAG_VIDEO_ENCODER,
            "Stream info updated: ${codec.sdpName} ${width}x$height @ ${fps}fps"
        )
    }

    /**
     * @param accessUnitLength valid bytes in [accessUnit]. The same buffer is
     * reused by the encoder for every frame, so it must not be retained.
     */
    fun dispatchSample(
        accessUnit: ByteArray,
        accessUnitLength: Int,
        presentationTimeUs: Long,
        isKeyFrame: Boolean
    ) {
        val info = streamInfo ?: return
        val timestamp = ((presentationTimeUs * 90L) / 1000L).coerceAtLeast(0L)
        // Iterate backwards so removing a failed client does not shift the
        // indices of clients that still need this frame.
        for (index in clients.lastIndex downTo 0) {
            val client = clients[index]
            if (!client.isReadyToStream()) continue
            try {
                client.sendAccessUnit(
                    accessUnit = accessUnit,
                    accessUnitLength = accessUnitLength,
                    timestamp = timestamp,
                    isKeyFrame = isKeyFrame,
                    info = info
                )
            } catch (error: Exception) {
                Log.w(
                    TAG_CLIENT_CONNECTION,
                    "Streaming error for ${client.clientLabel}: ${error.message}",
                    error
                )
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
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = socket.getOutputStream()
        private val sessionId = UUID.randomUUID().toString()
        private val ssrc = Random.nextInt().toLong() and 0xffffffffL
        private var cSeq: String = "1"
        private var sequenceNumber = Random.nextInt(0, 65_535)

        private var rtpPacketCount = 0L
        private var rtpOctetCount = 0L
        private var lastRtpTimestamp = 0L
        private var lastRtpWallClockNanos = 0L
        private var lastRtcpSentNanos = 0L

        @Volatile
        private var transportSession: TransportSession? = null

        // Reused per frame by the single encoder thread that dispatches RTP.
        private val rtpHeader = ByteArray(RTP_HEADER_SIZE)
        private val fuPrefix = ByteArray(3)

        @Volatile
        private var closed = false

        @Volatile
        private var playing = false

        @Volatile
        private var waitingForKeyFrame = true

        val clientLabel: String = "${socket.inetAddress.hostAddress}:${socket.port}"
        private val serverAddress: String =
            (socket.localAddress as? Inet4Address)?.hostAddress ?: hostAddress

        fun handleRequests() {
            socket.tcpNoDelay = true
            runCatching { socket.sendBufferSize = 512 * 1024 }
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
            accessUnitLength: Int,
            timestamp: Long,
            isKeyFrame: Boolean,
            info: StreamInfo
        ) {
            val transport = transportSession ?: return
            // Starting a client on a P-frame produces missing-reference
            // warnings and corrupted first frames. Wait for the next IDR.
            if (waitingForKeyFrame) {
                if (!isKeyFrame) return
                waitingForKeyFrame = false
            }
            val nalCount = countNalUnits(accessUnit, accessUnitLength)
            if (nalCount == 0) return

            // All packets of one access unit are appended to the transport
            // buffer and flushed with a single socket write.
            transport.beginAccessUnit()
            if (isKeyFrame) {
                if (info.codec == VideoCodec.H265) {
                    info.vps?.let {
                        sendSingleNal(transport, it, 0, it.size, timestamp, marker = false)
                    }
                }
                sendSingleNal(transport, info.sps, 0, info.sps.size, timestamp, marker = false)
                sendSingleNal(transport, info.pps, 0, info.pps.size, timestamp, marker = false)
            }
            var sent = 0
            forEachNalUnit(accessUnit, accessUnitLength) { offset, size ->
                sent++
                sendNalUnit(
                    transport = transport,
                    source = accessUnit,
                    offset = offset,
                    length = size,
                    timestamp = timestamp,
                    marker = sent == nalCount,
                    codec = info.codec
                )
            }
            transport.endAccessUnit()
            lastRtpTimestamp = timestamp and 0xFFFFFFFFL
            lastRtpWallClockNanos = System.nanoTime()
            maybeSendRtcp(transport)
        }

        private fun sendNalUnit(
            transport: TransportSession,
            source: ByteArray,
            offset: Int,
            length: Int,
            timestamp: Long,
            marker: Boolean,
            codec: VideoCodec
        ) {
            if (length <= 0) return
            if (codec == VideoCodec.H265) {
                sendH265NalUnit(transport, source, offset, length, timestamp, marker)
            } else {
                sendH264NalUnit(transport, source, offset, length, timestamp, marker)
            }
        }

        private fun sendH264NalUnit(
            transport: TransportSession,
            source: ByteArray,
            offset: Int,
            length: Int,
            timestamp: Long,
            marker: Boolean
        ) {
            if (length <= RTP_PAYLOAD_LIMIT) {
                sendSingleNal(transport, source, offset, length, timestamp, marker)
                return
            }

            val nalHeader = source[offset].toInt() and 0xFF
            val fuIndicator = (nalHeader and 0xE0) or 28
            val nalType = nalHeader and 0x1F
            val end = offset + length
            var position = offset + 1
            var first = true
            while (position < end) {
                val chunkSize = (end - position).coerceAtMost(RTP_PAYLOAD_LIMIT - 2)
                val last = position + chunkSize >= end
                fuPrefix[0] = fuIndicator.toByte()
                fuPrefix[1] = ((if (first) 0x80 else 0x00) or
                    (if (last) 0x40 else 0x00) or
                    nalType).toByte()
                buildRtpHeader(timestamp, marker && last)
                sendRtpPacket(
                    transport = transport,
                    header = rtpHeader,
                    prefix = fuPrefix,
                    prefixLength = 2,
                    payload = source,
                    payloadOffset = position,
                    payloadLength = chunkSize
                )
                position += chunkSize
                first = false
            }
        }

        /**
         * HEVC RTP payload per RFC 7798: single NAL unit for small NALs,
         * FU (type 49) with a 2-byte payload header and 1-byte FU header for
         * larger NALs.
         */
        private fun sendH265NalUnit(
            transport: TransportSession,
            source: ByteArray,
            offset: Int,
            length: Int,
            timestamp: Long,
            marker: Boolean
        ) {
            if (length <= RTP_PAYLOAD_LIMIT) {
                sendSingleNal(transport, source, offset, length, timestamp, marker)
                return
            }
            if (length < 4) return

            val nalHeader0 = source[offset].toInt() and 0xFF
            val nalHeader1 = source[offset + 1].toInt() and 0xFF
            val nalType = (nalHeader0 ushr 1) and 0x3F
            val fuIndicator0 = (nalHeader0 and 0x81) or (49 shl 1)
            fuPrefix[0] = fuIndicator0.toByte()
            fuPrefix[1] = nalHeader1.toByte()
            val end = offset + length
            var position = offset + 2
            var first = true
            while (position < end) {
                val chunkSize = (end - position).coerceAtMost(RTP_PAYLOAD_LIMIT - 3)
                val last = position + chunkSize >= end
                fuPrefix[2] = ((if (first) 0x80 else 0x00) or
                    (if (last) 0x40 else 0x00) or
                    nalType).toByte()
                buildRtpHeader(timestamp, marker && last)
                sendRtpPacket(
                    transport = transport,
                    header = rtpHeader,
                    prefix = fuPrefix,
                    prefixLength = 3,
                    payload = source,
                    payloadOffset = position,
                    payloadLength = chunkSize
                )
                position += chunkSize
                first = false
            }
        }

        private fun sendSingleNal(
            transport: TransportSession,
            payload: ByteArray,
            payloadOffset: Int,
            payloadLength: Int,
            timestamp: Long,
            marker: Boolean
        ) {
            if (payloadLength <= 0) return
            buildRtpHeader(timestamp, marker)
            sendRtpPacket(
                transport = transport,
                header = rtpHeader,
                prefix = null,
                prefixLength = 0,
                payload = payload,
                payloadOffset = payloadOffset,
                payloadLength = payloadLength
            )
        }

        private fun sendRtpPacket(
            transport: TransportSession,
            header: ByteArray,
            prefix: ByteArray?,
            prefixLength: Int,
            payload: ByteArray,
            payloadOffset: Int,
            payloadLength: Int
        ) {
            transport.sendRtp(
                header = header,
                prefix = prefix,
                prefixLength = prefixLength,
                payload = payload,
                payloadOffset = payloadOffset,
                payloadLength = payloadLength
            )
            rtpPacketCount++
            rtpOctetCount += (prefixLength + payloadLength).toLong()
        }

        private fun buildRtpHeader(timestamp: Long, marker: Boolean) {
            rtpHeader[0] = 0x80.toByte()
            rtpHeader[1] = ((if (marker) 0x80 else 0x00) or RTP_PAYLOAD_TYPE).toByte()
            rtpHeader[2] = (sequenceNumber shr 8).toByte()
            rtpHeader[3] = sequenceNumber.toByte()
            sequenceNumber = (sequenceNumber + 1) and 0xFFFF
            rtpHeader[4] = (timestamp shr 24).toByte()
            rtpHeader[5] = (timestamp shr 16).toByte()
            rtpHeader[6] = (timestamp shr 8).toByte()
            rtpHeader[7] = timestamp.toByte()
            rtpHeader[8] = (ssrc shr 24).toByte()
            rtpHeader[9] = (ssrc shr 16).toByte()
            rtpHeader[10] = (ssrc shr 8).toByte()
            rtpHeader[11] = ssrc.toByte()
        }

        private fun maybeSendRtcp(transport: TransportSession) {
            if (!playing) return
            val rtpWallClock = lastRtpWallClockNanos
            if (rtpWallClock == 0L) return
            val now = System.nanoTime()
            if (lastRtcpSentNanos != 0L && now - lastRtcpSentNanos < RTCP_INTERVAL_NS) {
                return
            }
            val elapsed = (now - rtpWallClock).coerceAtLeast(0L)
            val rtpNow = (lastRtpTimestamp + elapsed * 90_000L / 1_000_000_000L) and
                0xFFFFFFFFL
            transport.sendRtcp(buildRtcpSenderReport(rtpNow, System.currentTimeMillis()))
            lastRtcpSentNanos = now
        }

        /**
         * Compound RTCP packet: Sender Report followed by SDES with CNAME.
         * The SR gives the client an authoritative NTP <-> RTP mapping.
         */
        private fun buildRtcpSenderReport(
            rtpTimestamp: Long,
            wallClockMillis: Long
        ): ByteArray {
            val cname = "RemCam-${ssrc.toString(16)}"
                .toByteArray(StandardCharsets.US_ASCII)
            val sdesChunkLength = 4 + 2 + cname.size + 1
            val sdesLength = (sdesChunkLength + 3) and -4
            val packet = ByteArray(RTCP_SR_SIZE + RTCP_SDES_HEADER_SIZE + sdesLength)

            packet[0] = 0x80.toByte()
            packet[1] = RTCP_PT_SENDER_REPORT.toByte()
            putUInt16(packet, 2, 6)
            putUInt32(packet, 4, ssrc)
            val ntpSeconds = wallClockMillis / 1000L + NTP_EPOCH_OFFSET_SECONDS
            val ntpFraction = ((wallClockMillis % 1000L) shl 32) / 1000L
            putUInt32(packet, 8, ntpSeconds)
            putUInt32(packet, 12, ntpFraction)
            putUInt32(packet, 16, rtpTimestamp)
            putUInt32(packet, 20, rtpPacketCount)
            putUInt32(packet, 24, rtpOctetCount)

            var offset = RTCP_SR_SIZE
            packet[offset] = 0x81.toByte()
            packet[offset + 1] = RTCP_PT_SOURCE_DESCRIPTION.toByte()
            putUInt16(packet, offset + 2, sdesLength / 4)
            offset += RTCP_SDES_HEADER_SIZE
            putUInt32(packet, offset, ssrc)
            offset += 4
            packet[offset++] = 1 // CNAME item
            packet[offset++] = cname.size.toByte()
            System.arraycopy(cname, 0, packet, offset, cname.size)
            offset += cname.size
            packet[offset] = 0 // END item; remaining bytes are zero padding
            return packet
        }

        private fun sendInterleavedData(data: ByteArray, length: Int) {
            synchronized(outputLock) {
                output.write(data, 0, length)
                output.flush()
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
                        sender = ::sendInterleavedData
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
            waitingForKeyFrame = true
            playing = true
            respond(
                request = request,
                status = "200 OK",
                headers = listOf(
                    "Session: $sessionId",
                    "RTP-Info: url=${normalizedTrackUrl()};seq=$sequenceNumber"
                )
            )
        }

        private fun respondWithSession(request: RtspRequest, status: String) {
            val headers = buildList {
                if (transportSession != null) add("Session: $sessionId")
            }
            respond(request, status, headers)
        }

        private fun buildSdp(info: StreamInfo): String {
            val spsB64 = Base64.getEncoder().encodeToString(info.sps)
            val ppsB64 = Base64.getEncoder().encodeToString(info.pps)

            return buildString {
                appendLine("v=0")
                appendLine("o=- 0 0 IN IP4 $serverAddress")
                appendLine("s=RemCam")
                appendLine("c=IN IP4 $serverAddress")
                appendLine("t=0 0")
                appendLine("a=control:*")
                appendLine("m=video 0 RTP/AVP 96")
                when (info.codec) {
                    VideoCodec.H264 -> {
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
                        appendLine("a=rtpmap:96 H264/90000")
                        appendLine(
                            "a=fmtp:96 packetization-mode=1;" +
                                "profile-level-id=$profileLevelId;" +
                                "sprop-parameter-sets=$spsB64,$ppsB64"
                        )
                    }

                    VideoCodec.H265 -> {
                        val vpsB64 = info.vps?.let {
                            Base64.getEncoder().encodeToString(it)
                        } ?: ""
                        appendLine("a=rtpmap:96 H265/90000")
                        appendLine(
                            "a=fmtp:96 sprop-vps=$vpsB64;" +
                                "sprop-sps=$spsB64;sprop-pps=$ppsB64"
                        )
                    }
                }
                appendLine("a=framerate:${info.fps}")
                appendLine("a=framesize:96 ${info.width}-${info.height}")
                appendLine("a=control:trackID=0")
            }
        }

        private fun readRequest(): RtspRequest? {
            val requestLine = readLineSkippingInterleaved() ?: return null
            if (requestLine.isBlank()) return null
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readLineSkippingInterleaved()
                    ?: throw EOFException("Unexpected EOF while reading RTSP headers")
                if (line.isBlank()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase(Locale.US)] =
                        line.substring(separator + 1).trim()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val bytes = ByteArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val read = input.read(bytes, readTotal, contentLength - readTotal)
                    if (read == -1) break
                    readTotal += read
                }
                String(bytes, 0, readTotal, StandardCharsets.UTF_8)
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

        /**
         * Reads an RTSP line while consuming any client-to-server interleaved
         * RTCP frames. VLC sends RTCP receiver reports on the same TCP socket,
         * so a plain line reader would otherwise desynchronise the stream.
         */
        private fun readLineSkippingInterleaved(): String? {
            while (true) {
                var value = input.read()
                if (value == -1) return null
                if (value == '$'.code) {
                    skipInterleavedFrame()
                    continue
                }
                val line = StringBuilder()
                while (value != -1 && value != '\n'.code) {
                    if (value != '\r'.code) line.append(value.toChar())
                    value = input.read()
                }
                return line.toString()
            }
        }

        private fun skipInterleavedFrame() {
            val channel = input.read()
            val lengthHigh = input.read()
            val lengthLow = input.read()
            if (channel < 0 || lengthHigh < 0 || lengthLow < 0) {
                throw EOFException("Unexpected EOF while reading interleaved frame")
            }
            var remaining = (lengthHigh shl 8) or lengthLow
            while (remaining > 0) {
                val skipped = input.skip(remaining.toLong())
                if (skipped > 0) {
                    remaining -= skipped.toInt()
                } else {
                    if (input.read() == -1) {
                        throw EOFException("Unexpected EOF while reading interleaved frame")
                    }
                    remaining--
                }
            }
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
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    val fps: Int,
    val vps: ByteArray?,
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
    fun beginAccessUnit() = Unit
    fun sendRtp(
        header: ByteArray,
        prefix: ByteArray?,
        prefixLength: Int,
        payload: ByteArray,
        payloadOffset: Int,
        payloadLength: Int
    )
    fun endAccessUnit() = Unit
    fun sendRtcp(packet: ByteArray)
    fun close()
}

private class TcpTransportSession(
    private val rtpChannel: Int,
    private val rtcpChannel: Int,
    private val sender: (ByteArray, Int) -> Unit
) : TransportSession {
    override val description: String = "tcp(interleaved=$rtpChannel-$rtcpChannel)"

    /**
     * Interleaved framing for a complete access unit is assembled here and
     * handed to the socket in one write, which avoids a flush per RTP packet.
     */
    private var frameBuffer = ByteArray(64 * 1024)
    private var frameLength = 0

    override fun transportHeader(): String =
        "Transport: RTP/AVP/TCP;unicast;interleaved=$rtpChannel-$rtcpChannel"

    override fun beginAccessUnit() {
        frameLength = 0
    }

    override fun sendRtp(
        header: ByteArray,
        prefix: ByteArray?,
        prefixLength: Int,
        payload: ByteArray,
        payloadOffset: Int,
        payloadLength: Int
    ) {
        val packetLength = RTP_HEADER_SIZE + prefixLength + payloadLength
        require(packetLength <= 0xFFFF) { "RTP packet too large: $packetLength" }
        ensureCapacity(frameLength + 5 + packetLength)

        frameBuffer[frameLength++] = '$'.code.toByte()
        frameBuffer[frameLength++] = rtpChannel.toByte()
        frameBuffer[frameLength++] = ((packetLength shr 8) and 0xFF).toByte()
        frameBuffer[frameLength++] = (packetLength and 0xFF).toByte()
        System.arraycopy(header, 0, frameBuffer, frameLength, RTP_HEADER_SIZE)
        frameLength += RTP_HEADER_SIZE
        if (prefix != null && prefixLength > 0) {
            System.arraycopy(prefix, 0, frameBuffer, frameLength, prefixLength)
            frameLength += prefixLength
        }
        System.arraycopy(payload, payloadOffset, frameBuffer, frameLength, payloadLength)
        frameLength += payloadLength
    }

    override fun endAccessUnit() {
        if (frameLength == 0) return
        val data = frameBuffer
        val length = frameLength
        frameLength = 0
        sender(data, length)
    }

    override fun sendRtcp(packet: ByteArray) {
        require(packet.size <= 0xFFFF) { "RTCP packet too large: ${packet.size}" }
        val frame = ByteArray(4 + packet.size)
        frame[0] = '$'.code.toByte()
        frame[1] = rtcpChannel.toByte()
        frame[2] = ((packet.size shr 8) and 0xFF).toByte()
        frame[3] = (packet.size and 0xFF).toByte()
        System.arraycopy(packet, 0, frame, 4, packet.size)
        sender(frame, frame.size)
    }

    private fun ensureCapacity(required: Int) {
        if (required <= frameBuffer.size) return
        var newSize = frameBuffer.size
        while (newSize < required) newSize = newSize shl 1
        frameBuffer = frameBuffer.copyOf(newSize)
    }

    override fun close() = Unit
}

private class UdpTransportSession(
    clientAddress: String,
    val clientRtpPort: Int,
    val clientRtcpPort: Int
) : TransportSession {
    private val rtpSocket = DatagramSocket().apply {
        runCatching { sendBufferSize = 512 * 1024 }
    }
    private val rtcpSocket = DatagramSocket()
    private val address = java.net.InetAddress.getByName(clientAddress)
    private val serverRtpPort = rtpSocket.localPort
    private val serverRtcpPort = rtcpSocket.localPort
    private var packetBuffer = ByteArray(RTP_HEADER_SIZE + RTP_PAYLOAD_LIMIT)

    override val description: String =
        "udp(client=$clientRtpPort-$clientRtcpPort server=$serverRtpPort-$serverRtcpPort)"

    override fun transportHeader(): String =
        "Transport: RTP/AVP;unicast;client_port=$clientRtpPort-$clientRtcpPort;server_port=$serverRtpPort-$serverRtcpPort"

    override fun sendRtp(
        header: ByteArray,
        prefix: ByteArray?,
        prefixLength: Int,
        payload: ByteArray,
        payloadOffset: Int,
        payloadLength: Int
    ) {
        val packetLength = RTP_HEADER_SIZE + prefixLength + payloadLength
        if (packetBuffer.size < packetLength) packetBuffer = ByteArray(packetLength)
        System.arraycopy(header, 0, packetBuffer, 0, RTP_HEADER_SIZE)
        var offset = RTP_HEADER_SIZE
        if (prefix != null && prefixLength > 0) {
            System.arraycopy(prefix, 0, packetBuffer, offset, prefixLength)
            offset += prefixLength
        }
        System.arraycopy(payload, payloadOffset, packetBuffer, offset, payloadLength)
        rtpSocket.send(DatagramPacket(packetBuffer, packetLength, address, clientRtpPort))
    }

    override fun sendRtcp(packet: ByteArray) {
        rtcpSocket.send(DatagramPacket(packet, packet.size, address, clientRtcpPort))
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

internal fun splitNalUnits(buffer: ByteArray): List<ByteArray> {
    if (buffer.isEmpty()) return emptyList()
    val nalUnits = ArrayList<ByteArray>(4)
    forEachNalUnit(buffer, buffer.size) { offset, size ->
        nalUnits += buffer.copyOfRange(offset, offset + size)
    }
    return nalUnits
}

private fun countNalUnits(buffer: ByteArray, length: Int): Int {
    var count = 0
    forEachNalUnit(buffer, length) { _, _ -> count++ }
    return count
}

/**
 * Iterates NAL units without copying them. Supports both Annex-B start codes
 * and the 4-byte length-prefixed layout produced by MediaCodec.
 */
private inline fun forEachNalUnit(
    buffer: ByteArray,
    length: Int,
    action: (offset: Int, size: Int) -> Unit
) {
    if (length <= 0) return
    val annexB = length >= 4 &&
        buffer[0] == 0.toByte() && buffer[1] == 0.toByte() &&
        ((buffer[2] == 0.toByte() && buffer[3] == 1.toByte()) || buffer[2] == 1.toByte())
    if (annexB) {
        var startCode = findStartCode(buffer, 0, length)
        while (startCode >= 0) {
            val startCodeLength = if (buffer[startCode + 2] == 1.toByte()) 3 else 4
            val nalStart = startCode + startCodeLength
            val nextStartCode = findStartCode(buffer, nalStart, length)
            val nalEnd = if (nextStartCode < 0) length else nextStartCode
            if (nalStart < nalEnd) action(nalStart, nalEnd - nalStart)
            startCode = nextStartCode
        }
        return
    }

    var offset = 0
    while (offset + 4 <= length) {
        val nalLength =
            ((buffer[offset].toInt() and 0xFF) shl 24) or
                ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
                ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
                (buffer[offset + 3].toInt() and 0xFF)
        offset += 4
        if (nalLength <= 0 || nalLength > length - offset) break
        action(offset, nalLength)
        offset += nalLength
    }
}

private fun findStartCode(buffer: ByteArray, offset: Int, length: Int): Int {
    var index = offset
    while (index + 3 < length) {
        if (buffer[index] == 0.toByte() && buffer[index + 1] == 0.toByte()) {
            if (buffer[index + 2] == 1.toByte()) return index
            if (buffer[index + 2] == 0.toByte() && buffer[index + 3] == 1.toByte()) {
                return index
            }
        }
        index++
    }
    return -1
}

private fun putUInt16(destination: ByteArray, offset: Int, value: Int) {
    destination[offset] = (value shr 8).toByte()
    destination[offset + 1] = value.toByte()
}

private fun putUInt32(destination: ByteArray, offset: Int, value: Long) {
    destination[offset] = (value shr 24).toByte()
    destination[offset + 1] = (value shr 16).toByte()
    destination[offset + 2] = (value shr 8).toByte()
    destination[offset + 3] = value.toByte()
}

private const val RTP_HEADER_SIZE = 12
private const val RTP_PAYLOAD_TYPE = 96
private const val RTP_PAYLOAD_LIMIT = 1_400
private const val RTCP_SR_SIZE = 28
private const val RTCP_SDES_HEADER_SIZE = 4
private const val RTCP_PT_SENDER_REPORT = 200
private const val RTCP_PT_SOURCE_DESCRIPTION = 202
private const val RTCP_INTERVAL_NS = 2_000_000_000L
private const val NTP_EPOCH_OFFSET_SECONDS = 2_208_988_800L
private const val TAG_RTSP_SERVER = "RTSP_SERVER"
private const val TAG_VIDEO_ENCODER = "VIDEO_ENCODER"
private const val TAG_CLIENT_CONNECTION = "CLIENT_CONNECTION"
