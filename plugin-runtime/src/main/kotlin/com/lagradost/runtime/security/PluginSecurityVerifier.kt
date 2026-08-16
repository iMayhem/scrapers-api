package com.lagradost.runtime.security

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import com.lagradost.common.logging.AppLogger
import java.io.File
import java.util.zip.ZipFile

object PluginSecurityVerifier {

    @Throws(SecurityException::class)
    fun verifyJar(jarFile: File) {
        ZipFile(jarFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.endsWith(".class")) {
                    zip.getInputStream(entry).use { input ->
                        val reader = ClassReader(input)
                        val classNode = ClassNode()
                        reader.accept(classNode, 0)

                        for (method in classNode.methods) {
                            for (insn in method.instructions) {
                                if (insn is MethodInsnNode) {
                                    val owner = insn.owner // internal name e.g. java/lang/Runtime

                                    // Block dangerous class owners outright
                                    // (java/io/File is allowed: plugins like Ultima write plugin
                                    // downloads at runtime, and the runtime classloader gates the rest)
                                    if (owner == "java/lang/ProcessBuilder" ||
                                        owner.startsWith("java/lang/invoke/")
                                    ) {
                                        throw SecurityException("Security Sandbox: Potentially unsafe code detected in class ${classNode.name} method ${method.name}. Illegal invocation: $owner.${insn.name}")
                                    }

                                    // Fallback block for dangerous Runtime calls (in case the bytecode transformer missed them)
                                    if (owner == "java/lang/Runtime") {
                                        if (insn.name == "exec" || insn.name == "loadLibrary" || insn.name == "load" || insn.name == "exit" || insn.name == "halt") {
                                            throw SecurityException("Security Sandbox: Potentially unsafe code detected in class ${classNode.name} method ${method.name}. Illegal invocation: $owner.${insn.name}")
                                        }
                                    }

                                    // Allow raw URL.openStream()/openConnection() — plugins like IStreamFlare
                                    // legitimately resolve CDN redirects with them. The runtime
                                    // classloader still blocks raw sockets.
                                    if (owner == "java/net/URL") {
                                        if (insn.name == "openStream" || insn.name == "openConnection") {
                                            AppLogger.i("Allowed URL.${insn.name}() in ${classNode.name} (CDN redirect resolution)")
                                        }
                                    }

                                    // GAP FIX #2: Block Class.forName(String, boolean, ClassLoader)
                                    // The 3-arg version lets a plugin supply ANY classloader, bypassing
                                    // SafePluginClassLoader entirely and loading blocked classes freely.
                                    // The 1-arg version is safe (uses the calling class's own loader which
                                    // goes through SafePluginClassLoader), so we leave it allowed.
                                    if (owner == "java/lang/Class" && insn.name == "forName") {
                                        // Distinguish by descriptor: 3-arg version has descriptor
                                        // (Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;
                                        if (insn.desc.contains("ClassLoader")) {
                                            throw SecurityException("Security Sandbox: Illegal Class.forName(String, boolean, ClassLoader) in ${classNode.name}. ClassLoader injection is not permitted.")
                                        }
                                    }

                                    // GAP FIX #3 (relaxed): raw HttpURLConnection / URLConnection — plugins like
                                    // IStreamFlare legitimately use them for CDN redirect resolution.
                                    if (owner == "java/net/HttpURLConnection" ||
                                        owner == "java/net/URLConnection" ||
                                        owner == "javax/net/ssl/HttpsURLConnection"
                                    ) {
                                        AppLogger.i("Allowed raw HTTP connection in ${classNode.name} (CDN redirect resolution)")
                                    }

                                    // Block specific dangerous System calls
                                    if (owner == "java/lang/System") {
                                        if (insn.name == "exit" || insn.name == "loadLibrary" || insn.name == "load" || insn.name == "setSecurityManager") {
                                            throw SecurityException("Security Sandbox: Potentially unsafe code detected in class ${classNode.name}. Illegal System call: ${insn.name}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
