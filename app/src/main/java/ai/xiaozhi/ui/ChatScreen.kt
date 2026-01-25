package ai.xiaozhi.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ai.xiaozhi.R
import ai.xiaozhi.utils.SpeechRecognizerManager
import ai.xiaozhi.utils.SherpaModelManager
import ai.xiaozhi.utils.WakeWordDetector
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.max

// ==========================================
// Data: Action Command Pool (保持不变)
// ==========================================
val allCommands = listOf(
    "打开淘宝查看待收货快递", "去拼多多百亿补贴看看", "京东搜索最新款手机",
    "打开闲鱼看看有没有二手iPhone", "在淘宝帮我买一箱抽纸", "拼多多查看我的订单",
    "打开得物搜索篮球鞋", "去唯品会看看衣服", "打开闲鱼发布闲置",
    "打开淘宝搜索连衣裙并按照销量排序", "打开京东查看我的订单", "在拼多多上搜索水果并选择百亿补贴",
    "打开闲鱼发布一件闲置商品", "打开得物查看AJ1的报价", "打开唯品会查看母婴用品",
    "打开淘宝直播看李佳琦的直播", "打开京东到家购买超市商品", "打开拼多多参与砍价免费拿",
    "打开闲鱼搜索附近的二手手机", "打开淘宝查看物流信息", "打开京东查看PLUS会员优惠",
    "打开拼多多查看今日限时秒杀", "打开得物购买潮流服饰", "打开唯品会查看母婴用品",
    "打开淘宝充值中心充值话费", "打开淘宝特价版查看低价商品", "打开苏宁易购查看家电",
    "打开考拉海购查看进口商品", "打开蘑菇街查看时尚女装",
    "帮我点一杯蜜雪冰城", "美团搜索附近的烧烤", "饿了么点一份猪脚饭",
    "打开大众点评找附近的火锅店", "帮我查一下明天的天气", "查看最近的电影院",
    "帮我打车去机场", "查询现在的油价", "打开携程订酒店",
    "打开美团点一份外卖", "打开饿了么领取会员红包", "打开大众点评搜索附近的餐厅",
    "打开携程预订火车票", "打开高德地图导航到公司", "打开支付宝收取蚂蚁森林能量",
    "打开菜鸟裹裹查看快递", "打开滴滴出行呼叫快车", "打开哈啰单车扫码骑车",
    "打开58同城查看租房信息", "打开贝壳找房查看二手房", "打开掌上公交查询公交路线",
    "打开铁路12306查询火车票", "打开航旅纵横查看航班动态", "打开饿了么下单一杯星巴克",
    "打开美团预订酒店", "打开大众点评查看餐厅评价", "打开支付宝充值话费",
    "打开美团买菜购买蔬菜", "打开叮咚买菜下单水果", "打开盒马鲜生购买海鲜",
    "打开京东健康购买药品", "打开平安好医生在线咨询", "打开Keep进行健身训练",
    "打开悦跑圈记录跑步", "打开薄荷健康记录饮食", "打开墨迹天气查看天气预报",
    "打开百度地图查看实时路况", "打开车来了查询公交到站时间", "打开曹操出行叫一辆专车",
    "打开货拉拉叫一辆搬家货车", "打开e代驾叫代驾司机",
    "打开微信给妈妈发消息说不回家吃饭", "打开抖音搜索美食教程", "去B站看AutoGLM的视频",
    "打开小红书搜索穿搭攻略", "快手看一会直播", "打开网易云音乐播放每日推荐",
    "微博查看今天的热搜", "知乎搜索如何学习编程", "打开QQ查看新消息",
    "打开微信发朋友圈", "打开抖音观看直播", "打开B站观看番剧",
    "打开小红书查看美妆教程", "打开快手拍摄短视频", "打开网易云音乐听歌识曲",
    "打开微博发布动态", "打开知乎查看热门话题", "打开QQ发送文件",
    "打开微信阅读阅读小说", "打开腾讯视频观看电影", "打开爱奇艺观看电视剧",
    "打开优酷观看综艺", "打开斗鱼观看游戏直播", "打开虎牙观看王者荣耀直播",
    "打开喜马拉雅听有声书", "打开懒人听书收听小说", "打开咪咕音乐下载歌曲",
    "打开酷狗音乐搜索歌曲", "打开酷我音乐播放列表", "打开全民K歌录制歌曲",
    "打开Soul发布瞬间", "打开脉脉查看职场动态", "打开钉钉查看工作消息",
    "打开企业微信查看审批", "打开飞书查看日程安排", "打开百度贴吧浏览帖子",
    "打开豆瓣查看电影评分", "打开知乎日报阅读文章", "打开得到学习课程",
    "打开樊登读书听一本书", "打开网易公开课观看课程", "打开腾讯课堂学习技能",
    "打开中国大学MOOC学习课程", "打开扇贝单词背单词", "打开百词斩学习英语",
    "打开多邻国学习外语", "打开每日英语听力练习听力", "打开开言英语学习口语",
    "打开WPS编辑文档", "打开百度网盘下载文件", "打开腾讯会议加入会议",
    "打开网易有道词典查询单词", "打开百度搜索关键词", "打开谷歌翻译翻译句子",
    "打开今日头条查看新闻", "打开新浪新闻查看时事", "打开央视新闻观看直播",
    "打开凤凰新闻阅读文章", "打开西瓜视频观看短视频", "打开懂车帝查看汽车资讯",
    "打开汽车之家查看车型", "打开美颜相机拍照", "打开轻颜相机自拍",
    "打开VSCO编辑照片", "打开剪映编辑视频", "打开印象笔记记录想法",
    "打开石墨文档协作编辑", "打开百度输入法更换皮肤", "打开讯飞输入法语音输入",
    "打开搜狗输入法使用斗图", "打开夸克浏览器搜索内容", "打开UC浏览器看新闻",
    "打开QQ浏览器看小说", "打开360清理大师清理垃圾", "打开腾讯手机管家杀毒",
    "打开百度网盘上传照片", "打开天翼云盘备份文件", "打开百度文库查看资料",
    "打开扫描全能王扫描文件", "打开白描识别文字", "打开CSDN查看技术文章",
    "打开GitHub查看开源项目", "打开Stack Overflow查问题", "打开LeetCode刷算法题",
    "打开BOSS直聘找工作", "打开拉勾网查看职位", "打开智联招聘更新简历",
    "打开前程无忧搜索工作", "打开脉脉找人内推", "打开猎聘看高端职位",
    "打开微信，搜索文件传输助手，发送'测试'", 
    "打开淘宝，搜索机械键盘，按销量排序",
    "打开高德地图，导航去最近的加油站",
    "打开支付宝，查看蚂蚁森林收能量",
    "打开淘宝，搜索空气净化器，按价格从低到高排序",
    "打开美团，搜索附近的健身房，查看评价",
    "打开高德地图，搜索最近的星巴克，导航过去",
    "打开微信，找到工作群，发送明天会议的通知",
    "打开抖音，搜索美食教程，收藏喜欢的视频",
    "打开B站，关注科技UP主，查看最新视频",
    "打开小红书，搜索旅游攻略，点赞收藏",
    "打开网易云音乐，创建跑步歌单，添加歌曲",
    "打开微博，关注热点话题，发表评论",
    "打开知乎，搜索育儿经验，关注问题",
    "打开热点，关闭WiFi，打开流量，关闭深色",
    "打开NFC，打开勿扰模式，打开自动旋转",
    "截屏，静音，最大音量"
)

val qaCommands = listOf(
    // 生活百科 & 妙招
    "如何快速去除衣服上的油渍？",
    "番茄炒蛋怎么做才好吃？",
    "失眠了有什么缓解办法？",
    "怎么判断鸡蛋是否新鲜？",
    "红酒洒在桌布上怎么洗？",
    "推荐几部适合周末看的电影",
    "讲一个好笑的冷笑话",
    "空调不制冷可能是什么原因？",
    "如何科学地进行断舍离？",
    "感冒了吃什么水果比较好？",
    "怎么挑选甜西瓜？",
    "给孩子起个好听的名字，姓李",
    "如何制作一杯好喝的手冲咖啡？",
    "保温杯里的茶垢怎么清洗？",
    
    // 地理
    "中国的首都是哪里？",
    "世界上最高的山峰是什么？",
    "介绍一下黄河的源头",
    "法国的首都是哪个城市？",
    "太平洋和大西洋哪个更大？",
    "马尔代夫在哪里？",
    "介绍一下九寨沟的景点",
    "为什么北极没有企鹅？",
    "赤道经过哪些国家？",
    "长城一共有多长？",
    "死海为什么人可以浮起来？",

    // 化学
    "水的化学式是什么？",
    "为什么油浮在水面上？",
    "黄金的化学符号是什么？",
    "光合作用产生了什么？",
    "食盐的主要成分是什么？",
    "为什么苹果切开后会变色？",
    "介绍一下元素周期表",
    "二氧化碳对环境有什么影响？",
    "铁为什么会生锈？",
    "什么是酸碱中和反应？",
    "钻石和石墨有什么区别？",

    // 数学
    "圆的面积公式是什么？",
    "勾股定理的内容是什么？",
    "1加到100等于多少？",
    "什么是质数？",
    "三角形的内角和是多少度？",
    "15的平方是多少？",
    "如何计算长方体的体积？",
    "斐波那契数列是什么？",
    "0.618代表什么比例？",
    "解方程：2x + 5 = 15",
    "鸡兔同笼问题怎么解？"
)

fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun TwoDotsIcon(
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(24.dp)) {
        val dotRadius = 2.8.dp.toPx()
        val spacing = 9.dp.toPx()
        drawCircle(
            color = tint,
            radius = dotRadius,
            center = Offset(size.width / 2, size.height / 2 - spacing / 2)
        )
        drawCircle(
            color = tint,
            radius = dotRadius,
            center = Offset(size.width / 2, size.height / 2 + spacing / 2)
        )
    }
}

// ==========================================
// Theme Background & Color Helpers
// ==========================================

fun getThemeBackgroundColors(themeIndex: Int, themeColor: Color): Pair<Color, Color> {
    return if (themeIndex == 5) {
        Pair(Color(0xFFE3F2FD), Color(0xFFF1F8FF))
    } else {
        val topColor = themeColor.copy(alpha = 0.12f).compositeOver(Color.White)
        val bottomColor = themeColor.copy(alpha = 0.05f).compositeOver(Color.White)
        Pair(topColor, bottomColor)
    }
}

// ==========================================
// Voice Orb Component (Smart Palette)
// ==========================================

data class OrbPalette(
    val innerTint: Color,
    val mainColor: Color,
    val deepShade: Color
)

@Composable
fun getOrbPalette(baseColor: Color): OrbPalette {
    val colorInt = baseColor.toArgb()
    
    return when (colorInt) {
        0xFFE53935.toInt() -> OrbPalette(
            innerTint = Color(0xFFFF8A80),
            mainColor = baseColor,
            deepShade = Color(0xFFB71C1C)
        )
        0xFFFB8C00.toInt() -> OrbPalette(
            innerTint = Color(0xFFFFD180),
            mainColor = baseColor,
            deepShade = Color(0xFFE65100)
        )
        0xFFFFA000.toInt() -> OrbPalette(
            innerTint = Color(0xFFFFE082),
            mainColor = baseColor,
            deepShade = Color(0xFFFF6F00)
        )
        0xFF43A047.toInt() -> OrbPalette(
            innerTint = Color(0xFFA5D6A7),
            mainColor = baseColor,
            deepShade = Color(0xFF1B5E20)
        )
        0xFF00ACC1.toInt() -> OrbPalette(
            innerTint = Color(0xFF80DEEA),
            mainColor = baseColor,
            deepShade = Color(0xFF006064)
        )
        0xFF2196F3.toInt() -> OrbPalette(
            innerTint = Color(0xFF40C4FF), 
            mainColor = Color(0xFF2962FF), 
            deepShade = Color(0xFF000051)  
        )
        0xFF8E24AA.toInt() -> OrbPalette(
            innerTint = Color(0xFFCE93D8),
            mainColor = baseColor,
            deepShade = Color(0xFF4A148C)
        )
        else -> OrbPalette(
            innerTint = baseColor.copy(alpha = 0.6f),
            mainColor = baseColor,
            deepShade = baseColor.copy(red = baseColor.red * 0.4f, green = baseColor.green * 0.4f, blue = baseColor.blue * 0.4f)
        )
    }
}

@Composable
fun VoiceOrb(
    isListening: Boolean,
    soundLevel: Float,
    baseColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_breathing")
    val idleScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idleScale"
    )

    val targetScale = if (isListening) {
        val normalized = ((soundLevel + 60f) / 60f).coerceIn(0f, 1f)
        1.0f + (normalized * 0.4f)
    } else {
        1.0f
    }

    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = 0.5f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "soundScale"
    )

    val finalScale = if (isListening) animatedScale else idleScale

    val palette = getOrbPalette(baseColor)
    
    val coreWhite = Color(0xFFF0F8FF)
    val glowColor = baseColor

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = finalScale
                scaleY = finalScale
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if(isListening) 0.6f else 0.3f }) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glowColor.copy(alpha = 0.3f),
                        glowColor.copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    radius = size.width / 1.5f
                )
            )
        }

        Canvas(modifier = Modifier.fillMaxSize(0.9f)) {
            val radius = size.width / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        coreWhite,
                        palette.innerTint,
                        palette.mainColor,
                        palette.deepShade
                    ),
                    center = Offset(radius * 0.6f, radius * 0.6f),
                    radius = radius * 2.5f
                )
            )
        }

        Canvas(modifier = Modifier.fillMaxSize(0.9f)) {
            val width = size.width
            val height = size.height
            
            drawOval(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.8f),
                        Color.White.copy(alpha = 0.0f)
                    ),
                    start = Offset(width * 0.2f, height * 0.2f),
                    end = Offset(width * 0.5f, height * 0.5f)
                ),
                topLeft = Offset(width * 0.15f, height * 0.15f),
                size = androidx.compose.ui.geometry.Size(width * 0.35f, height * 0.25f)
            )

            drawArc(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        palette.innerTint.copy(alpha = 0.7f)
                    )
                ),
                startAngle = 30f,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = Offset(width * 0.05f, height * 0.05f),
                size = androidx.compose.ui.geometry.Size(width * 0.9f, height * 0.9f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
            )
        }
    }
}

@Composable
fun MiniVoiceOrb(
    baseColor: Color,
    modifier: Modifier = Modifier
) {
    val palette = getOrbPalette(baseColor)
    val coreWhite = Color(0xFFF0F8FF)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.width / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        coreWhite,
                        palette.innerTint,
                        palette.mainColor,
                        palette.deepShade
                    ),
                    center = Offset(radius * 0.6f, radius * 0.6f),
                    radius = radius * 2.5f
                )
            )
            drawOval(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.8f),
                        Color.White.copy(alpha = 0.0f)
                    ),
                    start = Offset(size.width * 0.2f, size.height * 0.2f),
                    end = Offset(size.width * 0.5f, size.height * 0.5f)
                ),
                topLeft = Offset(size.width * 0.15f, size.height * 0.15f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.35f, size.height * 0.25f)
            )
        }
    }
}

@Composable
fun UserAvatar(
    avatarPath: String?,
    modifier: Modifier = Modifier,
    isDark: Boolean = false
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(if (isDark) Color.DarkGray else Color.LightGray),
        contentAlignment = Alignment.Center
    ) {
        if (avatarPath != null) {
            val bitmap = remember(avatarPath) {
                BitmapFactory.decodeFile(avatarPath)
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "User Avatar",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(4.dp).fillMaxSize()
                )
            }
        } else {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.padding(4.dp).fillMaxSize()
            )
        }
    }
}

@Composable
fun SuggestionChip(
    text: String,
    isDark: Boolean,
    themeColor: Color,
    onClick: () -> Unit
) {
    val luminance = (0.299 * themeColor.red + 0.587 * themeColor.green + 0.114 * themeColor.blue)
    
    val textColor = if (!isDark && luminance > 0.6) {
        Color(
            red = themeColor.red * 0.45f, 
            green = themeColor.green * 0.45f, 
            blue = themeColor.blue * 0.45f,
            alpha = 1f
        )
    } else {
        if (isDark) themeColor.copy(alpha = 0.9f) else themeColor.copy(
            red = max(0f, themeColor.red - 0.1f), 
            green = max(0f, themeColor.green - 0.1f), 
            blue = max(0f, themeColor.blue - 0.1f)
        )
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (isDark) Color(0xFF424242).copy(alpha = 0.8f) else Color.White.copy(alpha = 0.6f), 
        modifier = Modifier.height(40.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

// ==========================================
// Main Chat Screen
// ==========================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val isDeepThinking by viewModel.isDeepThinkingMode.collectAsState()
    
    val themeColor = AppThemeColors[uiState.themeColorIndex]

    var showMenu by remember { mutableStateOf(false) }
    var showModelSwitch by remember { mutableStateOf(false) }
    
    var currentRecommendations by remember { 
        mutableStateOf(allCommands.shuffled().take(3)) 
    }
    
    LaunchedEffect(isDeepThinking) {
        val targetList = if (isDeepThinking) qaCommands else allCommands
        currentRecommendations = targetList.shuffled().take(3)
    }
    
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val speechRecognizerManager = remember { SpeechRecognizerManager(context) }
    val isListening by speechRecognizerManager.isListening.collectAsState()
    val soundLevel by speechRecognizerManager.soundLevel.collectAsState()
    // 监听实时字幕
    val partialText by speechRecognizerManager.partialResult.collectAsState()
    
    var isVoiceMode by remember { mutableStateOf(true) }

    val modelState by SherpaModelManager.modelState.collectAsState()
    val isModelReady = modelState is SherpaModelManager.ModelState.Ready

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var isAutoScroll by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        SherpaModelManager.initModel(context)
    }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun playSound(fileName: String) {
        scope.launch(Dispatchers.IO) {
            var mediaPlayer: MediaPlayer? = null
            try {
                mediaPlayer = MediaPlayer()
                val descriptor: AssetFileDescriptor = context.assets.openFd("sounds/$fileName")
                mediaPlayer.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                descriptor.close()
                mediaPlayer.prepare()
                mediaPlayer.start()
                while (mediaPlayer.isPlaying) {
                    delay(100)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                mediaPlayer?.release()
            }
        }
    }

    val triggerSend: (String) -> Unit = { text ->
        if (text.isNotBlank()) {
            viewModel.sendMessage(text)
            inputText = ""
            isAutoScroll = true
            keyboardController?.hide()
            focusManager.clearFocus()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.listeningTrigger.collect {
            isVoiceMode = true
            playSound("1.mp3")
            if (!isModelReady) {
                SherpaModelManager.initModel(context)
                delay(200)
            }
            if (!isListening) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.RECORD_AUDIO
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    speechRecognizerManager.startListening(
                        onResultCallback = { result ->
                            inputText = result
                            scope.launch {
                                triggerSend(result)
                                delay(500)
                                // 【关键修复】检查开关，再决定是否重启唤醒
                                val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                                if (prefs.getBoolean("wake_up_enabled", false)) {
                                    WakeWordDetector.startWakeWordMode(context)
                                }
                            }
                        },
                        onErrorCallback = { err ->
                             scope.launch {
                                 delay(500)
                                 // 【关键修复】检查开关
                                 val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                                 if (prefs.getBoolean("wake_up_enabled", false)) {
                                     WakeWordDetector.startWakeWordMode(context)
                                 }
                             }
                        }
                    )
                }
            }
        }
    }

    val isUserScrolling by listState.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(listState) {
        snapshotFlow { 
            Triple(
                listState.firstVisibleItemIndex, 
                listState.firstVisibleItemScrollOffset,
                listState.layoutInfo.totalItemsCount
            ) 
        }.collect {
            if (isUserScrolling) {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                if (totalItems > 0) {
                    val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                    if (lastVisibleItem != null) {
                         val isAtBottom = lastVisibleItem.index == totalItems - 1 &&
                                          (lastVisibleItem.offset + lastVisibleItem.size <= layoutInfo.viewportEndOffset + 50)
                         isAutoScroll = isAtBottom
                    }
                }
            }
        }
    }

    LaunchedEffect(listState, uiState.messages, isUserScrolling, isAutoScroll) {
        snapshotFlow { listState.layoutInfo }
            .collect { layoutInfo ->
                if (isUserScrolling) return@collect
                if (!isAutoScroll) return@collect
                
                val totalItems = layoutInfo.totalItemsCount
                if (totalItems == 0) return@collect
                
                val lastIndex = totalItems - 1
                val lastMessage = uiState.messages.lastOrNull()
                
                val shouldFollow = (lastMessage?.role == "assistant" && lastMessage.isGenerating) ||
                                   (uiState.isLoading && totalItems > 0)

                if (shouldFollow) {
                    val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                    if (lastVisibleItem != null && lastVisibleItem.index == lastIndex) {
                        val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
                        val itemBottom = lastVisibleItem.offset + lastVisibleItem.size
                        val buffer = 10 
                        if (itemBottom > viewportHeight) {
                            val diff = itemBottom - viewportHeight + buffer
                            listState.scrollBy(diff.toFloat())
                        }
                    } else if (lastVisibleItem != null && lastVisibleItem.index < lastIndex) {
                        listState.scrollToItem(lastIndex)
                    }
                }
            }
    }
    
    LaunchedEffect(uiState.messages.size) {
        if (!isUserScrolling && uiState.messages.isNotEmpty()) {
            if (!isAutoScroll) return@LaunchedEffect
            val hasLoadingItem = uiState.isLoading && uiState.messages.lastOrNull()?.role != "assistant"
            val totalItems = uiState.messages.size + if (hasLoadingItem) 1 else 0
            if (totalItems > 0) {
                listState.animateScrollToItem(totalItems - 1)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            speechRecognizerManager.destroy()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(context, context.getString(R.string.voice_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkServiceStatus()
                viewModel.checkOverlayPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(uiState.missingAccessibilityService) {
        if (uiState.missingAccessibilityService) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }
    }
    LaunchedEffect(uiState.missingOverlayPermission) {
        if (uiState.missingOverlayPermission) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }
    }

    val isDark = isSystemInDarkTheme()

    val contentPrimaryColor = if (isDark) Color(0xFFEEEEEE) else Color.Black
    val contentSecondaryColor = if (isDark) Color(0xFFB0BEC5) else Color.Gray
    val surfaceColor = if (isDark) Color(0xFF303030) else Color.White
    
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
        val (topColor, bottomColor) = getThemeBackgroundColors(uiState.themeColorIndex, themeColor)
        Brush.verticalGradient(
            colors = listOf(
                topColor, 
                topColor, 
                bottomColor
            )
        )
    }
    
    val menuBackground = if (isDark) Color(0xFF424242).copy(alpha = 0.95f) else Color.White.copy(alpha = 0.9f)
    
    val inputAreaBackground = if (isDark) Color(0xFF424242).copy(alpha = 0.6f) else Color.White.copy(alpha = 0.6f)
    val inputTextColor = if (isDark) Color.White else Color.Black

    val neonCyan = Color(0xFF00FFFF)
    val neonBlue = Color(0xFF2979FF)
    val neonPurple = Color(0xFFD500F9)
    val neonPink = Color(0xFFFF00FF)
    val neonYellow = Color(0xFFFFEA00)
    
    val borderColors = listOf(
        neonCyan, neonBlue, neonPurple, neonPink, neonYellow, neonCyan
    )

    val infiniteTransition = rememberInfiniteTransition(label = "border_effects")
    
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val orbPositionBias by animateFloatAsState(
        targetValue = if (isVoiceMode) 0f else -1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "orbPos"
    )
    
    val orbSize by animateDpAsState(
        targetValue = if (isVoiceMode) 72.dp else 40.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "orbSize"
    )

    val inputAlpha by animateFloatAsState(
        targetValue = if (isVoiceMode) 0f else 1f,
        animationSpec = tween(300),
        label = "inputAlpha"
    )
    val keyboardIconAlpha by animateFloatAsState(
        targetValue = if (isVoiceMode) 1f else 0f,
        animationSpec = tween(300),
        label = "kbIconAlpha"
    )

    val customTextSelectionColors = TextSelectionColors(
        handleColor = themeColor,
        backgroundColor = themeColor.copy(alpha = 0.4f)
    )

    val glassContainerColor = if (isDark) Color(0xFF303030).copy(alpha = 0.6f) else Color.White.copy(alpha = 0.4f)

    CompositionLocalProvider(LocalTextSelectionColors provides customTextSelectionColors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgBrush)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding() 
                    .imePadding()
            ) {
                // ================= Header =================
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.width(40.dp))
                    
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showModelSwitch = true }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isDeepThinking) "DeepSeek R1" else "小安助手", 
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp
                                ),
                                color = if (isDark) Color.White else Color.Black
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Switch Model",
                                tint = if (isDark) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        MaterialTheme(
                            colorScheme = MaterialTheme.colorScheme.copy(
                                surface = menuBackground,
                                onSurface = contentPrimaryColor
                            ),
                            shapes = MaterialTheme.shapes.copy(
                                extraSmall = RoundedCornerShape(12.dp)
                            )
                        ) {
                            DropdownMenu(
                                expanded = showModelSwitch,
                                onDismissRequest = { showModelSwitch = false },
                                modifier = Modifier.background(menuBackground, RoundedCornerShape(12.dp))
                            ) {
                                DropdownMenuItem(
                                    text = { 
                                        Text("小安助手 (控手机)", fontWeight = if(!isDeepThinking) FontWeight.Bold else FontWeight.Normal) 
                                    },
                                    onClick = {
                                        if (isDeepThinking) viewModel.toggleDeepThinkingMode()
                                        showModelSwitch = false
                                    },
                                    trailingIcon = {
                                        if (!isDeepThinking) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = themeColor)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { 
                                        Text("DeepSeek R1 (深度思考)", fontWeight = if(isDeepThinking) FontWeight.Bold else FontWeight.Normal) 
                                    },
                                    onClick = {
                                        if (!isDeepThinking) viewModel.toggleDeepThinkingMode()
                                        showModelSwitch = false
                                    },
                                    trailingIcon = {
                                        if (isDeepThinking) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = themeColor)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            TwoDotsIcon(
                                tint = if (isDark) Color(0xFFE0E0E0) else Color(0xFF424242)
                            )
                        }

                        MaterialTheme(
                            colorScheme = MaterialTheme.colorScheme.copy(
                                surface = menuBackground,
                                onSurface = contentPrimaryColor
                            ),
                            shapes = MaterialTheme.shapes.copy(
                                extraSmall = RoundedCornerShape(16.dp)
                            )
                        ) {
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                modifier = Modifier.background(menuBackground, RoundedCornerShape(16.dp))
                            ) {
                                DropdownMenuItem(
                                    text = { 
                                        Text(
                                            stringResource(R.string.menu_clear_history),
                                            color = contentPrimaryColor, 
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        ) 
                                    },
                                    onClick = {
                                        viewModel.clearHistory()
                                        showMenu = false
                                        Toast.makeText(context, context.getString(R.string.history_cleared), Toast.LENGTH_SHORT).show()
                                    },
                                    colors = MenuDefaults.itemColors(
                                        textColor = contentPrimaryColor
                                    )
                                )
                                
                                DropdownMenuItem(
                                    text = { 
                                        Text(
                                            stringResource(R.string.menu_settings),
                                            color = contentPrimaryColor, 
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        ) 
                                    },
                                    onClick = {
                                        onOpenSettings()
                                        showMenu = false
                                    },
                                    colors = MenuDefaults.itemColors(
                                        textColor = contentPrimaryColor
                                    )
                                )
                            }
                        }
                    }
                }

                // ================= Content Area =================
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (uiState.messages.isEmpty() && !uiState.isLoading) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isDark) Color(0xFF333333).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.35f)
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp)
                                ) {
                                    Text(
                                        text = if (isDeepThinking) "Hi，我是 DeepSeek~" else "Hi，我是 小安~",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = contentPrimaryColor.copy(alpha = 0.8f),
                                        fontSize = 20.sp
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = if (isDeepThinking) "我可以帮你深度思考、答疑解惑、探索知识" else "我可以帮你操作手机完成任务", 
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = contentPrimaryColor.copy(alpha = 0.7f),
                                        lineHeight = 24.sp
                                    )
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        currentRecommendations.forEach { cmd ->
                                            SuggestionChip(cmd, isDark, themeColor) { triggerSend(cmd) }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                val targetList = if (isDeepThinking) qaCommands else allCommands
                                                currentRecommendations = targetList.shuffled().take(3)
                                            },
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = null,
                                            tint = contentSecondaryColor,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "换一换",
                                            color = contentSecondaryColor,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(uiState.messages) { message ->
                                key(isDark, themeColor) {
                                    MessageItem(message, isDark, uiState.userAvatarPath, themeColor)
                                }
                            }
                            if (uiState.isLoading && uiState.messages.lastOrNull()?.role != "assistant") {
                                item {
                                    LoadingMessageItem(isDark, themeColor)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(80.dp))
            }

            // ==========================================
            // 【悬浮层】Bottom Input Area
            // ==========================================
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .systemBarsPadding() 
                    .imePadding()
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.isRunning,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                    modifier = Modifier
                        .align(Alignment.TopCenter) 
                        .offset(y = (-60).dp)       
                ) {
                    Card(
                        onClick = { viewModel.stopGeneration() },
                        shape = RoundedCornerShape(24.dp), 
                        colors = CardDefaults.cardColors(
                            containerColor = glassContainerColor 
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        modifier = Modifier.wrapContentSize()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop",
                                tint = themeColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "停止执行",
                                color = themeColor,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .height(IntrinsicSize.Min),
                    contentAlignment = Alignment.Center 
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 12.dp)
                            .graphicsLayer { alpha = keyboardIconAlpha }
                    ) {
                        if (isVoiceMode) {
                            IconButton(
                                onClick = { 
                                    isVoiceMode = false
                                    // 【关键修复】切换到键盘模式，先检查开关再决定是否重启监听
                                    scope.launch { 
                                        speechRecognizerManager.stopListening() 
                                        delay(500)
                                        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                                        if (prefs.getBoolean("wake_up_enabled", false)) {
                                            WakeWordDetector.startWakeWordMode(context)
                                        }
                                    }
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Keyboard,
                                    contentDescription = "Switch to Keyboard",
                                    tint = contentSecondaryColor,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }

                    if (!isVoiceMode) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 48.dp) 
                                .background(inputAreaBackground, RoundedCornerShape(28.dp))
                                .drawWithContent {
                                    drawContent() 
                                    
                                    val strokeWidth = 2.dp.toPx()
                                    val cornerRadius = 28.dp.toPx()
                                    
                                    val outerPath = Path().apply {
                                        addRoundRect(RoundRect(rect = Rect(offset = Offset.Zero, size = size), cornerRadius = CornerRadius(cornerRadius)))
                                    }
                                    val innerPath = Path().apply {
                                        addRoundRect(RoundRect(
                                            left = strokeWidth, top = strokeWidth, 
                                            right = size.width - strokeWidth, bottom = size.height - strokeWidth,
                                            cornerRadius = CornerRadius(cornerRadius - strokeWidth)
                                        ))
                                    }
                                    val borderPath = Path().apply {
                                        op(outerPath, innerPath, PathOperation.Difference)
                                    }
                                    
                                    clipPath(borderPath) {
                                        rotate(rotation) {
                                            val maxSize = max(size.width, size.height) * 2.5f
                                            drawCircle(
                                                brush = Brush.sweepGradient(borderColors),
                                                radius = maxSize / 2,
                                                center = center
                                            )
                                        }
                                    }
                                }
                                .graphicsLayer { alpha = inputAlpha },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                                    .heightIn(min = 56.dp, max = 150.dp),
                                placeholder = { 
                                    Text(stringResource(R.string.input_placeholder), color = contentSecondaryColor, fontSize = 16.sp) 
                                },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    cursorColor = themeColor,
                                    focusedTextColor = inputTextColor,
                                    unfocusedTextColor = inputTextColor
                                ),
                                maxLines = 5,
                                singleLine = false 
                            )
                            
                            if (inputText.isNotBlank()) {
                                IconButton(
                                    onClick = { triggerSend(inputText) },
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Send, 
                                        contentDescription = "Send", 
                                        tint = themeColor
                                    )
                                }
                            }
                        }
                    }

                    VoiceOrb(
                        isListening = isListening,
                        soundLevel = soundLevel,
                        baseColor = themeColor,
                        onClick = {
                            if (isVoiceMode) {
                                if (!isModelReady) {
                                    scope.launch { SherpaModelManager.initModel(context) }
                                    return@VoiceOrb
                                }
                                if (androidx.core.content.ContextCompat.checkSelfPermission(
                                        context, android.Manifest.permission.RECORD_AUDIO
                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    if (isListening) {
                                        scope.launch { 
                                            speechRecognizerManager.stopListening() 
                                            delay(500)
                                            // 【关键修复】检查开关
                                            val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                                            if (prefs.getBoolean("wake_up_enabled", false)) {
                                                WakeWordDetector.startWakeWordMode(context)
                                            }
                                        }
                                    } else {
                                        inputText = "" 
                                        speechRecognizerManager.startListening(
                                            onResultCallback = { result ->
                                                inputText = result
                                                scope.launch {
                                                    delay(300)
                                                    triggerSend(result)
                                                    delay(500)
                                                    // 【关键修复】检查开关
                                                    val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                                                    if (prefs.getBoolean("wake_up_enabled", false)) {
                                                        WakeWordDetector.startWakeWordMode(context)
                                                    }
                                                }
                                            },
                                            onErrorCallback = { err ->
                                                Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                                scope.launch {
                                                    delay(500)
                                                    // 【关键修复】检查开关
                                                    val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                                                    if (prefs.getBoolean("wake_up_enabled", false)) {
                                                        WakeWordDetector.startWakeWordMode(context)
                                                    }
                                                }
                                            }
                                        )
                                    }
                                } else {
                                    permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                }
                            } else {
                                isVoiceMode = true
                            }
                        },
                        modifier = Modifier
                            .align(BiasAlignment(orbPositionBias, 0f))
                            .size(orbSize)
                    )
                    
                    if (isVoiceMode) {
                        Column(
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 0.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            AnimatedVisibility(
                                visible = isListening,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically()
                            ) {
                                // 【新增功能】显示实时字幕
                                val displayText = if (partialText.isNotBlank()) partialText else "聆听中..."
                                
                                Text(
                                    text = displayText,
                                    color = if (isDark) themeColor.copy(alpha=0.9f) else themeColor,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.offset(y = (-24).dp),
                                    style = LocalTextStyle.current.copy(
                                        shadow = androidx.compose.ui.graphics.Shadow(
                                            color = Color.Black.copy(alpha = 0.3f),
                                            offset = Offset(0f, 2f),
                                            blurRadius = 4f
                                        )
                                    )
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.error != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {},
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = uiState.error!!,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { viewModel.clearError() },
                                colors = ButtonDefaults.buttonColors(containerColor = themeColor)
                            ) {
                                Text(stringResource(R.string.close))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MessageItem(message: UiMessage, isDark: Boolean, userAvatarPath: String?, themeColor: Color) {
    val isUser = message.role == "user"
    val arrangement = if (isUser) Arrangement.End else Arrangement.Start
    
    val containerColor = if (isUser) {
        themeColor 
    } else {
        if (isDark) Color(0xFF424242) else Color.White
    }
    
    val contentColor = if (isUser) {
        Color.White
    } else {
        if (isDark) Color(0xFFEEEEEE) else Color.Black
    }
    
    val thinkingTextColor = if (isDark) Color.Gray else Color.Gray

    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    
    val bubbleMaxWidth = if (isUser) screenWidth * 0.72f else screenWidth * 0.92f

    val isPureLoading = message.role != "user" && 
                        message.isGenerating && 
                        message.thinking.isEmpty() && 
                        message.content.isEmpty()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            MiniVoiceOrb(baseColor = themeColor, modifier = Modifier.size(36.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }

        Surface(
            shape = if (isUser)
                RoundedCornerShape(6.dp, 0.dp, 6.dp, 6.dp) 
            else
                RoundedCornerShape(0.dp, 6.dp, 6.dp, 6.dp),
            color = containerColor,
            shadowElevation = if (isUser) 2.dp else 1.dp,
            modifier = Modifier
                .then(
                    if (isPureLoading) Modifier.wrapContentWidth()
                    else Modifier.widthIn(min = 60.dp, max = bubbleMaxWidth)
                )
                .weight(1f, fill = false) 
                .animateContentSize()
        ) {
            if (isPureLoading) {
                Box(
                    modifier = Modifier.padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = themeColor,
                        strokeWidth = 2.dp
                    )
                }
            } else {
                SelectionContainer {
                    Column {
                        if (message.thinking.isNotEmpty()) {
                            var isExpanded by remember { mutableStateOf(true) }
                            
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) { isExpanded = !isExpanded },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = message.thinkingTimeDisplay, 
                                        style = MaterialTheme.typography.labelSmall,
                                        color = thinkingTextColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                                        tint = thinkingTextColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                
                                AnimatedVisibility(visible = isExpanded) {
                                    Column(modifier = Modifier.padding(top = 8.dp)) {
                                        MarkdownText(
                                            markdown = message.thinking,
                                            color = thinkingTextColor,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp)
                                        )
                                    }
                                }
                            }
                            
                            Divider(color = thinkingTextColor.copy(alpha = 0.2f), thickness = 1.dp)
                        }
                        
                        if (message.content.isNotEmpty()) {
                            Box(modifier = Modifier.padding(12.dp)) {
                                val fontSize = if (isUser) 16.sp else 14.sp
                                val lineHeight = if (isUser) 24.sp else 22.sp
                                
                                MarkdownText(
                                    markdown = message.content,
                                    color = contentColor,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize, lineHeight = lineHeight)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            UserAvatar(
                avatarPath = userAvatarPath,
                isDark = isDark,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

@Composable
fun LoadingMessageItem(isDark: Boolean, themeColor: Color) {
    val containerColor = if (isDark) Color(0xFF424242) else Color.White
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        MiniVoiceOrb(baseColor = themeColor, modifier = Modifier.size(36.dp))
        Spacer(modifier = Modifier.width(8.dp))
        
        Surface(
            shape = RoundedCornerShape(0.dp, 6.dp, 6.dp, 6.dp),
            color = containerColor,
            shadowElevation = 1.dp,
            modifier = Modifier.wrapContentWidth()
        ) {
            Box(
                modifier = Modifier.padding(14.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = themeColor,
                    strokeWidth = 2.dp
                )
            }
        }
    }
}