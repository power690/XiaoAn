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
import android.os.PowerManager
import android.util.Log
import ai.xiaozhi.AutoGLMService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

object WakeWordDetector {
    private const val TAG = "WakeWordDetector"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    
    // 增益系数：SenseVoice 比较灵敏，5.0 足够
    private const val WAKE_WORD_GAIN_FACTOR = 5.0f
    private const val COMMAND_GAIN_FACTOR = 4.0f
    
    private var audioRecord: AudioRecord? = null
    private val isRunning = AtomicBoolean(false)
    private var detectionJob: Job? = null
    private var commandJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var _wakeWord = MutableStateFlow("小安小安")
    val wakeWord: StateFlow<String> = _wakeWord
    
    private val _realtimeCommand = MutableSharedFlow<String>()
    val realtimeCommand = _realtimeCommand.asSharedFlow()
    
    var onWakeWordDetected: (() -> Unit)? = null
    var onCommandReceived: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    private var autoGLMService: AutoGLMService? = null
    
    // CPU 唤醒锁，防止长时间运行后系统休眠导致识别迟钝
    private var wakeLock: PowerManager.WakeLock? = null
    
    fun bindService(service: AutoGLMService) {
        this.autoGLMService = service
    }
    
    private var isWakeWordMode = false
    private var isCommandMode = false
    
    // VAD Parameters (静音检测参数)
    private const val SILENCE_THRESHOLD_DB = -40.0f 
    private const val SILENCE_DURATION_MS = 1500L 
    private const val NO_SPEECH_TIMEOUT_MS = 5000L 
    
    // SenseVoice 解码帧数设置
    private const val FRAMES_PER_DECODE_WAKE = 3200 
    
    // 智能流重置阈值
    private const val SOFT_RESET_THRESHOLD_FRAMES = 16000 * 5 
    private const val HARD_RESET_THRESHOLD_FRAMES = 16000 * 15

    private var audioManager: AudioManager? = null
    private var savedVolume: Int = -1
    private var isMuted = false
    private var focusRequest: AudioFocusRequest? = null
    
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    
    fun initialize(context: Context) {
        Log.d(TAG, "Initializing WakeWordDetector")
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoGLM:WakeWordDetector")
        
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val savedWakeWord = prefs.getString("wake_word", "小安小安") ?: "小安小安"
        _wakeWord.value = savedWakeWord
        
        if (SherpaModelManager.modelState.value is SherpaModelManager.ModelState.NotInitialized) {
            CoroutineScope(Dispatchers.IO).launch {
                SherpaModelManager.initModel(context)
            }
        }
    }
    
    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock", e)
        }
    }

    // =========================================================
    // 1. 唤醒模式
    // =========================================================
    @SuppressLint("MissingPermission")
    fun startWakeWordMode(context: Context) {
        // 【关键修复】终极防御：如果开关是关的，绝对不允许启动！
        // 这解决了无论哪里调用 startWakeWordMode，只要开关没开，就无法启动录音的问题
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("wake_up_enabled", false)
        
        if (!isEnabled) {
            Log.i(TAG, "Wake up is disabled in settings. Aborting start.")
            stopListening() // 确保释放资源
            return
        }

        if (isRunning.get()) {
            stopListening()
        }
        
        val recognizer = SherpaModelManager.recognizer
        if (recognizer == null) {
            if (SherpaModelManager.modelState.value is SherpaModelManager.ModelState.Error) {
                onError?.invoke("模型加载失败，请重启APP")
            } else {
                CoroutineScope(Dispatchers.IO).launch {
                    SherpaModelManager.initModel(context)
                    delay(500)
                    startWakeWordMode(context) // 递归重试也会再次检查开关，安全
                }
            }
            return
        }
        
        isRunning.set(true)
        isWakeWordMode = true
        isCommandMode = false
        
        acquireWakeLock()
        
        Log.d(TAG, "Starting SenseVoice Wake Word Mode")
        
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
                onError?.invoke("麦克风初始化失败")
                releaseWakeLock()
                return
            }
            
            initAudioEffects(audioRecord!!.audioSessionId)
            audioRecord?.startRecording()
        } catch (e: Exception) {
            onError?.invoke("无法启动录音: ${e.message}")
            releaseWakeLock()
            return
        }
        
        detectionJob = scope.launch {
            val buffer = ShortArray(1024)
            var stream = recognizer.createStream()
            var framesSinceLastDecode = 0
            var framesInCurrentStream = 0
            
            try {
                while (isRunning.get() && isWakeWordMode) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        var sum = 0.0
                        val floatSamples = FloatArray(read) { i -> 
                            val sampleRaw = buffer[i]
                            val sampleNorm = sampleRaw / 32768.0f
                            sum += sampleNorm * sampleNorm
                            
                            var sample = sampleNorm * WAKE_WORD_GAIN_FACTOR
                            if (sample > 1.0f) sample = 1.0f
                            if (sample < -1.0f) sample = -1.0f
                            sample
                        }
                        
                        val rms = sqrt(sum / read)
                        val db = if (rms > 0) 20 * log10(rms) else -50.0

                        stream.acceptWaveform(floatSamples, SAMPLE_RATE)
                        framesSinceLastDecode += read
                        framesInCurrentStream += read
                        
                        if (framesSinceLastDecode >= FRAMES_PER_DECODE_WAKE) {
                            recognizer.decode(stream)
                            val result = recognizer.getResult(stream)
                            val text = result.text.trim()
                            
                            if (text.isNotEmpty()) {
                                if (text.contains(_wakeWord.value, ignoreCase = true)) {
                                    Log.d(TAG, "Wake word detected!")
                                    stream.release()
                                    handleWakeUpLogic(context)
                                    stopListening() 
                                    break
                                }
                            }
                            
                            // 智能流重置逻辑
                            val isSilence = db < SILENCE_THRESHOLD_DB
                            val shouldSoftReset = (framesInCurrentStream > SOFT_RESET_THRESHOLD_FRAMES) && isSilence
                            val shouldHardReset = framesInCurrentStream > HARD_RESET_THRESHOLD_FRAMES
                            
                            if (shouldSoftReset || shouldHardReset) {
                                stream.release()
                                stream = recognizer.createStream()
                                framesInCurrentStream = 0
                            }
                            
                            framesSinceLastDecode = 0
                        }
                    } else if (read < 0) {
                        break
                    }
                    delay(20) 
                }
            } catch (e: Exception) {
                Log.e(TAG, "Wake word loop error", e)
            } finally {
                try { stream.release() } catch (e: Exception) {}
            }
        }
    }
    
    private suspend fun handleWakeUpLogic(context: Context) {
        withContext(Dispatchers.Main) {
            onWakeWordDetected?.invoke()
            
            val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val screenOffWakeEnabled = prefs.getBoolean("screen_off_wake_enabled", false)
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val isScreenOn = powerManager.isInteractive
            
            if (!isScreenOn && screenOffWakeEnabled && autoGLMService != null) {
                autoGLMService?.wakeUpAndUnlock {
                    autoGLMService?.showWakeUpInput()
                    startCommandMode(context)
                }
            } else {
                if (autoGLMService != null) {
                    autoGLMService?.handleWakeUpAction()
                } else {
                    startCommandMode(context)
                }
            }
        }
    }
    
    // =========================================================
    // 2. 指令模式
    // =========================================================
    @SuppressLint("MissingPermission")
    fun startCommandMode(context: Context) {
        if (isRunning.get()) {
            stopListening()
        }

        val recognizer = SherpaModelManager.recognizer
        if (recognizer == null) {
            Log.e(TAG, "Recognizer not initialized for command mode")
            return
        }
        
        muteMedia()
        acquireWakeLock()
        
        isRunning.set(true)
        isWakeWordMode = false
        isCommandMode = true
        
        Log.i(TAG, "Starting SenseVoice Command Mode.")
        
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
                unmuteMedia()
                releaseWakeLock()
                return
            }
            
            initAudioEffects(audioRecord!!.audioSessionId)
            audioRecord?.startRecording()
        } catch (e: Exception) {
            unmuteMedia()
            releaseWakeLock()
            return
        }
        
        commandJob = scope.launch {
            val buffer = ShortArray(1024)
            val localAudioBuffer = ArrayList<Float>()

            var hasSpeechStarted = false
            var lastActiveTime = System.currentTimeMillis()
            val startTime = System.currentTimeMillis()
            
            var currentStream = recognizer.createStream()
            var partialFrames = 0
            
            try {
                while (isRunning.get() && isCommandMode) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        var sum = 0.0
                        
                        for (i in 0 until read) {
                            val rawSample = buffer[i]
                            var amplifiedSample = (rawSample * COMMAND_GAIN_FACTOR).toInt()
                            if (amplifiedSample > Short.MAX_VALUE) amplifiedSample = Short.MAX_VALUE.toInt()
                            if (amplifiedSample < Short.MIN_VALUE) amplifiedSample = Short.MIN_VALUE.toInt()
                            
                            val floatSample = amplifiedSample / 32768.0f
                            localAudioBuffer.add(floatSample)
                            sum += floatSample * floatSample
                        }
                        
                        val rms = sqrt(sum / read)
                        val db = if (rms > 0) 20 * log10(rms) else -50.0
                        
                        val currentTime = System.currentTimeMillis()
                        
                        if (db > SILENCE_THRESHOLD_DB) {
                            if (!hasSpeechStarted) {
                                hasSpeechStarted = true
                            }
                            lastActiveTime = currentTime
                        }
                        
                        if (hasSpeechStarted) {
                            val floatChunk = FloatArray(read) { i ->
                                var s = (buffer[i] * COMMAND_GAIN_FACTOR).toInt()
                                if (s > 32767) s = 32767
                                if (s < -32768) s = -32768
                                s / 32768.0f
                            }
                            
                            currentStream.acceptWaveform(floatChunk, SAMPLE_RATE)
                            partialFrames += read
                            
                            if (partialFrames > 8000) {
                                recognizer.decode(currentStream)
                                val partialResult = recognizer.getResult(currentStream).text.trim()
                                if (partialResult.isNotBlank()) {
                                    _realtimeCommand.emit(partialResult)
                                }
                                partialFrames = 0
                            }
                        }
                        
                        if (hasSpeechStarted) {
                            if (currentTime - lastActiveTime > SILENCE_DURATION_MS) {
                                break 
                            }
                        } else {
                            if (currentTime - startTime > NO_SPEECH_TIMEOUT_MS) {
                                withContext(Dispatchers.Main) {
                                    onError?.invoke("超时未检测到指令")
                                }
                                stopListening()
                                return@launch
                            }
                        }
                    } else if (read < 0) {
                        break
                    }
                    delay(20)
                }
                
                if (hasSpeechStarted) {
                    currentStream.release()
                    if (localAudioBuffer.isNotEmpty()) {
                        val finalStream = recognizer.createStream()
                        val samplesArray = localAudioBuffer.toFloatArray()
                        finalStream.acceptWaveform(samplesArray, SAMPLE_RATE)
                        recognizer.decode(finalStream)
                        val result = recognizer.getResult(finalStream)
                        finalStream.release()
                        
                        val finalText = result.text.trim()
                        Log.d(TAG, "SenseVoice Final Result: $finalText")
                        
                        if (finalText.isBlank() || isHallucination(finalText)) {
                             withContext(Dispatchers.Main) {
                                 onError?.invoke("未听清，请重试") 
                             }
                        } else {
                            withContext(Dispatchers.Main) {
                                onCommandReceived?.invoke(finalText)
                            }
                        }
                    }
                } else {
                    stopListening()
                }
                
                stopListening()
                
            } catch (e: Exception) {
                Log.e(TAG, "Command loop error", e)
                withContext(Dispatchers.Main) { onError?.invoke("识别错误") }
            } finally {
               try { currentStream.release() } catch (e: Exception) {}
            }
        }
    }

    private fun initAudioEffects(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)
                echoCanceler?.enabled = true
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)
                noiseSuppressor?.enabled = true
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
    
    private fun isHallucination(text: String): Boolean {
        val lower = text.lowercase()
        return lower.isBlank() || lower == "." || lower == "。"
    }
    
    fun stopListening() {
        Log.d(TAG, "Stopping listening")
        
        isRunning.set(false)
        isWakeWordMode = false
        isCommandMode = false
        
        detectionJob?.cancel()
        commandJob?.cancel()
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) { }
        audioRecord = null
        
        releaseAudioEffects()
        unmuteMedia()
        releaseWakeLock()
    }
    
    fun updateWakeWord(newWakeWord: String) {
        _wakeWord.value = newWakeWord
    }
    
    fun destroy() {
        stopListening()
        scope.cancel()
        onWakeWordDetected = null
        onCommandReceived = null
        onError = null
        autoGLMService = null
        releaseWakeLock()
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

            val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
            if (currentVol > 0) {
                savedVolume = currentVol
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            }
            
            isMuted = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mute media", e)
        }
    }

    private fun unmuteMedia() {
        if (!isMuted) return
        try {
            if (savedVolume != -1) {
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
                savedVolume = -1
            }

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