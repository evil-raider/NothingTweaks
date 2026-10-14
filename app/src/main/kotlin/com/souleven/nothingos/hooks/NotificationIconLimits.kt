package com.souleven.nothingos.hooks

/** Existing icons-branch preference format: statusBar,AOD; one value applies to both. */
internal object NotificationIconLimits {
    fun statusBar(raw: String): Int? = parseAt(raw, 0)

    fun aod(raw: String): Int? = parseAt(raw, 1) ?: parseAt(raw, 0)

    private fun parseAt(raw: String, index: Int): Int? {
        val parts = raw.split(",")
        if (index >= parts.size) return null
        val value = parts[index].trim().toIntOrNull() ?: return null
        return value.takeIf { it in 1..50 }
    }
}
