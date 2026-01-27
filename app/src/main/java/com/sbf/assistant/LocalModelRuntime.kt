package com.sbf.assistant

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * Manages lifecycle of local models to optimize memory usage.
 * Tracks loaded models and releases idle ones automatically.
 */
class LocalModelRuntime(private val context: Context, private val settings: SettingsManager) {

    /** Callback invoked when a model needs to be released from memory. */
    fun interface ReleaseCallback {
        fun onRelease(filename: String)
    }

    private data class LoadedModel(
        val filename: String,
        val sizeBytes: Long,
        var lastUsedMs: Long,
        val releaseCallback: ReleaseCallback?
    )

    data class LoadedModelInfo(
        val filename: String,
        val sizeBytes: Long,
        val lastUsedMs: Long
    )

    private val loaded = LinkedHashMap<String, LoadedModel>()

    /**
     * Register a model as loaded with an optional release callback.
     * The callback will be invoked when the model is evicted due to idle timeout or memory pressure.
     */
    @Synchronized
    fun ensureLoaded(filename: String, sizeBytes: Long, releaseCallback: ReleaseCallback? = null): Boolean {
        cleanupIdle()
        val now = SystemClock.elapsedRealtime()
        val existing = loaded[filename]
        if (existing != null) {
            existing.lastUsedMs = now
            return true
        }

        loaded[filename] = LoadedModel(filename, sizeBytes, now, releaseCallback)
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
        val toRelease = mutableListOf<LoadedModel>()
        val iterator = loaded.values.iterator()
        while (iterator.hasNext()) {
            val model = iterator.next()
            if (now - model.lastUsedMs >= idleMs) {
                iterator.remove()
                toRelease.add(model)
                Log.d(TAG, "Unloaded idle model: ${model.filename}")
            }
        }
        // Release outside the synchronized iteration to avoid potential deadlocks
        toRelease.forEach { model ->
            try {
                model.releaseCallback?.onRelease(model.filename)
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing model: ${model.filename}", e)
            }
        }
    }

    /**
     * Evict the least recently used model to free memory.
     * Returns the filename of the evicted model, or null if no models are loaded.
     */
    @Synchronized
    fun evictLeastRecentlyUsed(): String? {
        val lru = loaded.values.minByOrNull { it.lastUsedMs } ?: return null
        loaded.remove(lru.filename)
        Log.w(TAG, "Evicted model due to memory pressure: ${lru.filename}")
        try {
            lru.releaseCallback?.onRelease(lru.filename)
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing evicted model: ${lru.filename}", e)
        }
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

    /**
     * Register an already loaded model with an optional release callback.
     */
    @Synchronized
    fun registerLoaded(filename: String, sizeBytes: Long, releaseCallback: ReleaseCallback? = null) {
        loaded[filename] = LoadedModel(
            filename = filename,
            sizeBytes = sizeBytes,
            lastUsedMs = SystemClock.elapsedRealtime(),
            releaseCallback = releaseCallback
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
