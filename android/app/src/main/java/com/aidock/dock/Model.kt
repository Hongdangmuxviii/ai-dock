package com.aidock.dock

import org.json.JSONArray
import org.json.JSONObject

data class UsageWindow(
    val key: String,
    val label: String,
    val usedPercent: Double,
    val resetsAt: Double?, // epoch 초 (PC 시계 기준)
)

data class Provider(
    val id: String,
    val name: String,
    val status: String,   // ok | stale | error | unavailable | unknown
    val source: String,
    val updatedAt: Double?,
    val windows: List<UsageWindow>,
    val message: String,
)

data class Media(
    val available: Boolean = false,
    val active: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val status: String = "",
    val position: Double = 0.0,
    val positionAt: Double = 0.0,
    val duration: Double = 0.0,
    val reason: String = "",
) {
    val playing get() = status == "playing"
}

sealed interface Connection {
    data object Searching : Connection
    data class Connecting(val address: String) : Connection
    data class Connected(val address: String) : Connection
    data class Failed(val address: String, val reason: String) : Connection
}

private fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (isNull(name) || !has(name)) null else optDouble(name).takeUnless { it.isNaN() }

fun parseProviders(arr: JSONArray): List<Provider> = (0 until arr.length()).map { i ->
    val p = arr.getJSONObject(i)
    val ws = p.optJSONArray("windows") ?: JSONArray()
    Provider(
        id = p.optString("id"),
        name = p.optString("name"),
        status = p.optString("status", "unknown"),
        source = p.optString("source"),
        updatedAt = p.optDoubleOrNull("updated_at"),
        windows = (0 until ws.length()).map { j ->
            val w = ws.getJSONObject(j)
            UsageWindow(
                key = w.optString("key"),
                label = w.optString("label"),
                usedPercent = w.optDouble("used_percent", 0.0),
                resetsAt = w.optDoubleOrNull("resets_at"),
            )
        },
        message = p.optString("message"),
    )
}

fun parseMedia(o: JSONObject) = Media(
    available = o.optBoolean("available"),
    active = o.optBoolean("active"),
    title = o.optString("title"),
    artist = o.optString("artist"),
    status = o.optString("status"),
    position = o.optDouble("position", 0.0),
    positionAt = o.optDouble("position_at", 0.0),
    duration = o.optDouble("duration", 0.0),
    reason = o.optString("reason"),
)
