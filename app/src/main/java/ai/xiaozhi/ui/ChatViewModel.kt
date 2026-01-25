package ai.xiaozhi.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.xiaozhi.AutoGLMService
import ai.xiaozhi.R
import ai.xiaozhi.network.Message
import ai.xiaozhi.network.ModelClient
import ai.xiaozhi.utils.IconSwitchHelper
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 定义应用支持的七种主题颜色
val AppThemeColors = listOf(
    Color(0xFFE53935), // 0. 殷红 (Red) - 节日专用
    Color(0xFFFB8C00), // 1. 橘橙 (Orange)
    Color(0xFFFFA000), // 2. 琥珀金 (Amber)
    Color(0xFF43A047), // 3. 翡翠绿 (Green)
    Color(0xFF00ACC1), // 4. 湖水青 (Cyan)
    Color(0xFF2196F3), // 5. 经典蓝 (Blue) - 默认
    Color(0xFF8E24AA)  // 6. 罗兰紫 (Purple)
)

// 2026-2099 除夕公历日期表 (格式: 0xMMDD, 月份1-12)
// 数据来源：万年历推算
private val ChuxiTable = mapOf(
    2026 to 216, 2027 to 205, 2028 to 125, 2029 to 212, 2030 to 202,
    2031 to 122, 2032 to 210, 2033 to 130, 2034 to 218, 2035 to 207,
    2036 to 127, 2037 to 214, 2038 to 203, 2039 to 123, 2040 to 211,
    2041 to 131, 2042 to 221, 2043 to 209, 2044 to 129, 2045 to 216,
    2046 to 205, 2047 to 125, 2048 to 213, 2049 to 201, 2050 to 122,
    2051 to 210, 2052 to 131, 2053 to 218, 2054 to 207, 2055 to 127,
    2056 to 214, 2057 to 203, 2058 to 123, 2059 to 211, 2060 to 201,
    2061 to 220, 2062 to 208, 2063 to 128, 2064 to 216, 2065 to 204,
    2066 to 125, 2067 to 213, 2068 to 202, 2069 to 122, 2070 to 210,
    2071 to 130, 2072 to 218, 2073 to 206, 2074 to 126, 2075 to 214,
    2076 to 204, 2077 to 123, 2078 to 211, 2079 to 201, 2080 to 221,
    2081 to 208, 2082 to 128, 2083 to 216, 2084 to 205, 2085 to 125,
    2086 to 213, 2087 to 202, 2088 to 123, 2089 to 210, 2090 to 129,
    2091 to 217, 2092 to 206, 2093 to 126, 2094 to 214, 2095 to 204,
    2096 to 124, 2097 to 211, 2098 to 131, 2099 to 220
)

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val isLoading: Boolean = false,
    val isRunning: Boolean = false,
    val error: String? = null,
    val missingAccessibilityService: Boolean = false,
    val missingOverlayPermission: Boolean = false,
    val apiKey: String = "",
    val siliconFlowToken: String = "",
    val userAvatarPath: String? = null,
    val themeColorIndex: Int = 5 // 默认索引 5 对应蓝色
)

data class UiMessage(
    val role: String,
    val content: String,
    val thinking: String = "",
    val thinkingTimeDisplay: String = "", 
    val isGenerating: Boolean = false, 
    val image: Bitmap? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()

    private val _listeningTrigger = MutableSharedFlow<Unit>()
    val listeningTrigger = _listeningTrigger.asSharedFlow()

    private val _isDeepThinkingMode = MutableStateFlow(false)
    val isDeepThinkingMode = _isDeepThinkingMode.asStateFlow()

    private var currentJob: Job? = null

    private val prefs by lazy {
        getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    }
    
    var onTaskCompleted: ((Boolean) -> Unit)? = null
    
    // 【关键修复】新增变量：用于暂存待切换的图标索引，防止立即切换导致闪退
    var pendingIconIndex: Int? = null
    
    init {
        val savedKey = prefs.getString("api_key", "") ?: ""
        val savedSiliconToken = prefs.getString("silicon_flow_token", "") ?: ""
        val savedAvatarPath = prefs.getString("user_avatar_path", null)
        
        var savedColorIndex = prefs.getInt("theme_color_index", 5) // 默认蓝色
        
        // ==========================================
        // 2026-2099 节日自动变色逻辑
        // ==========================================
        checkAndApplyHolidayTheme { newIndex ->
            savedColorIndex = newIndex
        }
        // ==========================================

        val validAvatarPath = if (savedAvatarPath != null && File(savedAvatarPath).exists()) {
            savedAvatarPath
        } else {
            null
        }
        
        _uiState.value = _uiState.value.copy(
            apiKey = savedKey,
            siliconFlowToken = savedSiliconToken,
            userAvatarPath = validAvatarPath,
            themeColorIndex = savedColorIndex.coerceIn(AppThemeColors.indices)
        )
        
        viewModelScope.launch {
            AutoGLMService.serviceInstance.collect { service ->
                if (service != null) {
                    val currentError = _uiState.value.error
                    if (currentError != null && (currentError.contains("无障碍服务") || currentError.contains("Accessibility Service"))) {
                        _uiState.value = _uiState.value.copy(error = null)
                    }
                    
                    launch {
                        service.taskRunningFlow.collect { isServiceRunning ->
                            if (!_isDeepThinkingMode.value) {
                                _uiState.value = _uiState.value.copy(isRunning = isServiceRunning)
                            }
                        }
                    }
                    
                    launch {
                        service.messageFlow.collect { message ->
                            if (message.content == AutoGLMService.CMD_CLEAR_HISTORY) {
                                _uiState.value = _uiState.value.copy(
                                    messages = emptyList(),
                                    error = null,
                                    isLoading = false
                                )
                            } else {
                                _uiState.value = _uiState.value.copy(
                                    messages = _uiState.value.messages + message,
                                    isLoading = message.role == "user"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 核心逻辑：检查是否在春节窗口期，并处理自动变色/恢复
     * 规则：
     * 1. 除夕开始 -> 自动变红 (红色 index = 0)，且重置"用户已修改"标记。
     * 2. 持续30天。
     * 3. 30天后 -> 自动恢复蓝 (蓝色 index = 5)。
     * 注意：如果期间用户手动修改过颜色，到期后不恢复，尊重用户选择。
     */
    private fun checkAndApplyHolidayTheme(onColorChanged: (Int) -> Unit) {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        
        // 仅处理 2026-2099 年
        if (year !in 2026..2099) return
        
        val monthCode = calendar.get(Calendar.MONTH) + 1 // Calendar.MONTH 是 0-11
        val dayCode = calendar.get(Calendar.DAY_OF_MONTH)
        val todayCode = monthCode * 100 + dayCode // 将日期转为 MMDD 整数，如 2月16日 -> 216

        // 获取当年的除夕日期
        val chuxiDate = ChuxiTable[year] ?: return // 如果表中没有，跳过

        // 构造 Calendar 对象进行 30天后 的计算
        val startDate = Calendar.getInstance().apply {
            set(year, (chuxiDate / 100) - 1, chuxiDate % 100, 0, 0, 0)
        }
        val endDate = (startDate.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 30) // 持续30天
        }

        // 状态 Key (每年独立)
        val KEY_RED_DONE = "theme_evt_red_done_$year"
        val KEY_RESTORE_DONE = "theme_evt_restore_done_$year"
        val KEY_USER_MODIFIED = "theme_evt_user_mod_$year"

        val now = Calendar.getInstance()
        
        // --- 逻辑 A: 进入节日窗口 (除夕 ~ 30天内) ---
        // 只要今天 >= 除夕 且 今天 <= 结束日
        // 且之前没变过红，就强制变红
        if (now >= startDate && now <= endDate) {
            val hasTurnedRed = prefs.getBoolean(KEY_RED_DONE, false)
            if (!hasTurnedRed) {
                // 执行变红
                val newIndex = 0 // 红色
                prefs.edit()
                    .putInt("theme_color_index", newIndex) 
                    .putBoolean(KEY_RED_DONE, true)
                    .putBoolean(KEY_USER_MODIFIED, false) // 重置用户修改标记，因为这是新的一轮节日
                    .apply()
                onColorChanged(newIndex)
                // 触发图标更新 (启动时检查不需要防闪退，直接切)
                IconSwitchHelper.changeIcon(getApplication(), newIndex)
            }
        }

        // --- 逻辑 B: 节日窗口结束 (超过30天) ---
        // 只要今天 > 结束日 且 之前没执行过恢复
        else if (now > endDate) {
            val hasRestored = prefs.getBoolean(KEY_RESTORE_DONE, false)
            if (!hasRestored) {
                // 检查：用户在节日期间是否手动改过颜色？
                val userModified = prefs.getBoolean(KEY_USER_MODIFIED, false)
                
                if (!userModified) {
                    // 如果没改过，恢复默认蓝
                    val defaultIndex = 5 // 蓝色
                    prefs.edit().putInt("theme_color_index", defaultIndex).apply() 
                    onColorChanged(defaultIndex)
                    // 触发图标更新
                    IconSwitchHelper.changeIcon(getApplication(), defaultIndex)
                }
                
                // 标记已处理恢复，防止每次启动都重置
                prefs.edit().putBoolean(KEY_RESTORE_DONE, true).apply()
            }
        }
    }

    // 用户手动更改主题颜色
    fun updateThemeColor(index: Int) {
        if (index in AppThemeColors.indices) {
            _uiState.value = _uiState.value.copy(themeColorIndex = index)
            
            val editor = prefs.edit()
            editor.putInt("theme_color_index", index)
            
            // 记录用户手动修改
            val year = Calendar.getInstance().get(Calendar.YEAR)
            if (year in 2026..2099) {
                editor.putBoolean("theme_evt_user_mod_$year", true)
            }
            
            editor.apply()
            
            // 【关键修复】不要立即调用 IconSwitchHelper.changeIcon，否则应用会因为Manifest变化被系统立即杀死
            // 我们记录下意图，等待 Activity onStop (退到后台) 时再执行
            pendingIconIndex = index
        }
    }
    
    // 【关键修复】新增方法：应用挂起的图标修改
    // 这个方法应该在 MainActivity 的 onStop() 中调用
    fun applyPendingIconChange(context: Context) {
        pendingIconIndex?.let { index ->
            IconSwitchHelper.changeIcon(context, index)
            pendingIconIndex = null
        }
    }
   
    fun toggleDeepThinkingMode() {
        _isDeepThinkingMode.value = !_isDeepThinkingMode.value
    }

    fun updateApiKey(apiKey: String) {
        _uiState.value = _uiState.value.copy(apiKey = apiKey)
        prefs.edit().putString("api_key", apiKey).apply()
        AutoGLMService.getInstance()?.initModelClient()
    }

    fun updateSiliconFlowToken(token: String) {
        _uiState.value = _uiState.value.copy(siliconFlowToken = token)
        prefs.edit().putString("silicon_flow_token", token).apply()
    }
    
    fun triggerListening() {
        viewModelScope.launch {
            _listeningTrigger.emit(Unit)
        }
    }

    fun updateUserAvatar(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val inputStream = context.contentResolver.openInputStream(uri)
                val filesDir = context.filesDir
                val fileName = "user_avatar_${System.currentTimeMillis()}.jpg"
                val file = File(filesDir, fileName)
                
                inputStream?.use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }

                val newPath = file.absolutePath
                prefs.edit().putString("user_avatar_path", newPath).apply()
                
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(userAvatarPath = newPath)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    fun clearHistory() {
        _uiState.value = _uiState.value.copy(
            messages = emptyList(),
            error = null,
            isLoading = false,
            isRunning = false
        )
        AutoGLMService.getInstance()?.clearHistory()
    }

    fun stopGeneration() {
        if (currentJob?.isActive == true) {
            currentJob?.cancel()
            currentJob = null
        }

        AutoGLMService.getInstance()?.stopTask()

        val oldList = _uiState.value.messages
        val newList = if (oldList.isNotEmpty() && oldList.last().isGenerating) {
            val lastMsg = oldList.last()
            oldList.dropLast(1) + lastMsg.copy(isGenerating = false, content = lastMsg.content + "\n[已手动停止]")
        } else {
            oldList
        }

        _uiState.value = _uiState.value.copy(
            messages = newList,
            isLoading = false,
            isRunning = false
        )
    }

    fun checkServiceStatus() {
        val context = getApplication<Application>()
        if (isAccessibilityServiceEnabled(context, AutoGLMService::class.java)) {
            _uiState.value = _uiState.value.copy(missingAccessibilityService = false)
            val currentError = _uiState.value.error
            if (currentError != null && (currentError.contains("无障碍服务") || currentError.contains("Accessibility Service"))) {
                 _uiState.value = _uiState.value.copy(error = null)
            }
        } else {
             _uiState.value = _uiState.value.copy(missingAccessibilityService = true)
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expectedComponentName = ComponentName(context, serviceClass)
        val enabledServicesSetting = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName)
                return true
        }
        return false
    }

    fun checkOverlayPermission(context: Context) {
        if (!Settings.canDrawOverlays(context)) {
            _uiState.value = _uiState.value.copy(missingOverlayPermission = true)
        } else {
            _uiState.value = _uiState.value.copy(missingOverlayPermission = false)
            _uiState.value = _uiState.value.copy(error = null)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        if (_isDeepThinkingMode.value) {
            val userMsg = UiMessage("user", text)
            var currentMessages = _uiState.value.messages + userMsg
            
            _uiState.value = _uiState.value.copy(
                messages = currentMessages,
                isLoading = true,
                isRunning = true
            )

            if (!isNetworkAvailable(getApplication())) {
                val errorMsg = UiMessage("assistant", "(网络未连接.请连接后重试)", isGenerating = false)
                _uiState.value = _uiState.value.copy(
                    messages = currentMessages + errorMsg,
                    isLoading = false,
                    isRunning = false
                )
                return
            }

            val token = _uiState.value.siliconFlowToken
            if (token.isBlank()) {
                val errorMsg = UiMessage("assistant", "(未填写 DeepSeek API Key, 前往设置填写才能使用深度思考)", isGenerating = false)
                _uiState.value = _uiState.value.copy(
                    messages = currentMessages + errorMsg,
                    isLoading = false,
                    isRunning = false
                )
                return
            }

            val initialAiMsg = UiMessage(
                role = "assistant", 
                content = "", 
                thinking = "", 
                thinkingTimeDisplay = "正在思考 0.0秒", 
                isGenerating = true
            )
            
            _uiState.value = _uiState.value.copy(
                messages = currentMessages + initialAiMsg
            )

            currentJob = viewModelScope.launch(Dispatchers.IO) {
                var timerJob: Job? = null
                var startTime = 0L 
                var isThinkingPhase = true 
                
                try {
                    val messagesSnapshot = _uiState.value.messages
                    val historyMessages = messagesSnapshot.dropLast(1)
                    val history = historyMessages.dropLast(1).map { Message(it.role, it.content) } + Message("user", text)
                    
                    val client = ModelClient("https://open.bigmodel.cn/api/paas/v4", "", "") 

                    client.streamSiliconFlowRequest(history, token).collect { update ->
                        if (!isActive) return@collect 

                        if (startTime == 0L && (update.thinking.isNotEmpty() || update.content.isNotEmpty())) {
                            startTime = System.currentTimeMillis()
                            
                            timerJob = launch {
                                while (isActive && isThinkingPhase) {
                                    delay(100) 
                                    val duration = (System.currentTimeMillis() - startTime) / 1000.0
                                    val formattedDuration = String.format("%.1f", duration)
                                    
                                    withContext(Dispatchers.Main) {
                                        val oldList = _uiState.value.messages
                                        if (oldList.isNotEmpty() && oldList.last().role == "assistant") {
                                            val lastMsg = oldList.last()
                                            if (lastMsg.isGenerating && isThinkingPhase) {
                                                val newMsg = lastMsg.copy(thinkingTimeDisplay = "正在思考 ${formattedDuration}秒")
                                                _uiState.value = _uiState.value.copy(
                                                    messages = oldList.dropLast(1) + newMsg
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            if (update.error != null) {
                                _uiState.value = _uiState.value.copy(
                                    messages = _uiState.value.messages.dropLast(1) + UiMessage("assistant", update.error, isGenerating = false),
                                    isLoading = false,
                                    isRunning = false
                                )
                                isThinkingPhase = false
                                timerJob?.cancel()
                            } else if (update.isDone) {
                                isThinkingPhase = false
                                timerJob?.cancel()
                                
                                val oldList = _uiState.value.messages
                                if (oldList.isNotEmpty() && oldList.last().role == "assistant") {
                                    val lastMsg = oldList.last()
                                    val finalDuration = if (startTime > 0) (System.currentTimeMillis() - startTime) / 1000.0 else 0.0
                                    val finalFormatted = String.format("%.1f", finalDuration)
                                    
                                    val finalDisplay = if (lastMsg.thinkingTimeDisplay.startsWith("已完成")) 
                                        lastMsg.thinkingTimeDisplay 
                                    else 
                                        "已完成思考 ${finalFormatted}秒"

                                    val newMsg = lastMsg.copy(
                                        isGenerating = false,
                                        thinkingTimeDisplay = finalDisplay
                                    )
                                    _uiState.value = _uiState.value.copy(
                                        messages = oldList.dropLast(1) + newMsg,
                                        isLoading = false,
                                        isRunning = false
                                    )
                                } else {
                                    _uiState.value = _uiState.value.copy(isLoading = false, isRunning = false)
                                }
                            } else {
                                val oldList = _uiState.value.messages
                                if (oldList.isNotEmpty()) {
                                    val lastMsg = oldList.last()
                                    if (lastMsg.role == "assistant") {
                                        val newContent = lastMsg.content + update.content
                                        val newThinking = lastMsg.thinking + update.thinking
                                        
                                        var currentThinkingDisplay = lastMsg.thinkingTimeDisplay
                                        
                                        if (isThinkingPhase && update.content.isNotEmpty()) {
                                            isThinkingPhase = false 
                                            timerJob?.cancel() 
                                            
                                            val stopTime = System.currentTimeMillis()
                                            val finalDuration = if (startTime > 0) (stopTime - startTime) / 1000.0 else 0.0
                                            val finalFormatted = String.format("%.1f", finalDuration)
                                            currentThinkingDisplay = "已完成思考 ${finalFormatted}秒"
                                        }
                                        
                                        val newMsg = lastMsg.copy(
                                            content = newContent, 
                                            thinking = newThinking,
                                            thinkingTimeDisplay = currentThinkingDisplay
                                        )
                                        
                                        _uiState.value = _uiState.value.copy(
                                            messages = oldList.dropLast(1) + newMsg
                                        )
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    timerJob?.cancel()
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false, 
                            isRunning = false,
                            error = "请求异常: ${e.message}"
                        )
                    }
                } finally {
                    timerJob?.cancel()
                    if (_uiState.value.isRunning && isActive) {
                         withContext(Dispatchers.Main) {
                              _uiState.value = _uiState.value.copy(isRunning = false)
                         }
                    }
                }
            }
            return
        }
        
        val service = AutoGLMService.getInstance()
        if (service == null) {
            checkServiceStatus()
            if (_uiState.value.missingAccessibilityService) {
                return
            }
            
            val userMsg = UiMessage("user", text)
            val errorMsg = UiMessage("assistant", getApplication<Application>().getString(R.string.error_service_not_connected), isGenerating = false)
            
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + userMsg + errorMsg,
                isLoading = false,
                isRunning = false 
            )
            return
        }
        
        service.executeCommand(text)
    }
}
