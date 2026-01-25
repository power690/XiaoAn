package ai.xiaozhi.utils

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku
import java.lang.reflect.Method

object ShizukuHelper {
    private const val TAG = "ShizukuHelper"

    // 检查 Shizuku 是否可用
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    // 检查是否已授权
    fun checkPermission(context: Context): Boolean {
        return if (Shizuku.isPreV11()) {
            false
        } else {
            try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                false
            }
        }
    }

    // 请求权限
    fun requestPermission(code: Int) {
        if (isShizukuAvailable() && !Shizuku.isPreV11()) {
            try {
                Shizuku.requestPermission(code)
            } catch (e: Exception) {
                Log.e(TAG, "Request permission failed", e)
            }
        }
    }

    // 执行 Shell 命令
    fun executeShellCommand(command: String): Boolean {
        if (!isShizukuAvailable()) return false

        return try {
            // Shizuku.newProcess 在新版中是隐藏的，必须使用反射调用
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            
            // 调用 newProcess(String[] cmd, String[] env, String dir)
            val process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process

            val exitCode = process.waitFor()
            Log.d(TAG, "Shizuku Command: $command, ExitCode: $exitCode")
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Execute command failed", e)
            false
        }
    }

    /**
     * 使用 Shizuku 自动开启本应用的无障碍服务
     * 原理：修改 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
     */
    fun enableAccessibilityService(context: Context): Boolean {
        if (!isShizukuAvailable() || !checkPermission(context)) return false

        try {
            val packageName = context.packageName
            // 完整的服务组件名
            val serviceComponent = "$packageName/.AutoGLMService"
            
            // 1. 获取当前已开启的服务列表 (使用标准 API 读取即可)
            val currentServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            // 如果已经包含，直接返回成功
            if (currentServices.contains(serviceComponent)) {
                Log.d(TAG, "Accessibility service already enabled")
                return true
            }

            // 2. 拼接新的服务列表 (注意使用冒号分隔)
            val newServices = if (currentServices.isBlank()) {
                serviceComponent
            } else {
                "$currentServices:$serviceComponent"
            }

            Log.d(TAG, "Enabling accessibility via Shizuku. New list: $newServices")

            // 3. 构造 Shell 命令写入 Settings
            // 需要两条命令：
            // A. 设置服务列表
            // B. 确保无障碍主开关是开启的
            val cmdPutServices = "settings put secure enabled_accessibility_services '$newServices'"
            val cmdEnableMaster = "settings put secure accessibility_enabled 1"

            val success1 = executeShellCommand(cmdPutServices)
            val success2 = executeShellCommand(cmdEnableMaster)

            return success1 && success2

        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-enable accessibility", e)
            return false
        }
    }
}