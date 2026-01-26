package com.sbf.assistant

import android.content.Context
import android.os.SystemClock
import android.util.Log

class LocalModelRuntime(private val context: Context, private val settings: SettingsManager) {
    private data class LoadedModel(
        val filename: String,
        val sizeBytes: Long,
        var lastUsedMs: Long
    )

    data class LoadedModelInfo(
        val filename: String,
        val sizeBytes: Long,
        val lastUsedMs: Long
    )

    private val loaded = LinkedHashMap<String, LoadedModel>()

    @Synchronized
    fun ensureLoaded(filename: String, sizeBytes: Long): Boolean {
        cleanupIdle()
        val now = SystemClock.elapsedRealtime()
        val existing = loaded[filename]
        if (existing != null) {
            existing.lastUsedMs = now
            return true
        }

        loaded[filename] = LoadedModel(filename, sizeBytes, now)
        Log.d(TAG, "Loaded model: $filename (${sizeBytes / (1024 * 1024)}MB)")
        return true
    }

    @Synchronized
    fun markUsed(filename: String) {
        loaded[filename]?.lastUsedMs = SystemClock.elapsedRealtime()
    }

    @Synchronized
    private fun cleanupIdle() {
        val idleMs = settings.localModelIdleMs
        if (idleMs <= 0) return
        val now = SystemClock.elapsedRealtime()
        val iterator = loaded.values.iterator()
        while (iterator.hasNext()) {
            val model = iterator.next()
            if (now - model.lastUsedMs >= idleMs) {
                iterator.remove()
                Log.d(TAG, "Unloaded idle model: ${model.filename}")
            }
        }
    }

    @Synchronized
    fun evictLeastRecentlyUsed(): String? {
        val lru = loaded.values.minByOrNull { it.lastUsedMs } ?: return null
        loaded.remove(lru.filename)
        Log.w(TAG, "Evicted model due to memory pressure: ${lru.filename}")
        return lru.filename
    }

    @Synchronized
    fun getLoadedModelInfo(filename: String): LoadedModelInfo? {
        val model = loaded[filename] ?: return null
        return LoadedModelInfo(model.filename, model.sizeBytes, model.lastUsedMs)
    }

    @Synchronized
    fun isLoaded(filename: String): Boolean {
        return loaded.containsKey(filename)
    }

    @Synchronized
    fun registerLoaded(filename: String, sizeBytes: Long) {
        loaded[filename] = LoadedModel(
            filename = filename,
            sizeBytes = sizeBytes,
            lastUsedMs = SystemClock.elapsedRealtime()
        )
    }

    @Synchronized
    fun removeLoaded(filename: String) {
        loaded.remove(filename)
    }


    companion object {
        private const val TAG = "LocalModelRuntime"
    }
}
