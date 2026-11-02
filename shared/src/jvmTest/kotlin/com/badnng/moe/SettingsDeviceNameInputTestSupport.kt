package com.badnng.moe

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextRange
import java.awt.event.KeyEvent
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private data class FocusedDeviceNameValue(val text: String, val selection: TextRange)

/** Read the live focused field rather than retaining a detached semantics node or invoking SetText. */
private fun ComposePixelScene.focusedDeviceNameValue(expectedBounds: Rect): FocusedDeviceNameValue {
    val values = mutableListOf<FocusedDeviceNameValue>()
    val bounds = unmergedSemanticsBounds { config ->
        val text = config.getOrNull(SemanticsProperties.EditableText)?.text
        val selection = config.getOrNull(SemanticsProperties.TextSelectionRange)
        if (config.getOrNull(SemanticsProperties.Focused) == true && text != null && selection != null) {
            values += FocusedDeviceNameValue(text, selection)
            true
        } else false
    }
    val matches = bounds.indices.filter { bounds[it].overlaps(expectedBounds) }
    return values[assertNotNull(matches.singleOrNull(), "The pointer-selected device-name editor must own focus and selection")]
}

/** Real pointer, Ctrl+A, Backspace and typing; each controlled value must reach the next frame. */
internal fun ComposePixelScene.replaceDeviceNameEditorUsingKeys(field: Rect, replacement: String) {
    moveTo(field.center.x, field.center.y)
    pressAt(field.center.x, field.center.y)
    releaseAt(field.center.x, field.center.y)
    settledFrame(2, 16)
    val original = focusedDeviceNameValue(field)
    assertTrue(original.text.isNotEmpty(), "This replacement regression must start with a real existing name")

    selectAll()
    settledFrame(2, 16)
    val selected = focusedDeviceNameValue(field)
    assertEquals(original.text, selected.text, "Ctrl+A must not alter the existing text")
    assertEquals(0, selected.selection.min, "Ctrl+A must select from the start")
    assertEquals(original.text.length, selected.selection.max, "Ctrl+A must select the complete existing name")

    keyStroke(KeyEvent.VK_BACK_SPACE)
    settledFrame(2, 16)
    val cleared = focusedDeviceNameValue(field)
    assertEquals("", cleared.text, "Backspace must remove the entire selected name")
    assertEquals(TextRange.Zero, cleared.selection, "The empty controlled value must retain a valid caret")

    replacement.forEach { character ->
        keyStroke(KeyEvent.getExtendedKeyCodeForChar(character.code), character)
        settledFrame(1, 16)
    }
    settledFrame(2, 16)
    val typed = focusedDeviceNameValue(field)
    assertEquals(replacement, typed.text, "The actual keyboard events must produce the exact replacement")
    assertEquals(TextRange(replacement.length), typed.selection, "The caret must remain at the end of the typed name")
}
