package com.hiddify.hiddify

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.hiddify.hiddify.bg.AppChangeReceiver
import go.Seq
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.hiddify.hiddify.Application as BoxApplication

class Application : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    override fun onCreate() {
        super.onCreate()

        setupCrashHandler()

        try {
            Seq.setContext(this)
        } catch (t: Throwable) {
            Log.e("Application", "Failed to set Seq context", t)
        }

        try {
            ContextCompat.registerReceiver(
                this,
                AppChangeReceiver(),
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addDataScheme("package")
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (t: Throwable) {
            Log.e("Application", "Failed to register AppChangeReceiver", t)
        }
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                val stackTrace = sw.toString()
                Log.e("CRASH", "Uncaught exception on thread ${thread.name}: $stackTrace")

                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val crashMessage = "\n[$time] [FATAL CRASH] Thread '${thread.name}' (id ${thread.id}):\n$stackTrace\n"

                val dir = getExternalFilesDir(null) ?: filesDir
                dir.mkdirs()
                File(dir, "crash.log").appendText(crashMessage)
                File(dir, "app.log").appendText(crashMessage)
            } catch (e: Throwable) {
                Log.e("CRASH", "Error saving crash log", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    companion object {
        lateinit var application: BoxApplication
        val notification by lazy { application.getSystemService<NotificationManager>()!! }
        val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
        val packageManager by lazy { application.packageManager }
        val powerManager by lazy { application.getSystemService<PowerManager>()!! }
        val notificationManager by lazy { application.getSystemService<NotificationManager>()!! }

        val wifiManager by lazy { application.getSystemService<WifiManager>()!! }
    }
}