package ai.xiaozhi.network

import com.google.gson.annotations.SerializedName

// === AutoGLM (BigModel) Request ===
data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    @SerializedName("max_tokens") val maxTokens: Int = 3000,
    val temperature: Double = 0.0,
    @SerializedName("top_p") val topP: Double = 0.85,
    @SerializedName("frequency_penalty") val frequencyPenalty: Double = 0.2,
    val stream: Boolean = false
)

data class Message(
    val role: String,
    val content: Any // Can be String or List<ContentItem>
)

data class ContentItem(
    val type: String,
    val text: String? = null,
    @SerializedName("image_url") val imageUrl: ImageUrl? = null
)

data class ImageUrl(
    val url: String
)

data class ChatResponse(
    val choices: List<Choice>
)

data class Choice(
    val message: MessageResponse
)

data class MessageResponse(
    val content: String
)

// === SiliconFlow (DeepSeek) Streaming Request/Response ===

data class SiliconChatRequest(
    val model: String,
    val messages: List<Message>,
    val stream: Boolean = true, // 强制开启流式
    @SerializedName("max_tokens") val maxTokens: Int,
    @SerializedName("enable_thinking") val enableThinking: Boolean = true, // 开启思考
    @SerializedName("thinking_budget") val thinkingBudget: Int,
    @SerializedName("min_p") val minP: Double,
    val stop: String? = null,
    val temperature: Double,
    @SerializedName("top_p") val topP: Double,
    @SerializedName("top_k") val topK: Int,
    @SerializedName("frequency_penalty") val frequencyPenalty: Double,
    val n: Int,
    @SerializedName("response_format") val responseFormat: ResponseFormat,
    val tools: List<Tool>? = null
)

data class ResponseFormat(
    val type: String = "text"
)

data class Tool(
    val type: String,
    val function: ToolFunction
)

data class ToolFunction(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>,
    val strict: Boolean = false
)

// --- 流式响应数据结构 ---
data class StreamChatResponse(
    val id: String,
    val choices: List<StreamChoice>
)

data class StreamChoice(
    val delta: StreamDelta,
    val finish_reason: String?
)

// 【关键修复】字段必须可空，否则解析时会Crash
data class StreamDelta(
    val content: String?,
    @SerializedName("reasoning_content") val reasoningContent: String? // 深度思考内容
)