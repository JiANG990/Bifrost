package io.nekohasekai.sfa.mcp

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.CommandTarget
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * JSON-RPC 2.0 dispatcher implementing the MCP tool surface of the app.
 */
object McpTools {

    private const val PROTOCOL_VERSION = "2024-11-05"
    private const val SERVER_NAME = "sing-box-for-android"
    private const val SERVER_VERSION = "1.0.0"

    fun health(): String = JSONObject()
        .put("ok", true)
        .put("server", SERVER_NAME)
        .put("core", Libbox.version())
        .put("connected", McpState.connected)
        .toString()

    fun handle(body: String): String {
        val request = try {
            JSONObject(body)
        } catch (e: Exception) {
            return McpServer.jsonRpcError(null, -32700, "parse error")
        }
        val id = if (request.has("id")) request.get("id") else null
        val method = request.optString("method")
        val params = request.optJSONObject("params") ?: JSONObject()
        return when (method) {
            "initialize" -> result(id, JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject().put("name", SERVER_NAME).put("version", SERVER_VERSION)))
            "notifications/initialized" -> "{}"
            "ping" -> result(id, JSONObject())
            "tools/list" -> result(id, JSONObject().put("tools", toolList()))
            "tools/call" -> callTool(id, params)
            else -> McpServer.jsonRpcError(id, -32601, "method not found: $method")
        }
    }

    private fun result(id: Any?, payload: JSONObject): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("result", payload)
        .toString()

    private fun text(id: Any?, payload: Any): String {
        val body = JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", payload.toString())))
            .put("isError", false)
        return result(id, body)
    }

    private fun failure(id: Any?, message: String): String {
        val body = JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
            .put("isError", true)
        return result(id, body)
    }

    private fun callTool(id: Any?, params: JSONObject): String {
        val name = params.optString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        return try {
            text(id, execute(name, args))
        } catch (e: Exception) {
            failure(id, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun execute(name: String, args: JSONObject): Any = when (name) {
        "status" -> status()
        "outbounds" -> outbounds()
        "connections" -> connections(args)
        "logs" -> logs(args)
        "profiles" -> profiles()
        "get_profile" -> getProfile(args)
        "select_outbound" -> selectOutbound(args)
        "close_connection" -> closeConnection(args)
        "close_all_connections" -> closeAllConnections()
        "set_clash_mode" -> setClashMode(args)
        "url_test" -> urlTest(args)
        "service" -> service(args)
        "set_profile" -> setProfile(args)
        else -> throw IllegalArgumentException("unknown tool: $name")
    }

    // ---- read tools -------------------------------------------------------

    private fun status(): JSONObject {
        val status = McpState.status
        return JSONObject()
            .put("connected", McpState.connected)
            .put("coreVersion", Libbox.version())
            .put("clashMode", McpState.clashMode)
            .put("uplink", status?.uplink ?: 0L)
            .put("downlink", status?.downlink ?: 0L)
            .put("uplinkTotal", status?.uplinkTotal ?: 0L)
            .put("downlinkTotal", status?.downlinkTotal ?: 0L)
            .put("selectedProfile", runBlocking { Settings.selectedProfile })
    }

    private fun outbounds(): JSONObject {
        val groups = JSONArray()
        for (group in McpState.groups) {
            val items = JSONArray()
            for (item in group.items.toList()) {
                items.put(
                    JSONObject()
                        .put("tag", item.tag)
                        .put("type", item.type)
                        .put("urlTestDelay", item.urlTestDelay),
                )
            }
            groups.put(
                JSONObject()
                    .put("tag", group.tag)
                    .put("type", group.type)
                    .put("selected", group.selected)
                    .put("items", items),
            )
        }
        return JSONObject().put("groups", groups).put("count", groups.length())
    }

    private fun connections(args: JSONObject): JSONObject {
        val limit = args.optInt("limit", 50).coerceIn(1, 500)
        val search = args.optString("search").lowercase()
        val all = McpState.snapshotConnections()
            .asSequence()
            .filter { search.isEmpty() || it.displayDestination.lowercase().contains(search) || it.outbound.lowercase().contains(search) }
            .sortedByDescending { it.createdAt }
            .toList()
        val items = JSONArray()
        for (connection in all.take(limit)) {
            items.put(
                JSONObject()
                    .put("id", connection.id)
                    .put("network", connection.network)
                    .put("source", connection.source)
                    .put("destination", connection.destination)
                    .put("domain", connection.domain)
                    .put("rule", connection.rule)
                    .put("outbound", connection.outbound)
                    .put("outboundType", connection.outboundType)
                    .put("chain", JSONArray(connection.chain))
                    .put("upload", connection.upload)
                    .put("download", connection.download)
                    .put("createdAt", connection.createdAt),
            )
        }
        return JSONObject().put("total", all.size).put("count", items.length()).put("items", items)
    }

    private fun logs(args: JSONObject): JSONObject {
        val limit = args.optInt("limit", 100).coerceIn(1, 500)
        val minLevel = when (args.optString("level").lowercase()) {
            "trace" -> 6
            "debug" -> 5
            "warn", "warning" -> 3
            "error" -> 2
            "fatal" -> 1
            else -> 4
        }
        val items = JSONArray()
        for (entry in McpState.snapshotLogs(limit, minLevel)) {
            items.put(
                JSONObject()
                    .put("level", entry.level)
                    .put("message", entry.message),
            )
        }
        return JSONObject().put("count", items.length()).put("items", items)
    }

    private fun profiles(): JSONObject {
        val selected = runBlocking { Settings.selectedProfile }
        val list = runBlocking { ProfileManager.list() }
        val items = JSONArray()
        for (profile in list) {
            items.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("type", profile.typed.type.name)
                    .put("remoteURL", profile.typed.remoteURL)
                    .put("lastUpdated", profile.typed.lastUpdated.time)
                    .put("selected", profile.id == selected),
            )
        }
        return JSONObject().put("selected", selected).put("count", items.length()).put("items", items)
    }

    private fun getProfile(args: JSONObject): JSONObject {
        val id = args.optLong("id", -1L)
        require(id > 0) { "id is required" }
        val profile = runBlocking { ProfileManager.get(id) } ?: throw IllegalArgumentException("profile not found: $id")
        val file = File(profile.typed.path)
        val content = if (file.exists()) file.readText() else ""
        val maxChars = args.optInt("maxChars", 20_000).coerceIn(1000, 200_000)
        return JSONObject()
            .put("id", profile.id)
            .put("name", profile.name)
            .put("path", profile.typed.path)
            .put("size", content.length)
            .put("truncated", content.length > maxChars)
            .put("content", content.take(maxChars))
    }

    // ---- write tools ------------------------------------------------------

    private fun selectOutbound(args: JSONObject): JSONObject {
        val group = args.optString("group")
        val item = args.optString("item")
        require(group.isNotEmpty() && item.isNotEmpty()) { "group and item are required" }
        CommandTarget.standaloneClient().selectOutbound(group, item)
        return JSONObject().put("ok", true).put("group", group).put("selected", item)
    }

    private fun closeConnection(args: JSONObject): JSONObject {
        val id = args.optString("id")
        require(id.isNotEmpty()) { "id is required" }
        CommandTarget.standaloneClient().closeConnection(id)
        return JSONObject().put("ok", true).put("closed", id)
    }

    private fun closeAllConnections(): JSONObject {
        CommandTarget.standaloneClient().closeConnections()
        return JSONObject().put("ok", true)
    }

    private fun setClashMode(args: JSONObject): JSONObject {
        val mode = args.optString("mode")
        require(mode.isNotEmpty()) { "mode is required" }
        CommandTarget.standaloneClient().setClashMode(mode)
        return JSONObject().put("ok", true).put("mode", mode)
    }

    private fun urlTest(args: JSONObject): JSONObject {
        val tag = args.optString("tag")
        require(tag.isNotEmpty()) { "tag is required" }
        CommandTarget.standaloneClient().urlTest(tag)
        return JSONObject().put("ok", true).put("tag", tag)
    }

    private fun service(args: JSONObject): JSONObject {
        when (args.optString("action")) {
            "start" -> BoxService.start()
            "stop" -> BoxService.stop()
            "restart" -> {
                BoxService.stop()
                BoxService.start()
            }
            else -> throw IllegalArgumentException("action must be start, stop or restart")
        }
        return JSONObject().put("ok", true).put("action", args.optString("action"))
    }

    private fun setProfile(args: JSONObject): JSONObject {
        val id = args.optLong("id", -1L)
        require(id > 0) { "id is required" }
        val profile = runBlocking { ProfileManager.get(id) } ?: throw IllegalArgumentException("profile not found: $id")
        runBlocking { Settings.selectedProfile = profile.id }
        return JSONObject().put("ok", true).put("selected", profile.id).put("name", profile.name)
    }

    // ---- schema -----------------------------------------------------------

    private fun schema(vararg required: String): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject())
        .also { obj ->
            if (required.isNotEmpty()) obj.put("required", JSONArray(required.toList()))
        }

    private fun param(
        obj: JSONObject,
        name: String,
        type: String,
        description: String,
        default: Any? = null,
    ): JSONObject {
        val properties = obj.getJSONObject("properties")
        val property = JSONObject().put("type", type).put("description", description)
        if (default != null) property.put("default", default)
        properties.put(name, property)
        return obj
    }

    private fun tool(
        name: String,
        description: String,
        inputSchema: JSONObject,
    ): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("inputSchema", inputSchema)

    private fun toolList(): JSONArray = JSONArray()
        .put(tool("status", "Running state of the VPN core: connected, core version, live traffic, clash mode, selected profile.", schema()))
        .put(tool("outbounds", "All outbound groups (selectors, url-test) with their items and last measured delay.", schema()))
        .put(
            tool(
                "connections",
                "Active proxied connections, newest first, with rule, outbound and chain.",
                schema()
                    .let { param(it, "limit", "integer", "Maximum connections to return (1-500).", 50) }
                    .let { param(it, "search", "string", "Case-insensitive filter on destination/domain/outbound.") },
            ),
        )
        .put(
            tool(
                "logs",
                "Recent core log lines buffered in the app.",
                schema()
                    .let { param(it, "limit", "integer", "Maximum lines (1-500).", 100) }
                    .let { param(it, "level", "string", "Minimum level: debug, info, warn, error.", "info") },
            ),
        )
        .put(tool("profiles", "Configuration profiles with type, subscription URL and which one is selected.", schema()))
        .put(
            tool(
                "get_profile",
                "Read the raw JSON of one profile (truncated to maxChars).",
                schema("id")
                    .let { param(it, "id", "integer", "Profile id from the profiles tool.") }
                    .let { param(it, "maxChars", "integer", "Maximum characters to return.", 20000) },
            ),
        )
        .put(
            tool(
                "select_outbound",
                "Select an item inside an outbound group.",
                schema("group", "item")
                    .let { param(it, "group", "string", "Group tag, e.g. 🚀 出国") }
                    .let { param(it, "item", "string", "Item tag inside the group") },
            ),
        )
        .put(
            tool(
                "close_connection",
                "Close one active connection by id.",
                schema("id").let { param(it, "id", "string", "Connection id from the connections tool.") },
            ),
        )
        .put(tool("close_all_connections", "Close every active connection.", schema()))
        .put(
            tool(
                "set_clash_mode",
                "Switch the clash mode (rule / global / direct).",
                schema("mode").let { param(it, "mode", "string", "Target clash mode") },
            ),
        )
        .put(
            tool(
                "url_test",
                "Trigger a latency test for an outbound group or item.",
                schema("tag").let { param(it, "tag", "string", "Group or outbound tag") },
            ),
        )
        .put(
            tool(
                "service",
                "Start, stop or restart the VPN core.",
                schema("action").let { param(it, "action", "string", "start, stop or restart") },
            ),
        )
        .put(
            tool(
                "set_profile",
                "Select a profile as the active configuration. Restart the service afterwards to apply.",
                schema("id").let { param(it, "id", "integer", "Profile id") },
            ),
        )
}
