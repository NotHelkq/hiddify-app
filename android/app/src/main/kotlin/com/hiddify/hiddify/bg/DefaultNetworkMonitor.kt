package com.hiddify.hiddify.bg

import android.net.Network
import android.os.Build
import com.hiddify.hiddify.Application
import com.hiddify.core.libbox.InterfaceUpdateListener
import com.hiddify.hiddify.constant.Bugs


import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.net.NetworkInterface

object DefaultNetworkMonitor {

    var defaultNetwork: Network? = null
    private var listener: InterfaceUpdateListener? = null

    suspend fun start() {
        DefaultNetworkListener.start(this) {
            defaultNetwork = it
            checkDefaultInterfaceUpdate(it)
        }
        defaultNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Application.connectivity.activeNetwork
        } else {
            DefaultNetworkListener.get()
        }
    }

    suspend fun stop() {
        DefaultNetworkListener.stop(this)
    }

    suspend fun require(): Network {
        val network = defaultNetwork
        if (network != null) {
            return network
        }
        return DefaultNetworkListener.get()
    }

    fun setListener(listener: InterfaceUpdateListener?) {
        this.listener = listener
        checkDefaultInterfaceUpdate(defaultNetwork)
    }

    private fun checkDefaultInterfaceUpdate(newNetwork: Network?) {
        val listener = listener ?: return
        if (newNetwork != null) {
            val linkProps = Application.connectivity.getLinkProperties(newNetwork) ?: return
            val interfaceName = linkProps.interfaceName ?: return
            for (times in 0 until 10) {
                val interfaceIndex: Int
                try {
                    val netIf = NetworkInterface.getByName(interfaceName) ?: throw NullPointerException("not found")
                    interfaceIndex = netIf.index
                } catch (e: Exception) {
                    Thread.sleep(100)
                    continue
                }
                try {
                    listener.updateDefaultInterface(interfaceName, interfaceIndex, false, false)
                } catch (t: Throwable) {
                    Application.log("DefaultNetworkMonitor", "updateDefaultInterface failed: ${t.message}")
                }
                break
            }
        } else {
            try {
                listener.updateDefaultInterface("", -1, false, false)
            } catch (t: Throwable) {
                Application.log("DefaultNetworkMonitor", "updateDefaultInterface empty failed: ${t.message}")
            }
        }
    }
}