package com.badnng.moe

import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposePixelSceneClockTest {
    @Test fun coroutineTimeoutsFollowRenderedTimeEvenWhenTheHostPauses() {
        var expired = false
        ComposePixelScene(16, 16, virtualTime = true) {
            LaunchedEffect(Unit) { delay(200); expired = true }
        }.use { scene ->
            scene.settledFrame(1, 16)
            Thread.sleep(250)
            scene.settledFrame(1, 16)
            assertFalse(expired, "A slow renderer must not expire a UI effect before its sampled animation frames.")
            scene.settledFrame(4, 64)
            assertTrue(expired, "Advancing rendered time must still expire the real coroutine delay.")
        }
    }
}
