package com.sbf.assistant

import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PermissionController(private val activity: AppCompatActivity) {
    private var permissionResult: CompletableDeferred<Boolean>? = null
    private var singleResult: ((Boolean) -> Unit)? = null

    private val requestPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        val callback = singleResult
        singleResult = null
        callback?.invoke(isGranted)
    }

    private val requestMultiplePermissionsLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        permissionResult?.complete(granted)
        permissionResult = null
    }

    fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun requestPermission(permission: String, onResult: (Boolean) -> Unit) {
        singleResult = onResult
        requestPermissionLauncher.launch(permission)
    }

    suspend fun ensurePermissions(
        permissions: List<String>,
        showSettingsDialog: () -> Unit
    ): Boolean {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        val shouldShow = missing.any { activity.shouldShowRequestPermissionRationale(it) }

        return withContext(Dispatchers.Main) {
            if (!shouldShow) {
                val deniedPermanently = missing.any {
                    ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED &&
                        !activity.shouldShowRequestPermissionRationale(it)
                }
                if (deniedPermanently) {
                    showSettingsDialog()
                    return@withContext false
                }
            }
            val deferred = CompletableDeferred<Boolean>()
            permissionResult = deferred
            requestMultiplePermissionsLauncher.launch(missing.toTypedArray())
            deferred.await()
        }
    }
}
