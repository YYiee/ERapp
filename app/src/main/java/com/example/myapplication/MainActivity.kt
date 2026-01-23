package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Base64
import android.widget.Button
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
                val sentiment = withContext(Dispatchers.IO) {
                    sentimentClassify(nlpToken, text)
                }

                tvResult.text = "识别文字：\n$text\n\n情绪结果：\n$sentiment"

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
    private fun sentimentClassify(token: String, text: String): String {
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

                return "倾向：$label\n置信度：$confidence\n积极概率：$positiveProb\n消极概率：$negativeProb"
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
