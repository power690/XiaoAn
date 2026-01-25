package ai.xiaozhi.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import ai.xiaozhi.R
import kotlin.system.exitProcess

@Composable
fun PrivacyScreen(
    themeIndex: Int, // 接收主题索引
    onAgree: () -> Unit
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val scrollState = rememberScrollState()
    
    // 获取当前主题色
    val themeColor = AppThemeColors[themeIndex]
    
    // ================== 改进背景纹理逻辑 ==================
    val bgBrush = if (isDark) {
        // 深色模式：将主题色以 15% 透明度叠加在深灰背景上，透出淡淡的色调
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
        if (themeIndex == 5) {
            // 默认蓝色：完全还原初代经典色值 (E3F2FD -> F1F8FF)
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFE3F2FD), 
                    Color(0xFFE3F2FD), 
                    Color(0xFFF1F8FF)  
                )
            )
        } else {
            // 其他颜色：算法生成对应的淡彩背景
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
    
    // 按钮颜色定义
    val mainButtonColor = themeColor
    val titleColor = if (isDark) Color.White else Color.Black
    val contentColor = if (isDark) Color(0xFFCCCCCC) else Color(0xFF444444)
    val disagreeButtonBorderColor = if (isDark) Color.White else Color(0xFF444444)
    val disagreeButtonContentColor = if (isDark) Color.White else Color(0xFF444444)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 顶部标题和内容区域
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 60.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 标题
                Text(
                    text = stringResource(R.string.privacy_title),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = titleColor
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 协议内容 - 可滚动
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.privacy_content),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            lineHeight = 22.sp
                        ),
                        color = contentColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                    )
                    
                    // 滚动指示器 (底部遮罩)
                    if (scrollState.canScrollForward) {
                        // 计算遮罩颜色 (与背景底部颜色一致)
                        // 注意：这里需要与 bgBrush 的底部颜色匹配
                        val maskColorStart = Color.Transparent
                        val maskColorEnd = if (isDark) {
                            Color.Black.copy(alpha = 0.9f) // 深色模式底部是纯黑
                        } else {
                            if (themeIndex == 5) Color(0xFFF1F8FF).copy(alpha = 0.9f)
                            else themeColor.copy(alpha = 0.05f).compositeOver(Color.White).copy(alpha = 0.9f)
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(20.dp)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(maskColorStart, maskColorEnd)
                                    )
                                )
                        )
                    }
                }
            }

            // 底部按钮区域
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 40.dp, top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 同意按钮
                Button(
                    onClick = onAgree,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = mainButtonColor,
                        contentColor = Color.White
                    ),
                    shape = MaterialTheme.shapes.large,
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.privacy_agree),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                    )
                }

                // 不同意按钮
                OutlinedButton(
                    onClick = {
                        if (context is ComponentActivity) {
                            context.finish()
                            exitProcess(0)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = disagreeButtonContentColor,
                        containerColor = Color.Transparent
                    ),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = Brush.horizontalGradient(
                            colors = listOf(disagreeButtonBorderColor, disagreeButtonBorderColor)
                        )
                    ),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = stringResource(R.string.privacy_disagree),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                    )
                }
            }
        }
    }
}