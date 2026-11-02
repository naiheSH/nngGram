package com.badnng.moe.security

import com.badnng.moe.platform.DesktopPlatform

/** Application-activity fallback. Wall time includes system sleep; clock regressions are
 * rejected by the existing controller and lock conservatively instead of extending a lease.
 * No fabricated OS-wide last-input timestamp is supplied on unsupported native hosts.
 */
internal class ApplicationLocalLockIdleSource(private val now: () -> Long = System::currentTimeMillis) : LocalLockIdleSource {
    override fun tickMillis(): Long? = now().takeIf { it >= 0 }
    override fun lastInputDword(): Long? = null
}

internal fun desktopLocalLockIdleSource(): LocalLockIdleSource =
    if (DesktopPlatform.current.isWindows) WindowsLocalLockIdleSource() else ApplicationLocalLockIdleSource()
