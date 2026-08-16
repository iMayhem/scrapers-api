package com.lagradost.runtime.loader

class SafePluginClassLoader(parent: ClassLoader) : ClassLoader(parent) {
    private val ghostCache = java.util.concurrent.ConcurrentHashMap<String, Class<*>>()

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // Prevent loading dangerous packages directly from the plugin bytecode
        if (isBlocked(name)) {
            throw SecurityException("Security Sandbox: Access to class '$name' is blocked.")
        }
        // Old plugins were compiled against kotlinx-coroutines that still had the
        // internal BuildersKt.runBlockingK, removed in newer versions. Provide a
        // compat BuildersKt that forwards to the real implementation.
        if (name == "kotlinx.coroutines.BuildersKt") {
            val real = try { super.loadClass(name, false) } catch (e: ClassNotFoundException) { null }
            if (real != null) {
                if (real.declaredMethods.none { it.name == "runBlockingK" }) {
                    return defineBuildersKtCompat(real)
                }
                return real
            }
        }
        // Older kotlinc mangled inline functions as name_<suffix> (underscore);
        // newer kotlinx-coroutines ships name-<suffix> (hyphen). Expose the old
        // underscore spellings as aliases on a generated compat class.
        if (name.startsWith("kotlinx.coroutines.")) {
            val real = try { super.loadClass(name, false) } catch (e: ClassNotFoundException) { null }
            if (real != null) {
                val hyphenMethods = real.declaredMethods.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.name.contains('-') }
                if (hyphenMethods.isNotEmpty()) {
                    return defineMangledCompat(real, name.replace('.', '/'), hyphenMethods)
                }
                return real
            }
        }
        return try {
            super.loadClass(name, resolve)
        } catch (e: ClassNotFoundException) {
            // If the plugin requests an Android API or CloudStream API that we haven't stubbed, generate a ghost stub
            if (name.startsWith("android.") || name.startsWith("androidx.") || name.startsWith("com.android.") || name.startsWith("com.lagradost.") || name.startsWith("com.google.")) {
                generateGhostStub(name)
            } else {
                throw e
            }
        }
    }

    // ── kotlinx.coroutines compat layer ──────────────────────────────────
    private fun defineBuildersKtCompat(real: Class<*>): Class<*> {
        val internalName = "kotlinx/coroutines/BuildersKt"
        val cw = newCompatClassWriter(internalName)
        for (m in real.declaredMethods) {
            if (!java.lang.reflect.Modifier.isStatic(m.modifiers) || m.name == "runBlockingK") continue
            emitCompatForwarder(cw, internalName, m)
        }
        emitRunBlockingK(cw)
        emitRunBlockingKDefault(cw)
        return finishCompatClass(internalName, cw)
    }

    private fun defineMangledCompat(real: Class<*>, internalName: String, hyphenMethods: List<java.lang.reflect.Method>): Class<*> {
        val cw = newCompatClassWriter(internalName)
        for (m in real.declaredMethods) {
            if (!java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
            emitCompatForwarder(cw, internalName, m)
        }
        for (m in hyphenMethods) {
            emitUnderscoreAlias(cw, m)
        }
        return finishCompatClass(internalName, cw)
    }

    private fun emitUnderscoreAlias(cw: org.objectweb.asm.ClassWriter, m: java.lang.reflect.Method) {
        val argTypes = m.parameterTypes.map { org.objectweb.asm.Type.getType(it) }.toTypedArray()
        val retType = org.objectweb.asm.Type.getType(m.returnType)
        val desc = org.objectweb.asm.Type.getMethodDescriptor(retType, *argTypes)

        val mv = cw.visitMethod(
            org.objectweb.asm.Opcodes.ACC_PUBLIC + org.objectweb.asm.Opcodes.ACC_STATIC,
            m.name.replace('-', '_'), desc, null, null
        )
        mv.visitCode()
        var slot = 0
        for (t in argTypes) {
            mv.visitVarInsn(loadOpcode(t), slot)
            if (t.sort == org.objectweb.asm.Type.LONG || t.sort == org.objectweb.asm.Type.DOUBLE) slot += 2 else slot++
        }
        mv.visitMethodInsn(
            org.objectweb.asm.Opcodes.INVOKESTATIC,
            "kotlinx/coroutines/" + m.declaringClass.simpleName,
            m.name,
            desc,
            false
        )
        when (retType.sort) {
            org.objectweb.asm.Type.VOID -> mv.visitInsn(org.objectweb.asm.Opcodes.RETURN)
            org.objectweb.asm.Type.LONG -> mv.visitInsn(org.objectweb.asm.Opcodes.LRETURN)
            org.objectweb.asm.Type.DOUBLE -> mv.visitInsn(org.objectweb.asm.Opcodes.DRETURN)
            org.objectweb.asm.Type.FLOAT -> mv.visitInsn(org.objectweb.asm.Opcodes.FRETURN)
            org.objectweb.asm.Type.INT -> mv.visitInsn(org.objectweb.asm.Opcodes.IRETURN)
            org.objectweb.asm.Type.BOOLEAN -> mv.visitInsn(org.objectweb.asm.Opcodes.IRETURN)
            else -> mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN)
        }
        mv.visitMaxs(slot.coerceAtLeast(1), slot.coerceAtLeast(1))
        mv.visitEnd()
    }

    private fun newCompatClassWriter(internalName: String): org.objectweb.asm.ClassWriter {
        val cw = org.objectweb.asm.ClassWriter(0)
        cw.visit(
            org.objectweb.asm.Opcodes.V1_8,
            org.objectweb.asm.Opcodes.ACC_PUBLIC + org.objectweb.asm.Opcodes.ACC_FINAL,
            internalName,
            null,
            "java/lang/Object",
            null
        )

        // private constructor
        val ctor = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PRIVATE, "<init>", "()V", null, null)
        ctor.visitCode()
        ctor.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
        ctor.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        ctor.visitInsn(org.objectweb.asm.Opcodes.RETURN)
        ctor.visitMaxs(1, 1)
        ctor.visitEnd()
        return cw
    }

    private fun finishCompatClass(internalName: String, cw: org.objectweb.asm.ClassWriter): Class<*> {
        cw.visitEnd()
        val bytecode = cw.toByteArray()
        val clazz = defineClass(internalName.replace('/', '.'), bytecode, 0, bytecode.size)
        compatCache[internalName] = clazz
        return clazz
    }

    private fun emitRunBlockingK(cw: org.objectweb.asm.ClassWriter) {
        val desc = "(Lkotlin/coroutines/CoroutineContext;Lkotlin/jvm/functions/Function2;)Ljava/lang/Object;"
        val mv = cw.visitMethod(
            org.objectweb.asm.Opcodes.ACC_PUBLIC + org.objectweb.asm.Opcodes.ACC_STATIC,
            "runBlockingK", desc, null, null
        )
        mv.visitCode()
        mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
        mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1)
        mv.visitMethodInsn(
            org.objectweb.asm.Opcodes.INVOKESTATIC,
            "com/lagradost/runtime/loader/CoroutinesCompat",
            "runBlocking",
            "(Lkotlin/coroutines/CoroutineContext;Lkotlin/jvm/functions/Function2;)Ljava/lang/Object;",
            false
        )
        mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN)
        mv.visitMaxs(2, 2)
        mv.visitEnd()
    }

    private fun emitRunBlockingKDefault(cw: org.objectweb.asm.ClassWriter) {
        // Kotlin default-args wrapper referenced by dex-transpiled plugins:
        // runBlockingK$default(CoroutineContext, Function2, int, Object)
        val desc = "(Lkotlin/coroutines/CoroutineContext;Lkotlin/jvm/functions/Function2;ILjava/lang/Object;)Ljava/lang/Object;"
        val mv = cw.visitMethod(
            org.objectweb.asm.Opcodes.ACC_PUBLIC + org.objectweb.asm.Opcodes.ACC_STATIC,
            "runBlockingK\$default", desc, null, null
        )
        mv.visitCode()
        mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
        mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1)
        mv.visitMethodInsn(
            org.objectweb.asm.Opcodes.INVOKESTATIC,
            "com/lagradost/runtime/loader/CoroutinesCompat",
            "runBlocking",
            "(Lkotlin/coroutines/CoroutineContext;Lkotlin/jvm/functions/Function2;)Ljava/lang/Object;",
            false
        )
        mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN)
        mv.visitMaxs(2, 4)
        mv.visitEnd()
    }

    private fun emitCompatForwarder(cw: org.objectweb.asm.ClassWriter, ownerInternalName: String, m: java.lang.reflect.Method) {
        val op = org.objectweb.asm.Opcodes.ACC_PUBLIC + org.objectweb.asm.Opcodes.ACC_STATIC
        val argTypes = m.parameterTypes.map { org.objectweb.asm.Type.getType(it) }.toTypedArray()
        val retType = org.objectweb.asm.Type.getType(m.returnType)
        val desc = org.objectweb.asm.Type.getMethodDescriptor(retType, *argTypes)

        val mv = cw.visitMethod(op, m.name, desc, null, null)
        mv.visitCode()

        // push owner, methodName, desc first (invokeStatic(owner, name, desc, args))
        mv.visitLdcInsn(ownerInternalName.replace('/', '.'))
        mv.visitLdcInsn(m.name)
        mv.visitLdcInsn(desc)

        // build Object[] args array (last parameter, stays on top of the stack)
        mv.visitLdcInsn(argTypes.size)
        mv.visitTypeInsn(org.objectweb.asm.Opcodes.ANEWARRAY, "java/lang/Object")
        var slot = 0
        for (i in argTypes.indices) {
            val t = argTypes[i]
            mv.visitInsn(org.objectweb.asm.Opcodes.DUP)
            mv.visitLdcInsn(i)
            mv.visitVarInsn(loadOpcode(t), slot)
            if (t.sort == org.objectweb.asm.Type.LONG || t.sort == org.objectweb.asm.Type.DOUBLE) slot += 2 else slot++
            if (t.sort != org.objectweb.asm.Type.OBJECT && t.sort != org.objectweb.asm.Type.ARRAY) {
                val boxed = boxInternalName(t)
                mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, boxed, "valueOf", "(" + t.descriptor + ")L" + boxed + ";", false)
            }
            mv.visitInsn(org.objectweb.asm.Opcodes.AASTORE)
        }

        mv.visitMethodInsn(
            org.objectweb.asm.Opcodes.INVOKESTATIC,
            "com/lagradost/runtime/loader/CoroutinesCompat",
            "invokeStatic",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;",
            false
        )

        when (retType.sort) {
            org.objectweb.asm.Type.VOID -> mv.visitInsn(org.objectweb.asm.Opcodes.POP)
            org.objectweb.asm.Type.BOOLEAN -> { mv.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, "java/lang/Boolean"); mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false); mv.visitInsn(org.objectweb.asm.Opcodes.IRETURN) }
            org.objectweb.asm.Type.BYTE -> { mv.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, "java/lang/Byte"); mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false); mv.visitInsn(org.objectweb.asm.Opcodes.IRETURN) }
            org.objectweb.asm.Type.CHAR -> { mv.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, "java/lang/Character"); mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false); mv.visitInsn(org.objectweb.asm.Opcodes.IRETURN) }
            org.objectweb.asm.Type.SHORT -> { mv.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, "java/lang/Short"); mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false); mv.visitInsn(org.objectweb.asm.Opcodes.IRETURN) }
            org.objectweb.asm.Type.INT -> { mv.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, "java/lang/Integer"); mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false); mv.visitInsn(org.objectweb.asm.Opcodes.IRETURN) }
            org.objectweb.asm.Type.LONG -> { mv.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, "java/lang/Long"); mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false); mv.visitInsn(org.objectweb.asm.Opcodes.LRETURN) }
            org.objectweb.asm.Type.FLOAT -> { mv.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, "java/lang/Float"); mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false); mv.visitInsn(org.objectweb.asm.Opcodes.FRETURN) }
            org.objectweb.asm.Type.DOUBLE -> { mv.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, "java/lang/Double"); mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false); mv.visitInsn(org.objectweb.asm.Opcodes.DRETURN) }
            else -> {
                if (retType.sort == org.objectweb.asm.Type.OBJECT && retType.internalName != "java/lang/Object") {
                    mv.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, retType.internalName)
                } else if (retType.sort == org.objectweb.asm.Type.ARRAY) {
                    mv.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, retType.descriptor)
                }
                mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN)
            }
        }
        mv.visitMaxs(argTypes.size * 2 + 6, slot)
        mv.visitEnd()
    }

    private fun loadOpcode(t: org.objectweb.asm.Type): Int = when (t.sort) {
        org.objectweb.asm.Type.BOOLEAN, org.objectweb.asm.Type.BYTE, org.objectweb.asm.Type.CHAR,
        org.objectweb.asm.Type.SHORT, org.objectweb.asm.Type.INT -> org.objectweb.asm.Opcodes.ILOAD
        org.objectweb.asm.Type.LONG -> org.objectweb.asm.Opcodes.LLOAD
        org.objectweb.asm.Type.FLOAT -> org.objectweb.asm.Opcodes.FLOAD
        org.objectweb.asm.Type.DOUBLE -> org.objectweb.asm.Opcodes.DLOAD
        else -> org.objectweb.asm.Opcodes.ALOAD
    }

    private fun boxInternalName(t: org.objectweb.asm.Type): String = when (t.sort) {
        org.objectweb.asm.Type.BOOLEAN -> "java/lang/Boolean"
        org.objectweb.asm.Type.BYTE -> "java/lang/Byte"
        org.objectweb.asm.Type.CHAR -> "java/lang/Character"
        org.objectweb.asm.Type.SHORT -> "java/lang/Short"
        org.objectweb.asm.Type.INT -> "java/lang/Integer"
        org.objectweb.asm.Type.LONG -> "java/lang/Long"
        org.objectweb.asm.Type.FLOAT -> "java/lang/Float"
        org.objectweb.asm.Type.DOUBLE -> "java/lang/Double"
        else -> "java/lang/Object"
    }

    companion object {
        private val compatCache = java.util.concurrent.ConcurrentHashMap<String, Class<*>>()
    }

    private fun generateGhostStub(name: String): Class<*> {
        ghostCache[name]?.let { return it }

        println("[GhostStub] Dynamically generated stub for missing Android API: $name")

        val internalName = name.replace('.', '/')
        val cw = org.objectweb.asm.ClassWriter(0)

        // Heuristic to detect if it's supposed to be an interface
        val isInterface = name.endsWith("Listener") || name.endsWith("Callback") || name.endsWith("Observer") || name.contains("\$On")

        val access = if (isInterface) {
            org.objectweb.asm.Opcodes.ACC_PUBLIC + org.objectweb.asm.Opcodes.ACC_ABSTRACT + org.objectweb.asm.Opcodes.ACC_INTERFACE
        } else {
            org.objectweb.asm.Opcodes.ACC_PUBLIC + org.objectweb.asm.Opcodes.ACC_SUPER
        }

        cw.visit(
            org.objectweb.asm.Opcodes.V1_8,
            access,
            internalName,
            null,
            "java/lang/Object",
            null,
        )

        if (!isInterface) {
            // default constructor
            val mv1 = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
            mv1.visitCode()
            mv1.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
            mv1.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            mv1.visitInsn(org.objectweb.asm.Opcodes.RETURN)
            mv1.visitMaxs(1, 1)
            mv1.visitEnd()

            // constructor(Context)
            val mv2 = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "(Landroid/content/Context;)V", null, null)
            mv2.visitCode()
            mv2.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
            mv2.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            mv2.visitInsn(org.objectweb.asm.Opcodes.RETURN)
            mv2.visitMaxs(1, 2)
            mv2.visitEnd()

            // constructor(Context, AttributeSet)
            val mv3 = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "(Landroid/content/Context;Landroid/util/AttributeSet;)V", null, null)
            mv3.visitCode()
            mv3.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
            mv3.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            mv3.visitInsn(org.objectweb.asm.Opcodes.RETURN)
            mv3.visitMaxs(1, 3)
            mv3.visitEnd()

            // constructor(Context, AttributeSet, int)
            val mv4 = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "(Landroid/content/Context;Landroid/util/AttributeSet;I)V", null, null)
            mv4.visitCode()
            mv4.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
            mv4.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            mv4.visitInsn(org.objectweb.asm.Opcodes.RETURN)
            mv4.visitMaxs(1, 4)
            mv4.visitEnd()

            // constructor(int) - Used by ColorDrawable and similar resource-based constructors
            val mv5 = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null)
            mv5.visitCode()
            mv5.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
            mv5.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            mv5.visitInsn(org.objectweb.asm.Opcodes.RETURN)
            mv5.visitMaxs(1, 2)
            mv5.visitEnd()
        }

        cw.visitEnd()

        val bytecode = cw.toByteArray()
        val clazz = defineClass(name, bytecode, 0, bytecode.size)
        ghostCache[name] = clazz
        return clazz
    }

    private fun isBlocked(name: String): Boolean {
        // Block file system access, but allow benign streams/readers/writers
        if (name.startsWith("java.io.")) {
            val safeIo = setOf(
                "java.io.File",
                "java.io.FileInputStream",
                "java.io.FileOutputStream",
                "java.io.FileReader",
                "java.io.FileWriter",
                "java.io.InputStream",
                "java.io.OutputStream",
                "java.io.ByteArrayInputStream",
                "java.io.ByteArrayOutputStream",
                "java.io.StringReader",
                "java.io.StringWriter",
                "java.io.InputStreamReader",
                "java.io.OutputStreamWriter",
                "java.io.BufferedReader",
                "java.io.BufferedWriter",
                "java.io.IOException",
                "java.io.EOFException",
                "java.io.FileNotFoundException",
                "java.io.InterruptedIOException",
                "java.io.UnsupportedEncodingException",
                "java.io.FilterInputStream",
                "java.io.FilterOutputStream",
                "java.io.BufferedInputStream",
                "java.io.BufferedOutputStream",
                "java.io.DataInputStream",
                "java.io.DataOutputStream",
                "java.io.Reader",
                "java.io.Writer",
                "java.io.Serializable",
                "java.io.Closeable",
                "java.io.PrintStream",
                "java.io.PrintWriter",
                "java.io.ObjectStreamException"
            )
            if (!safeIo.contains(name)) {
                return true
            }
        }

        // Allow NIO file APIs (plugins like Ultima download plugins at runtime),
        // but keep blocking raw channels/sockets and other unsafe NIO.
        if (name.startsWith("java.nio.")) {
            val safeNio = setOf(
                "java.nio.file.Files",
                "java.nio.file.Path",
                "java.nio.file.Paths",
                "java.nio.file.StandardCopyOption",
                "java.nio.file.StandardOpenOption",
                "java.nio.file.OpenOption",
                "java.nio.file.CopyOption",
                "java.nio.file.LinkOption",
                "java.nio.file.FileVisitResult",
                "java.nio.file.FileVisitor",
                "java.nio.file.SimpleFileVisitor",
                "java.nio.file.attribute.FileAttribute",
                "java.nio.file.attribute.BasicFileAttributes",
                "java.nio.file.attribute.FileTime",
                "java.nio.file.attribute.PosixFilePermission",
                "java.nio.file.attribute.PosixFilePermissions"
            )
            if (name.startsWith("java.nio.file.") && name in safeNio) {
                // allowed
            } else if (!name.startsWith("java.nio.charset.") && !name.contains("Buffer")) {
                return true
            }
        }

        // Block OS command execution
        if (name == "java.lang.ProcessBuilder") {
            return true
        }

        // Allow benign reflection (plugins like Ultima read their own fields),
        // keep blocking proxies/invocation handlers and the rest
        if (name.startsWith("java.lang.reflect.")) {
            val safeReflect = setOf(
                "java.lang.reflect.Field",
                "java.lang.reflect.Method",
                "java.lang.reflect.Constructor",
                "java.lang.reflect.Modifier",
                "java.lang.reflect.Array",
                "java.lang.reflect.Parameter",
                "java.lang.reflect.Type",
                "java.lang.reflect.GenericDeclaration",
                "java.lang.reflect.AnnotatedElement"
            )
            if (!safeReflect.contains(name)) {
                return true
            }
        }

        // Block method handles but ALLOW LambdaMetafactory and StringConcatFactory required for Java 8+ lambdas
        if (name.startsWith("java.lang.invoke.")) {
            val safeInvoke = setOf(
                "java.lang.invoke.LambdaMetafactory",
                "java.lang.invoke.MethodHandles",
                "java.lang.invoke.MethodHandles\$Lookup",
                "java.lang.invoke.MethodType",
                "java.lang.invoke.CallSite",
                "java.lang.invoke.ConstantCallSite",
                "java.lang.invoke.MutableCallSite",
                "java.lang.invoke.VolatileCallSite",
                "java.lang.invoke.StringConcatFactory",
                "java.lang.invoke.TypeDescriptor",
                "java.lang.invoke.TypeDescriptor\$OfField",
                "java.lang.invoke.TypeDescriptor\$OfMethod"
            )
            if (!safeInvoke.contains(name)) {
                return true
            }
        }

        // Block compiler and unsafe memory access
        if (name.startsWith("sun.misc.") || name.startsWith("jdk.internal.") || name.startsWith("sun.reflect.")) {
            return true
        }

        // Block raw sockets
        if (name == "java.net.Socket" || name == "java.net.ServerSocket" || name == "java.net.DatagramSocket") {
            return true
        }

        return false
    }
}
