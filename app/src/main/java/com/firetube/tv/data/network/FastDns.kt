package com.firetube.tv.data.network

import android.util.Log
import okhttp3.Dns
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Fire TV 向け超軽量インメモリ DNS キャッシュ
 * - 名前解決結果を 10 分間メモリ保持
 * - i.ytimg.com, www.youtube.com, googlevideo.com 等の毎回の名前解決 (50〜200ms) を 0ms に短縮
 * - スレッドセーフかつ低RAM環境に配慮（不要なオブジェクト生成を抑制）
 */
object FastDns : Dns {

    private const val TAG = "FastDns"
    private const val TTL_MS = 10 * 60 * 1000L // 10分間

    private data class CachedDns(
        val addresses: List<InetAddress>,
        val timestamp: Long
    )

    private val cache = ConcurrentHashMap<String, CachedDns>()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        val cached = cache[hostname]
        if (cached != null && (now - cached.timestamp) < TTL_MS) {
            return cached.addresses
        }

        return try {
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.isNotEmpty()) {
                cache[hostname] = CachedDns(addresses, now)
            }
            addresses
        } catch (e: Exception) {
            // キャッシュが期限切れでも手元にあればフォールバックとして活用
            cached?.addresses ?: throw e
        }
    }

    fun clearCache() {
        cache.clear()
    }
}
