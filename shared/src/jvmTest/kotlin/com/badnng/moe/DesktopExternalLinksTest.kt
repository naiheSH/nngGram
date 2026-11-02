package com.badnng.moe

import java.net.URI
import kotlin.test.*

class DesktopExternalLinksTest {
    @Test fun unsafeInputNeverReachesAnyPlatformLauncher() {
        val opened = mutableListOf<URI>()
        for (url in listOf("file:///tmp/private", "javascript:alert(1)", "https://a.test/\nopen", "https://user@a.test/", "https://a.test/\\x")) {
            assertFalse(openDesktopExternalLink(url) { opened += it; true })
        }
        assertTrue(opened.isEmpty())
        assertTrue(openDesktopExternalLink("https://example.org/a?b=1&c=2") { opened += it; true })
        assertEquals(listOf(URI("https://example.org/a?b=1&c=2")), opened)
    }

    @Test fun unavailableLaunchersReportFailureWithoutOpeningAnAlternative() {
        assertFalse(openDesktopExternalLink("https://example.org") { error("synthetic unavailable backend") })
        assertFalse(openDesktopExternalLink("https://example.org") { false })
    }
}
