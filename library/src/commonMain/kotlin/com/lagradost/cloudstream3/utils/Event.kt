package com.lagradost.cloudstream3.utils

/** Minimal CS3 Event stub so plugins referencing it can load. */
class Event<T> {
    private val listeners = mutableListOf<(T?) -> Unit>()

    fun subscribe(listener: (T?) -> Unit) {
        listeners.add(listener)
    }

    operator fun plusAssign(listener: (T?) -> Unit) {
        subscribe(listener)
    }

    fun unsubscribe(listener: (T?) -> Unit) {
        listeners.remove(listener)
    }

    fun emit(value: T?) {
        listeners.toList().forEach { it(value) }
    }
}