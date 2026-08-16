package com.lagradost.cloudstream3.syncproviders

import android.content.Context

/**
 * Minimal stub so plugins referencing AccountManager load on the JVM.
 * Accounts are not supported server-side; operations are no-ops.
 */
object AccountManager {
    fun removeAccounts(context: Context) {}
    fun getAccount(context: Context): Any? = null
    fun saveAccount(context: Context, account: Any?) {}

    object Companion
}