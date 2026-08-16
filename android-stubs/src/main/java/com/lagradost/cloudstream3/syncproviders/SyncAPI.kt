package com.lagradost.cloudstream3.syncproviders

import android.content.Context

/**
 * Minimal stub of the CS3 SyncAPI so plugins can subclass it and load.
 * All operations are no-ops returning defaults.
 */
open class SyncAPI {
    open fun getName(): String = ""
    open fun getIconUrl(): String? = null
    open fun getMainUrl(): String? = null
    open fun getAniListApi(): Any? = null
    open fun getAniyomiApi(): Any? = null
    open fun getKitsuApi(): Any? = null
    open fun getKitsuUserInfo(): Any? = null
    open fun isLoggedIn(): Boolean = false
    open fun removeAccount(context: Context) {}
    open fun loadAccount(context: Context): Boolean = false
    open fun saveAccount(context: Context) {}
    open fun getExtraUserInformation(context: Context): Any? = null
}