package net.clickarr

import android.app.Application
import android.util.Log as AndroidLog
import dagger.hilt.android.HiltAndroidApp
import net.clickarr.core.common.Log
import net.clickarr.core.common.Redact

@HiltAndroidApp
class ClickarrApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.sink = Log.Sink { level, tag, message, throwable ->
            val priority = when (level) {
                Log.Level.VERBOSE -> AndroidLog.VERBOSE
                Log.Level.DEBUG -> AndroidLog.DEBUG
                Log.Level.INFO -> AndroidLog.INFO
                Log.Level.WARN -> AndroidLog.WARN
                Log.Level.ERROR -> AndroidLog.ERROR
            }
            val text = if (throwable == null) message else "$message\n${Redact.apply(AndroidLog.getStackTraceString(throwable))}"
            AndroidLog.println(priority, "Clickarr/$tag", text)
        }
        Log.minLevel = if (BuildConfig.DEBUG) Log.Level.VERBOSE else Log.Level.INFO
    }
}
