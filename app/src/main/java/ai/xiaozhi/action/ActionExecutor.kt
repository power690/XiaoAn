package ai.xiaozhi.action

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import ai.xiaozhi.AutoGLMInputMethodService
import ai.xiaozhi.AutoGLMService
import ai.xiaozhi.action.AppMapper
import ai.xiaozhi.utils.ShizukuHelper
import kotlinx.coroutines.delay

class ActionExecutor(private val service: AutoGLMService) {

    private val myImeId = "${service.packageName}/.AutoGLMInputMethodService"

    suspend fun execute(action: Action): Boolean {
        return when (action) {
            is Action.Tap -> {
                Log.d("ActionExecutor", "Tapping ${action.x}, ${action.y}")
                val success = service.performTap(action.x.toFloat(), action.y.toFloat())
                delay(1000)
                success
            }
            is Action.DoubleTap -> {
                Log.d("ActionExecutor", "Double Tapping ${action.x}, ${action.y}")
                // Execute two taps with a short delay
                val success1 = service.performTap(action.x.toFloat(), action.y.toFloat())
                delay(150) 
                val success2 = service.performTap(action.x.toFloat(), action.y.toFloat())
                delay(1000)
                success1 && success2
            }
            is Action.LongPress -> {
                Log.d("ActionExecutor", "Long Pressing ${action.x}, ${action.y}")
                val success = service.performLongPress(action.x.toFloat(), action.y.toFloat())
                delay(1000)
                success
            }
            is Action.Swipe -> {
                Log.d("ActionExecutor", "Swiping ${action.startX},${action.startY} -> ${action.endX},${action.endY}")
                val success = service.performSwipe(
                    action.startX.toFloat(), action.startY.toFloat(),
                    action.endX.toFloat(), action.endY.toFloat()
                )
                delay(1000)
                success
            }
            is Action.Type -> {
                Log.d("ActionExecutor", "Typing via IME: ${action.text}")
                executeTypeWithImeSwitch(action.text)
            }
            is Action.Launch -> {
                Log.d("ActionExecutor", "Launching ${action.appName}")
                val packageName = AppMapper.getPackageName(action.appName)
                if (packageName != null) {
                    val intent = service.packageManager.getLaunchIntentForPackage(packageName)
                    if (intent != null) {
                        Log.d("ActionExecutor", "Found intent for $packageName, starting activity...")
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            service.startActivity(intent)
                            Log.d("ActionExecutor", "Activity started successfully")
                            delay(2000)
                            true
                        } catch (e: Exception) {
                            Log.e("ActionExecutor", "Failed to start activity: ${e.message}")
                            false
                        }
                    } else {
                        Log.e("ActionExecutor", "Launch intent is null for $packageName")
                        false
                    }
                } else {
                    Log.e("ActionExecutor", "Unknown app: ${action.appName} (mapped to null)")
                    false
                }
            }
            is Action.Back -> {
                service.performGlobalBack()
                delay(1000)
                true
            }
            is Action.Home -> {
                service.performGlobalHome()
                delay(1000)
                true
            }
            is Action.Wait -> {
                delay(action.durationMs)
                true
            }
            is Action.Finish -> {
                Log.i("ActionExecutor", "Task Finished: ${action.message}")
                true
            }
            is Action.Error -> {
                Log.e("ActionExecutor", "Error: ${action.reason}")
                false
            }
            Action.Unknown -> false
        }
    }

    /**
     * 使用 IME 切换方案输入文字：
     * 1. 使用 Shizuku/Settings 切换到 AutoGLM 输入法
     * 2. 发送广播输入文字
     * 3. 切换回原来的输入法
     */
    private suspend fun executeTypeWithImeSwitch(text: String): Boolean {
        // 0. 尝试点击输入框获取焦点
        val root = service.rootInActiveWindow
        val editableNode = findEditableNode(root)
        if (editableNode != null) {
            // 如果找到了编辑框，先点击一下确保它聚焦，触发系统弹出输入法
            if (!editableNode.isFocused) {
                 val rect = android.graphics.Rect()
                 editableNode.getBoundsInScreen(rect)
                 service.performTap(rect.centerX().toFloat(), rect.centerY().toFloat())
                 delay(500) // 等待键盘弹出
            }
        }

        // 1. 获取当前默认输入法 ID
        val resolver = service.contentResolver
        val defaultIme = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        
        Log.d("ActionExecutor", "Current IME: $defaultIme, Target: $myImeId")

        // 标记是否切换了输入法，以便后续切回
        var switched = false

        try {
            // 2. 切换到 AutoGLM 输入法
            if (defaultIme != myImeId) {
                val switchResult = switchIme(myImeId)
                if (!switchResult) {
                    Log.w("ActionExecutor", "Failed to switch IME. Fallback to Accessibility.")
                    return fallbackAccessibilityInput(text)
                }
                switched = true
                delay(800) // 等待输入法切换完成
            }

            // 3. 发送广播给 IME 服务输入文字
            val intent = Intent(AutoGLMInputMethodService.ACTION_TYPE_TEXT)
            intent.putExtra(AutoGLMInputMethodService.EXTRA_TEXT, text)
            service.sendBroadcast(intent)
            
            Log.d("ActionExecutor", "Broadcast sent with text: $text")
            
            // 等待输入完成
            delay(1500)

            // 4. 切换回原来的输入法
            if (switched && defaultIme != null) {
                switchIme(defaultIme)
                delay(500)
            }
            
            return true

        } catch (e: Exception) {
            Log.e("ActionExecutor", "Exception during IME switch typing", e)
            return fallbackAccessibilityInput(text)
        }
    }

    /**
     * 切换输入法：优先使用 Shizuku，失败则尝试 Settings API
     */
    private fun switchIme(imeId: String): Boolean {
        // 优先尝试 Shizuku (最稳，无需手动ADB)
        if (ShizukuHelper.isShizukuAvailable() && ShizukuHelper.checkPermission(service)) {
            Log.d("ActionExecutor", "Switching IME via Shizuku: $imeId")
            // 注意引号处理，防止 ID 中的特殊字符导致 Shell 命令错误
            return ShizukuHelper.executeShellCommand("settings put secure default_input_method '$imeId'")
        }

        // 降级尝试 Settings API (需要 WRITE_SECURE_SETTINGS 权限，通常需 ADB 授权)
        try {
            Log.d("ActionExecutor", "Switching IME via Settings API: $imeId")
            return Settings.Secure.putString(service.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, imeId)
        } catch (e: Exception) {
            Log.e("ActionExecutor", "Settings API failed", e)
        }
        
        return false
    }

    /**
     * 降级方案：使用无障碍 API 直接设置文本
     */
    private suspend fun fallbackAccessibilityInput(text: String): Boolean {
        Log.d("ActionExecutor", "Fallback: Using AccessibilityNodeInfo.ACTION_SET_TEXT")
        val root = service.rootInActiveWindow
        val editableNode = findEditableNode(root)
        
        if (editableNode != null) {
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val success = editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            delay(500)
            return success
        } else {
            Log.e("ActionExecutor", "No editable node found for fallback input")
            return false
        }
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        
        // 有些输入框 isEditable 为 false，但其实是 EditText
        if (node.className == "android.widget.EditText") return node
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val result = findEditableNode(child)
            if (result != null) return result
        }
        return null
    }
}