package com.rifters.riftedreader.domain.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.preferences.TTSPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

class TTSService : Service() {

    private lateinit var ttsEngine: TTSEngine
    private lateinit var replacementEngine: TTSReplacementEngine
    private lateinit var preferences: TTSPreferences
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusRequestCompat: AudioFocusRequestCompat? = null
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                handleStop()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (playbackState == TTSPlaybackState.PLAYING) {
                    shouldResumeAfterFocusGain = true
                }
                hasAudioFocus = false
                pausePlayback(releaseFocus = false)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (shouldResumeAfterFocusGain) {
                    shouldResumeAfterFocusGain = false
                    handleResume()
                }
            }
        }
    }
    private lateinit var mediaSession: MediaSessionCompat
    private val playbackStateBuilder: PlaybackStateCompat.Builder by lazy {
        PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
    }
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var sentences: List<String> = emptyList()
    private var currentSentenceIndex: Int = 0
    private var lastStoppedIndex: Int = 0
    private var currentText: String = ""
    private var playbackState: TTSPlaybackState = TTSPlaybackState.IDLE
    private var hasAudioFocus: Boolean = false
    private var shouldResumeAfterFocusGain: Boolean = false
    private var currentLanguageTag: String? = null
    private var pendingLanguageTag: String? = null
    private val pendingPlayIntents: MutableList<Intent> = mutableListOf()

    override fun onCreate() {
        super.onCreate()
        preferences = TTSPreferences(this)
        replacementEngine = TTSReplacementEngine()
        preferences.loadReplacementRules()?.let { replacementEngine.loadRulesFromText(it) }

        audioManager = ContextCompat.getSystemService(this, AudioManager::class.java)
            ?: throw IllegalStateException("AudioManager unavailable")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
        } else {
            val attributesCompat = AudioAttributesCompat.Builder()
                .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                .setContentType(AudioAttributesCompat.CONTENT_TYPE_SPEECH)
                .build()
            audioFocusRequestCompat = AudioFocusRequestCompat.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributesCompat)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
        }

        mediaSession = MediaSessionCompat(this, MEDIA_SESSION_TAG).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    handleResume()
                }

                override fun onPause() {
                    pausePlayback()
                }

                override fun onStop() {
                    handleStop()
                }

                override fun onSkipToNext() {
                    handleNext()
                }

                override fun onSkipToPrevious() {
                    handlePrevious()
                }
            })
        }

        ttsEngine = TTSEngine(this)
        ttsEngine.initialize { success ->
            if (!success) {
                stopSelf()
            } else {
                setupUtteranceListener()
                val tagToApply = pendingLanguageTag ?: preferences.languageTag
                applyLanguage(tagToApply)
                pendingLanguageTag = null
                
                // Process all pending play intents that arrived before initialization
                val intentsToProcess = pendingPlayIntents.toList()
                pendingPlayIntents.clear()
                intentsToProcess.forEach { intent ->
                    handlePlay(intent)
                }
            }
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> handlePlay(intent)
            ACTION_PAUSE -> handlePause()
            ACTION_STOP -> handleStop()
            ACTION_NEXT -> handleNext()
            ACTION_PREVIOUS -> handlePrevious()
            ACTION_UPDATE_CONFIG -> handleUpdateConfig(intent)
            ACTION_RESUME -> handleResume()
            ACTION_TOGGLE_PLAY_PAUSE -> handleTogglePlayPause()
            ACTION_RELOAD_REPLACEMENTS -> handleReloadReplacements()
        }
        return START_STICKY
    }

    private fun handlePlay(intent: Intent) {
        // If TTS is not ready yet, queue this intent for later
        if (!ttsEngine.isReady()) {
            pendingPlayIntents.add(intent)
            return
        }
        
        val text = intent.getStringExtra(EXTRA_TEXT)
        val speed = intent.getFloatExtra(EXTRA_SPEED, preferences.speed)
        val pitch = intent.getFloatExtra(EXTRA_PITCH, preferences.pitch)
        val autoScroll = intent.getBooleanExtra(EXTRA_AUTO_SCROLL, preferences.autoScroll)
        val highlight = intent.getBooleanExtra(EXTRA_HIGHLIGHT, preferences.highlightSentence)
        val languageExtra = intent.getStringExtra(EXTRA_LANGUAGE)
        val requestedLanguageTag = languageExtra?.takeIf { it.isNotBlank() }

        if (!text.isNullOrBlank()) {
            // Only reset position if the text has changed
            if (text != currentText) {
                currentText = text
                sentences = splitIntoSentences(text)
                currentSentenceIndex = 0
                lastStoppedIndex = 0
            } else {
                // Same text, resume from last stopped position
                currentSentenceIndex = lastStoppedIndex
            }
        }

        if (sentences.isEmpty()) {
            currentSentenceIndex = 0
            lastStoppedIndex = 0
            broadcastStatus(TTSPlaybackState.STOPPED)
            stopSelf()
            return
        }

        preferences.speed = speed
        preferences.pitch = pitch
        preferences.autoScroll = autoScroll
        preferences.highlightSentence = highlight
        ttsEngine.setSpeed(speed)
        ttsEngine.setPitch(pitch)
        val languageTagToApply = requestedLanguageTag ?: preferences.languageTag
        applyLanguage(languageTagToApply)

        if (!ensureAudioFocus()) {
            handleStop()
            return
        }

        mediaSession.isActive = true
        playbackState = TTSPlaybackState.PLAYING
        updatePlaybackState(playbackState)
        startForeground(NOTIFICATION_ID, buildNotification())
        speakCurrentSentence()
    }

    private fun handlePause() {
        pausePlayback()
    }

    private fun handleStop() {
        ttsEngine.stop()
        // Save the current position so we can resume from here later
        lastStoppedIndex = currentSentenceIndex.coerceIn(0, sentences.lastIndex.coerceAtLeast(0))
        abandonAudioFocus()
        broadcastStatus(TTSPlaybackState.STOPPED)
        mediaSession.isActive = false
        stopForegroundCompat()
        stopSelf()
    }

    private fun handleNext() {
        if (currentSentenceIndex < sentences.lastIndex) {
            currentSentenceIndex++
            if (ensureAudioFocus()) {
                speakCurrentSentence()
            } else {
                handleStop()
            }
        } else {
            handleStop()
        }
    }

    private fun handlePrevious() {
        if (currentSentenceIndex > 0) {
            currentSentenceIndex--
            if (ensureAudioFocus()) {
                speakCurrentSentence()
            } else {
                handleStop()
            }
        } else {
            if (ensureAudioFocus()) {
                speakCurrentSentence()
            }
        }
    }

    private fun handleUpdateConfig(intent: Intent) {
        val speed = intent.getFloatExtra(EXTRA_SPEED, preferences.speed)
        val pitch = intent.getFloatExtra(EXTRA_PITCH, preferences.pitch)
        val autoScroll = intent.getBooleanExtra(EXTRA_AUTO_SCROLL, preferences.autoScroll)
        val highlight = intent.getBooleanExtra(EXTRA_HIGHLIGHT, preferences.highlightSentence)
        val languageExtra = intent.getStringExtra(EXTRA_LANGUAGE)
        val requestedLanguageTag = languageExtra?.takeIf { it.isNotBlank() }
        preferences.speed = speed
        preferences.pitch = pitch
        preferences.autoScroll = autoScroll
        preferences.highlightSentence = highlight
        ttsEngine.setSpeed(speed)
        ttsEngine.setPitch(pitch)
        if (languageExtra != null) {
            val languageToApply = requestedLanguageTag ?: preferences.languageTag
            applyLanguage(languageToApply)
        }
        updateNotification()
        broadcastStatus(playbackState)
    }

    private fun handleResume() {
        if (sentences.isEmpty()) return
        if (currentSentenceIndex !in sentences.indices) {
            currentSentenceIndex = sentences.lastIndex.coerceAtLeast(0)
        }
        if (!ensureAudioFocus()) {
            return
        }
        speakCurrentSentence()
    }

    private fun handleTogglePlayPause() {
        when (playbackState) {
            TTSPlaybackState.PLAYING -> pausePlayback()
            TTSPlaybackState.PAUSED -> handleResume()
            else -> Unit
        }
    }

    private fun handleReloadReplacements() {
        val rules = preferences.loadReplacementRules()
        replacementEngine.clearRules()
        if (!rules.isNullOrBlank()) {
            replacementEngine.loadRulesFromText(rules)
        }
    }

    private fun applyLanguage(languageTag: String?) {
        val normalizedTag = languageTag?.takeIf { it.isNotBlank() }
        if (!ttsEngine.isReady()) {
            pendingLanguageTag = normalizedTag
            return
        }

        if (normalizedTag == currentLanguageTag) {
            return
        }

        val locale = normalizedTag
            ?.let(Locale::forLanguageTag)
            ?.takeUnless { it == Locale.ROOT }
            ?: Locale.getDefault()
        val result = ttsEngine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (normalizedTag != null) {
                Log.w(TAG, "Language $normalizedTag not supported; reverting to default locale")
                preferences.languageTag = null
                currentLanguageTag = null
                pendingLanguageTag = null
                ttsEngine.setLanguage(Locale.getDefault())
            }
        } else {
            currentLanguageTag = normalizedTag
            pendingLanguageTag = null
            if (normalizedTag == null) {
                preferences.languageTag = null
            } else {
                preferences.languageTag = normalizedTag
            }
        }
    }

    private fun speakCurrentSentence() {
        if (currentSentenceIndex !in sentences.indices) {
            handleStop()
            return
        }

        val sentence = sentences[currentSentenceIndex]
        val result = replacementEngine.applyReplacements(sentence)
        when (result.command) {
            TTSCommand.SKIP -> {
                currentSentenceIndex++
                serviceScope.launch {
                    delay(50)
                    speakCurrentSentence()
                }
                return
            }
            TTSCommand.STOP -> {
                handleStop()
                return
            }
            TTSCommand.NEXT -> {
                // Move to the end so reader can advance externally.
                currentSentenceIndex = sentences.size
                handleStop()
                return
            }
            TTSCommand.PAUSE -> {
                pausePlayback()
                return
            }
            null -> {
                if (!ensureAudioFocus()) {
                    handleStop()
                    return
                }
                ttsEngine.speak(result.text.ifBlank { sentence }, utteranceId())
                broadcastStatus(TTSPlaybackState.PLAYING)
            }
        }
        updateNotification()
    }

    private fun setupUtteranceListener() {
        ttsEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // No-op
            }

            override fun onDone(utteranceId: String?) {
                handleUtteranceCompletion()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handleUtteranceCompletion()
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                handleUtteranceCompletion()
            }
        })
    }

    private fun handleUtteranceCompletion() {
        currentSentenceIndex++
        serviceScope.launch {
            delay(200)
            speakCurrentSentence()
        }
    }

    private fun splitIntoSentences(text: String): List<String> {
        return text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun buildNotification(): Notification {
        return buildNotification(null)
    }

    private fun updateNotification(stateLabel: String? = null) {
        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(stateLabel))
    }

    private fun buildNotification(stateLabel: String?): Notification {
        val contentText = stateLabel ?: getString(
            R.string.tts_notification_content,
            (currentSentenceIndex + 1).coerceAtMost(sentences.size),
            sentences.size
        )
        val isPlaying = playbackState == TTSPlaybackState.PLAYING
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                R.drawable.ic_tts_pause_24,
                getString(R.string.tts_action_pause),
                servicePendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_tts_play_24,
                getString(R.string.tts_action_play),
                servicePendingIntent(ACTION_RESUME)
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tts_notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_reader_tts_24)
            .setOngoing(isPlaying)
            .addAction(
                R.drawable.ic_tts_prev_24,
                getString(R.string.tts_action_previous),
                servicePendingIntent(ACTION_PREVIOUS)
            )
            .addAction(playPauseAction)
            .addAction(
                R.drawable.ic_tts_next_24,
                getString(R.string.tts_action_next),
                servicePendingIntent(ACTION_NEXT)
            )
            .addAction(
                R.drawable.ic_tts_stop_24,
                getString(R.string.tts_action_stop),
                servicePendingIntent(ACTION_STOP)
            )
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun broadcastStatus(state: TTSPlaybackState) {
        playbackState = state
        updatePlaybackState(state)
        TTSStatusNotifier.update(
            TTSStatusSnapshot(
                state = state,
                sentenceIndex = currentSentenceIndex,
                sentenceTotal = sentences.size
            )
        )
    }

    private fun updatePlaybackState(state: TTSPlaybackState) {
        val playbackStateCompat = when (state) {
            TTSPlaybackState.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            TTSPlaybackState.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            TTSPlaybackState.STOPPED -> PlaybackStateCompat.STATE_STOPPED
            TTSPlaybackState.IDLE -> PlaybackStateCompat.STATE_NONE
        }
        mediaSession.setPlaybackState(
            playbackStateBuilder
                .setState(playbackStateCompat, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private fun ensureAudioFocus(): Boolean {
        if (hasAudioFocus) {
            return true
        }
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: return false
            audioManager.requestAudioFocus(request)
        } else {
            val request = audioFocusRequestCompat ?: return false
            AudioManagerCompat.requestAudioFocus(audioManager, request)
        }
        hasAudioFocus = granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            audioFocusRequestCompat?.let { AudioManagerCompat.abandonAudioFocusRequest(audioManager, it) }
        }
        hasAudioFocus = false
    }

    private fun pausePlayback(releaseFocus: Boolean = true) {
        if (playbackState == TTSPlaybackState.PAUSED && !releaseFocus) {
            return
        }
        ttsEngine.stop()
        // Save the current position so we can resume from here later
        lastStoppedIndex = currentSentenceIndex.coerceIn(0, sentences.lastIndex.coerceAtLeast(0))
        if (releaseFocus) {
            abandonAudioFocus()
        }
        broadcastStatus(TTSPlaybackState.PAUSED)
        updateNotification(getString(R.string.tts_notification_paused))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tts_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.tts_notification_channel_description)
            val manager = ContextCompat.getSystemService(this, NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, TTSService::class.java).apply { this.action = action }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, action.hashCode(), intent, flags)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsEngine.shutdown()
        abandonAudioFocus()
        broadcastStatus(TTSPlaybackState.STOPPED)
        mediaSession.isActive = false
        mediaSession.setCallback(null)
        mediaSession.release()
        serviceScope.cancel()
        pendingLanguageTag = null
        currentLanguageTag = null
        pendingPlayIntents.clear()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "tts_service_channel"
        private const val NOTIFICATION_ID = 1001
        private const val MEDIA_SESSION_TAG = "RiftedReaderTTS"
        private const val TAG = "TTSService"

        private const val ACTION_PLAY = "com.rifters.riftedreader.tts.ACTION_PLAY"
        private const val ACTION_PAUSE = "com.rifters.riftedreader.tts.ACTION_PAUSE"
        private const val ACTION_STOP = "com.rifters.riftedreader.tts.ACTION_STOP"
        private const val ACTION_NEXT = "com.rifters.riftedreader.tts.ACTION_NEXT"
        private const val ACTION_PREVIOUS = "com.rifters.riftedreader.tts.ACTION_PREVIOUS"
        private const val ACTION_UPDATE_CONFIG = "com.rifters.riftedreader.tts.ACTION_UPDATE_CONFIG"
        private const val ACTION_RESUME = "com.rifters.riftedreader.tts.ACTION_RESUME"
        private const val ACTION_TOGGLE_PLAY_PAUSE = "com.rifters.riftedreader.tts.ACTION_TOGGLE_PLAY_PAUSE"
        private const val ACTION_RELOAD_REPLACEMENTS = "com.rifters.riftedreader.tts.ACTION_RELOAD_REPLACEMENTS"

        private const val EXTRA_TEXT = "extra_text"
        private const val EXTRA_SPEED = "extra_speed"
        private const val EXTRA_PITCH = "extra_pitch"
        private const val EXTRA_AUTO_SCROLL = "extra_auto_scroll"
        private const val EXTRA_HIGHLIGHT = "extra_highlight"
        private const val EXTRA_LANGUAGE = "extra_language"

        fun start(context: Context, text: String, configuration: TTSConfiguration) {
            val intent = Intent(context, TTSService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_SPEED, configuration.speed)
                putExtra(EXTRA_PITCH, configuration.pitch)
                putExtra(EXTRA_AUTO_SCROLL, configuration.autoScroll)
                putExtra(EXTRA_HIGHLIGHT, configuration.highlightSentence)
                putExtra(EXTRA_LANGUAGE, configuration.languageTag ?: "")
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) {
            val intent = Intent(context, TTSService::class.java).apply { action = ACTION_PAUSE }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TTSService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        fun resume(context: Context) {
            val intent = Intent(context, TTSService::class.java).apply { action = ACTION_RESUME }
            context.startService(intent)
        }

        fun togglePlayPause(context: Context) {
            val intent = Intent(context, TTSService::class.java).apply { action = ACTION_TOGGLE_PLAY_PAUSE }
            context.startService(intent)
        }

        fun updateConfiguration(context: Context, configuration: TTSConfiguration) {
            val intent = Intent(context, TTSService::class.java).apply {
                action = ACTION_UPDATE_CONFIG
                putExtra(EXTRA_SPEED, configuration.speed)
                putExtra(EXTRA_PITCH, configuration.pitch)
                putExtra(EXTRA_AUTO_SCROLL, configuration.autoScroll)
                putExtra(EXTRA_HIGHLIGHT, configuration.highlightSentence)
                putExtra(EXTRA_LANGUAGE, configuration.languageTag ?: "")
            }
            context.startService(intent)
        }

        fun reloadReplacements(context: Context) {
            val intent = Intent(context, TTSService::class.java).apply { action = ACTION_RELOAD_REPLACEMENTS }
            context.startService(intent)
        }

        private fun utteranceId(): String = UUID.randomUUID().toString()
    }
}
