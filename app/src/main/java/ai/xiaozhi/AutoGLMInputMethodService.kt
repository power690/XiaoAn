package ai.xiaozhi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

class AutoGLMInputMethodService : InputMethodService() {

    companion object {
        const val ACTION_TYPE_TEXT = "com.sidhu.androidautoglm.TYPE_TEXT"
        const val EXTRA_TEXT = "text"
    }

    private val typeTextReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TYPE_TEXT) {
                val text = intent.getStringExtra(EXTRA_TEXT)
                if (!text.isNullOrEmpty()) {
                    Log.d("AutoGLMIME", "Received text: $text")
                    commitTextToInput(text)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(ACTION_TYPE_TEXT)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(typeTextReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(typeTextReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(typeTextReceiver)
    }

    override fun onCreateInputView(): View {
        // 返回一个高度为0的View，确保不占用屏幕布局空间
        return View(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(0, 0)
            visibility = View.GONE
        }
    }

    /**
     * 关键修复：解决输入法遮挡触摸区域的问题
     * 通过设置 touchableRegion 为空，让所有触摸事件穿透输入法层
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        // 设置内容和可见高度为 0
        outInsets.contentTopInsets = 0
        outInsets.visibleTopInsets = 0
        
        // 设置触摸区域为 REGION，并将 Region 设置为空
        // 这样所有触摸事件都会直接穿透到底层应用，不会被透明输入法拦截
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.setEmpty()
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        // 禁用全屏模式
        return false
    }

    private fun commitTextToInput(text: String) {
        val inputConnection: InputConnection? = currentInputConnection
        if (inputConnection != null) {
            try {
                inputConnection.commitText(text, 1)
            } catch (e: Exception) {
                Log.e("AutoGLMIME", "Error committing text", e)
            }
        }
    }
}