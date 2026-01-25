package ai.xiaozhi.utils

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object SherpaModelManager {
    private const val TAG = "SherpaModelManager"
    
    private val _modelState = MutableStateFlow<ModelState>(ModelState.NotInitialized)
    val modelState: StateFlow<ModelState> = _modelState

    var recognizer: OfflineRecognizer? = null
        private set

    sealed class ModelState {
        object NotInitialized : ModelState()
        object Loading : ModelState()
        object Ready : ModelState()
        data class Error(val message: String) : ModelState()
    }

    // 初始化模型：适配 SenseVoice (2025最新版)
    suspend fun initModel(context: Context) {
        if (recognizer != null) {
            _modelState.value = ModelState.Ready
            return
        }

        _modelState.value = ModelState.Loading

        withContext(Dispatchers.IO) {
            try {
                val modelDir = File(context.filesDir, "sherpa-model")
                if (!modelDir.exists()) modelDir.mkdirs()

                // 约定文件名：model.onnx 和 tokens.txt
                val modelName = "model.onnx"
                val tokensName = "tokens.txt"
                
                val modelFile = File(modelDir, modelName)
                val tokensFile = File(modelDir, tokensName)

                // 复制资源到私有目录
                copyAsset(context, "sherpa-model/$modelName", modelFile)
                copyAsset(context, "sherpa-model/$tokensName", tokensFile)

                // 校验文件
                if (!modelFile.exists() || !tokensFile.exists() || modelFile.length() == 0L || tokensFile.length() == 0L) {
                    _modelState.value = ModelState.Error("模型文件缺失，请检查 assets/sherpa-model")
                    return@withContext
                }

                // ==========================================
                // SenseVoice 2025 核心配置 (完整无删减)
                // ==========================================
                val config = OfflineRecognizerConfig(
                    featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(
                        sampleRate = 16000,
                        featureDim = 80
                    ),
                    modelConfig = OfflineModelConfig(
                        // 指定使用 SenseVoice 配置
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = modelFile.absolutePath,
                            // 关键：开启逆文本标准化，自动把"百分之五十"转为"50%"
                            // 这就是为什么代码变短了，因为模型变强了，不需要手动写替换逻辑了
                            useInverseTextNormalization = true, 
                            language = "zh" // 强制中文模式
                        ),
                        tokens = tokensFile.absolutePath,
                        debug = false, 
                        numThreads = 2, // 2-4线程适合 SenseVoice
                        modelType = "sense_voice"
                    )
                )

                recognizer = OfflineRecognizer(assetManager = null, config = config)
                Log.d(TAG, "Sherpa-ONNX (SenseVoice 2025) initialized successfully")
                _modelState.value = ModelState.Ready

            } catch (e: Exception) {
                e.printStackTrace()
                Log.e(TAG, "Failed to init Sherpa-ONNX: ${e.message}")
                _modelState.value = ModelState.Error(e.message ?: "初始化失败")
            }
        }
    }

    private fun copyAsset(context: Context, assetPath: String, outFile: File) {
        try {
            // 如果文件已存在且大小正常，跳过复制
            if (outFile.exists() && outFile.length() > 0) {
                 return 
            }
            Log.d(TAG, "Copying asset $assetPath to ${outFile.absolutePath}")
            context.assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy asset: $assetPath", e)
            if (outFile.exists()) outFile.delete()
        }
    }
    
    fun destroy() {
        recognizer?.release()
        recognizer = null
        _modelState.value = ModelState.NotInitialized
    }
}