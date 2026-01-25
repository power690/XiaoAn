package ai.xiaozhi.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.xiaozhi.AutoGLMService
import ai.xiaozhi.R
import ai.xiaozhi.receiver.MyDeviceAdminReceiver
import ai.xiaozhi.utils.WakeWordDetector
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    apiKey: String,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
    onOpenManual: () -> Unit 
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 获取ViewModel实例
    val viewModel: ChatViewModel = viewModel(context as androidx.activity.ComponentActivity)
    val uiState by viewModel.uiState.collectAsState()

    // 动态主题色
    val themeColor = AppThemeColors[uiState.themeColorIndex]

    // 防止重复点击返回导致白屏
    var lastBackClickTime by remember { mutableLongStateOf(0L) }

    // API Key State
    var isEditing by remember { mutableStateOf(apiKey.isEmpty()) }
    var newKey by remember { mutableStateOf("") }
    var isInputVisible by remember { mutableStateOf(false) }

    // SiliconFlow Token State
    val siliconToken = uiState.siliconFlowToken
    var isEditingSilicon by remember { mutableStateOf(false) }
    var newSiliconToken by remember { mutableStateOf("") }
    var isSiliconInputVisible by remember { mutableStateOf(false) }

    // Voice wake-up settings
    val prefs = context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
    var wakeUpEnabled by remember { mutableStateOf(prefs.getBoolean("wake_up_enabled", false)) }
    // Screen off wake settings
    var screenOffWakeEnabled by remember { mutableStateOf(prefs.getBoolean("screen_off_wake_enabled", false)) }
    // Hide from recents settings
    var hideRecents by remember { mutableStateOf(prefs.getBoolean("hide_recents", false)) }
    var wakeWord by remember { mutableStateOf(prefs.getString("wake_word", "小安小安") ?: "小安小安") }
    var wakeWordInput by remember { mutableStateOf(wakeWord) }

    // Avatar Picker Launcher
    val avatarPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.updateUserAvatar(uri)
        }
    }

    // ================== 深色模式适配 ==================
    val isDark = isSystemInDarkTheme()
    val contentPrimary = if (isDark) Color(0xFFEEEEEE) else Color.DarkGray
    val contentSecondary = if (isDark) Color(0xFFB0BEC5) else Color.Gray
    val iconColor = if (isDark) Color.White.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.8f)

    val customTextSelectionColors = TextSelectionColors(
        handleColor = themeColor,
        backgroundColor = themeColor.copy(alpha = 0.4f)
    )

    val glassCardColors = CardDefaults.cardColors(
        containerColor = if (isDark) Color(0xFF303030).copy(alpha = 0.6f) else Color.White.copy(alpha = 0.4f)
    )
    val glassElevation = CardDefaults.cardElevation(defaultElevation = 0.dp)

    // ================== 改进背景纹理逻辑 (深色模式透出主题色) ==================
    val bgBrush = if (isDark) {
        val deepThemeColor = themeColor.copy(alpha = 0.15f).compositeOver(Color(0xFF121212))
        val bottomColor = Color.Black
        
        Brush.verticalGradient(
            colors = listOf(
                deepThemeColor, 
                Color(0xFF121212),
                bottomColor
            )
        )
    } else {
        if (uiState.themeColorIndex == 5) {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFE3F2FD), 
                    Color(0xFFE3F2FD), 
                    Color(0xFFF1F8FF) 
                )
            )
        } else {
            val topColor = themeColor.copy(alpha = 0.12f).compositeOver(Color.White)
            val bottomColor = themeColor.copy(alpha = 0.05f).compositeOver(Color.White)
            Brush.verticalGradient(
                colors = listOf(
                    topColor,
                    topColor,
                    bottomColor
                )
            )
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            wakeUpEnabled = true
            prefs.edit().putBoolean("wake_up_enabled", true).apply()
            AutoGLMService.getInstance()?.startWakeUpListening()
            scope.launch {
                snackbarHostState.showSnackbar("语音唤醒已开启")
            }
        } else {
            wakeUpEnabled = false
            scope.launch {
                snackbarHostState.showSnackbar("需要麦克风权限才能使用语音唤醒")
            }
        }
    }

    val deviceAdminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, MyDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(componentName)) {
            screenOffWakeEnabled = true
            prefs.edit().putBoolean("screen_off_wake_enabled", true).apply()
            scope.launch {
                snackbarHostState.showSnackbar("熄屏唤醒已开启")
            }
        } else {
            screenOffWakeEnabled = false
            scope.launch {
                snackbarHostState.showSnackbar("权限申请被取消")
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val componentName = ComponentName(context, MyDeviceAdminReceiver::class.java)
                val isAdmin = dpm.isAdminActive(componentName)
                if (!isAdmin && screenOffWakeEnabled) {
                    screenOffWakeEnabled = false
                    prefs.edit().putBoolean("screen_off_wake_enabled", false).apply()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    CompositionLocalProvider(LocalTextSelectionColors provides customTextSelectionColors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgBrush)
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.settings_title), color = iconColor) },
                        navigationIcon = {
                            IconButton(onClick = {
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastBackClickTime > 500) {
                                    lastBackClickTime = currentTime
                                    onBack()
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                    tint = iconColor
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = if (isDark) Color(0xFF1E1E1E).copy(alpha = 0.8f) else Color.White.copy(alpha = 0.5f)
                        )
                    )
                },
                snackbarHost = { SnackbarHost(snackbarHostState) }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    
                    // ==========================================
                    // 0. Avatar Selection
                    // ==========================================
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                avatarPickerLauncher.launch("image/*")
                            },
                        shape = RoundedCornerShape(24.dp),
                        colors = glassCardColors,
                        elevation = glassElevation
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(24.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "用户头像",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = themeColor
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(if (isDark) Color.DarkGray else Color.LightGray)
                                ) {
                                    if (uiState.userAvatarPath != null) {
                                        val bitmap = remember(uiState.userAvatarPath) {
                                            BitmapFactory.decodeFile(uiState.userAvatarPath)
                                        }
                                        if (bitmap != null) {
                                            Image(
                                                bitmap = bitmap.asImageBitmap(),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Person,
                                                contentDescription = null,
                                                modifier = Modifier.align(Alignment.Center),
                                                tint = Color.White
                                            )
                                        }
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = null,
                                            modifier = Modifier.align(Alignment.Center),
                                            tint = Color.White
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = contentSecondary
                                )
                            }
                        }
                    }

                    // ==========================================
                    // 1. API Key Card (AutoGLM)
                    // ==========================================
                    if (!isEditing) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = glassCardColors,
                            elevation = glassElevation
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "AutoGLM API", 
                                        style = MaterialTheme.typography.titleMedium,
                                        color = themeColor
                                    )
                                    Text(
                                        text = stringResource(R.string.current_model, "autoglm-phone"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = contentSecondary
                                    )
                                }
                                Text(
                                    text = getMaskedKey(apiKey),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = contentPrimary
                                )
                                Button(
                                    onClick = { isEditing = true },
                                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = themeColor,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(stringResource(R.string.edit_api_key))
                                }
                            }
                        }
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = glassCardColors,
                            elevation = glassElevation
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "AutoGLM API",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = themeColor
                                )
                                OutlinedTextField(
                                    value = newKey,
                                    onValueChange = { newKey = it },
                                    label = { Text(stringResource(R.string.enter_api_key)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    visualTransformation = if (isInputVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        val image = if (isInputVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                                        IconButton(onClick = { isInputVisible = !isInputVisible }) {
                                            Icon(imageVector = image, contentDescription = null, tint = themeColor)
                                        }
                                    },
                                    singleLine = true,
                                    placeholder = { Text(stringResource(R.string.api_key_placeholder)) },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = themeColor,
                                        unfocusedBorderColor = contentSecondary.copy(alpha = 0.5f),
                                        focusedLabelColor = themeColor,
                                        unfocusedLabelColor = contentSecondary,
                                        cursorColor = themeColor,
                                        focusedTextColor = contentPrimary,
                                        unfocusedTextColor = contentPrimary
                                    )
                                )
                                TextButton(
                                    onClick = {
                                        val url = "https://bigmodel.cn/login?redirect=%2Fusercenter%2Fproj-mgmt%2Fapikeys"
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier.align(Alignment.Start)
                                ) {
                                    Text("申请 API Key", color = themeColor)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            if (apiKey.isNotEmpty()) {
                                                isEditing = false
                                                newKey = ""
                                            } else {
                                                onBack()
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        border = BorderStroke(1.dp, themeColor),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = themeColor)
                                    ) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                    Button(
                                        onClick = {
                                            if (newKey.isNotBlank()) {
                                                onSave(newKey)
                                                isEditing = false
                                                newKey = ""
                                            }
                                        },
                                        enabled = newKey.isNotBlank(),
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = themeColor,
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(stringResource(R.string.save))
                                    }
                                }
                            }
                        }
                    }

                    // ==========================================
                    // 1.5. DeepSeek API Card (Formerly SiliconFlow)
                    // ==========================================
                    if (!isEditingSilicon) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = glassCardColors,
                            elevation = glassElevation
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "DeepSeek API",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = themeColor
                                    )
                                    Text(
                                        text = if(siliconToken.isNotEmpty()) "已配置" else "未配置",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = contentSecondary
                                    )
                                }
                                if (siliconToken.isNotEmpty()) {
                                    Text(
                                        text = getMaskedKey(siliconToken),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = contentPrimary
                                    )
                                } else {
                                    Text(
                                        text = "未配置 (配置后可使用 DeepSeek R1 深度思考功能)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = contentSecondary
                                    )
                                }
                                Button(
                                    onClick = { isEditingSilicon = true },
                                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = themeColor,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(if (siliconToken.isNotEmpty()) "修改 API Key" else "配置 API Key")
                                }
                            }
                        }
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = glassCardColors,
                            elevation = glassElevation
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "DeepSeek API",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = themeColor
                                )
                                Text(
                                    text = "仅用于【深度思考】功能 (DeepSeek R1)。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = contentSecondary
                                )
                                OutlinedTextField(
                                    value = newSiliconToken,
                                    onValueChange = { newSiliconToken = it },
                                    label = { Text(stringResource(R.string.enter_api_key)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    visualTransformation = if (isSiliconInputVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        val image = if (isSiliconInputVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                                        IconButton(onClick = { isSiliconInputVisible = !isSiliconInputVisible }) {
                                            Icon(imageVector = image, contentDescription = null, tint = themeColor)
                                        }
                                    },
                                    singleLine = true,
                                    placeholder = { Text(stringResource(R.string.api_key_placeholder)) },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = themeColor,
                                        unfocusedBorderColor = contentSecondary.copy(alpha = 0.5f),
                                        focusedLabelColor = themeColor,
                                        unfocusedLabelColor = contentSecondary,
                                        cursorColor = themeColor,
                                        focusedTextColor = contentPrimary,
                                        unfocusedTextColor = contentPrimary
                                    )
                                )
                                TextButton(
                                    onClick = {
                                        val url = "https://m.siliconflow.cn/me/account/ak"
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier.align(Alignment.Start)
                                ) {
                                    Text("申请 API Key", color = themeColor)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            isEditingSilicon = false
                                            newSiliconToken = ""
                                        },
                                        modifier = Modifier.weight(1f),
                                        border = BorderStroke(1.dp, themeColor),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = themeColor)
                                    ) {
                                        Text("取消")
                                    }
                                    Button(
                                        onClick = {
                                            viewModel.updateSiliconFlowToken(newSiliconToken)
                                            isEditingSilicon = false
                                            newSiliconToken = ""
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = themeColor,
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("保存")
                                    }
                                }
                            }
                        }
                    }

                    // ==========================================
                    // 2. Voice Wake-up Settings
                    // ==========================================
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = glassCardColors,
                        elevation = glassElevation
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // 语音唤醒开关
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.wake_up_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = themeColor
                                )
                                Switch(
                                    checked = wakeUpEnabled,
                                    onCheckedChange = { isChecked ->
                                        if (isChecked) {
                                            if (ContextCompat.checkSelfPermission(
                                                    context,
                                                    Manifest.permission.RECORD_AUDIO
                                                ) == PackageManager.PERMISSION_GRANTED
                                            ) {
                                                wakeUpEnabled = true
                                                prefs.edit().putBoolean("wake_up_enabled", true).apply()
                                                AutoGLMService.getInstance()?.startWakeUpListening()
                                            } else {
                                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        } else {
                                            wakeUpEnabled = false
                                            prefs.edit().putBoolean("wake_up_enabled", false).apply()
                                            AutoGLMService.getInstance()?.stopWakeUpListening()
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = themeColor,
                                        checkedBorderColor = themeColor,
                                        uncheckedThumbColor = Color.White,
                                        uncheckedTrackColor = if (isDark) Color.DarkGray else Color.LightGray.copy(alpha = 0.5f),
                                        uncheckedBorderColor = Color.Transparent
                                    )
                                )
                            }

                            if (wakeUpEnabled) {
                                Divider(color = themeColor.copy(alpha = 0.1f))

                                // 熄屏唤醒开关
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.screen_off_wake_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = themeColor
                                        )
                                        Text(
                                            text = stringResource(R.string.screen_off_wake_hint),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = contentSecondary
                                        )
                                    }
                                    Switch(
                                        checked = screenOffWakeEnabled,
                                        onCheckedChange = { isChecked ->
                                            if (isChecked) {
                                                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                                                val componentName = ComponentName(context, MyDeviceAdminReceiver::class.java)
                                                if (dpm.isAdminActive(componentName)) {
                                                    screenOffWakeEnabled = true
                                                    prefs.edit().putBoolean("screen_off_wake_enabled", true).apply()
                                                } else {
                                                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                                                    intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                                                    intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.device_admin_description))
                                                    deviceAdminLauncher.launch(intent)
                                                }
                                            } else {
                                                screenOffWakeEnabled = false
                                                prefs.edit().putBoolean("screen_off_wake_enabled", false).apply()
                                            }
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = themeColor,
                                            checkedBorderColor = themeColor,
                                            uncheckedThumbColor = Color.White,
                                            uncheckedTrackColor = if (isDark) Color.DarkGray else Color.LightGray.copy(alpha = 0.5f),
                                            uncheckedBorderColor = Color.Transparent
                                        )
                                    )
                                }

                                Divider(color = themeColor.copy(alpha = 0.1f))

                                // 唤醒词输入
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = stringResource(R.string.wake_up_wake_word_label),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = contentSecondary
                                    )
                                    OutlinedTextField(
                                        value = wakeWordInput,
                                        onValueChange = { wakeWordInput = it },
                                        label = { Text(stringResource(R.string.wake_up_wake_word_hint)) },
                                        placeholder = { Text(stringResource(R.string.wake_up_wake_word_hint)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = themeColor,
                                            unfocusedBorderColor = contentSecondary.copy(alpha = 0.5f),
                                            focusedLabelColor = themeColor,
                                            unfocusedLabelColor = contentSecondary,
                                            cursorColor = themeColor,
                                            focusedTextColor = contentPrimary,
                                            unfocusedTextColor = contentPrimary
                                        ),
                                        trailingIcon = {
                                            if (wakeWordInput.isNotEmpty()) {
                                                IconButton(onClick = { wakeWordInput = "" }) {
                                                    Icon(
                                                        Icons.Default.Close,
                                                        contentDescription = stringResource(R.string.wake_up_clear),
                                                        tint = contentSecondary
                                                    )
                                                }
                                            }
                                        }
                                    )
                                    Button(
                                        onClick = {
                                            if (wakeWordInput.isNotBlank()) {
                                                wakeWord = wakeWordInput
                                                prefs.edit().putString("wake_word", wakeWordInput).apply()
                                                WakeWordDetector.updateWakeWord(wakeWordInput)
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        message = context.getString(R.string.wake_up_saved),
                                                        duration = SnackbarDuration.Short
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier.align(Alignment.End),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = themeColor,
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Save,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(context.getString(R.string.wake_up_save))
                                    }
                                }
                            } else {
                                Text(
                                    text = stringResource(R.string.wake_up_disable),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = contentSecondary
                                )
                            }
                        }
                    }

                    // ==========================================
                    // 3. Hide Recents
                    // ==========================================
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = glassCardColors,
                        elevation = glassElevation
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.hide_recents_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = themeColor
                                )
                                Text(
                                    text = stringResource(R.string.hide_recents_desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contentSecondary
                                )
                            }
                            Switch(
                                checked = hideRecents,
                                onCheckedChange = { isChecked ->
                                    hideRecents = isChecked
                                    prefs.edit().putBoolean("hide_recents", isChecked).apply()
                                    // 立即应用设置
                                    val activity = context.findActivity()
                                    if (activity is ai.xiaozhi.MainActivity) {
                                        activity.setExcludeFromRecents(isChecked)
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = themeColor,
                                    checkedBorderColor = themeColor,
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = if (isDark) Color.DarkGray else Color.LightGray.copy(alpha = 0.5f),
                                    uncheckedBorderColor = Color.Transparent
                                )
                            )
                        }
                    }

                    // ==========================================
                    // 4. Theme Color Picker
                    // ==========================================
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = glassCardColors,
                        elevation = glassElevation
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "主题颜色",
                                style = MaterialTheme.typography.titleMedium,
                                color = themeColor
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                AppThemeColors.forEachIndexed { index, color ->
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                            .border(
                                                width = if (uiState.themeColorIndex == index) 2.dp else 0.dp,
                                                color = if (isDark) Color.White else Color.Black,
                                                shape = CircleShape
                                            )
                                            .clickable {
                                                viewModel.updateThemeColor(index)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (uiState.themeColorIndex == index) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ==========================================
                    // 4.5. User Manual
                    // ==========================================
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenManual() },
                        shape = RoundedCornerShape(24.dp),
                        colors = glassCardColors,
                        elevation = glassElevation
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(24.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.manual_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isDark) Color.White else Color.Black
                            )
                            
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = contentSecondary
                            )
                        }
                    }

                    // ==========================================
                    // 4.6 Developer Card (完美修正版2.0)
                    // ==========================================
                    
                    var showEasterEgg by remember { mutableStateOf(false) }
                    
                    // 动画状态
                    val textAlpha = remember { Animatable(0f) }
                    val textOffsetY = remember { Animatable(10f) }
                    val avatarScale = remember { Animatable(1f) }
                    val avatarOffsetX = remember { Animatable(0f) }
                    val devTextAlpha = remember { Animatable(1f) }

                    var easterEggJob by remember { mutableStateOf<Job?>(null) }

                    val cardColor = if (isDark) Color(0xFF303030).copy(alpha = 0.6f) else Color.White.copy(alpha = 0.4f)

                    // 1. 使用 BoxWithConstraints 获取卡片总宽
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(10f) 
                            .background(cardColor, RoundedCornerShape(24.dp)) 
                    ) {
                        // 计算中心点位移
                        val density = LocalDensity.current
                        val targetTranslationX = remember(maxWidth) {
                            with(density) {
                                (maxWidth / 2).toPx() - 48.dp.toPx()
                            }
                        }

                        Row(
                            modifier = Modifier
                                .padding(24.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start
                        ) {
                            // 2. 移动容器：固定 48dp 宽度，作为移动载体
                            Box(
                                contentAlignment = Alignment.TopCenter,
                                modifier = Modifier
                                    .size(48.dp) // 强制固定宽度，防止气泡撑大容器
                                    .graphicsLayer {
                                        translationX = avatarOffsetX.value // 应用位移
                                    }
                                    .zIndex(1f)
                            ) {
                                // 3. 气泡文字：
                                // - 使用 requiredWidth 强制横向展开
                                // - 使用 wrapContentHeight 适应高度
                                // - 使用 offset 负值绝对悬浮在上方
                                if (showEasterEgg) {
                                    Surface(
                                        color = themeColor,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .requiredWidth(190.dp) // 【关键修正】强制宽度，拒绝竖条！
                                            .wrapContentHeight()
                                            .offset(y = (-65 + textOffsetY.value).dp) // 【关键修正】悬浮在头像上方，不占布局空间
                                            .alpha(textAlpha.value)
                                            .zIndex(10f), 
                                        shadowElevation = 4.dp
                                    ) {
                                        Text(
                                            text = "我可是很可爱的你怎么这么喜欢点我",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                    }
                                }

                                // 4. 头像
                                Image(
                                    painter = painterResource(id = R.drawable.developer_avatar),
                                    contentDescription = "Developer Avatar",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .scale(avatarScale.value)
                                        .clip(CircleShape)
                                        .pointerInput(Unit) {
                                            detectTapGestures(
                                                onDoubleTap = {
                                                    easterEggJob?.cancel()
                                                    easterEggJob = scope.launch {
                                                        // A. 并行动画
                                                        launch {
                                                            avatarScale.animateTo(1.2f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow))
                                                            avatarScale.animateTo(1.0f)
                                                        }
                                                        launch {
                                                            avatarOffsetX.animateTo(targetTranslationX, spring(dampingRatio = 0.7f, stiffness = 300f))
                                                        }
                                                        launch {
                                                            devTextAlpha.animateTo(0f, tween(200))
                                                        }

                                                        // B. 气泡出场
                                                        showEasterEgg = true
                                                        launch {
                                                            textOffsetY.snapTo(10f)
                                                            textOffsetY.animateTo(0f, tween(300))
                                                        }
                                                        textAlpha.snapTo(0f)
                                                        textAlpha.animateTo(1f, tween(300))

                                                        // C. 停留
                                                        delay(2500)

                                                        // D. 气泡退场
                                                        launch {
                                                            textAlpha.animateTo(0f, tween(300))
                                                            showEasterEgg = false
                                                        }
                                                        
                                                        // E. 回归
                                                        launch {
                                                            avatarOffsetX.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 300f))
                                                        }
                                                        launch {
                                                            delay(100)
                                                            devTextAlpha.animateTo(1f, tween(300))
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                )
                            }
                            
                            Spacer(modifier = Modifier.width(16.dp))
                            
                            // 5. 开发者文字
                            Column(
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.alpha(devTextAlpha.value)
                            ) {
                                Text(
                                    text = "开发者: 小威",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = if (isDark) Color.White else Color.Black
                                )
                            }
                        }
                    }

                    // ==========================================
                    // 5. Version Card
                    // ==========================================
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = glassCardColors,
                        elevation = glassElevation
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Text(
                                text = "当前版本: 1.0",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Medium
                                ),
                                color = if (isDark) Color.White else Color.Black
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

private fun getMaskedKey(key: String): String {
    if (key.length <= 8) return "******"
    return "${key.take(4)}...${key.takeLast(4)}"
}