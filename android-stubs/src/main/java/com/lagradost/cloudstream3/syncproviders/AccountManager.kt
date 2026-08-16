package com.lagradost.cloudstream3.syncproviders

import android.content.Context
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi

/**
 * Minimal stub so plugins referencing AccountManager load on the JVM.
 * Accounts are not supported server-side; operations are no-ops.
 * Provides both `AccountManager.Companion` and `AccountManager.INSTANCE`
 * access patterns used by different plugin generations.
 */
class AccountManager private constructor() {
    fun removeAccounts(context: Context) {}
    fun getAccount(context: Context): Any? = null
    fun saveAccount(context: Context, account: Any?) {}

    companion object {
        @JvmField
        val INSTANCE: AccountManager = AccountManager()

        fun removeAccounts(context: Context) {}
        fun getAccount(context: Context): Any? = null
        fun saveAccount(context: Context, account: Any?) {}
        fun getAniListApi(): AniListApi? = null
    }
}