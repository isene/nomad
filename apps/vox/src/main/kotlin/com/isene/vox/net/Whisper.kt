package com.isene.vox.net

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

/**
 * OpenAI Whisper transcription. One multipart POST; the network call is the
 * only non-local step. Caller runs this off the main thread.
 */
object Whisper {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    fun transcribe(apiKey: String, audio: File, lang: String): Result<String> {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audio.name, audio.asRequestBody("audio/mp4".toMediaType()))
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("language", lang)
                .addFormDataPart("response_format", "json")
                .build()
            val req = Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = try {
                        JSONObject(txt).optJSONObject("error")?.optString("message")
                    } catch (_: Exception) {
                        null
                    }
                    return Result.failure(IOException("HTTP ${resp.code}: ${msg ?: txt.take(200)}"))
                }
                Result.success(JSONObject(txt).optString("text").trim())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
