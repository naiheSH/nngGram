@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import org.jetbrains.skia.Bitmap

/** CPU-raster UI tests with real pointer input and a deterministic animation clock. */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
internal class ComposePixelScene(
    width: Int,
    height: Int,
    content: @Composable () -> Unit,
) : AutoCloseable {
    private val asynchronousFailure = AtomicReference<Throwable?>()
    private val previousHandler = onEventThread {
        Thread.currentThread().uncaughtExceptionHandler.also {
            Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                asynchronousFailure.compareAndSet(null, error)
            }
        }
    }
    private val scene = try {
        onEventThread { ImageComposeScene(width, height) {
            // Synthetic wheel units are viewport twentieths on every host. Native
            // Cocoa/WinUI/GNOME wheel acceleration belongs to platform integration tests.
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.foundation.gestures.LocalScrollConfig provides PortableSceneWheel,
                content = content,
            )
        } }
    } catch (error: Throwable) {
        onEventThread { Thread.currentThread().uncaughtExceptionHandler = previousHandler }
        throw error
    }
    private var timeNanos = 0L

    fun moveTo(x: Float, y: Float) = pointer(PointerEventType.Move, x, y)

    fun dragTo(x: Float, y: Float) = pointer(PointerEventType.Move, x, y, pressed = true)

    fun pressAt(x: Float, y: Float) = pointer(PointerEventType.Press, x, y, pressed = true)

    fun releaseAt(x: Float, y: Float) = pointer(PointerEventType.Release, x, y)

    fun secondaryClickAt(x: Float, y: Float) {
        pointer(PointerEventType.Press, x, y, pressed = true, secondary = true)
        pointer(PointerEventType.Release, x, y, secondary = true)
    }

    /** Keys stay inside the in-memory scene; no system keyboard input is generated. */
    fun keyStroke(code: Int, character: Char = java.awt.event.KeyEvent.CHAR_UNDEFINED, control: Boolean = false,
        shift: Boolean = false, meta: Boolean = false) = onEventThread {
        checkAsynchronousFailure()
        val source = object : java.awt.Component() {}
        val modifiers = (if (control) java.awt.event.InputEvent.CTRL_DOWN_MASK else 0) or
            (if (shift) java.awt.event.InputEvent.SHIFT_DOWN_MASK else 0) or
            (if (meta) java.awt.event.InputEvent.META_DOWN_MASK else 0)
        fun send(type: Int, keyCode: Int, char: Char) {
            val nativeEvent = java.awt.event.KeyEvent(source, type,
                timeNanos / 1_000_000, modifiers, keyCode, char)
            scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(
                key = androidx.compose.ui.input.key.Key(nativeKeyCode = keyCode),
                type = when (type) {
                    java.awt.event.KeyEvent.KEY_PRESSED -> androidx.compose.ui.input.key.KeyEventType.KeyDown
                    java.awt.event.KeyEvent.KEY_RELEASED -> androidx.compose.ui.input.key.KeyEventType.KeyUp
                    else -> androidx.compose.ui.input.key.KeyEventType.Unknown
                },
                codePoint = char.code, isCtrlPressed = control, isMetaPressed = meta, isShiftPressed = shift, nativeEvent = nativeEvent,
            ))
        }
        send(java.awt.event.KeyEvent.KEY_PRESSED, code, character)
        if (character != java.awt.event.KeyEvent.CHAR_UNDEFINED && !control && !meta)
            send(java.awt.event.KeyEvent.KEY_TYPED, java.awt.event.KeyEvent.VK_UNDEFINED, character)
        send(java.awt.event.KeyEvent.KEY_RELEASED, code, character)
    }

    fun selectAll() = shortcut(java.awt.event.KeyEvent.VK_A)

    fun shortcut(code: Int) = keyStroke(code,
        control = !com.badnng.moe.platform.DesktopPlatform.current.isMacOS,
        meta = com.badnng.moe.platform.DesktopPlatform.current.isMacOS)

    fun scrollAt(x: Float, y: Float, deltaX: Float = 0f, deltaY: Float = 0f) {
        // A physical wheel acts under the actual pointer. Update the scene hit path
        // first, including after composition has replaced the last clicked control.
        moveTo(x, y)
        pointer(PointerEventType.Scroll, x, y, scrollDelta = Offset(deltaX, deltaY))
    }

    /** Resolve current layout on the UI thread; no live semantics/layout nodes escape the scene. */
    fun semanticsBounds(predicate: (SemanticsConfiguration) -> Boolean): List<Rect> = nodeBounds(false, predicate)

    /** Inspect actual child image nodes when a clickable surface merges their accessibility data. */
    fun unmergedSemanticsBounds(predicate: (SemanticsConfiguration) -> Boolean): List<Rect> = nodeBounds(true, predicate)

    /** Visible list snapshots exclude unplaced prefetched nodes; state queries may still inspect them. */
    fun placedSemanticsBounds(predicate: (SemanticsConfiguration) -> Boolean): List<Rect> = nodeBounds(false, predicate, placedOnly = true)

    private fun nodeBounds(unmerged: Boolean, predicate: (SemanticsConfiguration) -> Boolean,
        placedOnly: Boolean = false): List<Rect> = onEventThread {
        checkAsynchronousFailure()
        buildList {
            fun visit(node: SemanticsNode) {
                // Lazy layouts may retain prefetched/unplaced nodes with old coordinates.
                // They are not visible hit targets in the current rendered scene.
                if (placedOnly && (!node.layoutInfo.isAttached || !node.layoutInfo.isPlaced || node.layoutInfo.isDeactivated)) return
                if (predicate(node.config)) add(node.boundsInRoot)
                node.children.forEach(::visit)
            }
            // Use the accessibility tree so a clickable parent's role and its icon/text label
            // share one configuration. Independent nested click targets remain separate nodes.
            scene.semanticsOwners.forEach { visit(if (unmerged) it.unmergedRootSemanticsNode else it.rootSemanticsNode) }
        }
    }

    /** Each frame yields the EDT so effects, input handlers and draws cannot race. */
    fun settledFrame(frameCount: Int = 24, stepMillis: Long = 64): ComposePixelFrame {
        require(frameCount > 0)
        require(stepMillis > 0)
        repeat(frameCount - 1) {
            onEventThread {
                checkAsynchronousFailure()
                timeNanos += stepMillis * 1_000_000L
                scene.render(timeNanos).close()
            }
        }
        return onEventThread {
            checkAsynchronousFailure()
            timeNanos += stepMillis * 1_000_000L
            scene.render(timeNanos).use { image ->
                Bitmap.makeFromImage(image).use { pixels ->
                    ComposePixelFrame(pixels.width, pixels.height,
                        IntArray(pixels.width * pixels.height) { index ->
                            pixels.getColor(index % pixels.width, index / pixels.width)
                        })
                }
            }
        }
    }

    private fun pointer(type: PointerEventType, x: Float, y: Float, pressed: Boolean = false,
        scrollDelta: Offset = Offset.Zero, secondary: Boolean = false) {
        onEventThread {
            checkAsynchronousFailure()
            scene.sendPointerEvent(
                eventType = type,
                position = Offset(x, y),
                scrollDelta = scrollDelta,
                timeMillis = timeNanos / 1_000_000L,
                buttons = PointerButtons(isPrimaryPressed = pressed && !secondary, isSecondaryPressed = pressed && secondary),
                button = if (type == PointerEventType.Press || type == PointerEventType.Release)
                    if (secondary) PointerButton.Secondary else PointerButton.Primary else null,
            )
        }
    }

    override fun close() {
        try {
            onEventThread { scene.close() }
        } finally {
            onEventThread { Thread.currentThread().uncaughtExceptionHandler = previousHandler }
        }
        checkAsynchronousFailure()
    }

    private fun checkAsynchronousFailure() {
        asynchronousFailure.get()?.let { throw AssertionError("Compose pixel scene failed on the EDT.", it) }
    }
}

private object PortableSceneWheel : androidx.compose.foundation.gestures.ScrollConfig {
    override val isSmoothScrollingEnabled = true
    override fun androidx.compose.ui.unit.Density.calculateMouseWheelScroll(
        event: androidx.compose.ui.input.pointer.PointerEvent,
        bounds: androidx.compose.ui.unit.IntSize,
    ): Offset {
        val delta = event.changes.fold(Offset.Zero) { total, change -> total + change.scrollDelta }
        return Offset(-delta.x * bounds.width / 20f, -delta.y * bounds.height / 20f)
    }
}

internal class ComposePixelFrame(val width: Int, val height: Int, private val colors: IntArray) {
    operator fun get(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height)
        return colors[y * width + x]
    }
}

private fun <T> onEventThread(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    val task = FutureTask(Callable(action))
    SwingUtilities.invokeAndWait(task)
    return try {
        task.get()
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    }
}
