package org.hku4sdg.handrail

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class ScreenGuideClient(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile var lastFailure = ""
        private set
    val available = ScreenGuideClient.available(context)
    fun cancel() { generation++; activeConnection?.disconnect(); activeConnection = null }
    fun close() { cancel(); worker.shutdownNow() }
    fun plan(goal: String, snapshot: ScreenSnapshot, text: GuideLanguage, voice: GuideLanguage,
        image: String, callback: (ScreenAdvice?) -> Unit) {
        val request = ++generation
        if (!available) { callback(null); return }
        worker.execute {
            if (request != generation) return@execute
            lastFailure = ""
            val advice = runCatching { requestAdvice(goal, snapshot, text, voice, image, request) }
                .onFailure { if (lastFailure.isEmpty()) lastFailure = it.javaClass.simpleName }.getOrNull()
            main.post { if (request == generation) callback(advice) }
        }
    }
    private fun requestAdvice(goal: String, snapshot: ScreenSnapshot, text: GuideLanguage,
        voice: GuideLanguage, image: String, requestId: Int): ScreenAdvice {
        val nodes = JSONArray()
        snapshot.nodes.forEach { n -> nodes.put(JSONObject().put("id", n.id).put("label", n.label).put("role", n.role)
            .put("clickable", n.clickable).put("editable", n.editable).put("scrollable", n.scrollable).put("enabled", n.enabled)
            .put("bounds", JSONArray(listOf(n.left, n.top, n.right, n.bottom))).put("state", n.state)) }
        val body = JSONObject().put("goal", goal).put("package", snapshot.packageName).put("snapshot_id", snapshot.id)
            .put("text_language", text.name.lowercase()).put("voice_language", voice.name.lowercase())
            .put("nodes", nodes).put("image", image).toString().toByteArray(Charsets.UTF_8)
        val connection = (URL("http://10.0.2.2:8765/screen-step").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 1000; readTimeout = 25000; doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            if (requestId != generation) error("Screen request cancelled")
            activeConnection = connection
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode != 200) {
                lastFailure = "HTTP ${connection.responseCode}"
                val bytes = connection.errorStream?.use { input ->
                    val output = ByteArrayOutputStream(); val chunk = ByteArray(256)
                    while (output.size() < 1024) {
                        val n = input.read(chunk, 0, minOf(chunk.size, 1024 - output.size()))
                        if (n < 0) break
                        output.write(chunk, 0, n)
                    }
                    output.toByteArray()
                }
                val reason = bytes?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)).optString("reason").take(100) }.getOrNull() }
                if (!reason.isNullOrEmpty()) lastFailure += ": $reason"
                error("Screen model unavailable")
            }
            val bytes = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream(); val chunk = ByteArray(1024)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (out.size() + count > 8192) error("Screen response too large")
                    out.write(chunk, 0, count)
                }
                out.toByteArray()
            }
            val reply = JSONObject(String(bytes, Charsets.UTF_8))
            if (reply.length() != 5 || reply.getString("snapshot_id") != snapshot.id) error("Wrong snapshot")
            val advice = ScreenAdvice(reply.getString("snapshot_id"), reply.getString("action"), reply.getString("node_id"),
                reply.getString("instruction"), reply.getString("spoken_instruction"))
            if (advice.instruction.length !in 1..160 || advice.spokenInstruction.length !in 1..160) error("Invalid instruction")
            if (advice.action in setOf("tap", "type", "scroll_forward", "scroll_backward")) {
                if (ScreenGuidePolicy.target(snapshot, advice) == null) error("Ungrounded target")
            } else if (advice.action !in setOf("clarify", "complete", "unsupported") || advice.nodeId.isNotEmpty()) error("Invalid decision")
            return advice
        } finally { if (activeConnection === connection) activeConnection = null; connection.disconnect() }
    }
    companion object {
        fun available(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            Build.HARDWARE in setOf("ranchu", "goldfish")
    }
}
