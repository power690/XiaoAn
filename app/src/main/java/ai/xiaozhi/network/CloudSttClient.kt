package ai.xiaozhi.network

import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

object CloudSttClient {
    private const val TAG = "CloudSttClient"
    private const val API_URL = "https://api.siliconflow.cn/v1/audio/transcriptions"
    
    // 确保使用您指定的模型
    private const val MODEL_NAME = "FunAudioLLM/SenseVoiceSmall"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    data class TranscriptionResponse(
        val text: String?
    )

    /**
     * 将 PCM Short 数据转换为 WAV 文件并上传识别
     */
    fun transcribe(pcmData: List<Short>, token: String, tempFile: File): String? {
        try {
            // 1. 将 PCM 数据写入 WAV 文件
            savePcmToWav(pcmData, tempFile)

            // 2. 构造请求
            val fileBody = tempFile.asRequestBody("audio/wav".toMediaTypeOrNull())
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.wav", fileBody)
                .addFormDataPart("model", MODEL_NAME)
                .build()

            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            // 3. 发送请求
            Log.d(TAG, "Sending audio to SiliconFlow API ($MODEL_NAME)...")
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful) {
                    Log.e(TAG, "API Error: ${response.code} - $responseBody")
                    return null
                }
                
                Log.d(TAG, "Response: $responseBody")
                val result = gson.fromJson(responseBody, TranscriptionResponse::class.java)
                return result.text
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            return null
        } finally {
            // 清理临时文件
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun savePcmToWav(pcmData: List<Short>, file: File) {
        val sampleRate = 16000
        val channels = 1
        val byteRate = 16 * sampleRate * channels / 8
        val totalAudioLen = pcmData.size * 2L
        val totalDataLen = totalAudioLen + 36

        FileOutputStream(file).use { out ->
            // WAV Header
            val header = ByteArray(44)
            val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            headerBuffer.put("RIFF".toByteArray())
            headerBuffer.putInt(totalDataLen.toInt())
            headerBuffer.put("WAVE".toByteArray())
            headerBuffer.put("fmt ".toByteArray())
            headerBuffer.putInt(16) // Subchunk1Size
            headerBuffer.putShort(1) // AudioFormat (PCM)
            headerBuffer.putShort(channels.toShort())
            headerBuffer.putInt(sampleRate)
            headerBuffer.putInt(byteRate)
            headerBuffer.putShort(2) // BlockAlign
            headerBuffer.putShort(16) // BitsPerSample
            headerBuffer.put("data".toByteArray())
            headerBuffer.putInt(totalAudioLen.toInt())

            out.write(header)

            // PCM Data
            val dataBuffer = ByteBuffer.allocate(pcmData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcmData) {
                dataBuffer.putShort(sample)
            }
            out.write(dataBuffer.array())
        }
    }
}