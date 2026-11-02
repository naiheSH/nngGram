package com.badnng.moe.security

import com.sun.jna.Pointer

/** The fake ABI invokes the Kotlin callback directly; it never calls a foreign function.
 * A fabricated pointer lets the state-machine/structure tests run on POSIX without
 * asking libffi to construct a Windows stdcall trampoline there.
 */
internal fun syntheticWindowsResumeHost(access: WindowsLocalLockResumeAccess, supported: Boolean = true) =
    LocalLockWindowsResumeHost(access, supported, callbackAddress = { Pointer(1L) })
