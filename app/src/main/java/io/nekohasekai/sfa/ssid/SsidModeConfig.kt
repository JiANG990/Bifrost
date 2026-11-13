package io.nekohasekai.sfa.ssid

import android.util.Log
import io.nekohasekai.sfa.database.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Injects Wi-Fi SSID based routing rules into the sing-box config before it is
 * handed to the core. Rules only touch the "route.rules" head, so the profile
 * file on disk stays untouched and the user's own rules keep working.
 */
object SsidModeConfig {
    private const val TAG = "SsidMode"

    @Volatile
    var lastError: String? = null
        private set

    fun wrap(content: String): String {
        if (!Settings.ssidModeEnabled) return content
        val mapping = Settings.ssidModeList
        if (mapping.isBlank()) return content
        return try {
            lastError = null
            val root = JSONObject(sanitize(content))
            val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
            val existing = route.optJSONArray("rules") ?: JSONArray()
            val finalTag = route.optString("final", "")
            val generated = JSONArray()
            for (line in mapping.lineSequence()) {
                val trimmed = line.trim()
                val index = trimmed.indexOf('=')
                if (index <= 0) continue
                val ssid = trimmed.substring(0, index).trim()
                val mode = trimmed.substring(index + 1).trim().lowercase()
                rule(ssid, mode, finalTag)?.let { generated.put(it) }
            }
            if (generated.length() == 0) {
                lastError = "no usable SSID rule (mode must be direct or global with route.final set)"
                return content
            }
            val merged = JSONArray()
            for (i in 0 until generated.length()) merged.put(generated.get(i))
            for (i in 0 until existing.length()) merged.put(existing.get(i))
            route.put("rules", merged)
            root.toString()
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "leave config untouched: ${e.message}")
            content
        }
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
