package com.audiophile.engine.playback

import org.junit.Assert.*
import org.junit.Test

class QueueEditsTest {
    @Test fun removingCurrentSelectsItsSuccessorOrLastRemainingItem() {
        assertEquals(QueueEdit(listOf(0, 3, 4), 1, true), QueueEdits.remove(5, 2, 1, 3))
        assertEquals(QueueEdit(listOf(0, 1), 1, true), QueueEdits.remove(4, 3, 2, 4))
    }

    @Test fun clearProducesAnEmptyQueueWithoutInvalidCurrentIndex() {
        assertEquals(QueueEdit(emptyList(), 0, true), QueueEdits.remove(4, 2, 0, 4))
    }

    @Test fun everyLegalMovePreservesOccurrencesAndThePlayingItem() {
        // Distinct integer identities model repeated occurrences of the same song.
        for (size in 1..10) for (current in 0 until size) {
            for (from in 0 until size) for (to in from + 1..size) {
                for (target in 0..size - (to - from)) {
                    val result = QueueEdits.move(size, current, from, to, target)
                    assertEquals((0 until size).toSet(), result.order.toSet())
                    assertEquals(size, result.order.size)
                    assertEquals(current, result.order[result.currentIndex])
                    assertFalse(result.currentRemoved)
                    assertEquals((from until to).toList(), result.order.subList(target, target + to - from))
                }
            }
        }
    }

    @Test fun removingOtherItemsNeverChangesTheSelectedOccurrence() {
        for (size in 1..10) for (current in 0 until size) {
            for (from in 0 until size) for (to in from + 1..size) {
                val result = QueueEdits.remove(size, current, from, to)
                if (current !in from until to) {
                    assertFalse(result.currentRemoved)
                    assertEquals(current, result.order[result.currentIndex])
                }
                assertTrue(result.order.none { it in from until to })
            }
        }
    }
}
