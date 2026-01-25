package ai.xiaozhi

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ai.xiaozhi.action.AppMapper
import ai.xiaozhi.ui.AppThemeColors
import ai.xiaozhi.ui.ChatScreen
import ai.xiaozhi.ui.ChatViewModel
import ai.xiaozhi.ui.PrivacyScreen
import ai.xiaozhi.ui.SettingsScreen
import ai.xiaozhi.ui.UserManualScreen
import ai.xiaozhi.ui.VoiceOrb 
import ai.xiaozhi.utils.ShizukuHelper
import ai.xiaozhi.utils.WakeWordDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.util.Locale
import kotlin.math.max

class MainActivity : ComponentActivity() {
    
    private val viewModel: ChatViewModel by viewModels()
    private lateinit var localBroadcastManager: LocalBroadcastManager
    private val wakeUpReceiver = WakeUpReceiver()
    private val directListenReceiver = DirectListenReceiver()
    
    private var isActivityResumed = false
    
    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach {
            Log.d("MainActivity", "Permission ${it.key} granted: ${it.value}")
        }
    }
    
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1002) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                Log.d("MainActivity", "Shizuku permission granted. Attempting to auto-enable Accessibility Service...")
                tryAutoEnableAccessibility()
            } else {
                Log.w("MainActivity", "Shizuku permission denied")
            }
        }
    }
    
    private inner class WakeUpReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.sidhu.androidautoglm.WAKE_UP_COMMAND" -> {
                    val command = intent.getStringExtra("command")
                    command?.let {
                        Log.d("MainActivity", "Received wake-up command via broadcast: $it")
                        runOnUiThread {
                            viewModel.sendMessage(it)
                        }
                    }
                }
            }
        }
    }
    
    private inner class DirectListenReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AutoGLMService.ACTION_START_LISTENING) {
                Log.d("MainActivity", "Received DIRECT_LISTEN intent. Triggering VoiceOrb.")
                runOnUiThread {
                    viewModel.triggerListening()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        
        val locale = Locale.CHINESE
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)

        val isNightMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (isNightMode) {
            window.decorView.setBackgroundColor(Color.parseColor("#121212"))
        } else {
            window.decorView.setBackgroundColor(Color.parseColor("#E3F2FD"))
        }

        super.onCreate(savedInstanceState)

        initHighRefreshRate()
        
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val privacyAgreed = prefs.getBoolean("privacy_agreed", false)
        
        if (ShizukuHelper.isShizukuAvailable()) {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        }
        
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        
        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        
        checkAndRequestPermissions()
        
        lifecycleScope.launch(Dispatchers.IO) {
            delay(1500) 
            Log.d("MainActivity", "Delayed task: Starting to refresh installed apps...")
            AppMapper.refreshInstalledApps(this@MainActivity)
            Log.d("MainActivity", "Installed apps refresh complete.")
        }
        
        AutoGLMService.onCommandReceived = { command ->
            Log.d("MainActivity", "Received wake-up command via callback: $command")
            runOnUiThread {
                viewModel.sendMessage(command)
            }
        }
        
        viewModel.onTaskCompleted = { success ->
            Log.d("MainActivity", "Task completed: success=$success")
            val service = AutoGLMService.getInstance()
            if (service != null) {
                runOnUiThread {
                    if (success) {
                        service.wakeUpInputController?.onTaskSuccess()
                    } else {
                        service.wakeUpInputController?.onTaskFailed()
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                AutoGLMService.serviceInstance.collect { service ->
                    if (service != null && isActivityResumed) {
                        Log.d("MainActivity", "Service connected/updated while resumed. Syncing foreground state: TRUE")
                        service.setAppForegroundState(true)
                    }
                }
            }
        }
        
        setContent {
            val isDarkTheme = isSystemInDarkTheme()
            val view = LocalView.current
            
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as Activity).window
                    val insetsController = WindowCompat.getInsetsController(window, view)
                    insetsController.isAppearanceLightStatusBars = !isDarkTheme
                    insetsController.isAppearanceLightNavigationBars = !isDarkTheme
                }
            }
            
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showSplash by remember { mutableStateOf(true) }
                    val uiState by viewModel.uiState.collectAsState()

                    LaunchedEffect(Unit) {
                        delay(2000) 
                        showSplash = false
                    }

                    Crossfade(
                        targetState = showSplash, 
                        animationSpec = tween(600),
                        label = "SplashTransition"
                    ) { isSplash ->
                        if (isSplash) {
                            // 传入当前主题索引
                            SplashScreen(uiState.themeColorIndex)
                        } else {
                            val navController = rememberNavController()
                            
                            // 监听导航状态
                            LaunchedEffect(navController) {
                                navController.addOnDestinationChangedListener { _, destination, _ ->
                                    val isChat = destination.route == "chat"
                                    Log.d("MainActivity", "Nav Destination Changed: ${destination.route}, isChat=$isChat")
                                    AutoGLMService.getInstance()?.setChatScreenVisibility(isChat)
                                }
                            }
                            
                            val animDuration = 400

                            NavHost(
                                navController = navController,
                                startDestination = if (privacyAgreed) "chat" else "privacy",
                                enterTransition = {
                                    slideIntoContainer(
                                        AnimatedContentTransitionScope.SlideDirection.Left,
                                        animationSpec = tween(animDuration)
                                    )
                                },
                                exitTransition = {
                                    slideOutOfContainer(
                                        AnimatedContentTransitionScope.SlideDirection.Left,
                                        animationSpec = tween(animDuration)
                                    )
                                },
                                popEnterTransition = {
                                    slideIntoContainer(
                                        AnimatedContentTransitionScope.SlideDirection.Right,
                                        animationSpec = tween(animDuration)
                                    )
                                },
                                popExitTransition = {
                                    slideOutOfContainer(
                                        AnimatedContentTransitionScope.SlideDirection.Right,
                                        animationSpec = tween(animDuration)
                                    )
                                }
                            ) {
                                composable("privacy") {
                                    PrivacyScreen(
                                        themeIndex = uiState.themeColorIndex, // 传入主题索引
                                        onAgree = {
                                            prefs.edit().putBoolean("privacy_agreed", true).apply()
                                            navController.navigate("chat") {
                                                popUpTo("privacy") { inclusive = true }
                                            }
                                        }
                                    )
                                }
                                composable("chat") {
                                    ChatScreen(
                                        viewModel = viewModel,
                                        onOpenSettings = { navController.navigate("settings") }
                                    )
                                }
                                composable("settings") {
                                    SettingsScreen(
                                        apiKey = uiState.apiKey,
                                        onSave = { newKey -> viewModel.updateApiKey(newKey) },
                                        onBack = { navController.popBackStack() },
                                        onOpenManual = { navController.navigate("manual") }
                                    )
                                }
                                composable("manual") {
                                    UserManualScreen(
                                        onBack = { navController.popBackStack() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        
        handleIntent(intent)
    }

    private fun initHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val display = windowManager.defaultDisplay
                val supportedModes = display.supportedModes
                val maxRefreshRateMode = supportedModes.maxByOrNull { it.refreshRate }
                
                maxRefreshRateMode?.let { mode ->
                    val params = window.attributes
                    params.preferredDisplayModeId = mode.modeId
                    window.attributes = params
                    Log.d("MainActivity", "High Refresh Rate Enabled: ${mode.refreshRate}Hz (Mode ID: ${mode.modeId})")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to set high refresh rate", e)
            }
        }
    }

    @Composable
    fun SplashScreen(themeIndex: Int) {
        val themeColor = AppThemeColors[themeIndex]
        val isDark = isSystemInDarkTheme()
        
        // 【关键】背景纹理与 ChatScreen 逻辑完全同步
        val bgBrush = if (isDark) {
            // 深色模式：保持原始黑色渐变
            Brush.verticalGradient(
                colors = listOf(
                    ComposeColor(0xFF1E1E1E), 
                    ComposeColor(0xFF121212),
                    ComposeColor(0xFF000000)
                )
            )
        } else {
            if (themeIndex == 5) {
                // 默认蓝色：完全还原初代经典色值 (E3F2FD -> F1F8FF)
                Brush.verticalGradient(
                    colors = listOf(
                        ComposeColor(0xFFE3F2FD), 
                        ComposeColor(0xFFE3F2FD), 
                        ComposeColor(0xFFF1F8FF)  
                    )
                )
            } else {
                // 其他颜色：算法生成对应的淡彩背景
                val topColor = themeColor.copy(alpha = 0.12f).compositeOver(ComposeColor.White)
                val bottomColor = themeColor.copy(alpha = 0.05f).compositeOver(ComposeColor.White)
                
                Brush.verticalGradient(
                    colors = listOf(
                        topColor, 
                        topColor, 
                        bottomColor
                    )
                )
            }
        }
        
        // 动态调整字体颜色：如果是高亮色（如琥珀金），文字变深色以免看不清
        val luminance = (0.299 * themeColor.red + 0.587 * themeColor.green + 0.114 * themeColor.blue)
        val textColor = if (!isDark && luminance > 0.6) {
             // 深色变体
             themeColor.copy(
                red = themeColor.red * 0.45f,
                green = themeColor.green * 0.45f,
                blue = themeColor.blue * 0.45f,
                alpha = 1f
             )
        } else {
            // 原逻辑：深色模式保持，浅色模式稍微加深
            if (isDark) themeColor else themeColor.copy(
                red = max(0f, themeColor.red - 0.2f),
                green = max(0f, themeColor.green - 0.2f),
                blue = max(0f, themeColor.blue - 0.2f)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgBrush),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 80.dp)
            ) {
                VoiceOrb(
                    isListening = false,
                    soundLevel = 0f,
                    baseColor = themeColor, // 传入主题色，VoiceOrb 内部会自动还原蓝色质感
                    onClick = {},
                    modifier = Modifier.size(100.dp)
                )

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "小安语音助手",
                    color = textColor,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "轻松解决复杂操作",
                    color = textColor.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 1.sp
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isActivityResumed = true
        AutoGLMService.getInstance()?.setAppForegroundState(true)
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        AutoGLMService.getInstance()?.setAppForegroundState(true)
        
        // 注册 LocalBroadcastManager
        val filter = IntentFilter("com.sidhu.androidautoglm.WAKE_UP_COMMAND")
        localBroadcastManager.registerReceiver(wakeUpReceiver, filter)
        
        // 注册全局广播接收器 (Service -> Activity)
        val listenFilter = IntentFilter(AutoGLMService.ACTION_START_LISTENING)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(directListenReceiver, listenFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(directListenReceiver, listenFilter)
        }
        
        // 【关键修复】: 检查开关状态
        // 只有在设置中明确开启了“语音唤醒”，才在回到应用时启动监听
        // 否则强制停止监听，防止麦克风占用
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val wakeUpEnabled = prefs.getBoolean("wake_up_enabled", false)
        
        if (wakeUpEnabled) {
            // 确保返回主界面时，唤醒词检测是开启的
            Thread {
                WakeWordDetector.startWakeWordMode(this)
            }.start()
        } else {
            // 如果开关是关闭的，强制停止监听
            WakeWordDetector.stopListening()
        }
        
        // 检查并请求 Shizuku 权限
        if (ShizukuHelper.isShizukuAvailable()) {
            if (!ShizukuHelper.checkPermission(this)) {
                ShizukuHelper.requestPermission(1002)
            } else {
                tryAutoEnableAccessibility()
            }
        }
        
        // 检查并请求勿扰权限
        checkNotificationPolicyPermission()
        
        // 应用隐藏后台设置
        val hideRecents = prefs.getBoolean("hide_recents", false)
        setExcludeFromRecents(hideRecents)
    }
    
    // 动态控制是否在最近任务中显示
    fun setExcludeFromRecents(exclude: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val tasks = am.appTasks
                if (tasks != null && tasks.isNotEmpty()) {
                    tasks[0].setExcludeFromRecents(exclude)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to set exclude from recents", e)
            }
        }
    }
    
    private fun checkNotificationPolicyPermission() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            Toast.makeText(this, "请授予应用勿扰权限以控制音量和静音模式", Toast.LENGTH_LONG).show()
            try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to open notification policy settings", e)
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        isActivityResumed = false
    }

    override fun onStop() {
        super.onStop()
        isActivityResumed = false
        AutoGLMService.getInstance()?.setAppForegroundState(false)
        
        // 【关键修复】在此处检测是否有挂起的主题切换任务
        // 如果有，说明用户在设置里改了颜色，但我们为了防闪退推迟了操作
        // 现在应用已经进入后台，可以放心地让系统杀死进程重启了
        viewModel.applyPendingIconChange(this)
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
    
    private fun tryAutoEnableAccessibility() {
        Thread {
            if (ShizukuHelper.enableAccessibilityService(this)) {
                Log.d("MainActivity", "Accessibility Service auto-enabled via Shizuku!")
                runOnUiThread {
                    viewModel.checkServiceStatus()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        localBroadcastManager.unregisterReceiver(wakeUpReceiver)
        unregisterReceiver(directListenReceiver)
        viewModel.onTaskCompleted = null
        AutoGLMService.onCommandReceived = null
        if (ShizukuHelper.isShizukuAvailable()) {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        }
    }
    
    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra("command")?.let { command ->
            Log.d("MainActivity", "Received command via intent: $command")
            runOnUiThread {
                viewModel.sendMessage(command)
            }
        }
    }
}
