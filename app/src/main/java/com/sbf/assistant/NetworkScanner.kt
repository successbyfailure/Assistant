package com.sbf.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class NetworkScanner(private val context: Context) {

    interface ScanCallback {
        fun onDeviceFound(ip: String)
        fun onScanFinished()
    }

    suspend fun scanForOllama(callback: ScanCallback) = withContext(Dispatchers.IO) {
        val localIp = getLocalIpAddress() ?: return@withContext
        val subnet = localIp.substringBeforeLast(".")

        // Limit concurrent connections to avoid overwhelming the network
        val semaphore = Semaphore(10)

        val deferreds = (1..254).map { i ->
            async {
                semaphore.withPermit {
                    val testIp = "$subnet.$i"
                    if (isOllamaReachable(testIp)) {
                        withContext(Dispatchers.Main) {
                            callback.onDeviceFound(testIp)
                        }
                    }
                }
            }
        }
        deferreds.awaitAll()
        withContext(Dispatchers.Main) {
            callback.onScanFinished()
        }
    }

    private fun getLocalIpAddress(): String? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return null
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
        
        for (address in linkProperties.linkAddresses) {
            val ip = address.address.hostAddress
            if (!address.address.isLoopbackAddress && ip?.contains(".") == true) {
                return ip
            }
        }
        return null
    }

    private fun isOllamaReachable(ip: String): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, 11434), 200) // Timeout corto para escaneo rápido
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }
}
