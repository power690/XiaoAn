package ai.xiaozhi

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import ai.xiaozhi.action.Action
import ai.xiaozhi.action.ActionExecutor
import ai.xiaozhi.action.ActionParser
import ai.xiaozhi.action.AppMapper
import ai.xiaozhi.network.ContentItem
import ai.xiaozhi.network.ImageUrl
import ai.xiaozhi.network.Message
import ai.xiaozhi.network.ModelClient
import ai.xiaozhi.ui.UiMessage
import ai.xiaozhi.utils.ShizukuHelper
import ai.xiaozhi.utils.WakeWordDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.min

class AutoGLMService : AccessibilityService() {

    companion object {
        private val _serviceInstance = MutableStateFlow<AutoGLMService?>(null)
        val serviceInstance = _serviceInstance.asStateFlow()
        
        fun getInstance(): AutoGLMService? = _serviceInstance.value
        
        var onCommandReceived: ((String) -> Unit)? = null
        
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "AutoGLM_Service_Channel"

        const val ACTION_START_LISTENING = "com.sidhu.androidautoglm.START_LISTENING"
        
        const val CMD_CLEAR_HISTORY = "###CMD_CLEAR_HISTORY###"
    }
    
    private val _currentApp = MutableStateFlow<String?>(null)
    val currentApp = _currentApp.asStateFlow()
    
    val messageFlow = MutableSharedFlow<UiMessage>(replay = 10)
    
    // 任务运行状态流
    val taskRunningFlow = MutableStateFlow(false)
    
    private val apiHistory = mutableListOf<Message>()
    
    var floatingWindowController: FloatingWindowController? = null
        private set
    var wakeUpInputController: WakeUpInputController? = null
        private set

    private var isAppInForeground = false
    
    // 【关键修复】默认设为 true。
    // 当服务重启（如刚授权）时，MainActivity 可能还没来得及同步状态。
    // 默认 true 可以确保用户在主界面唤醒时优先使用球体动画，而不是弹出悬浮窗。
    // 如果用户确实在其他界面，MainActivity 会在后续同步状态为 false。
    private var isChatScreenVisible = true
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var taskJob: Job? = null
    
    private var cameraManager: CameraManager? = null
    private var audioManager: AudioManager? = null
    private var notificationManager: NotificationManager? = null
    
    private var actionExecutor: ActionExecutor? = null
    private var modelClient: ModelClient? = null
    private lateinit var prefs: SharedPreferences

    // 【新增】保活使用的 1像素 View
    private var keepAliveView: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("AutoGLMService", "Service connected")
        
        prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        
        actionExecutor = ActionExecutor(this)
        floatingWindowController = FloatingWindowController(this)
        wakeUpInputController = WakeUpInputController(this)
        
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        serviceScope.launch {
            AppMapper.refreshInstalledApps(this@AutoGLMService)
        }
        
        initModelClient()
        initializeWakeUp()

        // 【新增】设置保活悬浮窗，极大提高后台存活率和唤醒灵敏度
        setupKeepAliveWindow()

        _serviceInstance.value = this
    }

    /**
     * 【新增】创建一个不可见但存在的 1x1 像素悬浮窗
     * 这会欺骗系统认为应用处于前台活跃状态，从而防止后台降频或杀进程
     */
    private fun setupKeepAliveWindow() {
        try {
            if (keepAliveView != null) return

            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            keepAliveView = View(this)
            
            // 设置 LayoutParams
            val params = WindowManager.LayoutParams(
                1, // 宽度 1 像素
                1, // 高度 1 像素
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                // 关键 Flags：不获取焦点、不可触摸、布局无限制
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            
            // 放在左上角
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 0
            
            // 设置极低透明度，肉眼不可见但系统认为可见
            params.alpha = 0.01f

            windowManager.addView(keepAliveView, params)
            Log.d("AutoGLMService", "Keep-alive 1px window added.")
        } catch (e: Exception) {
            Log.e("AutoGLMService", "Failed to setup keep-alive window", e)
        }
    }

    private fun removeKeepAliveWindow() {
        try {
            if (keepAliveView != null) {
                val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                windowManager.removeView(keepAliveView)
                keepAliveView = null
                Log.d("AutoGLMService", "Keep-alive 1px window removed.")
            }
        } catch (e: Exception) {
            Log.e("AutoGLMService", "Failed to remove keep-alive window", e)
        }
    }
    
    fun initModelClient() {
        val sharedPrefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        
        val apiKey = sharedPrefs.getString("api_key", "") ?: ""
        if (apiKey.isNotEmpty()) {
            val baseUrl = "https://open.bigmodel.cn/api/paas/v4"
            val modelName = "autoglm-phone"
            modelClient = ModelClient(baseUrl, apiKey, modelName)
        }
    }
    
    fun stopTask() {
        Log.d("AutoGLMService", "Stopping task manually")
        taskJob?.cancel()
        setTaskRunning(false)
        updateFloatingStatus(getString(R.string.status_stopped))
        wakeUpInputController?.onUserStopped()
        serviceScope.launch { 
            messageFlow.emit(UiMessage("assistant", "任务已停止。")) 
        }
    }
    
    fun clearHistory() {
        Log.d("AutoGLMService", "Clearing history and notifying UI")
        apiHistory.clear()
        taskJob?.cancel()
        setTaskRunning(false)
        
        serviceScope.launch {
            messageFlow.emit(UiMessage("system", CMD_CLEAR_HISTORY))
        }
    }

    fun setAppForegroundState(isForeground: Boolean) {
        isAppInForeground = isForeground
        Handler(Looper.getMainLooper()).post {
            floatingWindowController?.updateAppForegroundState(isForeground)
        }
        if (isForeground) {
            initModelClient()
        }
    }

    fun setChatScreenVisibility(isVisible: Boolean) {
        isChatScreenVisible = isVisible
        Log.d("AutoGLMService", "Chat Screen Visible: $isVisible")
    }
    
    private fun startForegroundService() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "AutoGLM Background Service",
                    NotificationManager.IMPORTANCE_LOW
                )
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(channel)
            }

            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("小安 正在运行")
                .setContentText("语音唤醒正在服务...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("AutoGLMService", "Failed to start foreground service", e)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        floatingWindowController?.hide()
        floatingWindowController = null
        wakeUpInputController?.cleanup()
        wakeUpInputController = null
        WakeWordDetector.stopListening()
        
        // 【新增】移除保活悬浮窗
        removeKeepAliveWindow()
        
        taskJob?.cancel()
        serviceScope.cancel()
        stopForeground(true)
        _serviceInstance.value = null
        return super.onUnbind(intent)
    }
    
    private fun initializeWakeUp() {
        val sharedPrefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val wakeUpEnabled = sharedPrefs.getBoolean("wake_up_enabled", false)
        if (wakeUpEnabled) {
            startWakeUpListening()
        }
    }
    
    fun startWakeUpListening() {
        startForegroundService()
        WakeWordDetector.initialize(this)
        
        WakeWordDetector.onWakeWordDetected = {
            if (taskJob?.isActive == true) {
                Log.d("AutoGLMService", "【语音打断】检测到唤醒词，停止当前任务")
                taskJob?.cancel()
                setTaskRunning(false)
                updateFloatingStatus(getString(R.string.status_stopped))
                serviceScope.launch {
                    messageFlow.emit(UiMessage("assistant", "已打断当前任务，请说出新指令"))
                }
            }
        }

        WakeWordDetector.onCommandReceived = { command ->
             Handler(Looper.getMainLooper()).post {
                 wakeUpInputController?.onCommandReceived(command)
             }
        }
        
        WakeWordDetector.onError = {
            Handler(Looper.getMainLooper()).post {
                wakeUpInputController?.onListenError()
            }
        }
        
        WakeWordDetector.bindService(this)
        WakeWordDetector.startWakeWordMode(this)
    }
    
    fun stopWakeUpListening() {
        WakeWordDetector.stopListening()
        Handler(Looper.getMainLooper()).post {
            wakeUpInputController?.hide()
        }
        stopForeground(true)
    }
    
    fun updateWakeWord(wakeWord: String) {
        WakeWordDetector.updateWakeWord(wakeWord)
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    // =================================================================================
    // 核心指令执行逻辑
    // =================================================================================

    fun executeCommand(text: String) {
        if (text.isBlank()) return

        apiHistory.clear()
        taskJob?.cancel()
        
        taskJob = serviceScope.launch {
            messageFlow.emit(UiMessage("user", text))
            
            // 优先处理本地指令（包含清空后台）
            // handleOfflineCommand 返回值: 
            // 0 = 未处理 (不是本地指令)
            // 1 = 处理成功
            // 2 = 处理失败 (如权限不足)
            val offlineResult = handleOfflineCommand(text)
            
            if (offlineResult != 0) {
                withContext(Dispatchers.Main) {
                    if (offlineResult == 1) {
                        wakeUpInputController?.onTaskSuccess() // 播放 2.mp3
                    } else {
                        wakeUpInputController?.onTaskFailed() // 播放 3.mp3
                    }
                }
                return@launch
            }

            if (!isNetworkAvailable()) {
                val err = "(网络未连接.请连接后重试)"
                messageFlow.emit(UiMessage("assistant", err))
                withContext(Dispatchers.Main) { wakeUpInputController?.onTaskFailed() }
                return@launch
            }

            if (modelClient == null) {
                initModelClient()
                if (modelClient == null) {
                    val err = "(未填写API Key前往设置填写才能使用大模型能力)"
                    messageFlow.emit(UiMessage("assistant", err))
                    withContext(Dispatchers.Main) { wakeUpInputController?.onTaskFailed() }
                    return@launch
                }
            }

            startAiTask(text)
        }
    }
    
    private suspend fun startAiTask(goal: String) {
        withContext(Dispatchers.Main) {
            showFloatingWindow(
                onStop = { stopTask() },
                onTimeout = { clearHistory() }
            )
            setTaskRunning(true)
            wakeUpInputController?.onUserStopped()
        }

        val matchedApp = AppMapper.findPackageNameInText(goal)
        if (matchedApp != null) {
            val (packageName, appName) = matchedApp
            Log.d("AutoGLMService", "Launching app: $appName")
            updateFloatingStatus("正在打开 $appName...")
            val launched = launchApp(packageName)
            delay(if (launched) 1500 else 800)
        } else {
            delay(800)
        }

        while (currentCoroutineContext().isActive) {
            
            if (apiHistory.isEmpty()) {
                val dateFormat = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.getDefault())
                val dateStr = getString(R.string.prompt_date_prefix) + dateFormat.format(Date())
                apiHistory.add(Message("system", dateStr + "\n" + ModelClient.SYSTEM_PROMPT))
            }

            var step = 0
            val maxSteps = 20
            var isFinished = false
            var terminatedByMaxSteps = false 
            
            val currentPrompt = goal

            try {
                while (currentCoroutineContext().isActive && step < maxSteps) {
                    step++
                    
                    updateFloatingStatus(getString(R.string.status_thinking))
                    
                    val screenshot = takeScreenshot()
                    if (screenshot == null) {
                        val err = getString(R.string.error_screenshot_failed)
                        updateFloatingStatus(err)
                        messageFlow.emit(UiMessage("assistant", err))
                        return 
                    }
                    
                    val currentAppName = _currentApp.value ?: "Unknown"
                    val contextInfo = if (matchedApp != null && step == 1) {
                         "我已经帮你打开了 ${matchedApp.second}，请根据界面继续操作：$currentPrompt"
                    } else {
                        currentPrompt
                    }
                    val screenInfo = "{\"current_app\": \"$currentAppName\"}"
                    val textPrompt = if (step == 1) "$contextInfo\n\n$screenInfo" else "** Screen Info **\n\n$screenInfo"
                    
                    val userContentItems = mutableListOf<ContentItem>()
                    val base64Image = ModelClient.bitmapToBase64(screenshot)
                    userContentItems.add(ContentItem("image_url", imageUrl = ImageUrl("data:image/jpeg;base64,$base64Image")))
                    userContentItems.add(ContentItem("text", text = textPrompt))
                    
                    apiHistory.add(Message("user", userContentItems))

                    val responseText = modelClient?.sendRequest(apiHistory, screenshot) ?: "Error: Client null"
                    
                    if (responseText.startsWith("Error")) {
                        val isCancelled = responseText.contains("cancelled", ignoreCase = true) ||
                                          responseText.contains("StandaloneCoroutine", ignoreCase = true)

                        if (isCancelled) { return }

                        val isNetworkError = responseText.contains("Unable to resolve host") ||
                                responseText.contains("No address associated with hostname")
                        
                        val err = if (isNetworkError) "(网络未连接.请连接后重试)" else "API 请求失败: $responseText"
                        updateFloatingStatus("API Error")
                        
                        if (!responseText.contains("API 请求失败") && !isNetworkError) {
                            messageFlow.emit(UiMessage("assistant", err))
                        }
                        return 
                    }
                    
                    val (thinking, actionStr) = ActionParser.parseResponseParts(responseText)
                    Log.d("AutoGLMService", "Action: $actionStr")
                    
                    apiHistory.add(Message("assistant", "<think>$thinking</think><answer>$actionStr</answer>"))
                    
                    val screenWidth = getScreenWidth()
                    val screenHeight = getScreenHeight()
                    val action = ActionParser.parse(responseText, screenWidth, screenHeight)
                    
                    val desc = getActionDescription(action)
                    updateFloatingStatus(desc)
                    
                    if (action is Action.Finish) {
                        isFinished = true
                        setTaskRunning(false)
                        updateFloatingStatus(getString(R.string.action_finish))
                        messageFlow.emit(UiMessage("assistant", action.message))
                        withContext(Dispatchers.Main) { wakeUpInputController?.onTaskSuccess() }
                        return 
                    }
                    
                    val executor = actionExecutor
                    if (executor == null) {
                         val err = getString(R.string.error_executor_null)
                         updateFloatingStatus(err)
                         messageFlow.emit(UiMessage("assistant", err))
                         return
                    }

                    val success = executor.execute(action)
                    
                    if (!success) {
                        apiHistory.add(Message("user", getString(R.string.error_last_action_failed)))
                    }
                    
                    removeImagesFromHistory(apiHistory)
                    
                    delay(1000)
                }
                
                if (step >= maxSteps && !isFinished) {
                    terminatedByMaxSteps = true
                }
                
            } catch (e: Exception) {
                if (e is CancellationException || 
                    e.message?.contains("cancelled", ignoreCase = true) == true ||
                    e.message?.contains("StandaloneCoroutine", ignoreCase = true) == true) {
                    return 
                }
                
                Log.e("AutoGLMService", "Task Error", e)
                val err = getString(R.string.error_runtime_exception, e.message)
                updateFloatingStatus(err)
                serviceScope.launch { messageFlow.emit(UiMessage("assistant", err)) }
                withContext(Dispatchers.Main) { wakeUpInputController?.onTaskFailed() }
                return 
            }

            if (terminatedByMaxSteps) {
                Log.d("AutoGLMService", "【自动接力】达到最大步数限制，清空上下文并继续执行...")
                apiHistory.clear()
                updateFloatingStatus(getString(R.string.status_thinking))
                delay(500)
                continue 
            } else {
                setTaskRunning(false)
                break 
            }
        }
    }
    
    private fun parseChineseNumberToInt(text: String): Int? {
        if (text.all { it.isDigit() }) return text.toIntOrNull()

        val numMap = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4, 
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
            '0' to 0, '1' to 1, '2' to 2, '3' to 3, '4' to 4,
            '5' to 5, '6' to 6, '7' to 7, '8' to 8, '9' to 9
        )
        
        var result = 0
        var temp = 0 
        var hasUnit = false 

        for (char in text) {
            if (numMap.containsKey(char)) {
                temp = numMap[char]!!
            } else if (char == '十') {
                if (temp == 0 && !hasUnit) temp = 1 
                result += temp * 10
                temp = 0
                hasUnit = true
            } else if (char == '百') {
                if (temp == 0 && !hasUnit) temp = 1 
                result += temp * 100
                temp = 0
                hasUnit = true
            }
        }
        result += temp
        return result
    }

    /**
     * 处理离线指令
     * 返回 Int 状态码:
     * 0: 未匹配到离线指令
     * 1: 匹配且执行成功
     * 2: 匹配但执行失败 (如权限不足)
     */
    private suspend fun handleOfflineCommand(text: String): Int {
        var cleanText = text.replace("。", "")
                            .replace("？", "")
                            .replace("！", "")
                            .replace("，", "")
                            .replace(".", "")
                            .trim()

        fun matches(pattern: String): Boolean = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(cleanText).find()
        fun checkShizuku(): Boolean = ShizukuHelper.isShizukuAvailable() && ShizukuHelper.checkPermission(this)

        var handledStatus = 0 // 0: None, 1: Success, 2: Failed
        val statusMsg = StringBuilder()

        fun runCmd(pattern: String, needShizuku: Boolean, action: () -> Pair<Boolean, String>) {
            if (matches(pattern)) {
                setTaskRunning(true)
                if (needShizuku && !checkShizuku()) {
                    statusMsg.append("需Shizuku权限; ")
                    handledStatus = 2 // 标记为失败 (权限不足)
                } else {
                    val (success, msg) = action()
                    statusMsg.append(msg)
                    // 如果命令执行返回true则为1，否则(虽然匹配了但操作失败)也视为失败2
                    handledStatus = if (success) 1 else 2
                }
            }
        }
        
        // ------------------ 清空后台逻辑 ------------------
        if (matches(".*(清空|删掉|删除|清理|关掉).*(后台).*")) {
            setTaskRunning(true)
            performClearBackgroundTasks()
            statusMsg.append("后台清理完成")
            handledStatus = 1
        }

        cleanText = cleanText.replace("百分百", "100")
                             .replace("满格", "100")
                             .replace("最大", "100")
                             .replace("满", "100")
                             .replace("百分之", "").replace("%", "")
        
        val cnNumPattern = Pattern.compile("[零一二三四五六七八九十百0-9]+")
        val matcher = cnNumPattern.matcher(cleanText)
        val sb = StringBuffer()
        while (matcher.find()) {
            val matchedStr = matcher.group()
            val containsChinese = matchedStr.any { "零一二三四五六七八九十百".contains(it) }
            if (containsChinese) {
                val num = parseChineseNumberToInt(matchedStr)
                matcher.appendReplacement(sb, num.toString())
            } else {
                matcher.appendReplacement(sb, matchedStr)
            }
        }
        matcher.appendTail(sb)
        cleanText = sb.toString()

        // ------------------ 亮度调节逻辑 ------------------
        
        val setBrightnessMatcher = Pattern.compile(".*(亮度|光).*(?:调到|设为|为|是).*?(\\d+).*").matcher(cleanText)
        if (setBrightnessMatcher.find()) {
            val numStr = setBrightnessMatcher.group(2)
            val percent = numStr?.toIntOrNull()
            if (percent != null) {
                setTaskRunning(true)
                if (setBrightness(percent)) {
                    statusMsg.append("亮度调至 $percent%")
                    handledStatus = 1
                } else {
                    statusMsg.append("调整亮度需权限")
                    handledStatus = 2
                }
            }
        } 
        else if (matches(".*(亮度|光).*(调大|变大|大点|增加|亮点).*")) {
            setTaskRunning(true)
            if (adjustBrightness(true)) {
                statusMsg.append("亮度已调高")
                handledStatus = 1
            } else {
                statusMsg.append("调整亮度需权限")
                handledStatus = 2
            }
        }
        else if (matches(".*(亮度|光).*(调小|变小|小点|减小|暗点).*")) {
            setTaskRunning(true)
            if (adjustBrightness(false)) {
                statusMsg.append("亮度已调低")
                handledStatus = 1
            } else {
                statusMsg.append("调整亮度需权限")
                handledStatus = 2
            }
        }
        
        // ------------------ 音量调节逻辑 ------------------

        val setVolumeMatcher = Pattern.compile(".*(声音|音量).*(?:调到|设为|为|是).*?(\\d+).*").matcher(cleanText)
        
        if (setVolumeMatcher.find()) {
            setTaskRunning(true)
            val numStr = setVolumeMatcher.group(2)
            val percent = numStr?.toIntOrNull()
            if (percent != null && checkShizuku()) {
                setVolumeBySimulation(percent)
                statusMsg.append("音量调至 $percent%")
                handledStatus = 1
            } else if (percent != null) {
                statusMsg.append("调整音量需要 Shizuku 权限")
                handledStatus = 2
            }
        } 
        else if (matches(".*(声音|音量).*(调大|变大|大点|增加).*")) {
            setTaskRunning(true)
            if (checkShizuku()) {
                simulateVolumeKey(24, 2)
                statusMsg.append("音量已调大")
                handledStatus = 1
            } else {
                statusMsg.append("需要 Shizuku 权限")
                handledStatus = 2
            }
        }
        else if (matches(".*(声音|音量).*(调小|变小|小点|减小).*")) {
            setTaskRunning(true)
            if (checkShizuku()) {
                simulateVolumeKey(25, 2)
                statusMsg.append("音量已调小")
                handledStatus = 1
            } else {
                statusMsg.append("需要 Shizuku 权限")
                handledStatus = 2
            }
        }

        runCmd(".*(锁屏|关闭屏幕|关屏).*|.*(lock screen).*", false) { Pair(performLockScreen(), "已锁屏") }
        runCmd(".*(截屏|截图).*|.*(screenshot).*", false) { Pair(performScreenshot(), "已截图") }
        if (!matches(".*(流量|数据).*")) {
             runCmd(".*(打开|开启|连接|连上).*(wifi|无线网).*", true) { Pair(toggleWifi(true), "已开启WiFi") }
            runCmd(".*(关闭|断开|关掉).*(wifi|无线网).*", true) { Pair(toggleWifi(false), "已关闭WiFi") }
        }
        runCmd(".*(打开|开启|连接).*(流量|数据|移动网络).*", true) { Pair(toggleMobileData(true), "已开启数据") }
        runCmd(".*(关闭|断开|关掉).*(流量|数据|移动网络).*", true) { Pair(toggleMobileData(false), "已关闭数据") }
        runCmd(".*(打开|开启).*(nfc|NFC).*", true) { Pair(toggleNFC(true), "已开启NFC") }
        runCmd(".*(关闭|关掉).*(nfc|NFC).*", true) { Pair(toggleNFC(false), "已关闭NFC") }
        runCmd(".*(打开|开启).*(勿扰|DND|免打扰).*", false) { Pair(toggleDoNotDisturb(true), "已开启勿扰") }
        runCmd(".*(关闭|关掉|退出).*(勿扰|DND|免打扰).*", false) { Pair(toggleDoNotDisturb(false), "已关闭勿扰") }
        runCmd(".*(打开|开启|切换).*(振动|震动).*", false) { Pair(setRingerMode(AudioManager.RINGER_MODE_VIBRATE), "已切换到振动") }
        runCmd(".*(关闭|关掉|退出).*(振动|震动).*", false) { Pair(setRingerMode(AudioManager.RINGER_MODE_NORMAL), "已关闭振动") }
        runCmd(".*(打开|开启|切换).*(静音).*", false) { Pair(setRingerMode(AudioManager.RINGER_MODE_SILENT), "已切换到静音") }
        runCmd(".*(关闭|关掉|退出).*(静音).*", false) { Pair(setRingerMode(AudioManager.RINGER_MODE_NORMAL), "已关闭静音") }
        runCmd(".*(打开|开启|切换).*(响铃|正常模式|铃声).*", false) { Pair(setRingerMode(AudioManager.RINGER_MODE_NORMAL), "已切换到响铃") }
        runCmd(".*(打开|开启).*(手电筒|闪光灯|灯).*", false) { Pair(toggleFlashlight(true), "已开启手电筒") }
        runCmd(".*(关闭|关掉).*(手电筒|闪光灯|灯).*", false) { Pair(toggleFlashlight(false), "已关闭手电筒") }
        runCmd(".*(打开|开启|切换|切到).*(深色|黑夜|暗色|夜间).*", true) { Pair(toggleDarkMode(true), "已开启深色模式") }
        runCmd(".*(关闭|关掉|退出).*(深色|黑夜|暗色|夜间).*|.*(打开|开启|切换|切到).*(浅色|亮色|日间|白天).*", true) { Pair(toggleDarkMode(false), "已关闭深色模式") }
        runCmd(".*(打开|开启).*(自动旋转|旋转屏幕).*", true) { Pair(toggleAutoRotate(true), "已开启自动旋转") }
        runCmd(".*(关闭|关掉).*(自动旋转|旋转屏幕).*", true) { Pair(toggleAutoRotate(false), "已关闭自动旋转") }
        runCmd(".*(打开|开启).*(蓝牙).*", true) { Pair(toggleBluetooth(true), "已开启蓝牙") }
        runCmd(".*(关闭|关掉|断开).*(蓝牙).*", true) { Pair(toggleBluetooth(false), "已关闭蓝牙") }
        runCmd(".*(打开|开启).*(飞行模式).*", true) { Pair(toggleAirplaneMode(true), "已开启飞行模式") }
        runCmd(".*(关闭|关掉|退出).*(飞行模式).*", true) { Pair(toggleAirplaneMode(false), "已关闭飞行模式") }
        runCmd(".*(打开|开启).*(省电|低电量).*", true) { Pair(togglePowerSaver(true), "已开启省电模式") }
        runCmd(".*(关闭|关掉|退出).*(省电|低电量).*", true) { Pair(togglePowerSaver(false), "已关闭省电模式") }
        runCmd(".*(打开|开启).*(定位|GPS|位置).*", true) { Pair(toggleLocation(true), "已开启定位") }
        runCmd(".*(关闭|关掉).*(定位|GPS|位置).*", true) { Pair(toggleLocation(false), "已关闭定位") }
        runCmd(".*(关闭|关掉).*(声音|媒体音量).*", false) { Pair(setVolume(AudioManager.STREAM_MUSIC, 0), "媒体音量已关") }

        if (handledStatus != 0) {
            val msg = statusMsg.toString()
            updateFloatingStatus(msg)
            messageFlow.emit(UiMessage("assistant", msg))
            Handler(Looper.getMainLooper()).postDelayed({
                setTaskRunning(false)
            }, 3000)
        }
        // 返回状态码而不是布尔值
        return handledStatus
    }

    /**
     * 智能清空后台
     */
    private suspend fun performClearBackgroundTasks() {
        Log.d("AutoGLMService", "Starting smart clear background tasks...")
        updateFloatingStatus("正在进入后台...")
        
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        delay(1500) 
        
        updateFloatingStatus("查找清理按钮...")
        if (findAndClickClearAll()) {
            updateFloatingStatus("已点击清理按钮")
            delay(1000)
            return
        }
        
        val width = getScreenWidth().toFloat()
        val height = getScreenHeight().toFloat()
        val startX = width / 2
        val startY = height * 0.75f
        val endX = width / 2
        val endY = height * 0.25f
        
        var count = 0
        val maxSwipes = 20
        
        updateFloatingStatus("开始逐个清理...")
        
        while (count < maxSwipes) {
            if (!taskRunningFlow.value) break 
            
            if (!isTaskCardVisible()) {
                Log.d("AutoGLMService", "No task card visible in center. Stopping.")
                break
            }
            
            count++
            updateFloatingStatus("清理中... $count")
            performSwipe(startX, startY, endX, endY, 250)
            delay(500)
        }
        
        delay(500)
        performGlobalHome()
        updateFloatingStatus("清理完成，共清理 $count 个")
    }
    
    private suspend fun findAndClickClearAll(): Boolean {
        val root = rootInActiveWindow ?: return false
        val keywords = listOf("清除", "清理", "关闭全部", "全部关闭", "Clear", "Close all")
        
        for (keyword in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            if (nodes != null && nodes.isNotEmpty()) {
                for (node in nodes) {
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    } else {
                        val parent = node.parent
                        if (parent != null && parent.isClickable) {
                            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            return true
                        }
                    }
                }
            }
        }
        return false
    }
    
    private fun isTaskCardVisible(): Boolean {
        val root = rootInActiveWindow ?: return false
        val screenWidth = getScreenWidth()
        val screenHeight = getScreenHeight()
        
        val centerX = screenWidth / 2
        val centerY = screenHeight / 2
        val checkRect = Rect(centerX - 10, centerY - 10, centerX + 10, centerY + 10)
        
        return findClickableNodeInRect(root, checkRect)
    }
    
    private fun findClickableNodeInRect(node: AccessibilityNodeInfo?, rect: Rect): Boolean {
        if (node == null || !node.isVisibleToUser) return false
        
        val nodeRect = Rect()
        node.getBoundsInScreen(nodeRect)
        
        if (!Rect.intersects(nodeRect, rect)) return false
        
        if (node.isClickable || node.isLongClickable) {
            val screenWidth = getScreenWidth()
            val screenHeight = getScreenHeight()
            if (nodeRect.width() < screenWidth && nodeRect.height() < screenHeight) {
                return true
            }
        }
        
        for (i in 0 until node.childCount) {
            if (findClickableNodeInRect(node.getChild(i), rect)) {
                return true
            }
        }
        return false
    }

    private fun removeImagesFromHistory(history: MutableList<Message>) {
        if (history.size < 2) return
        val lastUserIndex = history.size - 2
        if (lastUserIndex < 0) return
        val lastUserMsg = history[lastUserIndex]
        if (lastUserMsg.role == "user" && lastUserMsg.content is List<*>) {
            try {
                @Suppress("UNCHECKED_CAST")
                val contentList = lastUserMsg.content as List<ContentItem>
                val textOnlyList = contentList.filter { it.type == "text" }
                history[lastUserIndex] = lastUserMsg.copy(content = textOnlyList)
            } catch (e: Exception) { }
        }
    }
    
    private fun getActionDescription(action: Action): String {
        return when (action) {
            is Action.Tap -> getString(R.string.action_tap)
            is Action.DoubleTap -> getString(R.string.action_double_tap)
            is Action.LongPress -> getString(R.string.action_long_press)
            is Action.Swipe -> getString(R.string.action_swipe)
            is Action.Type -> getString(R.string.action_type, action.text)
            is Action.Launch -> getString(R.string.action_launch, action.appName)
            is Action.Back -> getString(R.string.action_back)
            is Action.Home -> getString(R.string.action_home)
            is Action.Wait -> getString(R.string.action_wait)
            is Action.Finish -> getString(R.string.action_finish)
            is Action.Error -> getString(R.string.action_error, action.reason)
            else -> getString(R.string.action_unknown)
        }
    }

    fun hasNotificationPolicyAccess(): Boolean = notificationManager?.isNotificationPolicyAccessGranted == true

    fun toggleFlashlight(enable: Boolean): Boolean {
        return try {
            val cameraId = cameraManager?.cameraIdList?.firstOrNull() ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cameraManager?.setTorchMode(cameraId, enable)
                true
            } else false
        } catch (e: Exception) { false }
    }

    fun toggleWifi(enable: Boolean): Boolean = ShizukuHelper.executeShellCommand(if (enable) "svc wifi enable" else "svc wifi disable")
    fun toggleMobileData(enable: Boolean): Boolean = ShizukuHelper.executeShellCommand(if (enable) "svc data enable" else "svc data disable")
    fun toggleBluetooth(enable: Boolean): Boolean = ShizukuHelper.executeShellCommand(if (enable) "svc bluetooth enable" else "svc bluetooth disable")
    fun toggleDarkMode(enable: Boolean): Boolean = ShizukuHelper.executeShellCommand(if (enable) "cmd uimode night yes" else "cmd uimode night no")

    fun toggleAirplaneMode(enable: Boolean): Boolean {
        val state = if (enable) "enable" else "disable"
        var success = ShizukuHelper.executeShellCommand("cmd connectivity airplane-mode $state")
        if (!success) {
             val value = if (enable) "1" else "0"
             val cmd1 = "settings put global airplane_mode_on $value"
             val cmd2 = "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $value"
             success = ShizukuHelper.executeShellCommand("$cmd1 && $cmd2")
        }
        return success
    }

    fun togglePowerSaver(enable: Boolean): Boolean {
        val mode = if (enable) "1" else "0"
        var success = ShizukuHelper.executeShellCommand("cmd power set-mode $mode")
        if (!success) {
             val value = if (enable) "1" else "0"
             success = ShizukuHelper.executeShellCommand("settings put global low_power $value")
        }
        return success
    }

    fun toggleLocation(enable: Boolean): Boolean = ShizukuHelper.executeShellCommand("cmd location set-location-enabled $enable")
    fun toggleNFC(enable: Boolean): Boolean = ShizukuHelper.executeShellCommand(if (enable) "svc nfc enable" else "svc nfc disable")

    fun toggleDoNotDisturb(enable: Boolean): Boolean {
        if (!hasNotificationPolicyAccess()) return false
        return try {
            notificationManager?.setInterruptionFilter(if (enable) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
            true
        } catch (e: Exception) { false }
    }

    fun setRingerMode(mode: Int): Boolean {
        if (!hasNotificationPolicyAccess()) return false
        return try {
            audioManager?.ringerMode = mode
            true
        } catch (e: Exception) { false }
    }

    fun toggleAutoRotate(enable: Boolean): Boolean = ShizukuHelper.executeShellCommand("settings put system accelerometer_rotation ${if (enable) "1" else "0"}")
    
    fun performScreenshot(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) else false
    
    private fun simulateVolumeKey(keyCode: Int, repeatCount: Int) {
        serviceScope.launch(Dispatchers.IO) {
            repeat(repeatCount) {
                ShizukuHelper.executeShellCommand("input keyevent $keyCode")
                delay(120) 
            }
        }
    }

    private fun setVolumeBySimulation(targetPercent: Int) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val stream = AudioManager.STREAM_MUSIC
                val max = audioManager?.getStreamMaxVolume(stream) ?: 15
                val current = audioManager?.getStreamVolume(stream) ?: 0
                
                val targetStep = (max * (targetPercent.coerceIn(0, 100) / 100f)).toInt()
                val delta = targetStep - current
                
                if (delta == 0) return@launch
                
                val keyCode = if (delta > 0) 24 else 25 
                val count = abs(delta)
                
                Log.d("AutoGLMService", "Simulating volume keys: Current=$current, Target=$targetStep, Press $keyCode x $count times")
                
                repeat(count) {
                    ShizukuHelper.executeShellCommand("input keyevent $keyCode")
                    delay(100) 
                }
            } catch (e: Exception) {
                Log.e("AutoGLMService", "Volume simulation failed", e)
            }
        }
    }

    fun setVolume(streamType: Int, percent: Int): Boolean {
        return try {
            val max = audioManager?.getStreamMaxVolume(streamType) ?: 0
            val target = (max * (percent / 100f)).toInt()
            audioManager?.setStreamVolume(streamType, target, AudioManager.FLAG_SHOW_UI)
            true
        } catch (e: Exception) { false }
    }

    private fun setBrightness(percent: Int): Boolean {
        if (ShizukuHelper.isShizukuAvailable() && ShizukuHelper.checkPermission(this)) {
            val brightnessValue = (percent.coerceIn(0, 100) * 255) / 100
            ShizukuHelper.executeShellCommand("settings put system screen_brightness_mode 0")
            return ShizukuHelper.executeShellCommand("settings put system screen_brightness $brightnessValue")
        }
        
        if (Settings.System.canWrite(this)) {
            return try {
                val brightnessValue = (percent.coerceIn(0, 100) * 255) / 100
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightnessValue)
                true
            } catch (e: Exception) {
                Log.e("AutoGLMService", "Failed to set brightness via API", e)
                false
            }
        }
        return false
    }

    private fun adjustBrightness(increase: Boolean): Boolean {
        val resolver = contentResolver
        
        var currentBrightness = 0
        try {
            currentBrightness = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Settings.SettingNotFoundException) {
            e.printStackTrace()
            return false
        }

        val step = 25
        val target = if (increase) {
            (currentBrightness + step).coerceAtMost(255)
        } else {
            (currentBrightness - step).coerceAtLeast(0)
        }

        if (ShizukuHelper.isShizukuAvailable() && ShizukuHelper.checkPermission(this)) {
            ShizukuHelper.executeShellCommand("settings put system screen_brightness_mode 0")
            return ShizukuHelper.executeShellCommand("settings put system screen_brightness $target")
        }
        
        if (Settings.System.canWrite(this)) {
            return try {
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, target)
                true
            } catch (e: Exception) {
                false
            }
        }
        
        return false
    }

    fun handleWakeUpAction() {
        Handler(Looper.getMainLooper()).post {
            if (isAppInForeground && isChatScreenVisible) {
                val intent = Intent(ACTION_START_LISTENING)
                intent.setPackage(packageName)
                sendBroadcast(intent)
            } 
            else {
                wakeUpInputController?.show { finalCommand ->
                    executeCommand(finalCommand)
                }
            }
        }
    }
    
    fun showWakeUpInput() {
        Handler(Looper.getMainLooper()).post {
            wakeUpInputController?.show { finalCommand ->
                executeCommand(finalCommand)
            }
        }
    }
    
    fun wakeUpAndUnlock(onComplete: () -> Unit) {
        serviceScope.launch {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "AutoGLM:WakeUpLock"
                )
                wakeLock.acquire(3000) 
                delay(300) 

                val width = getScreenWidth()
                val height = getScreenHeight()
                performSwipe(width / 2f, height * 0.9f, width / 2f, height * 0.2f, 300)
                delay(600)
                onComplete()
            } catch (e: Exception) {
                onComplete()
            }
        }
    }

    fun performLockScreen(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false

    fun launchApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                true
            } else false
        } catch (e: Exception) { false }
    }
    
    fun showFloatingWindow(onStop: () -> Unit, onTimeout: () -> Unit) {
        Handler(Looper.getMainLooper()).post {
            if (floatingWindowController == null) floatingWindowController = FloatingWindowController(this)
            floatingWindowController?.updateAppForegroundState(isAppInForeground)
            floatingWindowController?.show(onStop, onTimeout)
        }
    }
    
    fun hideFloatingWindow() {
        Handler(Looper.getMainLooper()).post { floatingWindowController?.hide() }
    }
    
    fun updateFloatingStatus(text: String) {
        Handler(Looper.getMainLooper()).post { floatingWindowController?.updateStatus(text) }
    }

    fun setTaskRunning(running: Boolean) {
        Handler(Looper.getMainLooper()).post { floatingWindowController?.setTaskRunning(running) }
        taskRunningFlow.value = running
    }
    
    fun goHome() { performGlobalAction(GLOBAL_ACTION_HOME) }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.let { _currentApp.value = it.toString() }
    }

    override fun onInterrupt() {}
    
    fun getScreenHeight(): Int {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.heightPixels
        }
    }
    
    fun getScreenWidth(): Int {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels
        }
    }

    private fun showGestureAnimation(startX: Float, startY: Float, endX: Float? = null, endY: Float? = null, duration: Long = 1000) {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val themeIndex = prefs.getInt("theme_color_index", 5) 
        
        val themeColors = listOf(
            0xFFE53935, 
            0xFFFB8C00, 
            0xFFFFA000, 
            0xFF43A047, 
            0xFF00ACC1, 
            0xFF2196F3, 
            0xFF8E24AA  
        )
        
        val themeColorInt = themeColors.getOrElse(themeIndex) { 0xFF2196F3 }.toInt()

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = object : View(this) {
            private val paint = Paint().apply {
                color = themeColorInt 
                style = Paint.Style.FILL
                alpha = 150
            }
            private val trailPaint = Paint().apply {
                color = themeColorInt 
                style = Paint.Style.STROKE
                strokeWidth = 20f
                alpha = 100
                strokeCap = Paint.Cap.ROUND
            }
            private var currentX = startX
            private var currentY = startY
            private var currentRadius = 30f
            
            init {
                if (endX != null && endY != null) {
                    val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
                    animator.duration = duration
                    animator.addUpdateListener { animation ->
                        val fraction = animation.animatedValue as Float
                        currentX = startX + (endX - startX) * fraction
                        currentY = startY + (endY - startY) * fraction
                        invalidate()
                    }
                    animator.start()
                } else {
                    val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
                    animator.duration = duration
                    animator.addUpdateListener { animation ->
                        val fraction = animation.animatedValue as Float
                        currentRadius = 30f + 30f * fraction
                        paint.alpha = (150 * (1 - fraction)).toInt()
                        invalidate()
                    }
                    animator.start()
                }
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (endX != null && endY != null) {
                    canvas.drawLine(startX, startY, currentX, currentY, trailPaint)
                }
                canvas.drawCircle(currentX, currentY, currentRadius, paint)
            }
        }

        val params = WindowManager.LayoutParams(
            getScreenWidth(),
            getScreenHeight(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0

        try {
            windowManager.addView(view, params)
            Handler(Looper.getMainLooper()).postDelayed({
                try { windowManager.removeView(view) } catch (e: Exception) { }
            }, duration + 200)
        } catch (e: Exception) { }
    }
    
    suspend fun takeScreenshot(): Bitmap? {
        val screenshot = suspendCoroutine<Bitmap?> { continuation ->
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val displayId = windowManager.defaultDisplay.displayId
            
            takeScreenshot(displayId, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        val softwareBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, true)
                        screenshot.hardwareBuffer.close()
                        
                        if (softwareBitmap != null) {
                            applyDynamicIslandMask(softwareBitmap)
                        }
                        
                        continuation.resume(softwareBitmap)
                    } catch (e: Exception) { continuation.resume(null) }
                }
                override fun onFailure(errorCode: Int) { continuation.resume(null) }
            })
        }
        return screenshot
    }

    private fun applyDynamicIslandMask(bitmap: Bitmap) {
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint().apply {
                color = Color.BLACK 
                style = Paint.Style.FILL
                isAntiAlias = true 
            }
            
            val width = bitmap.width
            val height = bitmap.height

            val capsuleWidth = (width * 0.35f)
            val safeHeight = (height * 0.045f).coerceAtLeast(110f)

            val left = (width - capsuleWidth) / 2f
            val right = left + capsuleWidth
            val top = 0f 
            val bottom = safeHeight

            val cornerRadius = safeHeight / 2f
            
            val rectF = RectF(left, top, right, bottom)
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
            
        } catch (e: Exception) {
            Log.e("AutoGLMService", "Failed to mask island", e)
        }
    }

    suspend fun performTap(x: Float, y: Float): Boolean {
        val safeY = y.coerceAtLeast(5f).coerceAtMost((getScreenHeight() - 5).toFloat())
        val safeX = x.coerceAtLeast(0f).coerceAtMost((getScreenWidth() - 1).toFloat())

        Handler(Looper.getMainLooper()).post { showGestureAnimation(safeX, safeY) }
        
        val result = suspendCoroutine<Boolean> { continuation ->
            val path = Path().apply { moveTo(safeX, safeY); lineTo(safeX, safeY) }
            val builder = GestureDescription.Builder()
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { continuation.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { continuation.resume(false) }
            }, null)
        }
        return result
    }
    
    suspend fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 1000): Boolean {
        Handler(Looper.getMainLooper()).post { showGestureAnimation(startX, startY, endX, endY, duration) }
        val result = suspendCoroutine<Boolean> { continuation ->
            val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
            val builder = GestureDescription.Builder()
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { continuation.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { continuation.resume(false) }
            }, null)
        }
        return result
    }

    suspend fun performLongPress(x: Float, y: Float, duration: Long = 1000): Boolean {
        return performSwipe(x, y, x, y, duration)
    }
    
    fun performGlobalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performGlobalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
}