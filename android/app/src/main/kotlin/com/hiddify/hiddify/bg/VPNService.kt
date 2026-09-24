package com.hiddify.hiddify.bg
import android.util.Log

import com.hiddify.hiddify.Application
import com.hiddify.hiddify.Settings
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.hiddify.core.libbox.Notification
import com.hiddify.hiddify.constant.PerAppProxyMode
import com.hiddify.hiddify.ktx.toIpPrefix
import com.hiddify.core.libbox.TunOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class VPNService : VpnService(), PlatformInterfaceWrapper {

    companion object {
        private const val TAG = "A/VPNService"
    }

    private val service = BoxService(this, this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Application.log("VPNService", "onStartCommand flags=$flags startId=$startId")
        return service.onStartCommand()
    }

    override fun onBind(intent: Intent): IBinder {
        val binder = super.onBind(intent)
        if (binder != null) {
            return binder
        }
        return service.onBind(intent)
    }

    override fun onDestroy() {
        service.onDestroy()
    }

    override fun onRevoke() {
        runBlocking {
            withContext(Dispatchers.Main) {
                service.onRevoke()
            }
        }
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    var systemProxyAvailable = false
    var systemProxyEnabled = false
    fun addIncludePackage(builder: Builder, packageName: String) {
        if (packageName == this.packageName) { 
            Log.d("VpnService","Cannot include myself: $packageName")
            return
        }
        try {     
            Log.d("VpnService","Including $packageName")
            builder.addAllowedApplication(packageName)
        } catch (e: NameNotFoundException) {
        }
    }

    fun addExcludePackage(builder: Builder, packageName: String) {
        try {     
            Log.d("VpnService","Excluding $packageName")
            builder.addDisallowedApplication(packageName)
        } catch (e: NameNotFoundException) {
        }
    }

    override fun openTun(options: TunOptions): Int {
        Application.log("VPNService", "openTun started: mtu=${options.mtu}")
        var hasPermission = false
        for (i in 0 until 20) {
            if (prepare(this) != null) {
                Log.w("VPN", "android: missing vpn permission")
            } else {
                hasPermission = true
                break
            }
            Thread.sleep(50)
        }

        if (!hasPermission) {
            Application.log("VPNService", "ERROR: android missing vpn permission")
            throw Exception("android: missing vpn permission")
        }
//        service.fileDescriptor?.close()

        val builder = Builder()
            .setSession("hiddify")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val inet4Address = options.inet4Address
        var v4AddressAdded = false
        while (inet4Address.hasNext()) {
            val address = inet4Address.next()
            runCatching {
                builder.addAddress(address.address(), address.prefix())
                v4AddressAdded = true
            }.onFailure { Log.w(TAG, "addAddress v4 failed: ${address.address()}/${address.prefix()}", it) }
        }

        val inet6Address = options.inet6Address
        while (inet6Address.hasNext()) {
            val address = inet6Address.next()
            runCatching {
                builder.addAddress(address.address(), address.prefix())
            }.onFailure { Log.w(TAG, "addAddress v6 failed: ${address.address()}/${address.prefix()}", it) }
        }

        if (!v4AddressAdded) {
            runCatching {
                builder.addAddress("172.19.0.1", 30)
            }.onFailure { Log.w(TAG, "fallback addAddress failed", it) }
        }

        if (options.autoRoute) {
            var dnsAdded = false
            runCatching {
                val dnsIterator = options.dnsServerAddress
                while (dnsIterator.hasNext()) {
                    val dns = dnsIterator.next()
                    if (!dns.isNullOrBlank()) {
                        val cleanDns = dns.substringBefore('%')
                        runCatching {
                            builder.addDnsServer(cleanDns)
                            dnsAdded = true
                        }.onFailure { Log.w(TAG, "addDnsServer failed for $cleanDns", it) }
                    }
                }
            }
            if (!dnsAdded) {
                runCatching {
                    builder.addDnsServer("1.1.1.1")
                }.onFailure { Log.w(TAG, "fallback addDnsServer failed", it) }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val inet4RouteAddress = options.inet4RouteAddress
                if (inet4RouteAddress.hasNext()) {
                    while (inet4RouteAddress.hasNext()) {
                        runCatching {
                            builder.addRoute(inet4RouteAddress.next().toIpPrefix())
                        }.onFailure { Log.w(TAG, "addRoute v4 failed", it) }
                    }
                } else {
                    runCatching { builder.addRoute("0.0.0.0", 0) }
                }

                val inet6RouteAddress = options.inet6RouteAddress
                if (inet6RouteAddress.hasNext()) {
                    while (inet6RouteAddress.hasNext()) {
                        runCatching {
                            builder.addRoute(inet6RouteAddress.next().toIpPrefix())
                        }.onFailure { Log.w(TAG, "addRoute v6 failed", it) }
                    }
                } else {
                    runCatching { builder.addRoute("::", 0) }
                }

                val inet4RouteExcludeAddress = options.inet4RouteExcludeAddress
                while (inet4RouteExcludeAddress.hasNext()) {
                    runCatching {
                        builder.excludeRoute(inet4RouteExcludeAddress.next().toIpPrefix())
                    }.onFailure { Log.w(TAG, "excludeRoute v4 failed", it) }
                }

                val inet6RouteExcludeAddress = options.inet6RouteExcludeAddress
                while (inet6RouteExcludeAddress.hasNext()) {
                    runCatching {
                        builder.excludeRoute(inet6RouteExcludeAddress.next().toIpPrefix())
                    }.onFailure { Log.w(TAG, "excludeRoute v6 failed", it) }
                }
            } else {
                val inet4RouteAddress = options.inet4RouteRange
                if (inet4RouteAddress.hasNext()) {
                    while (inet4RouteAddress.hasNext()) {
                        val address = inet4RouteAddress.next()
                        runCatching {
                            builder.addRoute(address.address(), address.prefix())
                        }.onFailure { Log.w(TAG, "addRoute v4 legacy failed", it) }
                    }
                }

                val inet6RouteAddress = options.inet6RouteRange
                if (inet6RouteAddress.hasNext()) {
                    while (inet6RouteAddress.hasNext()) {
                        val address = inet6RouteAddress.next()
                        runCatching {
                            builder.addRoute(address.address(), address.prefix())
                        }.onFailure { Log.w(TAG, "addRoute v6 legacy failed", it) }
                    }
                }
            }

            if (Settings.perAppProxyEnabled) {
                val appList = Settings.perAppProxyList
                if (Settings.perAppProxyMode == PerAppProxyMode.INCLUDE) {
                    appList.forEach {
                        addIncludePackage(builder, it)
                    }
                } else {
                    appList.forEach {
                        addExcludePackage(builder, it)
                    }
                    addExcludePackage(builder, packageName)
                }
            } else {
                val includePackage = options.includePackage
                if (includePackage.hasNext()) {
                    while (includePackage.hasNext()) {
                        addIncludePackage(builder, includePackage.next())
                    }
                } else {
                    val excludePackage = options.excludePackage
                    if (excludePackage.hasNext()) {
                        while (excludePackage.hasNext()) {
                            addExcludePackage(builder, excludePackage.next())
                        }
                    }
                    addExcludePackage(builder, packageName)
                }
            }
        }

        if (options.isHTTPProxyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            systemProxyAvailable = true
            systemProxyEnabled = Settings.systemProxyEnabled
            if (systemProxyEnabled) builder.setHttpProxy(
                ProxyInfo.buildDirectProxy(
                    options.httpProxyServer, options.httpProxyServerPort
                )
            )
        } else {
            systemProxyAvailable = false
            systemProxyEnabled = false
        }

        val pfd = try {
            builder.establish()
        } catch (t: Throwable) {
            Application.log(TAG, "builder.establish() threw: ${t.message}")
            throw Exception("Failed to establish VPN: ${t.message}", t)
        } ?: run {
            Application.log(TAG, "builder.establish() returned null!")
            throw Exception("android: the application is not prepared or is revoked")
        }
        service.fileDescriptor = pfd
        Application.log(TAG, "VPN established successfully! fd=${pfd.fd}")
        return pfd.fd
    }

//    override fun writeLog(message: String) = service.writeLog(message)

    override fun sendNotification(notification: Notification) {
//        service.sendNotification(notification)
    }
}