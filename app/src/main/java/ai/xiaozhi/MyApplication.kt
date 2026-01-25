package ai.xiaozhi

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize wake-up service if enabled
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val wakeUpEnabled = prefs.getBoolean("wake_up_enabled", false)
        
        if (wakeUpEnabled) {
            // Start wake-up service through AutoGLMService when it's connected
            // The service will start itself when connected
        }
    }
}