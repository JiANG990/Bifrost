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

    fun wrap(content: String): String {
        if (!Settings.ssidModeEnabled) return content
        val mapping = Settings.ssidModeList
        if (mapping.isBlank()) return content
        return try {
            val root = JSONObject(content)
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
            if (generated.length() == 0) return content
            val merged = JSONArray()
            for (i in 0 until generated.length()) merged.put(generated.get(i))
            for (i in 0 until existing.length()) merged.put(existing.get(i))
            route.put("rules", merged)
            root.toString()
        } catch (e: Throwable) {
            Log.w(TAG, "leave config untouched: ${e.message}")
            content
        }
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
