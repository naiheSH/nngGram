package com.badnng.moe.media

import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.test.*

/** Device-free tests of queue bounds, PCM alignment and interruption/stop ownership. */
class JavaSoundAudioTest {
    @Test fun playbackWritesAlignedChunksAndCloseInterruptsAFullQueue() {
        val available = AtomicInteger(8)
        val written = mutableListOf<Int>()
        val calls = mutableListOf<String>()
        val line = fake<SourceDataLine> { name, args ->
            synchronized(calls) { calls += name }
            when (name) {
                "available", "getBufferSize" -> available.get()
                "write" -> (args!![2] as Int).also { written += it }
                else -> null
            }
        }
        val output = JavaSoundAudioOutput(line)
        output.start()
        output.write(ByteArray(20), 20)
        assertEquals(listOf(8, 8, 4), written)
        available.set(0)
        val writer = Thread { output.write(ByteArray(16), 16) }
        writer.start()
        Thread.sleep(20)
        output.close()
        writer.join(1_000)
        assertFalse(writer.isAlive, "Closing a full device queue must release its writer")
        assertEquals(listOf("stop", "flush", "close"), synchronized(calls) { calls.takeLast(3) })
    }

    @Test fun captureReadsWholeSamplesAndStopsWithoutAnotherDeviceRead() {
        var reads = 0
        var closes = 0
        val line = fake<TargetDataLine> { name, args ->
            when (name) {
                "available" -> 9
                "read" -> { reads++; args!![2] as Int }
                "close" -> { closes++; null }
                else -> null
            }
        }
        val input = JavaSoundPcmInput(line)
        assertEquals(4, input.read(ByteArray(5)))
        input.stop()
        assertEquals(0, input.read(ByteArray(10)))
        assertEquals(1, reads)
        input.close(); input.close()
        assertEquals(1, closes)
    }

    private inline fun <reified T> fake(crossinline call: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, arguments ->
            call(method.name, arguments)
        } as T
}
