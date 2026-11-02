package com.badnng.moe.notifications

import com.sun.jna.Memory
import java.io.StringReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import kotlin.test.*

class WindowsToastAbiTest {
    @Test fun factoryCreatesAndInvokesTheActivationAndLockCallbacksThroughNativeFakeVtables() {
        org.junit.Assume.assumeTrue("Windows COM stdcall callback trampoline", com.sun.jna.Platform.isWindows())
        WindowsToastNativeSmoke.factoryCallbacks()
    }

    @Test fun comEmbeddingSuffixAndShortcutActivationAreParsedWithoutStartingTheRealApplication() {
        assertTrue(WindowsToastLaunchArguments.isActivation(arrayOf("--toast-activate")))
        assertTrue(WindowsToastLaunchArguments.isActivation(arrayOf("--toast-activate", "-Embedding")))
        assertFalse(WindowsToastLaunchArguments.isActivation(emptyArray()))
        assertFalse(WindowsToastLaunchArguments.isActivation(arrayOf("--idle-probe", "synthetic")))
        for (args in listOf(arrayOf("-Embedding"), arrayOf("-Embedding", "--toast-activate"),
            arrayOf("--toast-activate", "chat=123"), arrayOf("--toast-activate", "-Embedding", "extra"))) {
            assertFailsWith<IllegalArgumentException> { WindowsToastLaunchArguments.isActivation(args) }
        }
    }

    @Test fun callbackVtablesRoundTripIUnknownAndTheNativeInvokeWithoutAnOsBackend() {
        org.junit.Assume.assumeTrue("Windows COM stdcall callback trampoline", com.sun.jna.Platform.isWindows())
        WindowsToastNativeSmoke.run()
    }

    @Test fun agileMarkerQueriesRetainTheSameIdentityAndUnsupportedInterfacesClearTheOutput() {
        org.junit.Assume.assumeTrue("Windows COM stdcall callback trampoline", com.sun.jna.Platform.isWindows())
        val callback = WindowsToastComObject(emptySet(), emptyList())
        try {
            val agile = WindowsToastComAbi.query(callback.pointer, WindowsToastComAbi.IAGILE_OBJECT)
            assertEquals(callback.pointer, agile)
            assertEquals(2, callback.referenceCount)
            WindowsToastComAbi.release(agile)
            WindowsToastComAbi.guid("00112233-4455-6677-8899-aabbccddeeff").use { iid -> Memory(8).use { output ->
                output.setPointer(0, callback.pointer)
                assertEquals(WindowsToastComAbi.E_NOINTERFACE, WindowsToastComAbi.call(callback.pointer, 0, iid, output))
                assertNull(output.getPointer(0))
                assertEquals(1, callback.referenceCount)
            } }
        } finally { callback.releaseOwner() }
    }

    @Test fun aFactoryThatCapturedAnActivatorBeforeCloseCannotReviveItsZeroReferenceCount() {
        org.junit.Assume.assumeTrue("Windows COM stdcall callback trampoline", com.sun.jna.Platform.isWindows())
        val activator = WindowsToastComObject(setOf(WindowsToastComAbi.ACTIVATION_CALLBACK), emptyList())
        val captured = CountDownLatch(1)
        val continueQuery = CountDownLatch(1)
        val status = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val thread = Thread {
            val old = activator // Models RPC CreateInstance capturing the old owner before close.
            captured.countDown()
            try {
                check(continueQuery.await(5, TimeUnit.SECONDS))
                WindowsToastComAbi.guid(WindowsToastComAbi.ACTIVATION_CALLBACK).use { iid -> Memory(8).use { output ->
                    output.setPointer(0, old.pointer)
                    status.set(old.query(iid, output))
                    check(output.getPointer(0) == null)
                } }
            } catch (problem: Throwable) { failure.set(problem) }
            finally { done.countDown() }
        }.apply { isDaemon = true; start() }
        assertTrue(captured.await(5, TimeUnit.SECONDS))
        activator.releaseOwner()
        continueQuery.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        thread.join(500)
        assertNull(failure.get())
        assertEquals(WindowsToastComAbi.E_NOINTERFACE, status.get())
        assertEquals(0, activator.referenceCount)
        assertEquals(0, WindowsToastComAbi.call(activator.pointer, 1), "AddRef also cannot revive a released callback.")
        assertEquals(0, activator.referenceCount)
    }

    @Test fun aQueryThatRetainsFirstKeepsTheCallbackAliveUntilItsNativeReferenceIsReleased() {
        org.junit.Assume.assumeTrue("Windows COM stdcall callback trampoline", com.sun.jna.Platform.isWindows())
        val callback = WindowsToastComObject(emptySet(), emptyList())
        val retained = WindowsToastComAbi.query(callback.pointer, WindowsToastComAbi.IUNKNOWN)
        callback.releaseOwner()
        assertEquals(1, callback.referenceCount)
        WindowsToastComAbi.release(retained)
        assertEquals(0, callback.referenceCount)
    }

    @Test fun guidAndBoundedUtf16UseWindowsMemoryLayoutAndNeverAnUnboundedScan() {
        WindowsToastComAbi.guid("00112233-4455-6677-8899-aabbccddeeff").use { value ->
            assertContentEquals(byteArrayOf(0x33, 0x22, 0x11, 0, 0x55, 0x44, 0x77, 0x66, 0x88.toByte(), 0x99.toByte(),
                0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte()), value.getByteArray(0, 16))
        }
        Memory(8).use { value ->
            value.clear()
            value.setShort(0, '中'.code.toShort()); value.setShort(2, 0xd83d.toShort()); value.setShort(4, 0xde00.toShort())
            assertEquals("中😀", WindowsToastComAbi.wide(value, 4))
            assertNull(WindowsToastComAbi.wide(value, 3))
        }
    }

    @Test fun xmlEscapesEveryExternalTextFieldAndRetainsValidEmojiWithoutExposingXmlActions() {
        val nonce = "a".repeat(32)
        val xml = windowsToastXml(nonce, "<&\"' title 😀", "</text><actions>bad</actions>\u0000tail\uD800")
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setExpandEntityReferences(false)
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        assertEquals(0, document.getElementsByTagName("actions").length)
        assertEquals("<&\"' title 😀", document.getElementsByTagName("text").item(0).textContent)
        assertEquals("</text><actions>bad</actions>tail�", document.getElementsByTagName("text").item(1).textContent)
        assertEquals("true", document.getElementsByTagName("audio").item(0).attributes.getNamedItem("silent").nodeValue)
        assertEquals("nng1:$nonce", document.documentElement.getAttribute("launch"))
        assertNull(windowsToastActivationNonce("nng1:$nonce&chat=10"))
        assertFailsWith<IllegalArgumentException> { windowsToastXml("external", "title", "text") }
    }

    @Test fun windows11FocusSelectsMostRestrictiveActualProfileAndChecksThisAumid() {
        assertEquals(true, windowsFocusProfileBlocked(listOf("windows.unrestricted", "quiet.alarmsonly"), "owned") { error("No allowed-list request for alarm-only") })
        assertEquals(false, windowsFocusProfileBlocked(listOf("windows.unrestricted", "quiet.priorityonly"), "owned") { setOf("owned") })
        assertEquals(true, windowsFocusProfileBlocked(listOf("quiet.priorityonly"), "owned") { setOf("other") })
        assertNull(windowsFocusProfileBlocked(listOf("quiet.priorityonly"), "owned") { null })
        assertNull(windowsFocusProfileBlocked(listOf("unknown"), "owned") { emptySet() })
        assertNull(windowsFocusProfileBlocked(emptyList(), "owned") { emptySet() })
        assertEquals(false, windowsFocusProfileBlocked(listOf("windows.unrestricted"), "owned") { emptySet() })
    }

    @Test fun soundToastAndFlashPolicyKeepTheirDistinctSystemStatesAndUnknownFocusIsNotOff() {
        assertTrue(WindowsNotificationPolicy(false, 1).skipSound(true, false))
        assertFalse(WindowsNotificationPolicy(false, 1).skipCustomToast(true))
        assertFalse(WindowsNotificationPolicy(false, 3).skipSound(true, false))
        assertTrue(WindowsNotificationPolicy(false, 3).skipCustomToast(true))
        assertTrue(WindowsNotificationPolicy().skipSound(true, false))
        assertTrue(WindowsNotificationPolicy().skipCustomToast(true))
        assertFalse(WindowsNotificationPolicy().skipSound(false, false))
        assertTrue(WindowsNotificationPolicy(false, 5).skipSound(false, true))
    }
}
