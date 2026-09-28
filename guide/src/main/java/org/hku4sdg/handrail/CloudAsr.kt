package org.hku4sdg.handrail

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Debug emulator speech path. Audio is held in memory and sent only after the user stops recording. */
class CloudAsr(private val context: Context) {
    val enabled = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
        Build.HARDWARE in setOf("ranchu", "goldfish")
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var recording = false
    private var generation = 0

    fun start(language: GuideLanguage, onCaptured: () -> Unit, onResult: (String?) -> Unit): Boolean {
        if (!enabled || recording || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        if (bufferSize <= 0) return false
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(bufferSize, 6400))
        } catch (_: Exception) { return false }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        try { recorder.startRecording() } catch (_: Exception) {
            recorder.release()
            return false
        }
        recording = true
        val request = ++generation
        worker.execute {
            val audio = ByteArrayOutputStream()
            val chunk = ByteArray(6400)
            try {
                while (recording && audio.size() < 256000) {
                    val count = recorder.read(chunk, 0, minOf(chunk.size, 256000 - audio.size()))
                    if (count <= 0) break
                    audio.write(chunk, 0, count)
                }
            } finally {
                recording = false
                runCatching { recorder.stop() }
                recorder.release()
            }
            main.post { if (request == generation) onCaptured() }
            val transcript = if (audio.size() >= 16000) runCatching {
                transcribe(audio.toByteArray(), language)
            }.getOrNull() else null
            main.post { if (request == generation) onResult(transcript) }
        }
        return true
    }

    fun stop() { recording = false }
    fun isRecording() = recording
    fun close() {
        recording = false
        generation++
        worker.shutdownNow()
    }

    private fun transcribe(pcm: ByteArray, language: GuideLanguage): String {
        val connection = (URL("http://10.0.2.2:8765/asr").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 700
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("X-Guide-Language", language.name.lowercase())
            setFixedLengthStreamingMode(pcm.size)
        }
        try {
            connection.outputStream.use { it.write(pcm) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("ASR unavailable")
            val body = connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(256)
                while (output.size() <= 1024) {
                    val count = stream.read(chunk, 0, minOf(chunk.size, 1025 - output.size()))
                    if (count < 0) break
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
            if (body.isEmpty() || body.size > 1024) error("Invalid ASR response")
            val response = JSONObject(String(body, Charsets.UTF_8))
            if (response.length() != 1) error("Invalid ASR response")
            return response.getString("text").take(160)
        } finally {
            connection.disconnect()
        }
    }
}
