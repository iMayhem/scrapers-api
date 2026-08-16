package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Minimal desktop-compatible CloudStreamApp.
 * Real CS3 plugins subclass this; the companion object exists so dex-transpiled
 * plugins that reference `CloudStreamApp.Companion` can load.
 */
open class CloudStreamApp : Plugin() {
    companion object {
        @JvmField
        var binding: Any? = null

        @JvmField
        var isDestroyed: Boolean = false

        fun getContext(): android.content.Context? = null
    }
}