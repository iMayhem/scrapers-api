package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Minimal desktop stub of the CS3 DataStore (SharedPreferences-backed in the
 * real app). Server-side persistence is not needed; reads return defaults and
 * writes are no-ops so plugins referencing it can load and run.
 */
object DataStore {
    fun getSharedPrefs(context: Context): SharedPreferences? = null

    fun putString(key: String, value: String?) {}
    fun putInt(key: String, value: Int) {}
    fun putLong(key: String, value: Long) {}
    fun putBoolean(key: String, value: Boolean) {}
    fun putStringSet(key: String, value: Set<String>?) {}
    fun putFloat(key: String, value: Float) {}

    fun getString(key: String, default: String?): String? = default
    fun getInt(key: String, default: Int): Int = default
    fun getLong(key: String, default: Long): Long = default
    fun getBoolean(key: String, default: Boolean): Boolean = default
    fun getStringSet(key: String, default: Set<String>?): Set<String>? = default
    fun getFloat(key: String, default: Float): Float = default

    fun remove(key: String) {}
    fun clear() {}
}