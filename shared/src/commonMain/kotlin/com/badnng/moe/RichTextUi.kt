package com.badnng.moe

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import com.badnng.moe.chat.*
import com.badnng.moe.media.LocalAttachmentClipboard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.window.WindowDialog

internal expect fun openExternalChatLink(url: String): Boolean

internal fun TextFieldValue.richText(): RichMessageText = RichMessageText(text,
    annotatedString.getStringAnnotations(0, text.length).mapNotNull {
        val kind = MessageEntityKind.entries.firstOrNull { kind -> it.tag == "message-entity:${kind.name}" } ?: return@mapNotNull null
        MessageEntity(it.start, it.end - it.start, kind, it.item.substringAfter(':'), it.item.substringBefore(':').toLongOrNull() ?: 0)
    })

internal fun RichMessageText.editorValue(selection: TextRange = TextRange(text.length), composition: TextRange? = null): TextFieldValue {
    val annotated = AnnotatedString.Builder(text).apply {
        validEntities().forEach { addStringAnnotation("message-entity:${it.kind.name}", "${it.id}:${it.value}", it.offset, it.end) }
    }.toAnnotatedString()
    return TextFieldValue(annotated, selection, composition)
}

internal fun TextFieldValue.applyFormat(kind: MessageEntityKind?, link: String = ""): TextFieldValue {
    val source = richText()
    var start = selection.min
    var end = selection.max
    if (selection.collapsed || source.slice(start, end) == null) return this
    val quoteKinds = setOf(MessageEntityKind.BLOCK_QUOTE, MessageEntityKind.EXPANDABLE_BLOCK_QUOTE)
    if (kind in quoteKinds) {
        start = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (start == 0) 0 else it + 1 }
        end = text.indexOf('\n', end).takeIf { it >= 0 } ?: text.length
    }
    val remove = source.entities.any { it.kind == kind && it.offset <= start && it.end >= end }
    val exclusive = setOf(MessageEntityKind.CODE, MessageEntityKind.PRE)
    val entities = source.validEntities().flatMap { entity ->
        val overlap = entity.offset < end && entity.end > start
        val replace = kind == null || entity.kind == kind || kind in exclusive || entity.kind in exclusive ||
            (kind in quoteKinds && entity.kind in quoteKinds)
        if (!overlap || !replace) listOf(entity) else buildList {
            if (entity.offset < start) add(entity.copy(length = start - entity.offset))
            if (entity.end > end) add(entity.copy(offset = end, length = entity.end - end))
        }
    }.toMutableList()
    if (kind != null && !remove) entities.add(MessageEntity(start, end - start, kind, link))
    return RichMessageText(text, entities).normalized().editorValue(selection)
}

internal fun displayRichText(
    content: RichMessageText, foreground: Color, accent: Color, background: Color,
    revealSpoilers: Boolean = false,
    spoilerRevealProgress: Float = if (revealSpoilers) 1f else 0f,
    onRevealSpoiler: (() -> Unit)? = null,
    onLink: ((String) -> Unit)? = null,
    onEntityLink: ((MessageEntity, String) -> Unit)? = null,
): AnnotatedString {
    val entities = content.validEntities()
    val hidden = entities.filter { it.kind == MessageEntityKind.SPOILER && !revealSpoilers }
    return AnnotatedString.Builder(content.text).apply {
        entities.forEach { entity ->
            val style = when (entity.kind) {
                MessageEntityKind.BOLD -> SpanStyle(fontWeight = FontWeight.SemiBold)
                MessageEntityKind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                MessageEntityKind.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
                MessageEntityKind.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                MessageEntityKind.CODE, MessageEntityKind.PRE -> SpanStyle(fontFamily = FontFamily.Monospace)
                MessageEntityKind.HASHTAG, MessageEntityKind.CASHTAG, MessageEntityKind.MENTION_NAME -> SpanStyle(color = accent)
                else -> SpanStyle()
            }
            addStyle(style, entity.offset, entity.end)
            val target = entity.link(content.text)
            if (target != null && hidden.none { it.offset < entity.end && it.end > entity.offset }) {
                addStyle(SpanStyle(color = accent), entity.offset, entity.end)
                if (onLink != null || onEntityLink != null) addLink(LinkAnnotation.Url(target,
                    TextLinkStyles(style = SpanStyle(color = accent),
                        hoveredStyle = SpanStyle(textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { onEntityLink?.invoke(entity, target) ?: onLink?.invoke(target) }), entity.offset, entity.end)
            }
        }
        val decorated = entities.filter { it.kind == MessageEntityKind.UNDERLINE || it.kind == MessageEntityKind.STRIKETHROUGH }
        decorated.flatMap { listOf(it.offset, it.end) }.distinct().sorted().zipWithNext().forEach { (start, end) ->
            if (decorated.any { it.kind == MessageEntityKind.UNDERLINE && it.offset <= start && it.end >= end } &&
                decorated.any { it.kind == MessageEntityKind.STRIKETHROUGH && it.offset <= start && it.end >= end })
                addStyle(SpanStyle(textDecoration = TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))), start, end)
        }
        // The native spoiler owns its hit region. Revealing it never activates a hidden link.
        hidden.forEach {
            if (onRevealSpoiler != null) addLink(LinkAnnotation.Clickable("message-spoiler:${it.offset}",
                linkInteractionListener = { onRevealSpoiler() }), it.offset, it.end)
        }
        if (spoilerRevealProgress < 1f) entities.filter { it.kind == MessageEntityKind.SPOILER }.forEach {
            addStyle(SpanStyle(color = foreground.copy(alpha = spoilerRevealProgress.coerceIn(0f, 1f)),
                background = Color.Transparent), it.offset, it.end)
        }
    }.toAnnotatedString()
}

@Composable
internal fun RichMessageBody(content: RichMessageText, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, selectable: Boolean = true,
    foreground: Color = MiuixTheme.colorScheme.onSurface, accent: Color = MiuixTheme.colorScheme.primary,
    selectionState: SelectionState? = null, allowCopy: Boolean = true,
    canCopyNow: () -> Boolean = { allowCopy }, onTextLayout: ((TextLayoutResult) -> Unit)? = null) {
    var reveal by remember(content) { mutableStateOf(false) }
    val revealProgress by animateFloatAsState(if (reveal) 1f else 0f, tween(180), label = "message-spoiler-reveal")
    var linkFailed by remember(content) { mutableStateOf(false) }
    val compactLayout = LocalDesktopChatLayout.current.compact
    val bodyFontSize = CHAT_MESSAGE_FONT_SIZE
    val bodyLineHeight = TextUnit.Unspecified
    val colors = MiuixTheme.colorScheme
    val blocks = remember(content) { content.displayBlocks().map { it.withoutBlockSeparator(content.text.length) } }
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val state = selectionState ?: remember(content) { SelectionState() }
    val currentCopyAllowed by rememberUpdatedState(canCopyNow)
    val currentLinkHandler by rememberUpdatedState(LocalMessageBodyLinks.current)
    @Composable
    fun annotated(part: RichMessageText, sourceOffset: Int = 0): AnnotatedString =
        // SelectionController compares the complete AnnotatedString, including link listeners.
        // Recreating those on an unrelated native update clears a live mouse selection and its
        // remaining drag. Only actual paragraph/appearance changes may replace the annotations.
        remember(content, part, sourceOffset, foreground, accent, colors.surfaceContainer, reveal, revealProgress,
            selectable, selectionState != null) {
            displayRichText(part, foreground, accent, colors.surfaceContainer, reveal, revealProgress,
                onRevealSpoiler = if (selectable) ({ reveal = true }) else null,
                onEntityLink = if (selectable) ({ entity, target ->
                    val originalEntity = entity.copy(offset = sourceOffset + entity.offset)
                    linkFailed = !(currentLinkHandler?.open?.invoke(originalEntity) ?: openExternalChatLink(target))
                }) else null)
                .let { if (selectionState != null) it.withQuoteSourceOffsets(sourceOffset) else it }
                .withInlineMessageEmoji(part, revealProgress >= 1f)
        }
    val body: @Composable () -> Unit = {
        Column(if (selectable && allowCopy) Modifier else modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (selectable && maxLines == Int.MAX_VALUE && blocks.any { it.kind != null }) {
                blocks.forEach { block ->
                    val text = annotated(block.content, block.sourceOffset)
                    when (block.kind) {
                        MessageEntityKind.PRE -> Column(Modifier.fillMaxWidth()
                            .chatSquircleBackground(colors.secondaryContainer, 10.dp)) {
                            DisableSelection {
                                RichCodeActions(block.language, allowCopy) {
                                    if (currentCopyAllowed()) clipboard.setText(AnnotatedString(codeBlockClipboardText(block.content.text)))
                                }
                            }
                            RichMessageTextRun(block.content,
                                rememberHighlightedCodeText(text, block.language, colors.surface.luminance() < 0.5f), foreground, revealProgress,
                                fontSize = if (compactLayout) 13.sp else 14.sp,
                                lineHeight = if (compactLayout) 18.sp else 19.sp,
                                fontFamily = FontFamily.Monospace, softWrap = false, overflow = TextOverflow.Clip,
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                    .padding(horizontal = if (compactLayout) 10.dp else 12.dp, vertical = 8.dp))
                        }
                        MessageEntityKind.BLOCK_QUOTE, MessageEntityKind.EXPANDABLE_BLOCK_QUOTE -> {
                            var expanded by remember(content, block.sourceOffset) { mutableStateOf(false) }
                            val expandable = block.kind == MessageEntityKind.EXPANDABLE_BLOCK_QUOTE
                            Column(Modifier.fillMaxWidth().animateContentSize(tween(180))
                                .chatSquircleSurface(accent.copy(alpha = 0.10f), 8.dp)
                                .then(if (expandable) Modifier.clickable(role = Role.Button,
                                    onClickLabel = if (expanded) "收起引用" else "展开引用", onClick = { expanded = !expanded }) else Modifier)
                                .padding(horizontal = 8.dp, vertical = 6.dp)) {
                                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.width(3.dp).fillMaxHeight().chatSquircleBackground(accent, 1.dp))
                                    Box(Modifier.weight(1f)) {
                                        RichMessageTextRun(block.content, text, foreground, revealProgress,
                                            fontSize = bodyFontSize, lineHeight = bodyLineHeight,
                                            maxLines = if (expandable && !expanded) OFFICIAL_COLLAPSED_QUOTE_LINES else Int.MAX_VALUE,
                                            modifier = Modifier.padding(end = if (expandable) 18.dp else 0.dp))
                                        if (expandable) DisableSelection {
                                            Text(if (expanded) "⌃" else "⌄", color = accent, fontSize = 16.sp,
                                                modifier = Modifier.align(Alignment.BottomEnd))
                                        }
                                    }
                                }
                            }
                        }
                        else -> RichMessageTextRun(block.content, text, foreground, revealProgress,
                            fontSize = bodyFontSize, lineHeight = bodyLineHeight)
                    }
                }
            } else RichMessageTextRun(content, annotated(content), foreground, revealProgress,
                fontSize = if (selectable) bodyFontSize else 12.sp, lineHeight = if (selectable) bodyLineHeight else 16.sp,
                maxLines = maxLines, onTextLayout = if (blocks.none { it.kind != null }) onTextLayout else null)
            DisableSelection {
                if (linkFailed) Text("无法打开此链接，请检查默认浏览器。", fontSize = 11.sp, color = colors.error)
            }
        }
    }
    if (selectable && allowCopy) SelectionContainer(state = state, modifier = modifier, content = body) else body()
}

// lib_ui text_renderer.h: kQuoteCollapsedLines. Limit rendered lines, never cut UTF-16 text/entities.
internal const val OFFICIAL_COLLAPSED_QUOTE_LINES = 3

internal fun codeBlockClipboardText(text: String): String = if (text.endsWith('\n')) text else "$text\n"

/** The mask is drawn over the native entity's line boxes, without a separate reveal button or layout. */
@Composable
private fun RichMessageTextRun(
    content: RichMessageText, text: AnnotatedString, foreground: Color, revealProgress: Float,
    fontSize: TextUnit, lineHeight: TextUnit, modifier: Modifier = Modifier,
    fontFamily: FontFamily? = null, softWrap: Boolean = true, maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    var layout by remember(content) { mutableStateOf<TextLayoutResult?>(null) }
    val spoilers = remember(content) { content.validEntities().filter { it.kind == MessageEntityKind.SPOILER } }
    Text(text, color = foreground, fontSize = fontSize, lineHeight = lineHeight, fontFamily = fontFamily,
        style = chatMessageTextStyle(),
        softWrap = softWrap, maxLines = maxLines, overflow = overflow,
        inlineContent = rememberInlineMessageEmojiContent(content, revealProgress >= 1f, fontSize, foreground),
        onTextLayout = { layout = it; onTextLayout?.invoke(it) }, modifier = modifier.drawWithContent {
            drawContent()
            val measured = layout
            if (measured != null && revealProgress < 1f) spoilers.forEach { entity ->
                val path = measured.getPathForRange(entity.offset, entity.end)
                clipPath(path) {
                    drawPath(path, foreground.copy(alpha = (1f - revealProgress) * 0.10f))
                    val bounds = path.getBounds()
                    val gap = 4.dp.toPx()
                    var y = bounds.top + gap / 2
                    var row = 0
                    while (y < minOf(bounds.bottom, size.height)) {
                        var x = maxOf(0f, bounds.left) + gap / 2 + if (row % 2 == 0) 0f else gap / 2
                        while (x < minOf(bounds.right, size.width)) {
                            drawCircle(foreground.copy(alpha = (1f - revealProgress) * 0.55f), 0.8.dp.toPx(), Offset(x, y))
                            x += gap
                        }
                        y += gap
                        row++
                    }
                }
            }
        })
}

internal fun RichMessageBlock.withoutBlockSeparator(sourceLength: Int): RichMessageBlock {
    if (kind != null) return this
    // Block layout supplies its own gap; consume one separator, keeping intentional blank lines and quote offsets.
    val text = content.text
    val start = if (sourceOffset > 0) when {
        text.startsWith("\r\n") -> 2
        text.startsWith('\n') || text.startsWith('\r') -> 1
        else -> 0
    } else 0
    val end = if (sourceOffset + text.length < sourceLength) when {
        text.endsWith("\r\n") -> text.length - 2
        text.endsWith('\n') || text.endsWith('\r') -> text.length - 1
        else -> text.length
    } else text.length
    if (end <= start) return this
    return copy(content = content.slice(start, end) ?: return this, sourceOffset = sourceOffset + start)
}

@Composable
private fun RichCodeActions(language: String, allowCopy: Boolean, onCopy: () -> Unit) {
    val compactLayout = LocalDesktopChatLayout.current.compact
    val fontSize = if (compactLayout) 12.sp else 13.sp
    Row(Modifier.fillMaxWidth().chatSquircleSurface(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.05f), 10.dp)
        .then(if (allowCopy) Modifier.clickable(role = Role.Button, onClickLabel = "复制代码", onClick = onCopy) else Modifier)
        .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(language.ifBlank { if (allowCopy) "复制代码" else "代码" }, fontFamily = FontFamily.Monospace, fontSize = fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        if (allowCopy && language.isNotBlank()) Text("复制", fontSize = fontSize, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
internal fun RichTextEditor(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, label: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, maxLines: Int = 4, focusRequester: FocusRequester? = null,
    onSubmit: (() -> Unit)? = null, canSubmit: Boolean = true, onEscape: (() -> Unit)? = null,
    onEditLast: (() -> Unit)? = null, onAttach: (() -> Unit)? = null, allowEmptySubmit: Boolean = false,
    compactControls: Boolean = false,
    inlineControls: Boolean = false,
    allowAttachmentPaste: Boolean = true,
    readOnly: Boolean = false,
    leadingControl: (@Composable (onToggleFormatting: () -> Unit) -> Unit)? = null,
    trailingControls: (@Composable () -> Unit)? = null,
    headerContent: (@Composable () -> Unit)? = null) {
    var formatting by remember { mutableStateOf(false) }
    var linkSelection by remember { mutableStateOf<TextFieldValue?>(null) }
    var codeSelection by remember { mutableStateOf<TextFieldValue?>(null) }
    var language by remember { mutableStateOf(TextFieldValue()) }
    var url by remember { mutableStateOf(TextFieldValue("https://")) }
    val shortcuts = remember { ComposerShortcutHandler() }
    val sendKey = LocalSettingsPreferences.current.sendKey
    val pasteShortcuts = remember { AttachmentPasteShortcuts() }
    val clipboardAttachments = LocalAttachmentClipboard.current
    val requestAttachmentPaste = LocalAttachmentPasteRequest.current
    val colors = MiuixTheme.colorScheme
    val compactLayout = LocalDesktopChatLayout.current.compact
    val compactPlaceholder = compactControls && (compactLayout || inlineControls)
    var inlineFocused by remember { mutableStateOf(false) }
    val parsed = remember(value) { value.richText().withMarkdownFormatting() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compactControls && compactLayout) 4.dp else 6.dp)) {
        Column(Modifier.fillMaxWidth().then(if (inlineControls) Modifier
            .chatGlassSurface(16.dp, shadow = false)
            .squircleBorder(1.5.dp, if (inlineFocused) colors.primary else Color.Transparent, 16.dp)
            .animateContentSize(animationSpec = androidx.compose.animation.core.tween(180), alignment = Alignment.BottomStart) else Modifier)) {
            headerContent?.invoke()
            Box(Modifier.fillMaxWidth()) {
                LocalTextInputSessionHost.current {
                    TextField(value = value, onValueChange = { next ->
                        if (readOnly) onValueChange(value.copy(selection = next.selection, composition = next.composition))
                        else onValueChange(value.richText().replaceText(next.text).editorValue(next.selection, next.composition))
                    }, label = if (compactPlaceholder) "" else label, modifier = Modifier.fillMaxWidth()
                        .then(if (inlineControls) Modifier.padding(start = 44.dp, end = 80.dp) else Modifier)
                        .then(if (inlineControls) Modifier.heightIn(min = 48.dp) else Modifier)
                        .then(if (compactPlaceholder || inlineControls) Modifier.semantics { contentDescription = label } else Modifier)
                        .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                        .onFocusChanged {
                            inlineFocused = it.isFocused
                            if (!it.isFocused) { shortcuts.reset(); pasteShortcuts.reset() }
                        }
                        .onPreviewKeyEvent { event ->
                            val paste = pasteShortcuts.handle(event.key, event.type, control = event.isCtrlPressed || event.isMetaPressed,
                                shift = event.isShiftPressed, alt = event.isAltPressed,
                                composing = value.composition != null,
                                enabled = inlineControls && allowAttachmentPaste && enabled && linkSelection == null && codeSelection == null && requestAttachmentPaste != null,
                                attachments = { runCatching { clipboardAttachments?.hasAttachments() == true }.getOrDefault(false) })
                            if (paste != AttachmentPasteAction.PASS) {
                                if (paste == AttachmentPasteAction.PASTE) requestAttachmentPaste?.invoke()
                                return@onPreviewKeyEvent true
                            }
                            when (shortcuts.handle(event.key, event.type, shift = event.isShiftPressed, ctrl = event.isCtrlPressed,
                                alt = event.isAltPressed, meta = event.isMetaPressed, composing = value.composition != null,
                                enabled = enabled && linkSelection == null && codeSelection == null,
                                hasText = value.text.isNotBlank(), allowEmptySubmit = allowEmptySubmit,
                                isEmpty = value.text.isEmpty(), canSubmit = canSubmit,
                                submitAvailable = onSubmit != null, cancelAvailable = onEscape != null,
                                editLastAvailable = onEditLast != null, attachAvailable = onAttach != null,
                                sendKey = sendKey)) {
                                ComposerShortcutAction.PASS_THROUGH -> false
                                ComposerShortcutAction.CONSUME -> true
                                ComposerShortcutAction.SUBMIT -> { onSubmit?.invoke(); true }
                                ComposerShortcutAction.CANCEL -> { onEscape?.invoke(); true }
                                ComposerShortcutAction.EDIT_LAST -> { onEditLast?.invoke(); true }
                                ComposerShortcutAction.ATTACH -> { onAttach?.invoke(); true }
                            }
                        },
                        minLines = 1, maxLines = maxLines, enabled = enabled, readOnly = readOnly,
                        insideMargin = when {
                            inlineControls -> DpSize(0.dp, 12.dp)
                            compactControls && compactLayout -> DpSize(12.dp, 9.dp)
                            compactControls -> DpSize(16.dp, 12.dp)
                            else -> TextFieldDefaults.InsideMargin
                        },
                        colors = if (inlineControls) TextFieldDefaults.textFieldColors(
                            backgroundColor = Color.Transparent, borderColor = Color.Transparent,
                        ) else TextFieldDefaults.textFieldColors(),
                        cornerRadius = if (inlineControls) 16.dp else TextFieldDefaults.CornerRadius,
                        useLabelAsPlaceholder = compactControls,
                        textStyle = if (inlineControls) MiuixTheme.textStyles.main.copy(
                            fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal,
                        ) else if (compactControls) MiuixTheme.textStyles.main.copy(fontSize = if (compactLayout) 14.sp else 15.sp)
                            else MiuixTheme.textStyles.main,
                        cursorBrush = SolidColor(colors.primary),
                        leadingIcon = if (!inlineControls && leadingControl != null) ({ leadingControl { formatting = !formatting } }) else null,
                        trailingIcon = if (inlineControls) null else trailingControls ?: if (compactControls) ({
                            ChatButton(if (formatting) "收起" else "格式", modifier = Modifier.padding(end = 6.dp),
                                enabled = enabled, onClick = { formatting = !formatting })
                        }) else null,
                        visualTransformation = { TransformedText(displayRichText(value.richText(), colors.onSurface, colors.primary,
                            colors.surfaceContainer, revealSpoilers = true), OffsetMapping.Identity) })
                }
                // ComposeControls::updateControlsGeometry anchors buttons to the bottom row,
                // independently of reply/edit headers and the field's growing text height.
                if (inlineControls) {
                    leadingControl?.let { control ->
                        Box(Modifier.align(Alignment.BottomStart).padding(bottom = 8.dp)) { control {} }
                    }
                    trailingControls?.let { control ->
                        Box(Modifier.align(Alignment.BottomEnd).padding(bottom = 8.dp)) { control() }
                    }
                }
                if (compactPlaceholder && value.text.isEmpty()) {
                    Text(label, fontSize = if (inlineControls) 15.sp else 14.sp,
                        lineHeight = if (inlineControls) 20.sp else 21.sp, fontWeight = FontWeight.Normal,
                        color = colors.onSurfaceVariantSummary,
                        modifier = Modifier.align(Alignment.CenterStart)
                            .padding(start = if (inlineControls) 44.dp else 12.dp, end = if (inlineControls) 74.dp else 68.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (!compactControls) ChatButton(if (formatting) "收起格式" else "文字格式", enabled = enabled,
            onClick = { formatting = !formatting })
        if (!inlineControls && parsed.text != value.text) Text("发送时会将 Markdown 标记转换为文字格式。", fontSize = 11.sp, color = colors.onSurfaceVariantSummary)
        if (formatting) {
            Text("先选中文字，再设置格式或链接。", fontSize = 11.sp, color = colors.onSurfaceVariantSummary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("粗体" to MessageEntityKind.BOLD, "斜体" to MessageEntityKind.ITALIC,
                    "下划线" to MessageEntityKind.UNDERLINE, "删除线" to MessageEntityKind.STRIKETHROUGH,
                    "代码" to MessageEntityKind.CODE, "引用块" to MessageEntityKind.BLOCK_QUOTE,
                    "隐藏文字" to MessageEntityKind.SPOILER).forEach { (title, kind) ->
                    ChatButton(title, enabled = enabled && !value.selection.collapsed, onClick = { onValueChange(value.applyFormat(kind)) })
                }
                ChatButton("链接", enabled = enabled && !value.selection.collapsed, onClick = { linkSelection = value; url = TextFieldValue("https://") })
                ChatButton("代码块", enabled = enabled && !value.selection.collapsed, onClick = { codeSelection = value; language = TextFieldValue() })
                ChatButton("清除格式", enabled = enabled && !value.selection.collapsed, onClick = { onValueChange(value.applyFormat(null)) })
            }
            if (parsed.text.isNotBlank()) {
                Text("发送效果预览", fontSize = 11.sp, color = colors.onSurfaceVariantSummary)
                RichMessageBody(parsed, maxLines = 4, selectable = false)
            }
        }
    }
    linkSelection?.let { captured ->
        WindowDialog(show = true, title = "添加链接", maxWidth = 480.dp, onDismissRequest = { linkSelection = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(captured.text.substring(captured.selection.min, captured.selection.max), maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextField(value = url, onValueChange = { url = it }, label = "链接地址", modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatButton("取消", onClick = { linkSelection = null })
                    ChatButton("应用", enabled = enabled && value == captured && safeExternalLink(url.text) != null, primary = true,
                        onClick = { onValueChange(captured.applyFormat(MessageEntityKind.TEXT_URL, checkNotNull(safeExternalLink(url.text)))); linkSelection = null })
                }
            }
        }
    }
    codeSelection?.let { captured ->
        WindowDialog(show = true, title = "代码块", maxWidth = 480.dp, onDismissRequest = { codeSelection = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(value = language, onValueChange = { language = it }, label = "语言（可选，例如 python）", singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatButton("取消", onClick = { codeSelection = null })
                    ChatButton("应用", primary = true, enabled = enabled && value == captured && language.text.matches(Regex("[A-Za-z0-9_+#.\\-]{0,32}")),
                        onClick = { onValueChange(captured.applyFormat(MessageEntityKind.PRE, language.text)); codeSelection = null })
                }
            }
        }
    }
}

