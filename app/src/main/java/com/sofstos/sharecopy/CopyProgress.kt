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
    val active: Boolean = false,
    val operation: String = "",
    val destination: String = "",
    val items: List<CopyItemProgress> = emptyList(),
    val currentIndex: Int = -1,
    val message: String = ""
) {
    val completedCount: Int
        get() = items.count { it.state == CopyItemState.DONE }

    val currentItem: CopyItemProgress?
        get() = items.getOrNull(currentIndex)

    val totalBytes: Long
        get() = items.sumOf { it.totalBytes }

    val copiedBytes: Long
        get() = items.sumOf { it.bytes }
}

/** In-process progress bus; the notification remains the source of truth if the app process is recreated. */
object CopyProgressBus {
    private val listeners = CopyOnWriteArraySet<(CopyProgress) -> Unit>()

    @Volatile
    private var state = CopyProgress()

    fun current(): CopyProgress = state

    fun subscribe(listener: (CopyProgress) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(state)
        return { listeners.remove(listener) }
    }

    fun publish(newState: CopyProgress) {
        state = newState
        listeners.forEach { listener ->
            try {
                listener(newState)
            } catch (_: Exception) {
                // A UI listener must never affect the copy service.
            }
        }
    }

    fun clear() {
        publish(CopyProgress())
    }
}
