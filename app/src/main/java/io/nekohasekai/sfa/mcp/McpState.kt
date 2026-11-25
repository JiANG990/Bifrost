package io.nekohasekai.sfa.mcp

import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Connections
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.OutboundGroupItem
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

    private const val MAX_LOGS = 500

    @Volatile
    var status: StatusMessage? = null
        private set

    @Volatile
    var clashMode: String = ""
        private set

    @Volatile
    var groups: List<OutboundGroup> = emptyList()
        private set

    @Volatile
    var outbounds: List<OutboundGroupItem> = emptyList()
        private set

    @Volatile
    var connected: Boolean = false
        private set

    private val logs = ArrayDeque<LogEntry>()
    private val logsLock = Any()

    private var connectionStore: Connections? = null
    private val connectionsLock = Any()

    private var client: CommandClient? = null

    @OptIn(DelicateCoroutinesApi::class)
    @Synchronized
    fun start() {
        if (client != null) return
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
        client?.disconnect()
        client = null
        connected = false
        status = null
        synchronized(connectionsLock) { connectionStore = null }
    }

    fun clearLogs() {
        synchronized(logsLock) { logs.clear() }
    }

    fun snapshotLogs(limit: Int, minLevel: Int): List<LogEntry> = synchronized(logsLock) {
        logs.asSequence().filter { it.level >= minLevel }.toList().takeLast(limit)
    }

    fun snapshotConnections(): List<Connection> = synchronized(connectionsLock) {
        val store = connectionStore ?: return emptyList()
        store.iterator().toList()
            .filter { it.outboundType != "dns" }
            .map { Connection.from(it) }
            .filter { it.isActive }
    }

    override fun onConnected() {
        connected = true
    }

    override fun onDisconnected() {
        connected = false
    }

    override fun onConnectionError(message: String) {
        connected = false
    }

    override fun updateStatus(status: StatusMessage) {
        this.status = status
    }

    override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
        groups = newGroups.toList()
    }

    override fun updateOutbounds(outbounds: List<OutboundGroupItem>) {
        this.outbounds = outbounds
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
                logs.addLast(entry)
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
