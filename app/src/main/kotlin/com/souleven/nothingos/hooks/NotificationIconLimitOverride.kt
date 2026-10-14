package com.souleven.nothingos.hooks

import java.lang.reflect.Field
import java.util.WeakHashMap

/** Field-level override: also works when a ROM writes fields directly or ART inlines a setter. */
internal class NotificationIconLimitOverride(
    containerClass: Class<*>,
    private val onReset: (field: String, observed: Int, requested: Int) -> Unit = { _, _, _ -> }
) {
    private data class Value(var stock: Int, var applied: Int)
    private val values = WeakHashMap<Any, MutableMap<Field, Value>>()
    private val staticLayout = findField(containerClass, "mIsStaticLayout")
    private val fields = listOf("mMaxIcons", "mMaxStaticIcons", "mMaxIconsOnAod", "mMaxIconsOnLockscreen")
        .mapNotNull { findField(containerClass, it) }
        .filter { it.type == Int::class.javaPrimitiveType }

    val fieldNames: List<String> get() = fields.map { it.name }

    /**
     * Remember unmodified ROM values so clearing the preference restores stock behaviour.
     * [refreshedFields] have just been written by initResources/the real setter, even if their
     * new stock value happens to equal our last override. All other direct writes are detected
     * by comparing the current value with the last applied value.
     *
     * Non-static containers are the notification shelf, not status bar/AOD. Never cap the shelf.
     */
    fun apply(container: Any, limit: Int?, refreshedFields: Set<String> = emptySet()) {
        val requested = limit?.takeIf { it >= 0 }
            ?.takeUnless { staticLayout?.getBoolean(container) == false }
        val saved = values[container]
        if (requested == null && saved == null) return
        val state = saved ?: mutableMapOf<Field, Value>().also { values[container] = it }
        for (field in fields) {
            val current = field.getInt(container)
            val previous = state[field]
            if (requested != null && previous != null && current != previous.applied &&
                field.name !in refreshedFields) {
                onReset(field.name, current, requested)
            }
            if (previous != null && (field.name in refreshedFields || current != previous.applied)) {
                previous.stock = current
            }
            if (requested != null) {
                val value = previous ?: Value(current, requested).also { state[field] = it }
                if (current != requested) field.setInt(container, requested)
                value.applied = requested
            } else if (previous != null) {
                field.setInt(container, previous.stock)
            }
        }
        if (requested == null) values.remove(container)
    }

    companion object {
        fun parseLimit(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it >= 0 }

        private fun findField(type: Class<*>, name: String): Field? {
            var current: Class<*>? = type
            while (current != null) {
                try {
                    return current.getDeclaredField(name).apply { isAccessible = true }
                } catch (_: NoSuchFieldException) {
                    current = current.superclass
                }
            }
            return null
        }
    }
}
