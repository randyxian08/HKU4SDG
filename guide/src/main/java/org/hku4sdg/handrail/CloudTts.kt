package org.hku4sdg.handrail

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Emulator-only development bridge. The Volcano API key stays in the host process, never in the APK. */
class CloudTts(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val enabled = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
        Build.HARDWARE in setOf("ranchu", "goldfish")
    private var generation = 0
    private var player: MediaPlayer? = null
    private var audioFile: File? = null

    fun speak(text: String, language: GuideLanguage, onStarted: () -> Unit = {}, onUnavailable: () -> Unit) {
        stop()
        if (!enabled) {
            onUnavailable()
            return
        }
        val request = generation
        worker.execute {
            val bytes = runCatching { requestAudio(text, language) }.getOrNull()
            main.post {
                if (request != generation) return@post
                if (bytes == null) {
                    onUnavailable()
                    return@post
                }
                play(bytes, request, onStarted, onUnavailable)
            }
        }
    }

    fun stop() {
        generation++
        player?.runCatching { stop() }
        player?.release()
        player = null
        audioFile?.delete()
        audioFile = null
    }

    fun close() {
        stop()
        worker.shutdownNow()
    }

    private fun requestAudio(text: String, language: GuideLanguage): ByteArray {
        val connection = (URL("http://10.0.2.2:8765/tts").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 700
            readTimeout = 12000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            val body = org.json.JSONObject()
                .put("text", text)
                .put("language", language.name.lowercase())
                .toString().toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("TTS unavailable")
            val result = connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (output.size() <= 2_000_000) {
                    val count = stream.read(chunk, 0, minOf(chunk.size, 2_000_001 - output.size()))
                    if (count < 0) break
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
            if (result.isEmpty() || result.size > 2_000_000) error("Invalid audio size")
            return result
        } finally {
            connection.disconnect()
        }
    }

    private fun play(bytes: ByteArray, request: Int, onStarted: () -> Unit, onUnavailable: () -> Unit) {
        try {
            val file = File.createTempFile("handrail-tts-", ".mp3", context.cacheDir)
            file.writeBytes(bytes)
            audioFile = file
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener {
                    if (request == generation) {
                        try { it.start(); onStarted() }
                        catch (_: IllegalStateException) { this@CloudTts.stop(); onUnavailable() }
                    }
                }
                setOnCompletionListener { if (request == generation) this@CloudTts.stop() }
                setOnErrorListener { _, _, _ ->
                    if (request == generation) {
                        this@CloudTts.stop()
                        onUnavailable()
                    }
                    true
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            if (request == generation) {
                stop()
                onUnavailable()
            }
        }
    }
}
