package com.badnng.moe

/** Tao callbacks need not inherit the launch thread context class loader. */
internal object DesktopBranding {
    val windowIcon by lazy {
        checkNotNull(DesktopBranding::class.java.getResourceAsStream("/branding/nnggram.png"))
            .use { androidx.compose.ui.res.loadImageBitmap(it) }
    }
}
