package com.lagradost.runtime.loader

import kotlin.coroutines.CoroutineContext
import kotlin.jvm.functions.Function2

/**
 * Bridge used by the dynamically generated `kotlinx.coroutines.BuildersKt`
 * compat class (see SafePluginClassLoader). Because this class is loaded by
 * the parent (server) classloader, `Class.forName` and reflection here resolve
 * against the REAL kotlinx-coroutines classes on the server classpath.
 */
object CoroutinesCompat {

    @JvmStatic
    fun invokeStatic(owner: String, methodName: String, desc: String, args: Array<Any?>): Any? {
        val cls = Class.forName(owner)
        val paramCount = org.objectweb.asm.Type.getArgumentTypes(desc).size
        val method = cls.methods.firstOrNull { it.name == methodName && it.parameterCount == paramCount }
            ?: throw NoSuchMethodError("$owner.$methodName$desc")
        return method.invoke(null, *args)
    }

    @JvmStatic
    fun runBlocking(context: CoroutineContext?, block: Function2<*, *, *>): Any? {
        val cls = Class.forName("kotlinx.coroutines.BuildersKt")
        val method = cls.getMethod("runBlocking", CoroutineContext::class.java, Function2::class.java)
        // Kotlin default-arg calls can pass a null context; translate to EmptyCoroutineContext
        val effectiveContext: CoroutineContext = context ?: kotlin.coroutines.EmptyCoroutineContext
        return method.invoke(null, effectiveContext, block)
    }
}