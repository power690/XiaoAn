package ai.xiaozhi

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

class FloatingWindowController(private val context: Context) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var floatView: ComposeView? = null
    
    private var isAddedToWindow = false
    
    private var _isAppForeground by mutableStateOf(false)
    
    // 控制退出动画的状态
    private var _isExiting by mutableStateOf(false)
    
    private lateinit var windowParams: WindowManager.LayoutParams
    
    private var _statusText by mutableStateOf("")
    private var _isTaskRunning by mutableStateOf(true)
    
    private var _onStopClick: (() -> Unit)? = null
    private var _onTimeout: (() -> Unit)? = null 

    private val scope = CoroutineScope(Dispatchers.Main)
    private var autoHideJob: Job? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()

    init {
        _statusText = context.getString(R.string.fw_ready)
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun updateAppForegroundState(isForeground: Boolean) {
        _isAppForeground = isForeground
        
        if (isForeground && !_isTaskRunning) {
            hide()
            return
        }
        updateVisibility()
    }

    private fun getLayoutParams(): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or 
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.x = 0
        params.y = 6 

        if (_isAppForeground) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            params.width = 0
            params.height = 0
        }

        return params
    }

    private fun updateVisibility() {
        if (!isAddedToWindow || floatView == null) return

        try {
            if (_isAppForeground) {
                if (floatView?.visibility != View.GONE) {
                    floatView?.visibility = View.GONE
                    windowParams = getLayoutParams()
                    windowManager.updateViewLayout(floatView, windowParams)
                }
            } else {
                if (floatView?.visibility != View.VISIBLE) {
                    floatView?.visibility = View.VISIBLE
                    windowParams = getLayoutParams()
                    windowManager.updateViewLayout(floatView, windowParams)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun show(onStop: () -> Unit, onTimeout: () -> Unit) {
        autoHideJob?.cancel()
        
        _onStopClick = onStop
        _onTimeout = onTimeout
        _isTaskRunning = true
        _isExiting = false 
        
        if (isAddedToWindow) {
            updateVisibility()
            return
        }
        
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        windowParams = getLayoutParams()

        floatView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@FloatingWindowController)
            setViewTreeViewModelStoreOwner(this@FloatingWindowController)
            setViewTreeSavedStateRegistryOwner(this@FloatingWindowController)
            
            if (_isAppForeground) {
                visibility = View.GONE
            }

            setContent {
                FloatingWindowContent(
                    status = _statusText,
                    isTaskRunning = _isTaskRunning,
                    isVisible = !_isAppForeground,
                    isExiting = _isExiting, 
                    onAction = {
                        if (_isTaskRunning) {
                            _onStopClick?.invoke()
                        } else {
                            try {
                                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                if (intent != null) {
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                    context.startActivity(intent)
                                    hide()
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    },
                    onDismiss = {
                        hide() 
                    }
                )
            }
        }

        try {
            windowManager.addView(floatView, windowParams)
            isAddedToWindow = true
            updateVisibility()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateStatus(status: String) {
        _statusText = status
    }

    fun setTaskRunning(running: Boolean) {
        _isTaskRunning = running
        autoHideJob?.cancel()
        
        if (!running) {
            if (_isAppForeground) {
                hide()
            } else {
                autoHideJob = scope.launch {
                    delay(30000) 
                    _onTimeout?.invoke() 
                    _isExiting = true 
                }
            }
        }
    }

    fun hide() {
        autoHideJob?.cancel()
        
        if (!isAddedToWindow) return
        try {
            windowManager.removeView(floatView)
            isAddedToWindow = false
            floatView = null
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
}

// =========================================================
// 交叉轨道旋转图标 (Crossed Orbit Icon) - Updated with Color
// =========================================================
@Composable
fun CrossedOrbitIcon(
    modifier: Modifier = Modifier,
    color: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orbit_icon")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Canvas(modifier = modifier.size(18.dp)) { 
        val centerX = size.width / 2
        val centerY = size.height / 2
        
        rotate(rotation, pivot = Offset(centerX, centerY)) {
            val gradientBrush = Brush.linearGradient(
                colors = listOf(
                    color,  
                    Color.White.copy(alpha = 0.6f),
                    color   
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height)
            )

            rotate(45f, pivot = Offset(centerX, centerY)) {
                drawOval(
                    brush = gradientBrush,
                    topLeft = Offset(size.width * 0.35f, 0f),
                    size = Size(size.width * 0.3f, size.height),
                    style = Stroke(width = 3.5f)
                )
            }

            rotate(-45f, pivot = Offset(centerX, centerY)) {
                drawOval(
                    brush = gradientBrush,
                    topLeft = Offset(size.width * 0.35f, 0f),
                    size = Size(size.width * 0.3f, size.height),
                    style = Stroke(width = 3.5f)
                )
            }
            
            drawCircle(
                color = color,
                radius = 2.dp.toPx(),
                center = Offset(centerX, centerY)
            )
        }
    }
}

@Composable
fun FloatingWindowContent(
    status: String,
    isTaskRunning: Boolean,
    isVisible: Boolean, 
    isExiting: Boolean,
    onAction: () -> Unit,
    onDismiss: () -> Unit
) {
    // 读取主题色逻辑
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    // 注意：这里简单读取，如果应用内修改了颜色，下次显示浮窗时会更新。
    // 如果需要实时更新，需要额外的状态同步机制（但目前需求是简单变色）
    val themeIndex = remember { prefs.getInt("theme_color_index", 5) }
    
    val themeColors = listOf(
        0xFFE53935, // 红色
        0xFFFB8C00, // 橙色
        0xFFFFA000, // 琥珀金
        0xFF43A047, // 绿色
        0xFF00ACC1, // 青色
        0xFF2196F3, // 蓝色 (默认)
        0xFF8E24AA  // 紫色
    )
    
    val themeColorInt = themeColors.getOrElse(themeIndex) { 0xFF2196F3 }
    val themeColor = Color(themeColorInt)

    // 1. 缩放动画控制器
    val scale = remember { Animatable(0.2f) }

    LaunchedEffect(isVisible, isExiting) {
        if (isExiting) {
            // 退出缩回
            scale.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = 0.75f, 
                    stiffness = 400f 
                )
            )
            onDismiss()
        } else if (isVisible) {
            // 进场弹出
            scale.snapTo(0.2f) 
            scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.55f, 
                    stiffness = 120f
                )
            )
        }
    }

    // 2. 离场动画 (TranslationX for Swipe)
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    
    val swipeThreshold = with(density) { 60.dp.toPx() }
    val dismissTarget = with(density) { 400.dp.toPx() }

    // 【新增】检测深色模式，决定是否显示白色边框
    val isDark = isSystemInDarkTheme()
    val borderStroke = if (isDark) {
        // 超细(0.5dp) 半透明白色边框，增加深色背景下的区分度
        BorderStroke(0.5.dp, Color.White.copy(alpha = 0.2f))
    } else {
        null
    }

    Box(
        modifier = Modifier
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp, top = 0.dp) 
            .wrapContentSize()
    ) {
        MaterialTheme {
            Surface(
                modifier = Modifier
                    .width(192.dp)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        translationX = offsetX.value
                        transformOrigin = TransformOrigin(0.5f, 0f) 
                        
                        if (offsetX.value > 0) {
                            val progress = (offsetX.value / (swipeThreshold * 2)).coerceIn(0f, 1f)
                            alpha = 1f - progress
                        }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (offsetX.value > swipeThreshold) {
                                    scope.launch {
                                        offsetX.animateTo(
                                            targetValue = dismissTarget,
                                            animationSpec = tween(300, easing = FastOutLinearInEasing)
                                        )
                                        onDismiss() 
                                    }
                                } else {
                                    scope.launch {
                                        offsetX.animateTo(
                                            targetValue = 0f,
                                            animationSpec = spring(dampingRatio = 0.6f, stiffness = 600f)
                                        )
                                    }
                                }
                            },
                            onDragCancel = {
                                scope.launch { offsetX.animateTo(0f) }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val newOffset = offsetX.value + dragAmount
                                scope.launch {
                                    offsetX.snapTo(newOffset.coerceAtLeast(0f))
                                }
                            }
                        )
                    },
                shape = RoundedCornerShape(24.dp), 
                color = Color.Black,
                shadowElevation = 0.dp,
                // 【修改】应用边框
                border = borderStroke 
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isTaskRunning) {
                            CrossedOrbitIcon(
                                modifier = Modifier.padding(end = 8.dp),
                                color = themeColor // 传入主题色
                            )
                        }

                        Column {
                            val isError = status.startsWith("Error") || status.startsWith("出错") || status.startsWith("运行异常")
                            val titleText = when {
                                isTaskRunning -> stringResource(R.string.fw_running)
                                isError -> stringResource(R.string.fw_error_title)
                                else -> stringResource(R.string.fw_ready_title) 
                            }
                            
                            val titleColor = when {
                                isTaskRunning -> themeColor // 使用主题色
                                isError -> Color(0xFFFF5252)       
                                else -> themeColor          // 使用主题色
                            }

                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                                color = titleColor
                            )
                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 9.sp),
                                maxLines = 1,
                                color = Color.White
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.width(6.dp))

                    Button(
                        onClick = onAction,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF333333),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Icon(
                            if (isTaskRunning) Icons.Default.Stop else Icons.Default.OpenInNew, 
                            contentDescription = null, 
                            modifier = Modifier.size(10.dp),
                            tint = if (isTaskRunning) Color(0xFFFF5252) else themeColor // 使用主题色
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            if (isTaskRunning) stringResource(R.string.fw_stop) else stringResource(R.string.fw_return_app),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}