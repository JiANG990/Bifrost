package io.nekohasekai.sfa.ssid

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import io.nekohasekai.sfa.database.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * Injects Wi-Fi SSID based routing rules into the sing-box config before it is
 * handed to the core. Rules only touch the "route.rules" head, so the profile
 * file on disk stays untouched and the user's own rules keep working.
 *
 * When a listed SSID is set to "direct", the whole traffic of that Wi-Fi goes
 * straight out. The gateway on those networks usually also runs DNS based
 * splitting, so the DNS section is pointed at the current Wi-Fi gateway as
 * well; otherwise the device would resolve names through its own DoH and
 * bypass the gateway's answers.
 */
object SsidModeConfig {
    private const val TAG = "SsidMode"

    @Volatile
    var lastError: String? = null
        private set

    fun wrap(content: String, context: Context? = null): String {
        if (!Settings.ssidModeEnabled) return content
        val mapping = Settings.ssidModeList
        if (mapping.isBlank()) return content
        return try {
            lastError = null
            val root = runCatching { JSONObject(content) }
                .getOrElse { JSONObject(sanitize(content)) }
            val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
            val existing = route.optJSONArray("rules") ?: JSONArray()
            val finalTag = route.optString("final", "")
            val generated = JSONArray()
            val directSsids = ArrayList<String>()
            val seenSsids = HashSet<String>()
            for (line in mapping.lineSequence()) {
                val trimmed = line.trim()
                val index = trimmed.lastIndexOf('=')
                if (index <= 0) continue
                val ssid = trimmed.substring(0, index).trim()
                val mode = trimmed.substring(index + 1).trim().lowercase()
                if (ssid.isEmpty() || !seenSsids.add(ssid)) continue
                rule(ssid, mode, finalTag)?.let {
                    generated.put(it)
                    if (mode == "direct") directSsids.add(ssid)
                }
            }
            if (generated.length() == 0) {
                lastError = "no usable SSID rule (mode must be direct or global with route.final set)"
                return content
            }
            val merged = JSONArray()
            for (i in 0 until generated.length()) merged.put(generated.get(i))
            for (i in 0 until existing.length()) merged.put(existing.get(i))
            route.put("rules", merged)
            if (directSsids.isNotEmpty() && Settings.ssidModeDnsEnabled) {
                applyLanDns(root, context)
            }
            // On a "direct" Wi-Fi the local subnet must not be routed into the tun:
            // the core would source those packets from the tun address and the peers
            // on the LAN cannot answer it. Let the kernel send them directly instead.
            if (Settings.ssidModeLanExcludeEnabled &&
                directSsids.isNotEmpty() &&
                isCurrentSsidDirect(context, directSsids)
            ) {
                applyLanExclude(root, context)
            }
            root.toString()
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "leave config untouched: ${e.message}")
            content
        }
    }

    /**
     * Points dns.final at the gateway of the connected Wi-Fi network.
     *
     * Only the servers this method generates (tags "lan-gateway" / "lan-gateway6") are
     * rewritten, so a profile whose first UDP server is MagicDNS or a corporate resolver
     * keeps working. The AAAA rule is appended after the user's own rules so that a
     * geosite-cn -> DoH rule still wins for mainland domains.
     */
    private fun applyLanDns(root: JSONObject, context: Context?) {
        val gateways = currentGateways(context)
        val gateway = gateways.first ?: return // not on Wi-Fi: keep the profile DNS as is
        val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
        val servers = dns.optJSONArray("servers") ?: JSONArray().also { dns.put("servers", it) }
        val tag = "lan-gateway"
        val tag6 = "lan-gateway6"
        putGatewayServer(servers, tag, gateway)
        val gateway6 = gateways.second
        val has6 = !gateway6.isNullOrEmpty()
        if (has6) putGatewayServer(servers, tag6, gateway6!!)

        // On the home Wi-Fi every name is answered by the router, so the gateway is
        // the final server. Only Tailscale's own domain keeps using MagicDNS, which
        // the router cannot resolve.
        val rules = JSONArray()
        rules.put(JSONObject().put("domain_suffix", JSONArray().put("ts.net")).put("server", "ts-dns"))
        if (has6) rules.put(JSONObject().put("query_type", JSONArray().put("AAAA")).put("server", tag6))
        dns.put("rules", rules)
        dns.put("final", tag)
        dns.put("strategy", "prefer_ipv4")
        Log.i(TAG, "dns.final -> $gateway/$gateway6 ($tag)")
    }

    /** Adds or updates a plain UDP DNS server entry owned by this injector. */
    private fun putGatewayServer(servers: JSONArray, tag: String, address: String) {
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            if (server.optString("tag") != tag) continue
            server.put("type", "udp")
            server.put("server", address)
            server.put("server_port", 53)
            server.put("detour", "direct")
            server.remove("tls")
            return
        }
        servers.put(
            JSONObject()
                .put("type", "udp")
                .put("tag", tag)
                .put("server", address)
                .put("server_port", 53)
                .put("detour", "direct"),
        )
    }

    /** True when the Wi-Fi we are associated with is one of the direct-mode SSIDs. */
    @Suppress("DEPRECATION")
    private fun isCurrentSsidDirect(context: Context?, directSsids: List<String>): Boolean {
        val ctx = context ?: return false
        return try {
            val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
            val ssid = wifi.connectionInfo?.ssid?.trim('"') ?: return false
            directSsids.any { it == ssid }
        } catch (e: Throwable) {
            Log.w(TAG, "ssid check failed: ${e.message}")
            false
        }
    }

    /**
     * Collects the prefixes of the Wi-Fi link addresses (IPv4 and non-link-local IPv6)
     * so the tun can leave them alone.
     */
    private fun currentWifiCidrs(context: Context?): List<String> {
        val ctx = context ?: return emptyList()
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return emptyList()
            val out = LinkedHashSet<String>()
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val lp = cm.getLinkProperties(network) ?: continue
                for (linkAddress in lp.linkAddresses) {
                    val address = linkAddress.address ?: continue
                    if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                    val host = address.hostAddress?.substringBefore('%') ?: continue
                    out.add("$host/${linkAddress.prefixLength}")
                }
            }
            out.toList()
        } catch (e: Throwable) {
            Log.w(TAG, "wifi prefix lookup failed: ${e.message}")
            emptyList()
        }
    }

    /** Adds the current LAN prefixes to every tun inbound's route_exclude_address. */
    private fun applyLanExclude(root: JSONObject, context: Context?) {
        val cidrs = currentWifiCidrs(context)
        if (cidrs.isEmpty()) return
        val inbounds = root.optJSONArray("inbounds") ?: return
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (inbound.optString("type") != "tun") continue
            val merged = LinkedHashSet<String>()
            inbound.optJSONArray("route_exclude_address")?.let { array ->
                for (j in 0 until array.length()) merged.add(array.optString(j))
            }
            merged.addAll(cidrs)
            val array = JSONArray()
            merged.forEach { array.put(it) }
            inbound.put("route_exclude_address", array)
        }
    }

    /**
     * Reads the default gateway of the Wi-Fi network. activeNetwork cannot be
     * used here: while the VPN runs it is the tun interface and has no gateway.
     */
    @Suppress("DEPRECATION")
    private fun currentGateways(context: Context?): Pair<String?, String?> {
        val ctx = context ?: return null to null
        try {
            // allNetworks keeps listing a Wi-Fi network for a while after it loses its
            // link, so ask the WifiManager whether we are really associated. Without this
            // the injected gateway survives on cellular and every lookup waits for it.
            val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val associated = runCatching {
                wifi != null && wifi.isWifiEnabled && (wifi.connectionInfo?.networkId ?: -1) >= 0
            }.getOrDefault(false)
            if (!associated) return null to null
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                    val lp = cm.getLinkProperties(network) ?: continue
                    var v4: String? = null
                    var v6: String? = null
                    for (address in lp.dnsServers) {
                        if (address is Inet6Address && !address.isLinkLocalAddress && v6 == null) {
                            v6 = address.hostAddress?.substringBefore('%')
                        }
                    }
                    for (route in lp.routes) {
                        if (!route.isDefaultRoute) continue
                        val gateway = route.gateway ?: continue
                        if (gateway is Inet4Address) {
                            if (v4 == null) v4 = gateway.hostAddress
                        } else if (gateway is Inet6Address) {
                            if (v6 == null && !gateway.isLinkLocalAddress) v6 = gateway.hostAddress?.substringBefore('%')
                        }
                    }
                    if (v4 != null || v6 != null) return v4 to v6
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "gateway lookup failed: ${e.message}")
        }
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                @Suppress("DEPRECATION")
                val ip = wm.dhcpInfo.gateway
                if (ip != 0) {
                    return "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}" to null
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "dhcp gateway fallback failed: ${e.message}")
        }
        return null to null
    }

    /**
     * Accepts the JSON dialects people actually paste: line/block comments and
     * trailing commas. Comments are stripped outside strings first, then the
     * remaining text is handed to org.json.
     */
    private fun sanitize(input: String): String {
        val out = StringBuilder(input.length)
        var i = 0
        var inString = false
        var escaped = false
        while (i < input.length) {
            val c = input[i]
            if (inString) {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                i++
                continue
            }
            when {
                c == '"' -> {
                    inString = true
                    out.append(c)
                    i++
                }
                c == '/' && i + 1 < input.length && input[i + 1] == '/' -> {
                    while (i < input.length && input[i] != '\n') i++
                }
                c == '/' && i + 1 < input.length && input[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < input.length && !(input[i] == '*' && input[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(input.length)
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString().replace(Regex(",(\\s*[}\\]])"), "$1")
    }

    private fun rule(ssid: String, mode: String, finalTag: String): JSONObject? {
        if (ssid.isEmpty()) return null
        return when (mode) {
            "direct" -> JSONObject()
                .put("wifi_ssid", JSONArray().put(ssid))
                .put("action", "route")
                .put("outbound", "direct")

            "global" -> if (finalTag.isEmpty()) null else JSONObject()
                .put("wifi_ssid", JSONArray().put(ssid))
                .put("action", "route")
                .put("outbound", finalTag)

            else -> null
        }
    }
}
