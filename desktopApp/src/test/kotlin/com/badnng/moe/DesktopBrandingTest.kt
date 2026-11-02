package com.badnng.moe

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopBrandingTest {
    @Test fun windowIconLoadsWithoutTheLaunchThreadContextClassLoader() {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        try {
            thread.contextClassLoader = null
            assertEquals(1024, DesktopBranding.windowIcon.width)
            assertEquals(1024, DesktopBranding.windowIcon.height)
        } finally {
            thread.contextClassLoader = previous
        }
    }
}
