package io.nekohasekai.sfa.mcp

import android.util.Log
import io.nekohasekai.sfa.database.Settings
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
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
    private const val IDLE_TIMEOUT_MS = 60_000
    private const val SSE_PING_MS = 15_000L
    private const val MAX_SESSIONS = 32
    private const val MAX_SSE_STREAMS = 4
    private const val SESSION_TTL_MS = 30 * 60 * 1000L

    private val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

    @Volatile
    private var serverSocket: ServerSocket? = null

    private var acceptThread: Thread? = null

    private val workers = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "mcp-worker").apply { isDaemon = true }
    }

    private val sseWorkers = Executors.newCachedThreadPool { r ->
        Thread(r, "mcp-sse").apply { isDaemon = true }
    }

    private val sseActive = AtomicInteger()

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
            val host = if (Settings.mcpServerAllowLan) "0.0.0.0" else "127.0.0.1"
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

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (!socket.isClosed) Log.w(TAG, "accept failed", e)
                break
            }
            runCatching { workers.execute { serve(client) } }
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
                    socket.soTimeout = IDLE_TIMEOUT_MS
                    val request = try {
                        readRequest(input) ?: return
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
                            handedOff = dispatchSse(socket, output, request)
                            return
                        }
                        request.method == "DELETE" && request.path == "/mcp" -> {
                            handleDelete(output, request)
                            return
                        }
                        request.method == "POST" -> {
                            if (!handlePost(output, request)) return
                        }
                        request.method == "GET" && request.path.startsWith("/health") -> {
                            writeResponse(
                                output, 200, McpTools.health(), "application/json",
                                request.sessionId, request.wantsKeepAlive,
                            )
                            if (!request.wantsKeepAlive) return
                        }
                        else -> {
                            writeResponse(
                                output, 405, "\"method not allowed\"", "application/json", null, false,
                                mapOf("Allow" to "GET, POST, DELETE"),
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
        if (contentType.isNotEmpty() && !isJsonContentType(contentType)) {
            // Legacy clients (python urllib, curl --data) often send form content types;
            // the body is still JSON, so accept anything that is not obviously binary.
            if (contentType.contains("multipart/")) {
                writeResponse(output, 415, "\"unsupported media type\"", "application/json", null, keepAlive)
                return keepAlive
            }
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
        val responseSession = issued ?: sid

        if (isNotification) {
            writeResponse(output, 202, null, null, responseSession, keepAlive, issued = issued)
            return keepAlive
        }
        val wantsSse = request.headers["accept"]?.lowercase()?.contains("text/event-stream") == true
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
    private fun dispatchSse(socket: Socket, output: BufferedOutputStream, request: HttpRequest): Boolean {
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
        if (sseActive.incrementAndGet() > MAX_SSE_STREAMS) {
            sseActive.decrementAndGet()
            writeResponse(output, 503, "\"too many streams\"", "application/json", null, false)
            return false
        }
        sseWorkers.execute {
            try {
                serveSse(socket, output, sid)
            } finally {
                sseActive.decrementAndGet()
                clients.remove(socket)
                runCatching { socket.close() }
            }
        }
        return true
    }

    private fun serveSse(socket: Socket, output: BufferedOutputStream, sid: String?) {
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
        socket.soTimeout = 0
        while (isRunning && !socket.isClosed) {
            val ok = runCatching {
                val payload = ": ping\n\n".toByteArray(Charsets.US_ASCII)
                output.write("${Integer.toHexString(payload.size)}\r\n".toByteArray(Charsets.US_ASCII))
                output.write(payload)
                output.write("\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
            }.isSuccess
            if (!ok) return
            try {
                Thread.sleep(SSE_PING_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun isJsonContentType(value: String): Boolean {
        val media = value.substringBefore(';').trim()
        return media == "application/json" || media.endsWith("+json")
    }

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

    private fun readRequest(input: BufferedInputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
            }
        }
        val body = readBody(input, headers)
        return HttpRequest(parts[0].uppercase(), parts[1].substringBefore('?'), headers, body)
    }

    private fun readBody(input: BufferedInputStream, headers: Map<String, String>): String {
        val chunked = headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true
        if (chunked) {
            val builder = StringBuilder()
            while (true) {
                val sizeLine = readLine(input) ?: break
                val size = sizeLine.trim().substringBefore(';').toIntOrNull(16)
                    ?: throw IOException("invalid chunk size")
                if (size == 0) {
                    readLine(input) // trailing CRLF
                    break
                }
                if (size < 0 || size > MAX_BODY || builder.length + size > MAX_BODY) throw BodyTooLarge()
                builder.append(readExactly(input, size))
                readLine(input) // CRLF after chunk data
            }
            return builder.toString()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY) throw BodyTooLarge()
        if (length == 0) return ""
        val body = readExactly(input, length)
        if (body.length < length) throw IOException("truncated request body")
        return body
    }

    private fun readExactly(input: InputStream, length: Int): String {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(buffer, 0, read, Charsets.UTF_8)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val builder = StringBuilder()
        while (true) {
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
