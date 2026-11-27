package io.nekohasekai.sfa.mcp

import android.util.Log
import io.nekohasekai.sfa.database.Settings
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loopback-only MCP (JSON-RPC 2.0) endpoint speaking the Streamable HTTP transport.
 *
 * Bind address is always 127.0.0.1 unless the user opts into LAN access; the bearer
 * token adds a second layer for clients that share the phone.
 *
 * Transport behaviour:
 * - POST /mcp with JSON bodies (Content-Length or chunked); JSON, or a single SSE
 *   event when the client asks for text/event-stream
 * - GET /mcp opens a server-to-client SSE stream with periodic pings
 * - DELETE /mcp ends the session
 * - Mcp-Session-Id issued on initialize, validated on later requests
 * - Mcp-Protocol-Version validated when present
 * - real HTTP status codes (401/404/405/406/413/415/503) instead of a blanket 200
 * - HTTP keep-alive; SSE streams live on their own pool so they cannot starve POSTs
 */
object McpServer {

    private const val TAG = "McpServer"
    const val DEFAULT_PORT = 18080

    private const val MAX_BODY = 1_048_576
    private const val MAX_LINE = 8_192
    private const val MAX_HEADERS = 64
    private const val IDLE_TIMEOUT_MS = 60_000
    private const val REQUEST_TIMEOUT_MS = 10_000
    private const val SSE_POLL_MS = 1_000
    private const val SSE_PING_MS = 15_000L
    private const val MAX_SESSIONS = 32
    private const val MAX_SSE_STREAMS = 4
    private const val MAX_CONNECTIONS = 64
    private const val SESSION_TTL_MS = 30 * 60 * 1000L

    private val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

    @Volatile
    private var serverSocket: ServerSocket? = null

    private var acceptThread: Thread? = null

    // Cached pools: a keep-alive connection parks one thread while it waits, so a small
    // fixed pool would starve as soon as a handful of clients stayed connected.
    private val workers = Executors.newCachedThreadPool { r ->
        Thread(r, "mcp-worker").apply { isDaemon = true }
    }

    private val sseWorkers = Executors.newCachedThreadPool { r ->
        Thread(r, "mcp-sse").apply { isDaemon = true }
    }

    private val sseActive = AtomicInteger()

    /** Hard cap on live connections; the cached pool would otherwise grow without bound. */
    private val connectionSlots = AtomicInteger()

    /** Live client sockets, so stop() can abort in-flight requests. */
    private val clients: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    /** Issued session ids with their last-use timestamp. */
    private val sessions: MutableMap<String, Long> = ConcurrentHashMap()

    val isRunning: Boolean get() = serverSocket?.isClosed == false && acceptThread?.isAlive == true

    @Synchronized
    fun start() {
        if (isRunning) return
        if (Settings.mcpServerToken.isBlank()) {
            Settings.mcpServerToken = newToken()
        }
        val port = Settings.mcpServerPort.takeIf { it in 1024..65535 } ?: DEFAULT_PORT
        val socket = try {
            val hasAllowList = Settings.mcpServerAllowedCidrs.isNotBlank()
            val host = if (Settings.mcpServerAllowLan || hasAllowList) "0.0.0.0" else "127.0.0.1"
            ServerSocket(port, 16, InetAddress.getByName(host))
        } catch (e: Exception) {
            Log.e(TAG, "Cannot bind 127.0.0.1:$port", e)
            return
        }
        serverSocket = socket
        acceptThread = Thread({ acceptLoop(socket) }, "mcp-accept").apply {
            isDaemon = true
            start()
        }
        McpState.start()
        Log.i(TAG, "MCP listening on 127.0.0.1:$port")
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        sessions.clear()
        McpState.stop()
        Log.i(TAG, "MCP stopped")
    }

    fun restart() {
        stop()
        if (Settings.mcpServerEnabled) start()
    }

    /**
     * Loopback is always allowed. Anything else needs [Settings.mcpServerAllowLan]; when an
     * allow-list is configured it replaces the blanket "any LAN peer" rule.
     */
    private fun isAllowedPeer(address: InetAddress?): Boolean {
        if (address == null) return false
        if (address.isLoopbackAddress) return true
        if (!Settings.mcpServerAllowLan && Settings.mcpServerAllowedCidrs.isBlank()) return false
        val raw = Settings.mcpServerAllowedCidrs
        if (raw.isBlank()) return true
        return parseCidrs(raw).any { it.matches(address) }
    }

    private class Cidr(private val network: ByteArray, private val prefix: Int) {
        fun matches(address: InetAddress): Boolean {
            val bytes = address.address
            if (bytes.size != network.size) return false
            var bits = prefix
            var index = 0
            while (bits >= 8) {
                if (bytes[index] != network[index]) return false
                index++
                bits -= 8
            }
            if (bits > 0) {
                val mask = (0xFF shl (8 - bits)) and 0xFF
                if ((bytes[index].toInt() and mask) != (network[index].toInt() and mask)) return false
            }
            return true
        }
    }

    /** Accepts comma/newline separated literal IPs or CIDRs; host names are ignored. */
    private fun parseCidrs(raw: String): List<Cidr> = raw
        .split(',', '\n', ';', ' ')
        .mapNotNull { token ->
            val entry = token.trim()
            if (entry.isEmpty()) return@mapNotNull null
            val parts = entry.split('/')
            val address = parts[0]
            val literal = address.matches(Regex("^[0-9.]+$")) ||
                address.matches(Regex("^[0-9a-fA-F:]+$"))
            if (!literal) return@mapNotNull null
            runCatching {
                val network = InetAddress.getByName(address).address
                val prefix = if (parts.size > 1) parts[1].toInt() else network.size * 8
                if (prefix in 0..network.size * 8) Cidr(network, prefix) else null
            }.getOrNull()
        }

    /** Turns away a connection when every slot is taken, without reading the request. */
    private fun rejectBusy(client: Socket) {
        runCatching {
            client.getOutputStream().apply {
                write(
                    ("HTTP/1.1 503 Service Unavailable\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: 22\r\n" +
                        "Connection: close\r\n\r\n" +
                        "\"too many connections\"").toByteArray(Charsets.UTF_8),
                )
                flush()
            }
            client.close()
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (!socket.isClosed) Log.w(TAG, "accept failed", e)
                break
            }
            if (!isAllowedPeer(client.inetAddress)) {
                Log.w(TAG, "rejected MCP client ${client.inetAddress}")
                runCatching { client.close() }
                continue
            }
            if (connectionSlots.incrementAndGet() > MAX_CONNECTIONS) {
                connectionSlots.decrementAndGet()
                rejectBusy(client)
                continue
            }
            val accepted = runCatching { workers.execute { serve(client) } }.isSuccess
            if (!accepted) {
                connectionSlots.decrementAndGet()
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        clients.add(client)
        var handedOff = false
        try {
            run {
                val socket = client
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                while (isRunning) {
                    // Idle wait for the next request; a parked keep-alive connection is
                    // allowed to sit here for a minute.
                    socket.soTimeout = IDLE_TIMEOUT_MS
                    val requestLine = try {
                        readLine(input) ?: return
                    } catch (e: SocketTimeoutException) {
                        return
                    }
                    // Once a request started, everything about it is bounded in time.
                    socket.soTimeout = REQUEST_TIMEOUT_MS
                    val request = try {
                        readRequest(input, requestLine) ?: return
                    } catch (e: SocketTimeoutException) {
                        return
                    } catch (e: BodyTooLarge) {
                        runCatching {
                            writeResponse(output, 413, "\"payload too large\"", "application/json", null, false)
                        }
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "bad request", e)
                        return
                    }
                    when {
                        request.method == "GET" && request.path == "/mcp" -> {
                            handedOff = dispatchSse(socket, input, output, request)
                            return
                        }
                        request.method == "DELETE" && request.path == "/mcp" -> {
                            handleDelete(output, request)
                            return
                        }
                        request.method == "GET" && request.path == "/health" -> {
                            writeResponse(
                                output, 200, McpTools.health(), "application/json",
                                request.sessionId, request.wantsKeepAlive,
                            )
                            if (!request.wantsKeepAlive) return
                        }
                        request.method == "POST" && request.path == "/health" -> {
                            writeResponse(
                                output, 200, McpTools.health(), "application/json",
                                request.sessionId, request.wantsKeepAlive,
                            )
                            if (!request.wantsKeepAlive) return
                        }
                        request.method == "POST" && request.path == "/mcp" -> {
                            if (!handlePost(output, request)) return
                        }
                        request.path == "/mcp" || request.path == "/health" -> {
                            val allow = if (request.path == "/mcp") "GET, POST, DELETE" else "GET, POST"
                            writeResponse(
                                output, 405, "\"method not allowed\"", "application/json",
                                null, request.wantsKeepAlive, mapOf("Allow" to allow),
                            )
                            if (!request.wantsKeepAlive) return
                        }
                        else -> {
                            writeResponse(
                                output, 404, "\"not found\"", "application/json", null, false,
                            )
                            return
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "request failed", e)
        } finally {
            if (!handedOff) {
                clients.remove(client)
                runCatching { client.close() }
            }
            connectionSlots.decrementAndGet()
        }
    }

    /** Returns true when the connection may be reused for another request. */
    private fun handlePost(output: BufferedOutputStream, request: HttpRequest): Boolean {
        val keepAlive = request.wantsKeepAlive
        if (!authorized(request)) {
            writeResponse(
                output, 401, jsonRpcError(null, -32001, "unauthorized"), "application/json",
                null, keepAlive, mapOf("WWW-Authenticate" to "Bearer realm=\"bifrost-mcp\""),
            )
            return keepAlive
        }
        val contentType = request.headers["content-type"]?.lowercase().orEmpty()
        if (contentType.contains("multipart/")) {
            writeResponse(output, 415, "\"unsupported media type\"", "application/json", null, keepAlive)
            return keepAlive
        }
        if (!acceptsJson(request)) {
            writeResponse(output, 406, "\"not acceptable\"", "application/json", null, keepAlive)
            return keepAlive
        }
        val version = request.protocolVersion
        if (version != null && version !in SUPPORTED_VERSIONS) {
            writeResponse(
                output, 400, jsonRpcError(null, -32600, "unsupported protocol version $version"),
                "application/json", null, keepAlive,
            )
            return keepAlive
        }
        val sid = request.sessionId
        if (sid != null && !isLiveSession(sid)) {
            // Unknown/expired session: the client must start over with initialize.
            writeResponse(output, 404, "\"session not found\"", "application/json", null, keepAlive)
            return keepAlive
        }
        if (sid != null) sessions[sid] = System.currentTimeMillis()

        val body = request.body
        val method = runCatching { JSONObject(body).optString("method") }.getOrDefault("")
        val isNotification = runCatching {
            val obj = JSONObject(body)
            !obj.has("id") && obj.has("method")
        }.getOrDefault(false)

        val responseBody = McpTools.handle(body)
        val issued = if (method == "initialize") newSession() else null
        if (method == "initialize" && sid != null) sessions.remove(sid)
        val responseSession = issued ?: sid

        if (isNotification) {
            writeResponse(output, 202, null, null, responseSession, keepAlive, issued = issued)
            return keepAlive
        }
        // Most clients send "application/json, text/event-stream" but only parse JSON,
        // so prefer a plain JSON body unless the client accepts nothing else.
        val acceptHeader = request.headers["accept"]?.lowercase().orEmpty()
        val wantsSse = acceptHeader.contains("text/event-stream") &&
            !acceptHeader.contains("application/json") &&
            !acceptHeader.contains("*/*")
        if (wantsSse) {
            writeResponse(
                output, 200, "event: message\ndata: $responseBody\n\n", "text/event-stream",
                responseSession, keepAlive, issued = issued,
            )
        } else {
            writeResponse(
                output, 200, responseBody, "application/json",
                responseSession, keepAlive, issued = issued,
            )
        }
        return keepAlive
    }

    private fun handleDelete(output: BufferedOutputStream, request: HttpRequest) {
        if (!authorized(request)) {
            writeResponse(
                output, 401, jsonRpcError(null, -32001, "unauthorized"), "application/json",
                null, false, mapOf("WWW-Authenticate" to "Bearer realm=\"bifrost-mcp\""),
            )
            return
        }
        val sid = request.sessionId
        if (sid == null) {
            writeResponse(output, 400, "\"missing session id\"", "application/json", null, false)
            return
        }
        if (sessions.remove(sid) == null) {
            writeResponse(output, 404, "\"session not found\"", "application/json", null, false)
            return
        }
        writeResponse(output, 204, null, null, null, false)
    }

    /** Runs an SSE stream on its own pool so it cannot occupy a request worker. */
    private fun dispatchSse(
        socket: Socket,
        input: BufferedInputStream,
        output: BufferedOutputStream,
        request: HttpRequest,
    ): Boolean {
        if (!authorized(request)) {
            writeResponse(
                output, 401, jsonRpcError(null, -32001, "unauthorized"), "application/json",
                null, false, mapOf("WWW-Authenticate" to "Bearer realm=\"bifrost-mcp\""),
            )
            return false
        }
        val accept = request.headers["accept"]?.lowercase().orEmpty()
        if (accept.isNotEmpty() && !accept.contains("text/event-stream") && !accept.contains("*/*")) {
            writeResponse(output, 406, "\"not acceptable\"", "application/json", null, false)
            return false
        }
        val sid = request.sessionId
        if (sid != null && !isLiveSession(sid)) {
            writeResponse(output, 404, "\"session not found\"", "application/json", null, false)
            return false
        }
        // A client that only holds the stream open is still alive: slide the TTL.
        if (sid != null) sessions[sid] = System.currentTimeMillis()
        if (sseActive.incrementAndGet() > MAX_SSE_STREAMS) {
            sseActive.decrementAndGet()
            writeResponse(output, 503, "\"too many streams\"", "application/json", null, false)
            return false
        }
        sseWorkers.execute {
            try {
                serveSse(socket, input, output, sid)
            } finally {
                sseActive.decrementAndGet()
                clients.remove(socket)
                runCatching { socket.close() }
            }
        }
        return true
    }

    private fun serveSse(socket: Socket, input: BufferedInputStream, output: BufferedOutputStream, sid: String?) {
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Transfer-Encoding: chunked\r\n")
            if (sid != null) append("Mcp-Session-Id: ").append(sid).append("\r\n")
            append("\r\n")
        }
        runCatching {
            output.write(header.toByteArray(Charsets.US_ASCII))
            output.flush()
        }.onFailure { return }
        socket.soTimeout = SSE_POLL_MS
        // Flush one comment frame right away: some clients treat a silent stream as a dead one.
        if (!writeChunk(output, ": ping\n\n")) {
            runCatching { socket.close() }
            return
        }
        var lastPing = System.currentTimeMillis()
        while (isRunning && !socket.isClosed) {
            // A half-closed peer does not break writes, so poll the read side for EOF.
            try {
                if (input.read() < 0) break
            } catch (e: SocketTimeoutException) {
                // still connected, nothing to read
            } catch (e: Exception) {
                break
            }
            if (System.currentTimeMillis() - lastPing >= SSE_PING_MS) {
                val ok = writeChunk(output, ": ping\n\n")
                if (!ok) break
                lastPing = System.currentTimeMillis()
            }
        }
        writeChunk(output, "")
    }

    private fun writeChunk(output: BufferedOutputStream, payload: String): Boolean = runCatching {
        val body = payload.toByteArray(Charsets.US_ASCII)
        if (body.isEmpty()) {
            output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        } else {
            output.write("${Integer.toHexString(body.size)}\r\n".toByteArray(Charsets.US_ASCII))
            output.write(body)
            output.write("\r\n".toByteArray(Charsets.US_ASCII))
        }
        output.flush()
    }.isSuccess

    private fun authorized(request: HttpRequest): Boolean {
        val expected = Settings.mcpServerToken
        if (expected.isBlank()) return true
        val header = request.headers["authorization"] ?: return false
        return header == "Bearer $expected" || header == expected
    }

    private fun acceptsJson(request: HttpRequest): Boolean {
        val accept = request.headers["accept"] ?: return true
        return accept.contains("*/*") || accept.contains("application/json") || accept.contains("text/event-stream")
    }

    private fun isLiveSession(id: String): Boolean {
        val last = sessions[id] ?: return false
        if (System.currentTimeMillis() - last > SESSION_TTL_MS) {
            sessions.remove(id)
            return false
        }
        return true
    }

    private fun newSession(): String {
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { now - it.value > SESSION_TTL_MS }
        if (sessions.size >= MAX_SESSIONS) {
            sessions.entries.minByOrNull { it.value }?.let { sessions.remove(it.key) }
        }
        val id = UUID.randomUUID().toString().replace("-", "")
        sessions[id] = now
        return id
    }

    private class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    ) {
        val sessionId: String? get() = headers["mcp-session-id"]
        val protocolVersion: String? get() = headers["mcp-protocol-version"]
        val wantsKeepAlive: Boolean
            get() {
                val connection = headers["connection"]?.lowercase() ?: return true
                return !connection.contains("close")
            }
    }

    private class BodyTooLarge : IOException("request body too large")

    private fun readRequest(input: BufferedInputStream, requestLine: String): HttpRequest? {
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        // One deadline for the whole request: reading bytes one at a time must not extend it.
        val deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MS
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input, deadline) ?: break
            if (line.isEmpty()) break
            if (headers.size >= MAX_HEADERS) throw IOException("too many headers")
            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
            }
        }
        val body = readBody(input, headers, deadline)
        return HttpRequest(parts[0].uppercase(), parts[1].substringBefore('?'), headers, body)
    }

    private fun readBody(input: BufferedInputStream, headers: Map<String, String>, deadline: Long): String {
        val chunked = headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true
        if (chunked) {
            val out = ByteArrayOutputStream()
            while (true) {
                val sizeLine = readLine(input, deadline) ?: break
                val size = sizeLine.trim().substringBefore(';').toIntOrNull(16)
                    ?: throw IOException("invalid chunk size")
                if (size == 0) {
                    // Consume trailers until the blank line that ends the body.
                    while (true) {
                        val trailer = readLine(input, deadline) ?: break
                        if (trailer.isEmpty()) break
                    }
                    break
                }
                if (size < 0 || size > MAX_BODY || out.size() + size > MAX_BODY) throw BodyTooLarge()
                out.write(readExactly(input, size, deadline))
                readLine(input, deadline) // CRLF after chunk data
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY) throw BodyTooLarge()
        if (length == 0) return ""
        return String(readExactly(input, length, deadline), Charsets.UTF_8)
    }

    /** Reads exactly [length] bytes; a short read means the peer truncated the request. */
    private fun readExactly(input: InputStream, length: Int, deadline: Long): ByteArray {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            checkDeadline(deadline)
            val n = input.read(buffer, read, length - read)
            if (n < 0) throw IOException("truncated request body")
            read += n
        }
        return buffer
    }

    private fun checkDeadline(deadline: Long) {
        if (deadline != Long.MAX_VALUE && System.currentTimeMillis() > deadline) {
            throw SocketTimeoutException("request deadline exceeded")
        }
    }

    private fun readLine(input: BufferedInputStream, deadline: Long = Long.MAX_VALUE): String? {
        val builder = StringBuilder()
        while (true) {
            checkDeadline(deadline)
            val b = input.read()
            if (b < 0) return if (builder.isEmpty()) null else builder.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) builder.append(b.toChar())
            if (builder.length > MAX_LINE) throw IOException("header line too long")
        }
        return builder.toString()
    }

    /** Tokens are 32 hex chars; generated once and persisted when the server starts. */
    private fun newToken(): String = UUID.randomUUID().toString().replace("-", "")

    private fun writeResponse(
        output: BufferedOutputStream,
        status: Int,
        body: String?,
        contentType: String?,
        sessionId: String?,
        keepAlive: Boolean,
        extra: Map<String, String>? = null,
        issued: String? = null,
    ) {
        val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val session = issued ?: sessionId
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
            if (contentType != null) append("Content-Type: ").append(contentType).append("\r\n")
            if (session != null) append("Mcp-Session-Id: ").append(session).append("\r\n")
            extra?.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            // A bodyless response other than 204 still needs a zero length so that a
            // keep-alive client can tell where the message ends.
            if (body != null || status != 204) append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        406 -> "Not Acceptable"
        413 -> "Payload Too Large"
        415 -> "Unsupported Media Type"
        503 -> "Service Unavailable"
        else -> "Error"
    }

    fun jsonRpcError(id: Any?, code: Int, message: String): String {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))
        return body.toString()
    }
}
