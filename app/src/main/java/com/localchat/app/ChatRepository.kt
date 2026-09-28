package com.localchat.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

data class Attachment(val name: String, val uri: Uri, val mime: String, val size: Long)
private data class PreparedImage(val bytes: ByteArray, val mime: String)

class ChatRepository(private val context: Context, private val dao: ChatDao) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    suspend fun stream(
        conversationId: String,
        history: List<Message>,
        attachment: Attachment?,
        onStatus: suspend (String) -> Unit,
        onDelta: suspend (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val base = prefs.getString("baseUrl", "")!!.trimEnd('/')
        val token = prefs.getString("token", "") ?: ""
        val model = prefs.getString("model", "gpt-5.5") ?: "gpt-5.5"
        val wireApi = prefs.getString("wireApi", "responses") ?: "responses"
        require(base.isNotBlank()) { "请先在设置中填写 API 地址" }
        val messages = JSONArray().apply {
            history.forEachIndexed { index, item ->
                val message = JSONObject().put("role", item.role)
                if (wireApi == "responses" && attachment != null && index == history.lastIndex && item.role == "user") {
                    onStatus("image")
                    val prepared = if (attachment.mime.startsWith("image/")) prepareImage(attachment.uri, attachment.mime, onStatus) else {
                        PreparedImage(context.contentResolver.openInputStream(attachment.uri)?.use { it.readBytes() } ?: error("无法读取附件"), attachment.mime)
                    }
                    val bytes = prepared.bytes
                    require(bytes.size <= 20 * 1024 * 1024) { "附件不能超过 20 MB" }
                    onStatus(if (attachment.mime.startsWith("image/")) "image" else "thinking")
                    val dataUrl = "data:${prepared.mime};base64,${android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)}"
                    val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", item.content))
                    if (attachment.mime.startsWith("image/")) content.put(JSONObject().put("type", "input_image").put("image_url", dataUrl))
                    else content.put(JSONObject().put("type", "input_file").put("filename", attachment.name).put("file_data", dataUrl))
                    message.put("content", content)
                } else message.put("content", item.content)
                put(message)
            }
        }
        val payload = if (wireApi == "responses") JSONObject().put("model", model).put("stream", true).put("input", messages)
        else JSONObject().put("model", model).put("stream", true).put("messages", messages)
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val endpoint = if (wireApi == "responses") "$base/responses" else "$base/chat/completions"
        val request = Request.Builder().url(endpoint)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }.post(body).build()
        onStatus(if (attachment != null) "image" else "thinking")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("请求失败：HTTP ${response.code}")
            onStatus("thinking")
            val source = response.body?.source() ?: error("服务器没有返回内容")
            if (!readSse(source, onDelta)) error("连接在回答完成前中断")
        }
    }

    private suspend fun prepareImage(uri: Uri, originalMime: String, onStatus: suspend (String) -> Unit): PreparedImage {
        val resolver = context.contentResolver
        val original = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取图片")
        require(original.size <= 20 * 1024 * 1024) { "附件不能超过 20 MB" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return PreparedImage(original, originalMime)
        val longest = max(bounds.outWidth, bounds.outHeight)
        val sample = if (longest > MAX_IMAGE_EDGE) {
            var value = 1
            while (longest / (value * 2) >= MAX_IMAGE_EDGE) value *= 2
            value
        } else 1
        onStatus("image")
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeByteArray(original, 0, original.size, options)
            ?: return PreparedImage(original, originalMime)
        val scale = MAX_IMAGE_EDGE.toFloat() / max(decoded.width, decoded.height).toFloat()
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true) else decoded
        if (bitmap !== decoded) decoded.recycle()
        onStatus("image")
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, output)
        bitmap.recycle()
        return PreparedImage(output.toByteArray(), "image/jpeg")
    }

    companion object {
        private const val MAX_IMAGE_EDGE = 1600
        private const val IMAGE_QUALITY = 82
    }

    private suspend fun readSse(source: BufferedSource, onDelta: suspend (String) -> Unit): Boolean {
        var completed = false
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") { completed = true; break }
            var shouldStop = false
            runCatching {
                val json = JSONObject(data)
                val eventType = json.optString("type")
                val delta = json.optString("delta", "").ifEmpty {
                    json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content", "") ?: ""
                }
                if (delta.isNotEmpty()) onDelta(delta)
                shouldStop = eventType == "response.completed" || eventType == "response.failed" || eventType == "response.incomplete"
                if (eventType == "response.completed") completed = true
            }
            if (shouldStop) break
        }
        return completed
    }

    fun newId() = UUID.randomUUID().toString()
}
