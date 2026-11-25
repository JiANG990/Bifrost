package io.nekohasekai.sfa.mcp

import android.util.Log
import io.nekohasekai.sfa.database.Settings
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal loopback-only MCP (JSON-RPC 2.0) endpoint.
 *
 * Bind address is always 127.0.0.1 so nothing is reachable from the LAN; the
 * bearer token adds a second layer for clients that share the phone.
 */
object McpServer {

    private const val TAG = "McpServer"
    const val DEFAULT_PORT = 18080

    private const val MAX_BODY = 1_048_576
    private const val MAX_LINE = 8_192
    private const val REQUEST_TIMEOUT_MS = 5_000L

    @Volatile
    private var serverSocket: ServerSocket? = null

    private var acceptThread: Thread? = null

    private val workers = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "mcp-worker").apply { isDaemon = true }
    }

    /** Live client sockets, so stop() can abort in-flight requests. */
    private val clients: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

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
        client.use { socket ->
            socket.soTimeout = 15_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            try {
                val request = readRequest(input) ?: return
                val response = when {
                    request.path.startsWith("/health") -> McpTools.health()
                    request.path.startsWith("/mcp") || request.path == "/" -> {
                        if (!authorized(request)) {
                            jsonRpcError(null, -32001, "unauthorized")
                        } else {
                            McpTools.handle(request.body)
                        }
                    }
                    else -> jsonRpcError(null, -32601, "not found")
                }
                writeResponse(output, response)
            } catch (e: Exception) {
                Log.w(TAG, "request failed", e)
                runCatching { writeResponse(output, jsonRpcError(null, -32603, e.message ?: "internal error")) }
            } finally {
                clients.remove(socket)
            }
        }
    }

    private fun authorized(request: HttpRequest): Boolean {
        val expected = Settings.mcpServerToken
        if (expected.isBlank()) return true
        val header = request.headers["authorization"] ?: return false
        return header == "Bearer $expected" || header == expected
    }

    private class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

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
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY) throw java.io.IOException("request body too large")
        val body = if (length > 0) {
            val buffer = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buffer, read, length - read)
                if (n < 0) break
                read += n
            }
            String(buffer, 0, read, Charsets.UTF_8)
        } else {
            ""
        }
        return HttpRequest(parts[0], parts[1].substringBefore('?'), headers, body)
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

    private fun writeResponse(output: BufferedOutputStream, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    fun jsonRpcError(id: Any?, code: Int, message: String): String {
        val body = org.json.JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: org.json.JSONObject.NULL)
            .put("error", org.json.JSONObject().put("code", code).put("message", message))
        return body.toString()
    }
}
