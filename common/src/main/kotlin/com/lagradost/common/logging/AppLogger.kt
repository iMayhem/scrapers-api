package com.lagradost.common.logging

import org.slf4j.LoggerFactory

/**
 * Centralized logging utility for CloudStream Desktop.
 * Wraps SLF4J to provide an API similar to android.util.Log,
 * making it easy to migrate existing Android code.
 */
object AppLogger {
    private val logger = LoggerFactory.getLogger("CloudStreamDesktop")

    fun d(tag: String, message: String) {
        logger.debug("[$tag] $message")
    }

    fun d(message: String) {
        logger.debug(message)
    }

    fun i(tag: String, message: String) {
        logger.info("[$tag] $message")
    }

    fun i(message: String) {
        logger.info(message)
    }

    fun w(tag: String, message: String, t: Throwable? = null) {
        if (t != null) logger.warn("[$tag] $message", t) else logger.warn("[$tag] $message")
    }

    fun w(message: String, t: Throwable? = null) {
        if (t != null) logger.warn(message, t) else logger.warn(message)
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        if (t != null) logger.error("[$tag] $message", t) else logger.error("[$tag] $message")
    }

    fun e(message: String, t: Throwable? = null) {
        if (t != null) logger.error(message, t) else logger.error(message)
    }
}
