package org.hku4sdg.handrail

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var preferences: GuidePreferences
    private lateinit var input: EditText
    private lateinit var status: TextView
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = GuidePreferences(this)
        showHome()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) status.text = if (serviceEnabled())
            "引导服务已开启。练习只读取“数字扶手练习场”的控件位置。"
        else "请先开启“数字扶手 · 练习引导”无障碍服务，再开始练习。"
    }

    private fun showHome() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 40)
            setBackgroundColor(0xFFF5F5EF.toInt())
        }
        fun text(value: String, size: Float) = TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(0xFF182B28.toInt())
            body.addView(this)
        }
        fun button(value: String, click: () -> Unit) = Button(this).apply {
            text = value
            textSize = 18f
            minHeight = 64
            setOnClickListener { click() }
            body.addView(this)
        }
        text("数字扶手", 32f)
        text("Android 跨应用引导练习 · 不会更改真实手机设置", 17f)
        status = TextView(this).apply { textSize = 17f; body.addView(this) }
        text("只读取练习场已登记控件的位置；检测离开练习场时撤下旧提示，不读取其他应用页面。不会替你点击。", 16f)
        button("1 · 开启引导服务") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        text("2 · 选择三种语言（互不联动）", 21f)
        languageSelector(body, "输入语言", preferences.inputLanguage) { preferences.inputLanguage = it }
        languageSelector(body, "文字语言", preferences.textLanguage) { preferences.textLanguage = it }
        languageSelector(body, "播报语言", preferences.voiceLanguage) { preferences.voiceLanguage = it }
        input = EditText(this).apply {
            hint = "例如：手机啲字太细 / make text bigger"
            textSize = 19f
            minHeight = 64
            body.addView(this)
        }
        button("点按说话") { beginListening() }
        button("3 · 用文字开始练习") {
            if (TaskMatcher.matchesFontSize(input.text.toString())) startPractice()
            else Toast.makeText(this, "目前只支持“调大字体”；也可点下面的任务卡。", Toast.LENGTH_LONG).show()
        }
        button("任务卡：把字调大") { startPractice() }
        text("语音识别由设备服务处理，可能联网；使用前会单独提示。不可用时请用文字或任务卡。粤语效果需要真人实测。", 15f)
        setContentView(ScrollView(this).apply { addView(body) })
        onResume()
    }

    private fun languageSelector(
        body: LinearLayout, label: String, selected: GuideLanguage,
        onSelect: (GuideLanguage) -> Unit
    ) {
        body.addView(TextView(this).apply { text = label; textSize = 17f })
        body.addView(Spinner(this).apply {
            val languages = GuideLanguage.entries
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                languages.map { it.label })
            setSelection(languages.indexOf(selected))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    onSelect(languages[position])
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        })
    }

    private fun serviceEnabled(): Boolean {
        val id = ComponentName(this, GuideAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == id }
    }

    private fun startPractice() {
        if (!serviceEnabled()) {
            Toast.makeText(this, "请先开启引导服务", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent().setClassName("org.hku4sdg.demotarget", "org.hku4sdg.demotarget.PracticeActivity")
        try {
            preferences.active = true
            preferences.collapsed = false
            startActivity(intent)
        } catch (_: Exception) {
            preferences.active = false
            Toast.makeText(this, "请先安装同仓库的 demo-target 应用", Toast.LENGTH_LONG).show()
        }
    }

    private fun beginListening() {
        if (!preferences.speechConsent) {
            AlertDialog.Builder(this)
                .setTitle("启用语音输入？")
                .setMessage("点按说话后，音频会交给设备提供的语音识别服务处理；该服务可能联网，具体保留方式取决于设备设置。你也可以一直使用文字或任务卡。")
                .setNegativeButton("不用语音", null)
                .setPositiveButton("继续") { _, _ -> preferences.speechConsent = true; beginListening() }
                .show()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "此设备没有可用语音识别，请使用文字或任务卡", Toast.LENGTH_LONG).show()
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status.text = "正在聆听…" }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) { status.text = "没有听清（错误 $error），请改用文字或任务卡。" }
                override fun onResults(results: Bundle?) {
                    val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (transcript != null) {
                        input.setText(transcript)
                        status.text = "识别为：$transcript。请确认后再开始。"
                    } else status.text = "没有听清，请改用文字或任务卡。"
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, preferences.inputLanguage.tag)
            })
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginListening()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }
}
