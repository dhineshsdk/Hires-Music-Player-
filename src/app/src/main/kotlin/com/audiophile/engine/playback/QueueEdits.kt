package com.audiophile.engine.playback

/** Index permutations preserve distinct occurrences of the same track. */
data class QueueEdit(val order: List<Int>, val currentIndex: Int, val currentRemoved: Boolean)

object QueueEdits {
    fun remove(size: Int, current: Int, from: Int, to: Int): QueueEdit {
        val order = (0 until size).filterNot { it in from until to }
        val kept = order.indexOf(current)
        return QueueEdit(order, if (order.isEmpty()) 0 else if (kept >= 0) kept else from.coerceAtMost(order.lastIndex), kept < 0)
    }

    /** Media3's newIndex is an index in the list after removing the moved range. */
    fun move(size: Int, current: Int, from: Int, to: Int, newIndex: Int): QueueEdit {
        val order = (0 until size).toMutableList()
        val moved = order.subList(from, to).toList()
        order.subList(from, to).clear()
        order.addAll(newIndex.coerceIn(0, order.size), moved)
        return QueueEdit(order, order.indexOf(current).coerceAtLeast(0), false)
    }
}
