package com.lagradost.cloudstream3.syncproviders

import android.content.Context

/**
 * Minimal stub of the CS3 SyncRepo. The constructor must accept a SyncAPI
 * (that is what failed plugin loading before); everything else is a no-op.
 */
class SyncRepo(private val syncApi: SyncAPI?) {
    fun getSyncApi(): SyncAPI? = syncApi
    fun getAccount(context: Context): Any? = null
    fun loadAccount(context: Context): Boolean = false
    fun saveAccount(context: Context) {}
    fun removeAccount(context: Context) {}
    fun isLoggedIn(): Boolean = false
}