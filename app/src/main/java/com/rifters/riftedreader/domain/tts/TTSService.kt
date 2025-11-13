package com.rifters.riftedreader.domain.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.preferences.TTSPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class TTSService : Service() {

    private lateinit var ttsEngine: TTSEngine
    private lateinit var replacementEngine: TTSReplacementEngine
    private lateinit var preferences: TTSPreferences
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var sentences: List<String> = emptyList()
    private var currentSentenceIndex: Int = 0
    private var playbackState: TTSPlaybackState = TTSPlaybackState.IDLE

    override fun onCreate() {
        super.onCreate()
        preferences = TTSPreferences(this)
        replacementEngine = TTSReplacementEngine()
        preferences.loadReplacementRules()?.let { replacementEngine.loadRulesFromText(it) }

        ttsEngine = TTSEngine(this)
        ttsEngine.initialize { success ->
            if (!success) {
                stopSelf()
            } else {
                setupUtteranceListener()
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
        }
        return START_STICKY
    }

    private fun handlePlay(intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT)
        val speed = intent.getFloatExtra(EXTRA_SPEED, preferences.speed)
        val pitch = intent.getFloatExtra(EXTRA_PITCH, preferences.pitch)
        val autoScroll = intent.getBooleanExtra(EXTRA_AUTO_SCROLL, preferences.autoScroll)
        val highlight = intent.getBooleanExtra(EXTRA_HIGHLIGHT, preferences.highlightSentence)

        if (!text.isNullOrBlank()) {
            sentences = splitIntoSentences(text)
            currentSentenceIndex = 0
        }

        if (sentences.isEmpty()) {
            currentSentenceIndex = -1
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

        startForeground(NOTIFICATION_ID, buildNotification())
        speakCurrentSentence()
    }

    private fun handlePause() {
        ttsEngine.stop()
        updateNotification(stateLabel = getString(R.string.tts_notification_paused))
        broadcastStatus(TTSPlaybackState.PAUSED)
    }

    private fun handleStop() {
        ttsEngine.stop()
        currentSentenceIndex = -1
        broadcastStatus(TTSPlaybackState.STOPPED)
        stopForeground(true)
        stopSelf()
    }

    private fun handleNext() {
        if (currentSentenceIndex < sentences.lastIndex) {
            currentSentenceIndex++
            speakCurrentSentence()
        } else {
            handleStop()
        }
    }

    private fun handlePrevious() {
        if (currentSentenceIndex > 0) {
            currentSentenceIndex--
            speakCurrentSentence()
        } else {
            speakCurrentSentence()
        }
    }

    private fun handleUpdateConfig(intent: Intent) {
        val speed = intent.getFloatExtra(EXTRA_SPEED, preferences.speed)
        val pitch = intent.getFloatExtra(EXTRA_PITCH, preferences.pitch)
        val autoScroll = intent.getBooleanExtra(EXTRA_AUTO_SCROLL, preferences.autoScroll)
        val highlight = intent.getBooleanExtra(EXTRA_HIGHLIGHT, preferences.highlightSentence)
        preferences.speed = speed
        preferences.pitch = pitch
        preferences.autoScroll = autoScroll
        preferences.highlightSentence = highlight
        ttsEngine.setSpeed(speed)
        ttsEngine.setPitch(pitch)
        updateNotification()
        broadcastStatus(playbackState)
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
            }
            TTSCommand.STOP -> handleStop()
            TTSCommand.NEXT -> {
                // Move to the end so reader can advance externally.
                currentSentenceIndex = sentences.size
                handleStop()
            }
            TTSCommand.PAUSE -> {
                ttsEngine.stop()
                broadcastStatus(TTSPlaybackState.PAUSED)
            }
            null -> {
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
                currentSentenceIndex++
                serviceScope.launch {
                    delay(200)
                    speakCurrentSentence()
                }
            }

            override fun onError(utteranceId: String?) {
                currentSentenceIndex++
                serviceScope.launch {
                    delay(200)
                    speakCurrentSentence()
                }
            }
        })
    }

    private fun splitIntoSentences(text: String): List<String> {
        return text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun buildNotification(): Notification {
        val contentText = getString(
            R.string.tts_notification_content,
            (currentSentenceIndex + 1).coerceAtMost(sentences.size),
            sentences.size
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tts_notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_reader_tts_24)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_tts_prev_24,
                getString(R.string.tts_action_previous),
                servicePendingIntent(ACTION_PREVIOUS)
            )
            .addAction(
                R.drawable.ic_tts_pause_24,
                getString(R.string.tts_action_pause),
                servicePendingIntent(ACTION_PAUSE)
            )
            .addAction(
                R.drawable.ic_tts_stop_24,
                getString(R.string.tts_action_stop),
                servicePendingIntent(ACTION_STOP)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(stateLabel: String? = null) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tts_notification_title))
            .setContentText(
                stateLabel ?: getString(
                    R.string.tts_notification_content,
                    (currentSentenceIndex + 1).coerceAtMost(sentences.size),
                    sentences.size
                )
            )
            .setSmallIcon(R.drawable.ic_reader_tts_24)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_tts_prev_24,
                getString(R.string.tts_action_previous),
                servicePendingIntent(ACTION_PREVIOUS)
            )
            .addAction(
                R.drawable.ic_tts_pause_24,
                getString(R.string.tts_action_pause),
                servicePendingIntent(ACTION_PAUSE)
            )
            .addAction(
                R.drawable.ic_tts_stop_24,
                getString(R.string.tts_action_stop),
                servicePendingIntent(ACTION_STOP)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun broadcastStatus(state: TTSPlaybackState) {
        playbackState = state
        val intent = Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_STATE, state.name)
            putExtra(EXTRA_SENTENCE_INDEX, currentSentenceIndex)
            putExtra(EXTRA_SENTENCE_TOTAL, sentences.size)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
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

    override fun onDestroy() {
        super.onDestroy()
        ttsEngine.shutdown()
        serviceScope.cancel()
        broadcastStatus(TTSPlaybackState.STOPPED)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "tts_service_channel"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_PLAY = "com.rifters.riftedreader.tts.ACTION_PLAY"
        private const val ACTION_PAUSE = "com.rifters.riftedreader.tts.ACTION_PAUSE"
        private const val ACTION_STOP = "com.rifters.riftedreader.tts.ACTION_STOP"
        private const val ACTION_NEXT = "com.rifters.riftedreader.tts.ACTION_NEXT"
        private const val ACTION_PREVIOUS = "com.rifters.riftedreader.tts.ACTION_PREVIOUS"
        private const val ACTION_UPDATE_CONFIG = "com.rifters.riftedreader.tts.ACTION_UPDATE_CONFIG"
        const val ACTION_STATUS = "com.rifters.riftedreader.tts.ACTION_STATUS"

        private const val EXTRA_TEXT = "extra_text"
        private const val EXTRA_SPEED = "extra_speed"
        private const val EXTRA_PITCH = "extra_pitch"
        private const val EXTRA_AUTO_SCROLL = "extra_auto_scroll"
        private const val EXTRA_HIGHLIGHT = "extra_highlight"
        const val EXTRA_STATE = "extra_state"
        const val EXTRA_SENTENCE_INDEX = "extra_sentence_index"
        const val EXTRA_SENTENCE_TOTAL = "extra_sentence_total"

        fun start(context: Context, text: String, configuration: TTSConfiguration) {
            val intent = Intent(context, TTSService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_SPEED, configuration.speed)
                putExtra(EXTRA_PITCH, configuration.pitch)
                putExtra(EXTRA_AUTO_SCROLL, configuration.autoScroll)
                putExtra(EXTRA_HIGHLIGHT, configuration.highlightSentence)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) {
            val intent = Intent(context, TTSService::class.java).apply { action = ACTION_PAUSE }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TTSService::class.java).apply { action = ACTION_STOP }
            ContextCompat.startForegroundService(context, intent)
        }

        fun updateConfiguration(context: Context, configuration: TTSConfiguration) {
            val intent = Intent(context, TTSService::class.java).apply {
                action = ACTION_UPDATE_CONFIG
                putExtra(EXTRA_SPEED, configuration.speed)
                putExtra(EXTRA_PITCH, configuration.pitch)
                putExtra(EXTRA_AUTO_SCROLL, configuration.autoScroll)
                putExtra(EXTRA_HIGHLIGHT, configuration.highlightSentence)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        private fun utteranceId(): String = UUID.randomUUID().toString()
    }
}
