package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Base64
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    // ✅ 语音识别（第一个应用）的 Key/Secret
    private val speechApiKey = "FsLpSoL6rF73mtPMbhBbsgUM"
    private val speechSecretKey = "uwTyKfsjXM6ODgDjDyDFn8WwZuti4Bbc"

    // ✅ 情感分析（第二个应用）的 Key/Secret
    private val nlpApiKey = "Vqv2ZvOnzCBX8P6AQrSFdJuf"
    private val nlpSecretKey = "eK2DusW7xjSgcAhEyc8JhRKNoS4U3v4m"

    private lateinit var btnRecord: Button
    private lateinit var tvResult: TextView
    private lateinit var emotionProgressBar: ProgressBar
    private lateinit var mainLayout: android.widget.LinearLayout
    private var backgroundAnimator: android.animation.ValueAnimator? = null
    private var buttonPulseAnimator: android.animation.ValueAnimator? = null

    private val client = OkHttpClient()

    // 录音参数：16k、单声道、PCM 16bit
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordedPcmBytes: ByteArray? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ✅ 运行时申请录音权限（只需要这样写，不要加任何注解）
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                toggleRecord()
            } else {
                tvResult.text = "需要录音权限才能工作"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnRecord = findViewById(R.id.btnRecord)
        tvResult = findViewById(R.id.tvResult)
        emotionProgressBar = findViewById(R.id.emotionProgressBar)
        mainLayout = findViewById(R.id.mainLayout)

        btnRecord.setOnClickListener {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (granted) {
                toggleRecord()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    /**
     * 根据情感积极概率（0.0到1.0）返回一个渐变色。
     * @param prob 积极概率
     * @return 对应的颜色值
     */
    private fun getColorForProbability(prob: Double): Int {
        // 确保概率在0-1之间
        val safeProb = prob.coerceIn(0.0, 1.0).toFloat()
        val red: Float
        val green: Float

        if (safeProb < 0.5f) {
            // 低概率（偏消极）：红色为主，绿色逐渐增加
            red = 1.0f
            green = safeProb * 2 // 0.0 -> 1.0
        } else {
            // 高概率（偏积极）：绿色为主，红色逐渐减少
            red = (1.0f - safeProb) * 2 // 1.0 -> 0.0
            green = 1.0f
        }
        // 将RGB分量（0-1）转换为Android的颜色整数
        return android.graphics.Color.rgb((red * 255).toInt(), (green * 255).toInt(), 0)
    }
    // ==================== 背景呼吸动画函数 ====================
    private fun startBackgroundBreathAnimation() {
        backgroundAnimator?.cancel()
        val colorStart = android.graphics.Color.WHITE
        val colorEnd = android.graphics.Color.parseColor("#F3E5F5") // 浅紫色
        backgroundAnimator = android.animation.ValueAnimator.ofArgb(colorStart, colorEnd, colorStart).apply {
            duration = 2000
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                mainLayout.setBackgroundColor(animator.animatedValue as Int)
            }
        }
        backgroundAnimator?.start()
    }

    private fun stopBackgroundBreathAnimation() {
        backgroundAnimator?.cancel()
        mainLayout.animate()
            .setDuration(300)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction { mainLayout.setBackgroundColor(android.graphics.Color.WHITE) }
            .start()
    }

    // ==================== 按钮脉动动画函数 ====================
    private fun startButtonPulseAnimation() {
        buttonPulseAnimator?.cancel()
        buttonPulseAnimator = android.animation.ValueAnimator.ofFloat(0.98f, 1.02f, 0.98f).apply {
            duration = 900
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                btnRecord.scaleX = scale
                btnRecord.scaleY = scale
            }
        }
        buttonPulseAnimator?.start()
    }

    private fun stopButtonPulseAnimation() {
        buttonPulseAnimator?.cancel()
        btnRecord.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(200)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .start()
    }

    private fun toggleRecord() {
        if (!isRecording) startRecording() else stopRecordingAndAnalyze()
    }

    private fun startRecording() {
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            tvResult.text = "录音参数不支持（sampleRate/channel/format）"
            return
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            minBufferSize
        )


        audioRecord = recorder
        recorder.startRecording()

        isRecording = true
        btnRecord.text = "停止录音并识别"
        tvResult.text = "正在录音…请说一句话（建议 2~5 秒）"
        startBackgroundBreathAnimation()
        startButtonPulseAnimation()

        scope.launch(Dispatchers.IO) {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(minBufferSize)

            while (isRecording) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) output.write(buffer, 0, read)
            }

            recordedPcmBytes = output.toByteArray()
        }
    }

    private fun stopRecordingAndAnalyze() {
        isRecording = false
        stopBackgroundBreathAnimation()
        stopButtonPulseAnimation()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        btnRecord.text = "开始录音"
        tvResult.text = "正在识别中…"

        scope.launch {
            try {
                val pcm = recordedPcmBytes
                if (pcm == null || pcm.isEmpty()) {
                    tvResult.text = "没有录到音频"
                    return@launch
                }

                // 1) 分别获取两套 token（语音识别用第一套，情感分析用第二套）
                val speechToken = withContext(Dispatchers.IO) {
                    getAccessToken(speechApiKey, speechSecretKey)
                }
                val nlpToken = withContext(Dispatchers.IO) {
                    getAccessToken(nlpApiKey, nlpSecretKey)
                }

                // 2) 语音识别：PCM -> 文本
                val text = withContext(Dispatchers.IO) {
                    speechToText(speechToken, pcm)
                }

                // 3) 情感分析：文本 -> 情绪倾向
                // 3) 情感分析：文本 -> 情绪倾向 (同时获取积极概率)
                lateinit var sentimentText: String
                var positiveProb: Double = 0.5 // 默认值
                try {
                    val (textResult, prob) = withContext(Dispatchers.IO) {
                        sentimentClassify(nlpToken, text)
                    }
                    sentimentText = textResult
                    positiveProb = prob
                } catch (e: Exception) {
                    sentimentText = "情感分析失败：${e.message}"
                    positiveProb = 0.5
                }

// 显示结果文本
                tvResult.text = "识别文字：\n$text\n\n情绪结果：\n$sentimentText"

// ---------- 新增：更新动态进度条 ----------
                try {
                    // 1. 更新进度条进度 (0-100)
                    val progressValue = (positiveProb * 100).toInt()
                    emotionProgressBar.progress = progressValue

                    // 2. 根据积极概率计算并设置动态颜色
                    val progressColor = getColorForProbability(positiveProb)
                    emotionProgressBar.progressTintList = android.content.res.ColorStateList.valueOf(progressColor)

                    // 3. 确保进度条可见
                    emotionProgressBar.visibility = android.view.View.VISIBLE
                } catch (e: Exception) {
                    // 万一出错，不让进度条影响主功能
                    e.printStackTrace()
                }

            } catch (e: Exception) {
                tvResult.text = "出错了：${e.message}"
            }
        }
    }

    // -------------------------
    // 获取 access_token
    // -------------------------
    private fun getAccessToken(apiKey: String, secretKey: String): String {
        val url =
            "https://aip.baidubce.com/oauth/2.0/token" +
                    "?grant_type=client_credentials" +
                    "&client_id=$apiKey" +
                    "&client_secret=$secretKey"

        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: error("token响应为空")
            val json = JSONObject(body)
            if (!json.has("access_token")) {
                val err = json.optString("error_description", body)
                error("获取token失败：$err")
            }
            return json.getString("access_token")
        }
    }

    // -------------------------
    // 百度短语音识别：PCM -> text
    // -------------------------
    private fun speechToText(token: String, pcmBytes: ByteArray): String {
        val url = "https://vop.baidu.com/server_api"

        val speechBase64 = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)

        val payload = JSONObject().apply {
            put("format", "pcm")
            put("rate", 16000)
            put("channel", 1)
            put("cuid", "android_demo_cuid")
            put("token", token)
            put("speech", speechBase64)
            put("len", pcmBytes.size)
        }

        val body = payload.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(req).execute().use { resp ->
            val respStr = resp.body?.string() ?: error("语音识别响应为空")
            val json = JSONObject(respStr)

            if (json.has("result")) {
                val arr = json.getJSONArray("result")
                return arr.getString(0)
            } else {
                val errNo = json.optInt("err_no", -1)
                val errMsg = json.optString("err_msg", respStr)
                error("语音识别失败：err_no=$errNo, err_msg=$errMsg")
            }
        }
    }

    // -------------------------
    // 情感倾向分析：text -> sentiment
    // -------------------------
    private fun sentimentClassify(token: String, text: String): Pair<String, Double> {
        val url =
            "https://aip.baidubce.com/rpc/2.0/nlp/v1/sentiment_classify?access_token=$token"

        val payload = JSONObject().apply {
            put("text", text)
        }

        val body = payload.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(req).execute().use { resp ->
            val respStr = resp.body?.string() ?: error("情感分析响应为空")
            val json = JSONObject(respStr)

            if (json.has("items")) {
                val item = json.getJSONArray("items").getJSONObject(0)

                val sentiment = item.optInt("sentiment", -1) // 0消极 1中性 2积极（常见）
                val confidence = item.optDouble("confidence", 0.0)
                val positiveProb = item.optDouble("positive_prob", 0.0)
                val negativeProb = item.optDouble("negative_prob", 0.0)

                val label = when (sentiment) {
                    0 -> "消极"
                    1 -> "中性"
                    2 -> "积极"
                    else -> "未知"
                }

                // 返回一个 Pair：第一个是显示文本，第二个是积极概率值
                return Pair(
                    "倾向：$label\n置信度：$confidence\n积极概率：$positiveProb\n消极概率：$negativeProb",
                    positiveProb // 这是 Double 类型的概率值
                )
            } else {
                val errMsg = json.optString("error_msg", respStr)
                error("情感分析失败：$errMsg")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
