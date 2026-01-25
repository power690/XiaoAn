package ai.xiaozhi.action

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

object AppMapper {
    // 缓存已安装的应用映射：应用名 -> 包名
    private val installedAppMap = mutableMapOf<String, String>()

    // 原有的硬编码列表作为备用（保留原有数据以防未安装但需要识别的情况）
    private val hardcodedMap = mapOf(
        // === System Apps ===
        "AndroidSystemSettings" to "com.android.settings",
        "Android System Settings" to "com.android.settings",
        "设置" to "com.android.settings",
        "Settings" to "com.android.settings",
        "相机" to "com.android.camera2",
        "Camera" to "com.android.camera2",
        "电话" to "com.google.android.dialer",
        "Phone" to "com.google.android.dialer",
        "短信" to "com.google.android.apps.messaging",
        "Messages" to "com.google.android.apps.messaging",
        "相册" to "com.google.android.apps.photos",
        "Photos" to "com.google.android.apps.photos",
        "联系人" to "com.google.android.contacts",
        "Contacts" to "com.android.contacts",
        "日历" to "com.google.android.calendar",
        "Calendar" to "com.google.android.calendar",
        "时钟" to "com.google.android.deskclock",
        "Clock" to "com.android.deskclock",
        "计算器" to "com.google.android.calculator",
        "Calculator" to "com.google.android.calculator",
        "文件" to "com.google.android.documentsui",
        "Files" to "com.android.fileexplorer",
        "浏览器" to "com.android.chrome",
        "Chrome" to "com.android.chrome",
        "应用商店" to "com.android.vending",
        "Play Store" to "com.android.vending",
        
        // === Social & Communication (CN) ===
        "微信" to "com.tencent.mm",
        "WeChat" to "com.tencent.mm",
        "QQ" to "com.tencent.mobileqq",
        "微博" to "com.sina.weibo",
        "Weibo" to "com.sina.weibo",
        "小红书" to "com.xingin.xhs",
        "Xiaohongshu" to "com.xingin.xhs",
        "知乎" to "com.zhihu.android",
        "豆瓣" to "com.douban.frodo",

        // === Shopping & Life (CN) ===
        "支付宝" to "com.eg.android.AlipayGphone",
        "Alipay" to "com.eg.android.AlipayGphone",
        "淘宝" to "com.taobao.taobao",
        "Taobao" to "com.taobao.taobao",
        "京东" to "com.jingdong.app.mall",
        "JD" to "com.jingdong.app.mall",
        "拼多多" to "com.xunmeng.pinduoduo",
        "美团" to "com.sankuai.meituan",
        "饿了么" to "me.ele",
        "大众点评" to "com.dianping.v1",
        "闲鱼" to "com.taobao.idlefish",
        "携程" to "ctrip.android.view",
        "去哪儿" to "com.Qunar",
        "滴滴" to "com.sdu.did.psnger",
        "滴滴出行" to "com.sdu.did.psnger",

        // === Entertainment (CN) ===
        "抖音" to "com.ss.android.ugc.aweme",
        "Douyin" to "com.ss.android.ugc.aweme",
        "快手" to "com.smile.gifmaker",
        "Kuaishou" to "com.smile.gifmaker",
        "哔哩哔哩" to "tv.danmaku.bili",
        "Bilibili" to "tv.danmaku.bili",
        "B站" to "tv.danmaku.bili",
        "网易云" to "com.netease.cloudmusic",
        "网易云音乐" to "com.netease.cloudmusic",
        "QQ音乐" to "com.tencent.qqmusic",
        "爱奇艺" to "com.qiyi.video",
        "腾讯视频" to "com.tencent.qqlive",
        "优酷" to "com.youku.phone",
        "西瓜视频" to "com.ss.android.article.video", // 补充西瓜视频
        "华为智慧生活" to "com.huawei.smarthome",
        "智慧生活" to "com.huawei.smarthome",

        // === Tools & Navigation (CN) ===
        "高德地图" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        "钉钉" to "com.alibaba.android.rimet",
        "飞书" to "com.ss.android.lark",

        // === International Apps ===
        "YouTube" to "com.google.android.youtube",
        "Gmail" to "com.google.android.gm",
        "Google Maps" to "com.google.android.apps.maps",
        "Maps" to "com.google.android.apps.maps",
        "Twitter" to "com.twitter.android",
        "X" to "com.twitter.android",
        "Facebook" to "com.facebook.katana",
        "Instagram" to "com.instagram.android",
        "WhatsApp" to "com.whatsapp",
        "Telegram" to "org.telegram.messenger",
        "Spotify" to "com.spotify.music",
        "Netflix" to "com.netflix.mediaclient",
        "TikTok" to "com.zhiliaoapp.musically"
    )

    /**
     * 刷新已安装的应用列表
     * 需要在 MainActivity 启动时调用，或者在需要时调用
     */
    fun refreshInstalledApps(context: Context) {
        installedAppMap.clear()
        val pm = context.packageManager
        // 获取所有已安装的应用
        val packages = pm.getInstalledPackages(0)

        for (packageInfo in packages) {
            // FIX: applicationInfo 可能是空的，需要做安全调用
            val appInfo = packageInfo.applicationInfo
            if (appInfo == null) continue

            val appName = appInfo.loadLabel(pm).toString()
            val packageName = packageInfo.packageName

            // 过滤掉部分纯系统底层包，只保留有启动入口的或者用户能感知的
            if (pm.getLaunchIntentForPackage(packageName) != null) {
                installedAppMap[appName] = packageName
                // 同时保存小写版本方便匹配
                installedAppMap[appName.lowercase()] = packageName
                Log.d("AppMapper", "Loaded App: $appName -> $packageName")
            }
        }
        Log.d("AppMapper", "Total installed apps loaded: ${installedAppMap.size}")
    }

    /**
     * 根据应用名称获取包名
     * 优先查找动态扫描到的应用，其次查找硬编码列表
     */
    fun getPackageName(appName: String): String? {
        // 1. 动态列表精确匹配
        installedAppMap[appName]?.let { return it }
        installedAppMap[appName.lowercase()]?.let { return it }

        // 2. 硬编码列表精确匹配
        hardcodedMap[appName]?.let { return it }
        hardcodedMap.entries.find { it.key.equals(appName, ignoreCase = true) }?.let { return it.value }

        // 3. 动态列表模糊匹配 (包含关系)
        // 例如：输入"网易云"，匹配到"网易云音乐"
        val partialMatch = installedAppMap.entries.find { 
            it.key.contains(appName, ignoreCase = true) || appName.contains(it.key, ignoreCase = true)
        }
        if (partialMatch != null) return partialMatch.value

        return null
    }

    /**
     * 分析用户输入的文本，看是否包含已安装应用的名字
     * 逻辑改进：优先匹配出现在文本前面的应用名
     * 例如："打开微信发给文件传输助手" -> 优先匹配 "微信"
     * @param text 用户输入的指令
     * @return Pair(PackageName, AppName) 或 null
     */
    fun findPackageNameInText(text: String): Pair<String, String>? {
        val lowerText = text.lowercase()
        
        // 存储所有匹配到的应用：(索引位置, 应用名长度, 包名, 应用名)
        // 我们希望找 index 最小的；如果 index 相同，找名字最长的
        val matches = mutableListOf<MatchResult>()

        // 1. 遍历动态扫描到的应用列表
        for ((appName, packageName) in installedAppMap) {
            if (appName.length < 2) continue // 忽略太短的名字
            val index = lowerText.indexOf(appName.lowercase())
            if (index != -1) {
                matches.add(MatchResult(index, appName.length, packageName, appName))
            }
        }

        // 2. 遍历硬编码列表
        for ((appName, packageName) in hardcodedMap) {
            val index = lowerText.indexOf(appName.lowercase())
            if (index != -1) {
                matches.add(MatchResult(index, appName.length, packageName, appName))
            }
        }

        if (matches.isEmpty()) return null

        // 排序规则：
        // 1. 优先 index 小的 (出现在句子前面的优先)
        // 2. 如果 index 一样，优先 length 大的 (匹配更完整的名字，例如优先匹配"网易云音乐"而不是"网易云")
        matches.sortWith(compareBy({ it.index }, { -it.length }))

        val bestMatch = matches.first()
        Log.d("AppMapper", "Matched app: '${bestMatch.appName}' at index ${bestMatch.index} (pkg: ${bestMatch.packageName})")
        
        return Pair(bestMatch.packageName, bestMatch.appName)
    }

    private data class MatchResult(
        val index: Int,
        val length: Int,
        val packageName: String,
        val appName: String
    )
}