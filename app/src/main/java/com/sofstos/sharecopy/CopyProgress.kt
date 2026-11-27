package com.sofstos.sharecopy

import java.util.concurrent.CopyOnWriteArraySet

enum class CopyItemState {
    PENDING,
    COPYING,
    DONE,
    FAILED
}

data class CopyItemProgress(
    val name: String,
    val state: CopyItemState = CopyItemState.PENDING,
    val bytes: Long = 0L,
    val totalBytes: Long = 0L,
    val error: String? = null
)

data class CopyProgress(
    val instanceId: String = "",
    val active: Boolean = false,
    val operation: String = "",
    val destination: String = "",
    val items: List<CopyItemProgress> = emptyList(),
    val currentIndex: Int = -1,
    val message: String = "",
    val speedBytesPerSecond: Long = 0L
) {
    val completedCount: Int
        get() = items.count { it.state == CopyItemState.DONE }

    val currentItem: CopyItemProgress?
        get() = items.getOrNull(currentIndex)

    val totalBytes: Long
        get() = items.sumOf { it.totalBytes }

    val copiedBytes: Long
        get() = items.sumOf { it.bytes }

    val remainingBytes: Long
        get() = (totalBytes - copiedBytes).coerceAtLeast(0L)
}

data class CopyProgressOverview(val instances: List<CopyProgress> = emptyList()) {
    val active: Boolean
        get() = instances.any { it.active }
    val totalBytes: Long
        get() = instances.sumOf { it.totalBytes }
    val copiedBytes: Long
        get() = instances.sumOf { it.copiedBytes }
    val remainingBytes: Long
        get() = instances.sumOf { it.remainingBytes }
    val speedBytesPerSecond: Long
        get() = instances.sumOf { it.speedBytesPerSecond }
    val completedCount: Int
        get() = instances.sumOf { it.completedCount }
    val fileCount: Int
        get() = instances.sumOf { it.items.size }
}

/** In-process progress bus with one independent state per running copy instance. */
object CopyProgressBus {
    private val listeners = CopyOnWriteArraySet<(CopyProgressOverview) -> Unit>()
    private val states = LinkedHashMap<String, CopyProgress>()
    private val lock = Any()

    private fun overviewLocked(): CopyProgressOverview = CopyProgressOverview(states.values.toList())

    fun current(): CopyProgressOverview = synchronized(lock) { overviewLocked() }

    fun subscribe(listener: (CopyProgressOverview) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(current())
        return { listeners.remove(listener) }
    }

    fun publish(newState: CopyProgress) {
        val snapshot = synchronized(lock) {
            val id = newState.instanceId.ifBlank { "legacy-${System.nanoTime()}" }
            states[id] = newState.copy(instanceId = id)
            overviewLocked()
        }
        listeners.forEach { listener ->
            try {
                listener(snapshot)
            } catch (_: Exception) {
                // A UI listener must never affect the copy service.
            }
        }
    }

    fun remove(instanceId: String) {
        val snapshot = synchronized(lock) {
            states.remove(instanceId)
            overviewLocked()
        }
        listeners.forEach { listener ->
            try { listener(snapshot) } catch (_: Exception) { }
        }
    }

    fun clear() {
        val ids = synchronized(lock) { states.keys.toList() }
        ids.forEach { remove(it) }
    }
}
