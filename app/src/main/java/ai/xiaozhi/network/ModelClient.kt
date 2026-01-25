package ai.xiaozhi.network

import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Streaming
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

// 数据类用于 Flow 返回
data class StreamUpdate(
    val content: String = "",
    val thinking: String = "",
    val isDone: Boolean = false,
    val error: String? = null
)

interface OpenAIApi {
    @POST("chat/completions")
    suspend fun chatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: ChatRequest
    ): retrofit2.Response<ChatResponse>
}

interface SiliconFlowApi {
    @POST("v1/chat/completions")
    @Streaming // 关键：支持流式下载
    suspend fun streamChatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: SiliconChatRequest
    ): retrofit2.Response<ResponseBody>
}

class ModelClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val modelName: String
) {

    private val api: OpenAIApi
    private val siliconApi: SiliconFlowApi
    private val gson = Gson()

    init {
        // 【关键修复】大幅增加超时时间，DeepSeek R1 思考过程可能很长
        val client = OkHttpClient.Builder()
            .connectTimeout(300, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val finalBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        
        api = Retrofit.Builder()
            .baseUrl(finalBaseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenAIApi::class.java)

        siliconApi = Retrofit.Builder()
            .baseUrl("https://api.siliconflow.cn/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SiliconFlowApi::class.java)
    }

    // AutoGLM 请求 (保持不变)
    suspend fun sendRequest(history: List<Message>, screenshot: Bitmap?): String {
        val isDeepSeek = modelName.contains("deepseek", ignoreCase = true)
        val finalMessages = if (isDeepSeek) {
             history.map { msg ->
                if (msg.content is List<*>) {
                    val textContent = (msg.content as List<*>)
                        .filterIsInstance<ContentItem>()
                        .filter { it.type == "text" }
                        .joinToString("\n") { it.text ?: "" }
                    Message(msg.role, textContent)
                } else {
                    msg
                }
            }
        } else {
            history
        }
        val request = ChatRequest(
            model = modelName,
            messages = finalMessages,
            maxTokens = 3000,
            temperature = 0.0,
            topP = 0.85,
            frequencyPenalty = 0.2
        )
        try {
            val response = api.chatCompletion("Bearer $apiKey", request)
            if (response.isSuccessful) {
                return response.body()?.choices?.firstOrNull()?.message?.content ?: ""
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e("ModelClient", "API Error: $errorBody")
                return "Error: ${response.code()} $errorBody"
            }
        } catch (e: Exception) {
            Log.e("ModelClient", "Exception", e)
            return "Error: ${e.message}"
        }
    }

    // SiliconFlow 流式请求 (SSE)
    fun streamSiliconFlowRequest(history: List<Message>, token: String): Flow<StreamUpdate> = flow {
        // 1. 过滤历史消息中的图片，只保留文本
        val textOnlyMessages = history.map { msg ->
            if (msg.content is List<*>) {
                val textContent = (msg.content as List<*>)
                    .filterIsInstance<ContentItem>()
                    .filter { it.type == "text" }
                    .joinToString("\n") { it.text ?: "" }
                Message(msg.role, textContent)
            } else {
                msg
            }
        }

        // 2. 构造请求体
        val request = SiliconChatRequest(
            model = "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B", 
            messages = textOnlyMessages,
            stream = true,
            maxTokens = 8192,
            enableThinking = true, 
            thinkingBudget = 4096, 
            minP = 0.05,
            stop = null,
            temperature = 0.6,
            topP = 0.7,
            topK = 50,
            frequencyPenalty = 0.5,
            n = 1,
            responseFormat = ResponseFormat(type = "text"),
            tools = null 
        )

        try {
            val response = siliconApi.streamChatCompletion("Bearer $token", request)
            if (!response.isSuccessful) {
                val errBody = response.errorBody()?.string()
                Log.e("ModelClient", "Silicon API Error: ${response.code()} $errBody")
                emit(StreamUpdate(error = "API Error: ${response.code()} $errBody"))
                return@flow
            }

            val source = response.body()?.byteStream() ?: return@flow
            val reader = BufferedReader(source.reader())
            
            var line: String?
            // 【关键修复】增强解析循环的健壮性
            while (reader.readLine().also { line = it } != null) {
                val safeLine = line?.trim() ?: continue
                
                // 忽略空行
                if (safeLine.isEmpty()) continue
                
                // 处理 [DONE] 标记
                if (safeLine.contains("[DONE]")) {
                    emit(StreamUpdate(isDone = true))
                    break
                }

                // 必须以 data: 开头
                if (safeLine.startsWith("data:")) {
                    val json = safeLine.substring(5).trim()
                    // 再次检查是否是 [DONE] (有些API data: [DONE])
                    if (json == "[DONE]") {
                        emit(StreamUpdate(isDone = true))
                        break
                    }

                    try {
                        val chunk = gson.fromJson(json, StreamChatResponse::class.java)
                        val choice = chunk.choices.firstOrNull()
                        val delta = choice?.delta
                        val finishReason = choice?.finish_reason

                        if (delta != null) {
                            val content = delta.content ?: ""
                            val thinking = delta.reasoningContent ?: ""
                            
                            // 只有当有内容时才发射
                            if (content.isNotEmpty() || thinking.isNotEmpty()) {
                                emit(StreamUpdate(content = content, thinking = thinking))
                            }
                        }
                        
                        // 检查是否因停止原因结束
                        if (!finishReason.isNullOrEmpty() && finishReason != "null") {
                            emit(StreamUpdate(isDone = true))
                            break
                        }

                    } catch (e: Exception) {
                        // 忽略单行解析错误，不中断整个流
                        Log.w("ModelClient", "Parse error line: $safeLine", e)
                    }
                }
            }
            reader.close()
        } catch (e: Exception) {
            Log.e("ModelClient", "Stream Error", e)
            emit(StreamUpdate(error = "Connection Error: ${e.message}"))
        }
    }
    
    companion object {
        const val SYSTEM_PROMPT = """
你是一个智能体分析专家，可以根据操作历史和当前状态图执行一系列操作来完成任务。
你必须严格按照要求输出以下格式：
<think>{think}</think>
<answer>{action}</answer>

其中：
- {think} 是对你为什么选择这个操作的简短推理说明。
- {action} 是本次执行的具体操作指令，必须严格遵循下方定义的指令格式。

**重要提示：**
- 屏幕底部的悬浮窗是运行你的载体，请**绝对不要**关闭它，也不要对其进行任何点击操作（例如停止按钮）。
- 你的任务是操作其他应用，忽略悬浮窗的存在。

操作指令及其作用如下：
- do(action="Launch", app="xxx")  
    Launch是启动目标app的操作。
- do(action="Tap", element=[x,y])  
    Tap是点击操作，坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。
- do(action="Type", text="xxx")  
    Type是输入操作。
- do(action="Swipe", start=[x1,y1], end=[x2,y2])  
    Swipe是滑动操作。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。
- do(action="Back")  
    返回上一页。
- do(action="Home") 
    回到桌面。
- do(action="Wait", duration="x seconds")  
    等待。
- finish(message="xxx")  
    结束任务。

必须遵循的规则：
1. 如果进入到了无关页面，先执行 Back。
2. 如果页面未加载出内容，Wait。
3. 如果当前页面找不到目标，尝试 Swipe 滑动查找。
"""

        fun bitmapToBase64(bitmap: Bitmap): String {
            val maxDimension = 2560
            val scale = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                val ratio = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
                ratio
            } else {
                1.0f
            }
            
            val finalBitmap = if (scale < 1.0f) {
                val newWidth = (bitmap.width * scale).toInt()
                val newHeight = (bitmap.height * scale).toInt()
                Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            } else {
                bitmap
            }

            val outputStream = ByteArrayOutputStream()
            
            // 【性能优化】使用 WebP 格式替换 JPEG
            // WebP 在同等画质下比 JPEG 体积小 30%~50%，且对文字/UI边缘保留更好。
            // 减小体积可以大幅提升网络上传速度，从而加快 AI 响应。
            val compressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            
            // 质量 80 的 WebP 通常比质量 85 的 JPEG 清晰度更高且体积更小
            finalBitmap.compress(compressFormat, 80, outputStream)
            
            val bytes = outputStream.toByteArray()
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }
}