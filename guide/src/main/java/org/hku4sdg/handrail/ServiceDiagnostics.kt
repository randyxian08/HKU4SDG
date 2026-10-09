package org.hku4sdg.handrail

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/** Bounded local lifecycle metadata. No screen text, goals, audio or exception messages. */
object ServiceDiagnostics {
    @Synchronized fun record(context: Context, event: String, error: Throwable? = null, sync: Boolean = false) {
        runCatching {
            val prefs = context.getSharedPreferences("service_diagnostics", Context.MODE_PRIVATE)
            val old = runCatching { JSONArray(prefs.getString("events", "[]")) }.getOrDefault(JSONArray())
            val next = JSONArray()
            for (i in maxOf(0, old.length() - 39) until old.length()) next.put(old.getJSONObject(i))
            next.put(JSONObject().put("time", System.currentTimeMillis()).put("event", event)
                .put("error_class", error?.javaClass?.simpleName ?: JSONObject.NULL))
            val edit = prefs.edit().putString("events", next.toString())
            if (sync) edit.commit() else edit.apply()
        }
    }
    fun enabled(context: Context): Boolean {
        val component = ComponentName(context, GuideAccessibilityService::class.java)
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
    }
    fun buildLabel() = "${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}/${BuildConfig.BUILD_TYPE} · ${BuildConfig.BUILD_ID}"
    fun report(context: Context): String {
        val prefs = GuidePreferences(context)
        val report = JSONObject().put("build", buildLabel()).put("android_api", Build.VERSION.SDK_INT)
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("permission_enabled", enabled(context)).put("service_connected", GuideAccessibilityService.connected)
            .put("session_phase", prefs.sessionState.phase.name).put("pause_reason", prefs.sessionState.reason?.name ?: JSONObject.NULL)
            .put("events", runCatching { JSONArray(context.getSharedPreferences("service_diagnostics", Context.MODE_PRIVATE)
                .getString("events", "[]")) }.getOrDefault(JSONArray()))
        if (Build.VERSION.SDK_INT >= 30) {
            val exits = JSONArray()
            runCatching { context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 5) }.getOrNull()?.forEach {
                exits.put(JSONObject().put("time", it.timestamp).put("reason", it.reason).put("status", it.status))
            }
            report.put("process_exits", exits)
        }
        return report.put("scope", "Local lifecycle and device metadata only; no screen, goal, audio, stack trace or exception message").toString(2)
    }
}

class HandrailApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceDiagnostics.record(this, "process_started")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            ServiceDiagnostics.record(this, "uncaught_exception", error, sync = true)
            // Preserve Android's normal crash reporting and termination; never silently retry a crash.
            if (previous != null) previous.uncaughtException(thread, error)
            else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }
}
