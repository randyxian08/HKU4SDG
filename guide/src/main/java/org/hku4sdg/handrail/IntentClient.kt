package org.hku4sdg.handrail

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Debug emulator bridge. Only the user's submitted goal is sent; no screen nodes or audio. */
class IntentClient(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val enabled = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
        Build.HARDWARE in setOf("ranchu", "goldfish")
    private var generation = 0

    data class Result(val decision: Decision, val taskId: String)
    enum class Decision { MATCH, CLARIFY, UNSUPPORTED }

    fun classify(goal: String, language: GuideLanguage, callback: (Result?) -> Unit) {
        val request = ++generation
        if (!enabled) {
            callback(null)
            return
        }
        worker.execute {
            val result = runCatching { requestIntent(goal, language) }.getOrNull()
            main.post { if (request == generation) callback(result) }
        }
    }

    fun close() {
        generation++
        worker.shutdownNow()
    }

    private fun requestIntent(goal: String, language: GuideLanguage): Result {
        val connection = (URL("http://10.0.2.2:8765/intent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 700
            readTimeout = 16000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            val body = JSONObject().put("text", goal).put("language", language.name.lowercase())
                .toString().toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("Intent unavailable")
            val bytes = connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(512)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 1024) error("Invalid intent response size")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.isEmpty()) error("Empty intent response")
            val response = JSONObject(String(bytes, Charsets.UTF_8))
            if (response.length() != 2) error("Unexpected intent response")
            val decision = when (response.getString("decision")) {
                "match" -> Decision.MATCH
                "clarify" -> Decision.CLARIFY
                "unsupported" -> Decision.UNSUPPORTED
                else -> error("Unknown intent decision")
            }
            val taskId = response.getString("task_id")
            if ((decision == Decision.MATCH) != (taskId == "font_size")) error("Unapproved task")
            return Result(decision, taskId)
        } finally {
            connection.disconnect()
        }
    }
}
