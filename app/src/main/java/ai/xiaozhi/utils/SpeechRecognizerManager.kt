package ai.xiaozhi.utils

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.coroutines.coroutineContext

class SpeechRecognizerManager(private val context: Context) {

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _soundLevel = MutableStateFlow(0f)
    val soundLevel: StateFlow<Float> = _soundLevel

    // 新增：实时字幕流
    private val _partialResult = MutableStateFlow("")
    val partialResult: StateFlow<String> = _partialResult

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // 仅保留本地识别 Buffer
    private val localAudioBuffer = ArrayList<Float>()
    
    private var onResult: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    private var lastActiveTime = 0L
    private var hasSpeechStarted = false
    
    // VAD (静音检测) 参数
    private val SILENCE_THRESHOLD_DB = -45.0f 
    private val SILENCE_DURATION_MS = 1500L 
    private val NO_SPEECH_TIMEOUT_MS = 5000L 

    private var audioManager: AudioManager? = null
    private var isMuted = false
    private var focusRequest: AudioFocusRequest? = null
    
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    companion object {
        // 关键修复：定义 TAG
        private const val TAG = "SpeechRecognizerManager"
        
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        
        private const val MIC_GAIN_FACTOR = 4.0f
        // 实时解码间隔帧数 (约 0.3秒)
        private const val PARTIAL_DECODE_INTERVAL = 4800
    }

    init {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    @SuppressLint("MissingPermission")
    fun startListening(
        onResultCallback: (String) -> Unit,
        onErrorCallback: (String) -> Unit
    ) {
        val modelState = SherpaModelManager.modelState.value
        if (SherpaModelManager.recognizer == null) {
            if (modelState is SherpaModelManager.ModelState.Error) {
                onErrorCallback("Model Error: ${(modelState as SherpaModelManager.ModelState.Error).message}")
            } else {
                onErrorCallback("模型正在加载中，请稍后再试...")
            }
            return
        }

        if (_isListening.value) return

        muteMedia()

        onResult = onResultCallback
        onError = onErrorCallback
        
        // 重置状态
        synchronized(localAudioBuffer) { localAudioBuffer.clear() }
        _partialResult.value = "" // 清空上一句的实时字幕
        
        hasSpeechStarted = false
        lastActiveTime = System.currentTimeMillis()

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                maxOf(minBufferSize * 2, SAMPLE_RATE * 2)
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onErrorCallback("麦克风初始化失败")
                unmuteMedia()
                return
            }

            initAudioEffects(audioRecord!!.audioSessionId)

            audioRecord?.startRecording()
            _isListening.value = true
            
            startRecordingLoop()
            
        } catch (e: Exception) {
            e.printStackTrace()
            _isListening.value = false
            unmuteMedia()
            onErrorCallback(e.message ?: "无法启动录音")
        }
    }

    private fun initAudioEffects(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init audio effects", e)
        }
    }

    private fun releaseAudioEffects() {
        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
    }

    private fun startRecordingLoop() {
        recordingJob = scope.launch {
            val readSize = 512 
            val buffer = ShortArray(readSize)
            val startTime = System.currentTimeMillis()
            
            val recognizer = SherpaModelManager.recognizer
            var stream = recognizer?.createStream()
            var framesSinceLastDecode = 0
            
            try {
                while (_isListening.value) {
                    val read = audioRecord?.read(buffer, 0, readSize) ?: 0
                    if (read > 0) {
                        var sum = 0.0
                        val floatChunk = FloatArray(read)
                        
                        for (i in 0 until read) {
                            var sampleVal = (buffer[i] * MIC_GAIN_FACTOR).toInt()
                            if (sampleVal > Short.MAX_VALUE) sampleVal = Short.MAX_VALUE.toInt()
                            if (sampleVal < Short.MIN_VALUE) sampleVal = Short.MIN_VALUE.toInt()
                            val finalSample = sampleVal.toShort()

                            val floatVal = finalSample / 32768.0f
                            floatChunk[i] = floatVal
                            
                            synchronized(localAudioBuffer) {
                                localAudioBuffer.add(floatVal)
                            }
                            
                            val rmsVal = finalSample / 32768.0f
                            sum += rmsVal * rmsVal
                        }

                        // VAD
                        val rms = sqrt(sum / read)
                        val db = if (rms > 0) 20 * log10(rms) else -50.0
                        _soundLevel.value = db.toFloat()
                        
                        val currentTime = System.currentTimeMillis()
                        
                        if (db > SILENCE_THRESHOLD_DB) {
                            if (!hasSpeechStarted) {
                                hasSpeechStarted = true
                            }
                            lastActiveTime = currentTime
                        }
                        
                        // 实时给模型喂数据
                        if (stream != null) {
                            stream.acceptWaveform(floatChunk, SAMPLE_RATE)
                            framesSinceLastDecode += read
                            
                            // 实时解码逻辑
                            if (hasSpeechStarted && framesSinceLastDecode > PARTIAL_DECODE_INTERVAL) {
                                recognizer?.decode(stream)
                                val partial = recognizer?.getResult(stream)?.text ?: ""
                                if (partial.isNotBlank()) {
                                    _partialResult.value = partial
                                }
                                framesSinceLastDecode = 0
                            }
                        }
                        
                        // 结束条件
                        if (hasSpeechStarted) {
                            if (currentTime - lastActiveTime > SILENCE_DURATION_MS) {
                                stopListeningAndProcess() 
                                break
                            }
                        } else {
                            if (currentTime - startTime > NO_SPEECH_TIMEOUT_MS) {
                                stopListening(process = false) 
                                withContext(Dispatchers.Main) {
                                    onError?.invoke("未听到指令")
                                }
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                stream?.release()
            }
        }
    }

    private suspend fun stopListeningAndProcess() {
        stopListening(process = true)
    }

    suspend fun stopListening() {
        stopListening(process = false)
    }

    private suspend fun stopListening(process: Boolean) {
        if (!_isListening.value) return

        _isListening.value = false
        _soundLevel.value = 0f
        
        try {
            if (recordingJob?.isActive == true && recordingJob != coroutineContext[Job]) {
                recordingJob?.cancel()
                recordingJob?.join()
            }
            
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            
            releaseAudioEffects()
            
            if (process) {
                processAudioLocal()
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            unmuteMedia()
        }
    }
    
    private suspend fun processAudioLocal() {
        val recognizer = SherpaModelManager.recognizer ?: return
        
        val samples: FloatArray
        synchronized(localAudioBuffer) {
            if (localAudioBuffer.isEmpty()) return
            samples = localAudioBuffer.toFloatArray()
        }
        
        try {
            // 最终完整识别一次
            val stream = recognizer.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            
            val text = result.text.trim()
            Log.d(TAG, "Paraformer Result: $text")

            if (text.isNotBlank() && !isHallucination(text)) {
                // 最终结果也更新一下实时文本，确保最后显示的是完整的
                _partialResult.value = text
                withContext(Dispatchers.Main) {
                    onResult?.invoke(text)
                }
            } else {
                 withContext(Dispatchers.Main) {
                     onError?.invoke("未识别到内容")
                 }
            }
            
            stream.release()
            
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                onError?.invoke("识别失败: ${e.message}")
            }
        }
    }
    
    private fun isHallucination(text: String): Boolean {
        val lower = text.lowercase()
        return lower == "yeah." || lower == "yeah" || 
               lower == "." || lower == "。" || 
               lower == "you." || lower == "huh?" || lower.isEmpty()
    }

    suspend fun cancel() {
        _isListening.value = false
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        releaseAudioEffects()
        synchronized(localAudioBuffer) { localAudioBuffer.clear() }
        _partialResult.value = "" // 清空
        unmuteMedia()
    }

    fun destroy() {
        scope.launch { cancel() }
    }

    private fun muteMedia() {
        if (isMuted) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { }
                    .build()
                
                audioManager?.requestAudioFocus(request)
                focusRequest = request
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
            isMuted = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mute media", e)
        }
    }

    private fun unmuteMedia() {
        if (!isMuted) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let {
                    audioManager?.abandonAudioFocusRequest(it)
                }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
            
            isMuted = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unmute media", e)
        }
    }
}