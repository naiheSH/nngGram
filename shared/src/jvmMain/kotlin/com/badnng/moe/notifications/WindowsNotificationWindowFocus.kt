package com.badnng.moe.notifications

import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/** Called only for an actual accepted notification activation; never during capability checks. */
internal fun focusWindowsNotificationOwner(hwnd: Long): Boolean {
    if (hwnd == 0L || !com.badnng.moe.platform.DesktopPlatform.current.isWindows) return false
    return runCatching {
        val user = NativeLibrary.getInstance("user32")
        val window = Pointer(hwnd)
        if (user.getFunction("IsIconic").invokeInt(arrayOf(window)) != 0)
            user.getFunction("ShowWindow").invokeInt(arrayOf(window, 9))
        user.getFunction("SetForegroundWindow").invokeInt(arrayOf(window)) != 0
    }.getOrDefault(false)
}
