package ai.xiaozhi.ui

import android.content.Intent
import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.xiaozhi.R
import dev.jeziellago.compose.markdowntext.MarkdownText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserManualScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: ChatViewModel = viewModel(context as androidx.activity.ComponentActivity)
    val uiState by viewModel.uiState.collectAsState()
    
    val themeColor = AppThemeColors[uiState.themeColorIndex]
    // 获取当前是否为深色模式
    val isDark = isSystemInDarkTheme()

    // 【关键修复】：使用 key(isDark) 包裹整个界面
    // 当深色/浅色模式切换时，强制销毁并重建整个界面，确保 MarkdownText 和背景色彻底刷新
    // 解决文字变暗、颜色不切换的 Bug
    key(isDark) {
        
        // ================== 改进背景纹理逻辑 ==================
        val bgBrush = if (isDark) {
            // 深色模式：将主题色以 15% 透明度叠加在深灰背景上
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
                    colors = listOf(Color(0xFFE3F2FD), Color(0xFFE3F2FD), Color(0xFFF1F8FF))
                )
            } else {
                val topColor = themeColor.copy(alpha = 0.12f).compositeOver(Color.White)
                val bottomColor = themeColor.copy(alpha = 0.05f).compositeOver(Color.White)
                Brush.verticalGradient(colors = listOf(topColor, topColor, bottomColor))
            }
        }

        // 2. 重新定义颜色 (去除透明度，保证高亮)
        val iconColor = if (isDark) Color.White else Color.Black
        // 强制指定Markdown颜色：深色模式纯白，浅色模式纯黑
        val markdownColor = if (isDark) Color(0xFFFFFFFF) else Color(0xFF000000)
        val linkColor = themeColor

        // 1. 顶部介绍文案
        val introContent = """
# 为什么需要 Shizuku？

小安助手需要 **Shizuku** 授权才能执行以下高级系统操作：
- 开关 **WiFi**、**移动数据**、**蓝牙**
- 控制 **手电筒**
- 切换 **飞行模式**
- 调整 **屏幕亮度**、**自动旋转**
- 切换 **深色模式**
- 切换 **定位服务**

如果没有 Shizuku，上述指令将无法执行。

# 下载 Shizuku
        """.trimIndent()

        // 2. 教程文案
        val tutorialContent = """
# 详细配对教程 (无需电脑)

前提：手机系统需为 **Android 11** 或更高版本。

## 通用步骤
1. 连接 WiFi。
2. 开启 **开发者选项** (设置 -> 关于手机 -> 连续点击版本号)。
3. 进入 **开发者选项**，开启 **无线调试**。
4. 打开 Shizuku，点击“配对”。
5. 点击“开发者选项”，进入无线调试页面，点击“**使用配对码配对设备**”。
6. 记住 6 位配对码，在通知栏的 Shizuku 通知中输入即可。
7. 回到 Shizuku 首页，点击“启动”。

## 品牌特殊设置 (重要)
部分品牌需要额外设置，否则容易断连：

**小米 / Redmi (MIUI/HyperOS)**
- 必须开启 **USB 调试 (安全设置)**。
- 必须开启 **禁用权限监控**。

**OPPO / OnePlus / Realme (ColorOS)**
- 必须开启 **禁止权限监控**。
- 建议将 Shizuku 的电池策略设为“无限制”。

**vivo / iQOO (OriginOS)**
- 如有权限监控选项请关闭。
- 建议将 Shizuku 锁定在后台。

**华为 / 荣耀 (HarmonyOS)**
- 需在开发者选项中开启“仅充电”模式下允许ADB调试。

---

# 电脑激活教程 (USB调试)
**适用系统**：
- **Android 10 及以下**
- **鸿蒙 (HarmonyOS) 4.3 及以下**

如果您的手机不支持无线调试，需使用电脑激活。

### 1. 准备工作
- 一台 Windows/Mac 电脑。
- 一根数据线。
- 下载 **ADB 工具包 (Platform-tools)** 并解压。

### 2. 手机设置
- 开启 **开发者选项**。
- 开启 **USB 调试**。
- **(华为/鸿蒙专用)**：连接电脑后，下拉通知栏将 USB 连接方式选为 **“仅充电”**，然后在开发者选项中开启 **“‘仅充电’模式下允许ADB调试”**。

### 3. 连接与激活
1. 用数据线将手机连接电脑。
2. 手机上若弹出“允许 USB 调试吗？”，勾选“始终允许”并点击确认。
3. 打开电脑解压好的 `platform-tools` 文件夹。
4. 在文件夹地址栏输入 `cmd` 并回车，打开命令行。
5. 输入 `adb devices`，确保列表中显示了您的设备。
6. **复制并运行以下命令**：

`adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`

7. 看到 `exit with 0` 字样即表示启动成功，打开手机上的 Shizuku 查看状态。
        """.trimIndent()

        // 界面结构
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgBrush)
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        title = { 
                            Text(
                                stringResource(R.string.manual_title), 
                                color = iconColor,
                                fontSize = 18.sp 
                            ) 
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                    tint = iconColor
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent
                        )
                    )
                }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    // 1. 介绍文本
                    MarkdownText(
                        markdown = introContent,
                        color = markdownColor, // 使用强制刷新的颜色
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // 2. 自定义高亮下载链接
                    val downloadText = "点击前往官网下载 Shizuku"
                    val annotatedString = buildAnnotatedString {
                        pushStyle(SpanStyle(
                            color = linkColor, 
                            fontWeight = FontWeight.Bold,
                            textDecoration = TextDecoration.Underline,
                            fontSize = 14.sp
                        ))
                        append(downloadText)
                        pop()
                    }

                    TextButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                            context.startActivity(intent)
                        },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(text = annotatedString)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Divider(color = markdownColor.copy(alpha = 0.15f), thickness = 0.5.dp)
                    
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "配对演示 (无线调试)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = markdownColor,
                        fontSize = 16.sp
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))

                    // 3. GIF 动图展示区域
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                            modifier = Modifier
                                .fillMaxWidth(0.65f)
                                .aspectRatio(857f / 1905f) 
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        setBackgroundColor(0)
                                        isVerticalScrollBarEnabled = false
                                        isHorizontalScrollBarEnabled = false
                                        
                                        settings.apply {
                                            loadWithOverviewMode = true
                                            useWideViewPort = true
                                            layoutAlgorithm = WebSettings.LayoutAlgorithm.SINGLE_COLUMN
                                        }
                                        
                                        loadUrl("file:///android_asset/shizuku_guide.gif")
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 4. 标注
                    Text(
                        text = "* 上图为 vivo 手机操作演示，仅供参考",
                        style = MaterialTheme.typography.labelSmall,
                        color = markdownColor.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 5. 详细教程文本 (含电脑激活)
                    MarkdownText(
                        markdown = tutorialContent,
                        color = markdownColor, // 使用强制刷新的颜色
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )
                    )

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}