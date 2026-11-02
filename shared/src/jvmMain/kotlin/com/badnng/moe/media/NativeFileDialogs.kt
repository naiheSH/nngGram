package com.badnng.moe.media

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogParent
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker

/** The borrowed native handle belongs to the enclosing Tao window. */
val LocalNativeDialogOwner = staticCompositionLocalOf<Long?> { null }

/** An in-memory attachment-picker boundary lets regressions avoid opening a system window. */
internal val LocalNativeFilePicker = staticCompositionLocalOf<suspend (String, Set<String>?, Long?) -> String?> { ::pickNativeFile }
internal val LocalNativeDirectoryPicker = staticCompositionLocalOf<suspend (Long?) -> String?> { ::pickNativeDirectory }
internal val LocalNativeSavePicker = staticCompositionLocalOf<suspend (String, Long?) -> String?> { ::pickNativeSaveFile }

internal suspend fun pickNativeFile(title: String, extensions: Set<String>?, owner: Long?): String? =
    FileKit.openFilePicker(
        type = FileKitType.File(extensions),
        dialogSettings = nativeDialogSettings(title, owner),
    )?.file?.absolutePath

internal suspend fun pickNativeSaveFile(name: String, owner: Long?): String? =
    FileKit.openFileSaver(
        suggestedName = SavedMediaFiles.safeDownloadName(name),
        defaultExtension = null,
        dialogSettings = nativeDialogSettings("另存为", owner),
    )?.file?.absolutePath

internal suspend fun pickNativeDirectory(owner: Long?): String? = FileKit.openDirectoryPicker(
    dialogSettings = nativeDialogSettings("选择下载目录", owner),
)?.file?.absolutePath

private fun nativeDialogSettings(title: String, owner: Long?) = FileKitDialogSettings(
    title = title,
    parent = owner?.takeIf { it != 0L && System.getProperty("os.name").startsWith("Windows") }
        ?.let(FileKitDialogParent::windows),
)
