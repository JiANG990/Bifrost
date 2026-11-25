package io.nekohasekai.sfa.mcp

import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Connections
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.sfa.compose.model.Connection
import io.nekohasekai.sfa.ktx.toList
import io.nekohasekai.sfa.utils.CommandClient
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope

/**
 * Keeps the latest state pushed by the running box so MCP tools can answer
 * pull-style requests without opening a new core connection per call.
 */
object McpState : CommandClient.Handler {

    /** Plain-Kotlin copy of a proxy item; libbox objects die with the core. */
    data class ItemSnapshot(val tag: String, val type: String, val urlTestDelay: Int)

    /** Plain-Kotlin copy of an outbound group, safe to read after a core reload. */
    data class GroupSnapshot(
        val tag: String,
        val type: String,
        val selected: String,
        val items: List<ItemSnapshot>,
    )

    private const val MAX_LOGS = 3000

    /** Config actually handed to the core, including the SSID/DNS injection. */
    @Volatile
    var runtimeConfig: String? = null
        private set

    @Volatile
    var runtimeConfigError: String? = null
        private set

    fun setRuntimeConfig(config: String, error: String?) {
        runtimeConfig = config
        runtimeConfigError = error
    }

    @Volatile
    var status: StatusMessage? = null
        private set

    @Volatile
    var clashMode: String = ""
        private set

    @Volatile
    var groups: List<GroupSnapshot> = emptyList()
        private set

    @Volatile
    var outbounds: List<ItemSnapshot> = emptyList()
        private set

    /**
     * Derived from [status] instead of a separate flag: the libbox command client can
     * reconnect underneath us without another connected() callback, and a stale flag
     * made /health report "connected": false while data was flowing.
     */
    val connected: Boolean get() = status != null

    /** One buffered log line. The seq is monotonic so clients can ask for what is new. */
    data class LogLine(val seq: Long, val level: Int, val message: String)

    private val logs = ArrayDeque<LogLine>()
    private var logSeq = 0L
    private val logsLock = Any()

    private var connectionStore: Connections? = null
    private val connectionsLock = Any()

    private var client: CommandClient? = null

    @OptIn(DelicateCoroutinesApi::class)
    @Synchronized
    fun start() {
        // A reload tears the core connection down without telling us, so a non-null
        // client does not mean we are still attached: reconnect when disconnected.
        if (client != null) {
            if (connected) return
            runCatching { client?.disconnect() }
            client = null
        }
        val created = CommandClient(
            GlobalScope,
            listOf(
                CommandClient.ConnectionType.Status,
                CommandClient.ConnectionType.Groups,
                CommandClient.ConnectionType.Outbounds,
                CommandClient.ConnectionType.Log,
                CommandClient.ConnectionType.ClashMode,
                CommandClient.ConnectionType.Connections,
            ),
            this,
            localOnly = true,
        )
        client = created
        created.connect()
    }

    @Synchronized
    fun stop() {
        runCatching { client?.disconnect() }
        client = null
        status = null
        clashMode = ""
        groups = emptyList()
        outbounds = emptyList()
        synchronized(logsLock) { logs.clear() }
        synchronized(connectionsLock) { connectionStore = null }
    }

    override fun clearLogs() {
        synchronized(logsLock) { logs.clear() }
    }

    fun snapshotLogs(limit: Int, minLevel: Int, since: Long = 0L): List<LogLine> = synchronized(logsLock) {
        logs.asSequence()
            .filter { it.seq > since && it.level <= minLevel }
            .toList()
            .takeLast(limit)
    }

    fun lastLogSeq(): Long = synchronized(logsLock) { logSeq }

    fun rawConnectionCount(): Int = synchronized(connectionsLock) {
        connectionStore?.let { runCatching { it.iterator().toList().size }.getOrElse { -2 } } ?: -1
    }

    fun snapshotConnections(): List<Connection> = synchronized(connectionsLock) {
        val store = connectionStore ?: return emptyList()
        store.iterator().toList()
            .filter { it.outboundType != "dns" }
            .map { Connection.from(it) }
            .filter { it.isActive }
    }

    override fun onDisconnected() {
        // The cached libbox objects are already invalid; reading them yields garbage
        // values (negative counters) instead of a clean "not connected".
        status = null
    }

    override fun onConnectionError(message: String) {
        status = null
    }

    override fun updateStatus(status: StatusMessage) {
        this.status = status
    }

    override fun updateGroups(newGroups: MutableList<io.nekohasekai.libbox.OutboundGroup>) {
        groups = newGroups.map { group ->
            val items = ArrayList<ItemSnapshot>()
            val iterator = group.items
            while (iterator.hasNext()) {
                val item = iterator.next()
                items.add(ItemSnapshot(item.tag, item.type, item.urlTestDelay))
            }
            GroupSnapshot(group.tag, group.type, group.selected, items)
        }
    }

    override fun updateOutbounds(outbounds: List<io.nekohasekai.libbox.OutboundGroupItem>) {
        this.outbounds = outbounds.map { ItemSnapshot(it.tag, it.type, it.urlTestDelay) }
    }

    override fun initializeClashMode(modeList: List<String>, currentMode: String) {
        clashMode = currentMode
    }

    override fun updateClashMode(newMode: String) {
        clashMode = newMode
    }

    override fun appendLogs(message: List<LogEntry>) {
        synchronized(logsLock) {
            for (entry in message) {
                logs.addLast(LogLine(++logSeq, entry.level, entry.message))
                while (logs.size > MAX_LOGS) logs.removeFirst()
            }
        }
    }

    override fun writeConnectionEvents(events: ConnectionEvents) {
        synchronized(connectionsLock) {
            val store = connectionStore ?: Connections().also { connectionStore = it }
            store.applyEvents(events)
        }
    }
}
