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
    private val fields = listOf("mMaxIcons", "mMaxStaticIcons", "mMaxIconsOnAod", "mMaxIconsOnLockscreen", "mSpeedBumpIndex")
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
    fun isStatic(container: Any): Boolean = staticLayout?.getBoolean(container) != false

    fun describe(container: Any): String = fields.joinToString { "${it.name}=${it.getInt(container)}" }

    fun apply(container: Any, limit: Int?, refreshedFields: Set<String> = emptySet()) {
        val requested = limit?.takeIf { it >= 0 }?.takeIf { isStatic(container) }
        val saved = values[container]
        if (requested == null && saved == null) return
        val state = saved ?: mutableMapOf<Field, Value>().also { values[container] = it }
        for (field in fields) {
            // A quiet-notification speed bump is an independent overflow trigger, not a count.
            // Suppress it only with an explicit limit, leaving the physical width check intact.
            val target = requested?.let { if (field.name == "mSpeedBumpIndex") -1 else it }
            val current = field.getInt(container)
            val previous = state[field]
            if (target != null && previous != null && current != previous.applied &&
                field.name !in refreshedFields) {
                onReset(field.name, current, target)
            }
            if (previous != null && (field.name in refreshedFields || current != previous.applied)) {
                previous.stock = current
            }
            if (target != null) {
                val value = previous ?: Value(current, target).also { state[field] = it }
                if (current != target) field.setInt(container, target)
                value.applied = target
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
