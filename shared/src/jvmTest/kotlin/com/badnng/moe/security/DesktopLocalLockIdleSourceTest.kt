package com.badnng.moe.security

import kotlin.test.*

class DesktopLocalLockIdleSourceTest {
    @Test fun applicationFallbackUsesRealActivityAndDoesNotInventSystemInput() {
        var now = 1_000_000L
        val source = ApplicationLocalLockIdleSource { now }
        val due = mutableListOf<LocalLockIdleDue>()
        val timer = LocalLockDeadlineScheduler { _, _ -> LocalLockDeadlineHandle {} }
        LocalLockIdleController(source, timer, due::add).use { idle ->
            idle.update(LocalLockIdleConfig(true, true, 1, 60))
            assertEquals(LocalLockIdleAvailability.APPLICATION_ONLY, idle.availability())
            assertNull(source.lastInputDword())
            assertTrue(due.isEmpty())
            now += 30_000
            idle.recordAppActivity()
            now += 25_000
            idle.recheck()
            assertTrue(due.isEmpty(), "Actual input extends the idle interval")
            now += 40_000
            idle.recheck()
            assertEquals(1, due.size)
            assertTrue(idle.claimDue(due.single()))
            assertFalse(idle.claimDue(due.single()))
        }
    }

    @Test fun aBackwardClockCannotExtendAnUnlockedSession() {
        var now = 1_000_000L
        val due = mutableListOf<LocalLockIdleDue>()
        val timer = LocalLockDeadlineScheduler { _, _ -> LocalLockDeadlineHandle {} }
        LocalLockIdleController(ApplicationLocalLockIdleSource { now }, timer, due::add).use { idle ->
            idle.update(LocalLockIdleConfig(true, true, 2, 60))
            now--
            idle.recordAppActivity()
            assertEquals(LocalLockIdleDueReason.CLOCK_UNAVAILABLE, due.single().reason)
        }
    }
}
