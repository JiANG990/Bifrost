package io.nekohasekai.sfa.mcp

import android.util.Log
import io.nekohasekai.sfa.database.Settings
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Loopback-only MCP (JSON-RPC 2.0) endpoint speaking the Streamable HTTP transport.
 *
 * Bind address is always 127.0.0.1 unless the user opts into LAN access; the bearer
 * token adds a second layer for clients that share the phone.
 *
 * Transport features implemented per the MCP Streamable HTTP spec:
 * - POST /mcp with JSON bodies (Content-Length or chunked), JSON or SSE responses
 * - GET /mcp opening a server-to-client SSE stream (keep-alive pings)
 * - DELETE /mcp terminating the session
 * - Mcp-Session-Id issue/echo/validate, Mcp-Protocol-Version validation
 * - proper HTTP status codes (401/404/405/406/413/415) instead of blanket 200
 * - HTTP keep-alive so a client can reuse one connection
 */
object McpServer {

    private const val TAG = "McpServer"
    const val DEFAULT_PORT = 18080

    private const val MAX_BODY = 1_048_576
    private const val MAX_LINE = 8_192
    private const val REQUEST_TIMEOUT_MS = 5_000L
    private const val KEEPALIVE_TIMEOUT_MS = 60_000
    private const val SSE_PING_MS = 15_000L
    private const val MAX_SESSIONS = 32

    private val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

    @Volatile
    private var serverSocket: ServerSocket? = null

    private var acceptThread: Thread? = null

    private val workers = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "mcp-worker").apply { isDaemon = true }
    }

    /** Live client sockets, so stop() can abort in-flight requests. */
    private val clients: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    /** Issued session ids with their last-use timestamp. */
    private val sessions: MutableMap<String, Long> = ConcurrentHashMap()

    private val requestSeq = AtomicLong()

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
        try {
            client.use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                while (isRunning) {
                    socket.soTimeout = REQUEST_TIMEOUT_MS
                    val request = try {
                        readRequest(input) ?: return
                    } catch (e: SocketTimeoutException) {
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "bad request", e)
                        return
                    }
                    when {
                        request.method == "GET" && isMcpPath(request.path) -> {
                            serveSse(socket, output, request)
                            return
                        }
                        request.method == "DELETE" && isMcpPath(request.path) -> {
                            val sid = request.headers["mcp-session-id"]
                            if (sid != null) sessions.remove(sid)
                            writeResponse(output, 204, null, null, sid, false)
                            return
                        }
                        request.method == "POST" -> {
                            val keepAlive = handlePost(output, request, socket)
                            if (!keepAlive) return
                            socket.soTimeout = KEEPALIVE_TIMEOUT_MS
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
            clients.remove(client)
        }
    }

    /** Returns true when the connection may be reused for another request. */
    private fun handlePost(output: BufferedOutputStream, request: HttpRequest, socket: Socket): Boolean {
        val path = request.path
        if (path.startsWith("/health")) {
            writeResponse(output, 200, McpTools.health(), "application/json", request.sessionId, request.wantsKeepAlive)
            return request.wantsKeepAlive
        }
        if (!isMcpPath(path)) {
            writeResponse(output, 404, "\"not found\"", "application/json", request.sessionId, request.wantsKeepAlive)
            return request.wantsKeepAlive
        }
        if (!authorized(request)) {
            writeResponse(
                output, 401, jsonRpcError(null, -32001, "unauthorized"), "application/json",
                request.sessionId, request.wantsKeepAlive,
                mapOf("WWW-Authenticate" to "Bearer realm=\"bifrost-mcp\""),
            )
            return request.wantsKeepAlive
        }
        val contentType = request.headers["content-type"]?.lowercase().orEmpty()
        if (contentType.isNotEmpty() && !contentType.startsWith("application/json")) {
            writeResponse(output, 415, "\"unsupported media type\"", "application/json", request.sessionId, request.wantsKeepAlive)
            return request.wantsKeepAlive
        }
        if (!acceptsJson(request)) {
            writeResponse(output, 406, "\"not acceptable\"", "application/json", request.sessionId, request.wantsKeepAlive)
            return request.wantsKeepAlive
        }
        val version = request.protocolVersion
        if (version != null && version !in SUPPORTED_VERSIONS) {
            writeResponse(
                output, 400, jsonRpcError(null, -32600, "unsupported protocol version $version"),
                "application/json", request.sessionId, request.wantsKeepAlive,
            )
            return request.wantsKeepAlive
        }
        val sid = request.sessionId
        if (sid != null) {
            // Unknown session: the client must start a new one with initialize.
            if (!sessions.containsKey(sid)) {
                writeResponse(output, 404, "\"session not found\"", "application/json", null, request.wantsKeepAlive)
                return request.wantsKeepAlive
            }
            sessions[sid] = System.currentTimeMillis()
        }

        val body = request.body
        val isInitialize = runCatching {
            JSONObject(body).optString("method") == "initialize"
        }.getOrDefault(false)
        val isNotification = runCatching {
            val obj = JSONObject(body)
            !obj.has("id") && obj.has("method")
        }.getOrDefault(false)

        val responseBody = McpTools.handle(body)

        // initialize (or any request without a session) yields a fresh session id
        var issued: String? = null
        var responseSession = sid
        if (isInitialize || responseSession == null) {
            val fresh = newSession()
            issued = fresh
            responseSession = fresh
        }

        if (isNotification) {
            writeResponse(output, 202, null, null, responseSession, request.wantsKeepAlive, extra = null, issued = issued)
            return request.wantsKeepAlive
        }

        val wantsSse = request.headers["accept"]?.lowercase()?.contains("text/event-stream") == true
        if (wantsSse) {
            writeResponse(
                output, 200, "event: message\ndata: $responseBody\n\n", "text/event-stream",
                responseSession, request.wantsKeepAlive, issued = issued,
            )
        } else {
            writeResponse(
                output, 200, responseBody, "application/json",
                responseSession, request.wantsKeepAlive, issued = issued,
            )
        }
        return request.wantsKeepAlive
    }

    /** Keeps a server-to-client SSE stream open with periodic comments. */
    private fun serveSse(socket: Socket, output: BufferedOutputStream, request: HttpRequest) {
        if (!authorized(request)) {
            writeResponse(
                output, 401, jsonRpcError(null, -32001, "unauthorized"), "application/json",
                request.sessionId, false,
                mapOf("WWW-Authenticate" to "Bearer realm=\"bifrost-mcp\""),
            )
            return
        }
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: keep-alive\r\n")
            request.sessionId?.let { append("Mcp-Session-Id: ").append(it).append("\r\n") }
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.flush()
        socket.soTimeout = 0
        while (isRunning && !socket.isClosed) {
            runCatching {
                output.write(": ping\n\n".toByteArray(Charsets.US_ASCII))
                output.flush()
            }.onFailure { return }
            try {
                Thread.sleep(SSE_PING_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun isMcpPath(path: String) = path.startsWith("/mcp") || path == "/"

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

    private fun newSession(): String {
        if (sessions.size >= MAX_SESSIONS) {
            val oldest = sessions.entries.minByOrNull { it.value }?.key
            if (oldest != null) sessions.remove(oldest)
        }
        val id = UUID.randomUUID().toString().replace("-", "")
        sessions[id] = System.currentTimeMillis()
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
                    ?: throw java.io.IOException("invalid chunk size")
                if (size == 0) {
                    readLine(input) // trailing CRLF
                    break
                }
                builder.append(readExactly(input, size))
                readLine(input) // CRLF after chunk data
                if (builder.length > MAX_BODY) throw java.io.IOException("request body too large")
            }
            return builder.toString()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY) throw java.io.IOException("request body too large")
        if (length <= 0) return ""
        return readExactly(input, length)
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
        val deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MS
        val builder = StringBuilder()
        while (true) {
            if (System.currentTimeMillis() > deadline) throw java.io.IOException("request timeout")
            val b = input.read()
            if (b < 0) return if (builder.isEmpty()) null else builder.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) builder.append(b.toChar())
            if (builder.length > MAX_LINE) throw java.io.IOException("header line too long")
        }
        return builder.toString()
    }

    /** Tokens are 32 hex chars; generated once and persisted when the server starts. */
    private fun newToken(): String = java.util.UUID.randomUUID().toString().replace("-", "")

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
            if (body != null) append("Content-Length: ").append(bytes.size).append("\r\n")
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
