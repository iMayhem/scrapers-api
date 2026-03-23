package com.moovie.plugins

import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.net.URI

object Redis {
    private var pool: JedisPool? = null

    init {
        val redisUrl = System.getenv("REDIS_URL")
        if (!redisUrl.isNullOrBlank()) {
            try {
                val uri = URI(redisUrl)
                val poolConfig = JedisPoolConfig().apply {
                    maxTotal = 20
                    maxIdle = 10
                    minIdle = 2
                }
                pool = JedisPool(poolConfig, uri)
                println("Redis connection pool initialized successfully.")
            } catch (e: Exception) {
                println("Failed to initialize Redis pool: ${e.message}")
            }
        } else {
            println("REDIS_URL environment variable not found. Redis caching will be disabled.")
        }
    }

    fun get(key: String): String? {
        if (pool == null) return null
        return try {
            pool!!.resource.use { jedis ->
                jedis.get(key)
            }
        } catch (e: Exception) {
            println("Redis GET error for key \$key: \${e.message}")
            null
        }
    }

    fun set(key: String, value: String, ttlSeconds: Int): Boolean {
        if (pool == null) return false
        return try {
            pool!!.resource.use { jedis ->
                jedis.setex(key, ttlSeconds.toLong(), value)
                true
            }
        } catch (e: Exception) {
            println("Redis SET error for key \$key: \${e.message}")
            false
        }
    }
    
    fun isEnabled(): Boolean = pool != null
}
