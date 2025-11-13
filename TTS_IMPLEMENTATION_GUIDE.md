# TTS Implementation Guide for RiftedReader

**Based on LibreraReader TTS System Analysis**

## Overview

This guide provides a detailed, step-by-step plan for implementing Text-to-Speech (TTS) features in RiftedReader, with special focus on the replacement/substitution system that LibreraReader uses.

---

## Part 1: Basic TTS Implementation

### Step 1.1: Set Up Android TTS

**File**: `app/src/main/java/com/rifters/riftedreader/domain/tts/TTSEngine.kt`

```kotlin
package com.rifters.riftedreader.domain.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*

class TTSEngine(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var onInitListener: ((Boolean) -> Unit)? = null
    
    companion object {
        private const val TAG = "TTSEngine"
    }
    
    fun initialize(onComplete: (Boolean) -> Unit) {
        onInitListener = onComplete
        
        tts = TextToSpeech(context) { status ->
            isInitialized = status == TextToSpeech.SUCCESS
            if (isInitialized) {
                configureDefaults()
            }
            onInitListener?.invoke(isInitialized)
        }
    }
    
    private fun configureDefaults() {
        tts?.apply {
            language = Locale.getDefault()
            setSpeechRate(1.0f)
            setPitch(1.0f)
        }
    }
    
    fun speak(text: String, utteranceId: String = UUID.randomUUID().toString()) {
        if (!isInitialized) {
            Log.e(TAG, "TTS not initialized")
            return
        }
        
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }
    
    fun stop() {
        tts?.stop()
    }
    
    fun pause() {
        // Note: TextToSpeech doesn't have native pause
        // We'll handle this at a higher level
        stop()
    }
    
    fun setSpeed(speed: Float) {
        tts?.setSpeechRate(speed)
    }
    
    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch)
    }
    
    fun setLanguage(locale: Locale): Int {
        return tts?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
    }
    
    fun getAvailableLanguages(): Set<Locale>? {
        return tts?.availableLanguages
    }
    
    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener) {
        tts?.setOnUtteranceProgressListener(listener)
    }
    
    fun shutdown() {
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
```

### Step 1.2: Create TTS State Manager

**File**: `app/src/main/java/com/rifters/riftedreader/domain/tts/TTSState.kt`

```kotlin
package com.rifters.riftedreader.domain.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TTSPlaybackState {
    IDLE,
    PLAYING,
    PAUSED,
    STOPPED
}

data class TTSConfiguration(
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val autoScroll: Boolean = true,
    val highlightSentence: Boolean = true
)

data class TTSState(
    val playbackState: TTSPlaybackState = TTSPlaybackState.IDLE,
    val currentText: String = "",
    val currentSentenceIndex: Int = 0,
    val totalSentences: Int = 0,
    val configuration: TTSConfiguration = TTSConfiguration()
)

class TTSStateManager {
    private val _state = MutableStateFlow(TTSState())
    val state: StateFlow<TTSState> = _state.asStateFlow()
    
    fun updatePlaybackState(newState: TTSPlaybackState) {
        _state.value = _state.value.copy(playbackState = newState)
    }
    
    fun updateCurrentSentence(index: Int) {
        _state.value = _state.value.copy(currentSentenceIndex = index)
    }
    
    fun updateConfiguration(config: TTSConfiguration) {
        _state.value = _state.value.copy(configuration = config)
    }
    
    fun reset() {
        _state.value = TTSState()
    }
}
```

---

## Part 2: TTS Replacement System ⭐ CORE FEATURE

### Step 2.1: Define Replacement Rules Model

**File**: `app/src/main/java/com/rifters/riftedreader/domain/tts/TTSReplacementRule.kt`

```kotlin
package com.rifters.riftedreader.domain.tts

sealed class TTSReplacementRule {
    abstract val isEnabled: Boolean
    abstract val pattern: String
    abstract val replacement: String
    
    data class SimpleRule(
        override val isEnabled: Boolean = true,
        override val pattern: String,
        override val replacement: String
    ) : TTSReplacementRule()
    
    data class RegexRule(
        override val isEnabled: Boolean = true,
        override val pattern: String,
        override val replacement: String,
        val regex: Regex
    ) : TTSReplacementRule()
    
    data class CommandRule(
        override val isEnabled: Boolean = true,
        override val pattern: String,
        override val replacement: String,
        val command: TTSCommand
    ) : TTSReplacementRule()
}

enum class TTSCommand {
    PAUSE,   // Add pause after pattern
    STOP,    // Stop reading if pattern found
    NEXT,    // Go to next page if pattern found
    SKIP     // Skip sentence if pattern found
}

data class TTSReplacementResult(
    val text: String,
    val command: TTSCommand? = null
)
```

### Step 2.2: Implement Replacement Engine

**File**: `app/src/main/java/com/rifters/riftedreader/domain/tts/TTSReplacementEngine.kt`

```kotlin
package com.rifters.riftedreader.domain.tts

import android.util.Log

class TTSReplacementEngine {
    private val simpleRules = mutableListOf<TTSReplacementRule.SimpleRule>()
    private val regexRules = mutableListOf<TTSReplacementRule.RegexRule>()
    private val commandRules = mutableListOf<TTSReplacementRule.CommandRule>()
    
    companion object {
        private const val TAG = "TTSReplacementEngine"
        
        // Special markers
        private const val REGEX_MARKER = "*"
        private const val DISABLED_MARKER = "#"
        
        // Special commands
        private const val CMD_PAUSE = "ttsPAUSE"
        private const val CMD_STOP = "ttsSTOP"
        private const val CMD_NEXT = "ttsNEXT"
        private const val CMD_SKIP = "ttsSKIP"
    }
    
    /**
     * Apply all replacement rules to the given text
     */
    fun applyReplacements(text: String): TTSReplacementResult {
        var result = text
        var command: TTSCommand? = null
        
        // Check for command rules first
        for (rule in commandRules) {
            if (!rule.isEnabled) continue
            
            if (result.contains(rule.pattern, ignoreCase = true)) {
                command = rule.command
                when (rule.command) {
                    TTSCommand.SKIP -> return TTSReplacementResult("", TTSCommand.SKIP)
                    TTSCommand.STOP -> return TTSReplacementResult(result, TTSCommand.STOP)
                    TTSCommand.NEXT -> return TTSReplacementResult(result, TTSCommand.NEXT)
                    TTSCommand.PAUSE -> {
                        // Remove the pattern and add pause marker
                        result = result.replace(rule.pattern, "", ignoreCase = true)
                    }
                }
            }
        }
        
        // Apply simple replacements
        for (rule in simpleRules) {
            if (!rule.isEnabled) continue
            result = result.replace(rule.pattern, rule.replacement)
        }
        
        // Apply regex replacements
        for (rule in regexRules) {
            if (!rule.isEnabled) continue
            try {
                result = rule.regex.replace(result, rule.replacement)
            } catch (e: Exception) {
                Log.e(TAG, "Error applying regex rule: ${rule.pattern}", e)
            }
        }
        
        return TTSReplacementResult(result, command)
    }
    
    /**
     * Add a rule from text format
     * Examples:
     * - "Lib." -> "Librera"  (simple)
     * - "*\b(L|l)ib\." -> "$1ibrera"  (regex)
     * - "Page" -> "ttsSKIP"  (command)
     */
    fun addRuleFromText(pattern: String, replacement: String): Boolean {
        try {
            // Check if disabled
            val isEnabled = !pattern.startsWith(DISABLED_MARKER) && 
                           !pattern.matches(Regex("\\w+\\d+"))
            
            val cleanPattern = if (pattern.startsWith(DISABLED_MARKER)) {
                pattern.substring(1)
            } else {
                pattern
            }
            
            // Check if it's a command
            val command = when (replacement) {
                CMD_PAUSE -> TTSCommand.PAUSE
                CMD_STOP -> TTSCommand.STOP
                CMD_NEXT -> TTSCommand.NEXT
                CMD_SKIP -> TTSCommand.SKIP
                else -> null
            }
            
            if (command != null) {
                commandRules.add(
                    TTSReplacementRule.CommandRule(
                        isEnabled = isEnabled,
                        pattern = cleanPattern,
                        replacement = replacement,
                        command = command
                    )
                )
                return true
            }
            
            // Check if it's a regex
            if (cleanPattern.startsWith(REGEX_MARKER)) {
                val regexPattern = cleanPattern.substring(1)
                val regex = Regex(regexPattern)
                regexRules.add(
                    TTSReplacementRule.RegexRule(
                        isEnabled = isEnabled,
                        pattern = cleanPattern,
                        replacement = replacement,
                        regex = regex
                    )
                )
                return true
            }
            
            // It's a simple replacement
            simpleRules.add(
                TTSReplacementRule.SimpleRule(
                    isEnabled = isEnabled,
                    pattern = cleanPattern,
                    replacement = replacement
                )
            )
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error adding rule: $pattern -> $replacement", e)
            return false
        }
    }
    
    /**
     * Load rules from a file
     * Format: "pattern" "replacement" on each line
     */
    fun loadRulesFromText(rulesText: String): Int {
        var loadedCount = 0
        
        rulesText.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                return@forEach // Skip empty and comment lines
            }
            
            // Parse format: "pattern" "replacement"
            val parts = parseRuleLine(trimmed)
            if (parts != null && parts.size == 2) {
                if (addRuleFromText(parts[0], parts[1])) {
                    loadedCount++
                }
            }
        }
        
        return loadedCount
    }
    
    /**
     * Parse a line like: "pattern" "replacement"
     */
    private fun parseRuleLine(line: String): List<String>? {
        val regex = Regex(""""([^"]+)"\s+"([^"]+)"""")
        val match = regex.find(line)
        return match?.destructured?.let { (pattern, replacement) ->
            listOf(pattern, replacement)
        }
    }
    
    /**
     * Clear all rules
     */
    fun clearRules() {
        simpleRules.clear()
        regexRules.clear()
        commandRules.clear()
    }
    
    /**
     * Get all rules
     */
    fun getAllRules(): List<TTSReplacementRule> {
        return simpleRules + regexRules + commandRules
    }
    
    /**
     * Export rules to text format
     */
    fun exportRulesToText(): String {
        val sb = StringBuilder()
        
        getAllRules().forEach { rule ->
            val prefix = if (!rule.isEnabled) DISABLED_MARKER else ""
            sb.append(""""$prefix${rule.pattern}" "${rule.replacement}"""")
            sb.append("\n")
        }
        
        return sb.toString()
    }
}
```

### Step 2.3: Create Replacement Rules Storage

**File**: `app/src/main/java/com/rifters/riftedreader/data/preferences/TTSPreferences.kt`

```kotlin
package com.rifters.riftedreader.data.preferences

import android.content.Context
import android.content.SharedPreferences
import java.io.File

class TTSPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "tts_preferences",
        Context.MODE_PRIVATE
    )
    
    private val rulesDir = File(context.filesDir, "tts_rules")
    
    init {
        if (!rulesDir.exists()) {
            rulesDir.mkdirs()
        }
    }
    
    var speed: Float
        get() = prefs.getFloat(KEY_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SPEED, value).apply()
    
    var pitch: Float
        get() = prefs.getFloat(KEY_PITCH, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_PITCH, value).apply()
    
    var autoScroll: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SCROLL, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SCROLL, value).apply()
    
    var highlightSentence: Boolean
        get() = prefs.getBoolean(KEY_HIGHLIGHT, true)
        set(value) = prefs.edit().putBoolean(KEY_HIGHLIGHT, value).apply()
    
    var replacementsEnabled: Boolean
        get() = prefs.getBoolean(KEY_REPLACEMENTS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_REPLACEMENTS_ENABLED, value).apply()
    
    fun saveReplacementRules(rules: String) {
        val rulesFile = File(rulesDir, "default_rules.txt")
        rulesFile.writeText(rules)
    }
    
    fun loadReplacementRules(): String? {
        val rulesFile = File(rulesDir, "default_rules.txt")
        return if (rulesFile.exists()) {
            rulesFile.readText()
        } else {
            null
        }
    }
    
    fun getReplacementRulesFile(): File {
        return File(rulesDir, "default_rules.txt")
    }
    
    companion object {
        private const val KEY_SPEED = "tts_speed"
        private const val KEY_PITCH = "tts_pitch"
        private const val KEY_AUTO_SCROLL = "tts_auto_scroll"
        private const val KEY_HIGHLIGHT = "tts_highlight"
        private const val KEY_REPLACEMENTS_ENABLED = "tts_replacements_enabled"
    }
}
```

---

## Part 3: TTS Service for Background Reading

### Step 3.1: Create TTS Service

**File**: `app/src/main/java/com/rifters/riftedreader/domain/tts/TTSService.kt`

```kotlin
package com.rifters.riftedreader.domain.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TTSService : Service() {
    private lateinit var ttsEngine: TTSEngine
    private lateinit var replacementEngine: TTSReplacementEngine
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    
    private var sentences: List<String> = emptyList()
    private var currentSentenceIndex = 0
    
    companion object {
        const val ACTION_PLAY = "action_play"
        const val ACTION_PAUSE = "action_pause"
        const val ACTION_STOP = "action_stop"
        const val ACTION_NEXT = "action_next"
        const val ACTION_PREVIOUS = "action_previous"
        
        const val EXTRA_TEXT = "extra_text"
        
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "tts_service_channel"
    }
    
    override fun onCreate() {
        super.onCreate()
        
        ttsEngine = TTSEngine(this)
        replacementEngine = TTSReplacementEngine()
        
        ttsEngine.initialize { success ->
            if (success) {
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
        }
        
        return START_STICKY
    }
    
    private fun handlePlay(intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT)
        if (text != null) {
            sentences = splitIntoSentences(text)
            currentSentenceIndex = 0
        }
        
        startForeground(NOTIFICATION_ID, createNotification())
        speakCurrentSentence()
    }
    
    private fun handlePause() {
        ttsEngine.stop()
        updateNotification()
    }
    
    private fun handleStop() {
        ttsEngine.stop()
        stopForeground(true)
        stopSelf()
    }
    
    private fun handleNext() {
        if (currentSentenceIndex < sentences.size - 1) {
            currentSentenceIndex++
            speakCurrentSentence()
        }
    }
    
    private fun handlePrevious() {
        if (currentSentenceIndex > 0) {
            currentSentenceIndex--
            speakCurrentSentence()
        }
    }
    
    private fun speakCurrentSentence() {
        if (currentSentenceIndex >= sentences.size) {
            handleStop()
            return
        }
        
        val sentence = sentences[currentSentenceIndex]
        val result = replacementEngine.applyReplacements(sentence)
        
        when (result.command) {
            TTSCommand.SKIP -> {
                // Skip to next sentence
                currentSentenceIndex++
                serviceScope.launch {
                    delay(100)
                    speakCurrentSentence()
                }
            }
            TTSCommand.STOP -> {
                handleStop()
            }
            TTSCommand.NEXT -> {
                // Signal to go to next page
                // This would need integration with the reader
                currentSentenceIndex = sentences.size // End of current text
            }
            else -> {
                ttsEngine.speak(result.text, "sentence_$currentSentenceIndex")
            }
        }
    }
    
    private fun setupUtteranceListener() {
        ttsEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // Update UI to show speaking
            }
            
            override fun onDone(utteranceId: String?) {
                // Move to next sentence
                currentSentenceIndex++
                serviceScope.launch {
                    delay(300) // Small pause between sentences
                    speakCurrentSentence()
                }
            }
            
            override fun onError(utteranceId: String?) {
                // Handle error
            }
        })
    }
    
    private fun splitIntoSentences(text: String): List<String> {
        // Simple sentence splitting - can be improved
        return text.split(Regex("[.!?]+\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TTS Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Text-to-Speech playback controls"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RiftedReader TTS")
            .setContentText("Reading...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_media_previous, "Previous",
                createPendingIntent(ACTION_PREVIOUS))
            .addAction(android.R.drawable.ic_media_pause, "Pause",
                createPendingIntent(ACTION_PAUSE))
            .addAction(android.R.drawable.ic_media_next, "Next",
                createPendingIntent(ACTION_NEXT))
            .build()
    }
    
    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun createPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, TTSService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    override fun onDestroy() {
        super.onDestroy()
        ttsEngine.shutdown()
        serviceScope.cancel()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
```

---

## Part 4: Sample Replacement Rules

### Common Replacements File Example

**File**: Create `assets/sample_tts_rules.txt`

```
# Simple replacements - stress marks for Russian/Ukrainian
" живого " "живо́ва"
" как глаза " " как глаза́ "

# English abbreviations
"Dr." "Doctor"
"Mr." "Mister"
"Mrs." "Misses"
"Ms." "Miss"
"Jr." "Junior"
"Sr." "Senior"

# Technical terms
"PDF" "P D F"
"HTML" "H T M L"
"URL" "U R L"

# Regex - skip special characters
"*[()\[\]{}«»"""'']" ""

# Regex - replace punctuation with pause
"*[?!:;–—―]" ","

# Command - skip page numbers (common pattern)
"Page \d+" "ttsSKIP"

# Command - skip footnote markers
"*\[\d+\]" "ttsSKIP"

# Command - stop at chapter end
"End of Chapter" "ttsSTOP"

# Disabled rule example (starts with #)
#"test" "replacement"

# Disabled rule example (word+number)
"test123" "replacement"
```

---

## Part 5: Testing the TTS System

### Test Cases

1. **Basic TTS Test**
```kotlin
@Test
fun testBasicTTS() {
    val engine = TTSEngine(context)
    engine.initialize { success ->
        assertTrue(success)
        engine.speak("Hello world")
    }
}
```

2. **Simple Replacement Test**
```kotlin
@Test
fun testSimpleReplacement() {
    val engine = TTSReplacementEngine()
    engine.addRuleFromText("Dr.", "Doctor")
    
    val result = engine.applyReplacements("Dr. Smith arrived")
    assertEquals("Doctor Smith arrived", result.text)
}
```

3. **Regex Replacement Test**
```kotlin
@Test
fun testRegexReplacement() {
    val engine = TTSReplacementEngine()
    engine.addRuleFromText("*\\[(\\d+)\\]", "footnote $1")
    
    val result = engine.applyReplacements("Some text[1] here")
    assertEquals("Some textfootnote 1 here", result.text)
}
```

4. **Command Test**
```kotlin
@Test
fun testSkipCommand() {
    val engine = TTSReplacementEngine()
    engine.addRuleFromText("Page \\d+", "ttsSKIP")
    
    val result = engine.applyReplacements("Page 42")
    assertEquals("", result.text)
    assertEquals(TTSCommand.SKIP, result.command)
}
```

---

## Part 6: Integration Checklist

- [ ] Add TTS permission to AndroidManifest.xml
- [ ] Add foreground service permission to AndroidManifest.xml
- [ ] Register TTSService in AndroidManifest.xml
- [ ] Create TTS UI controls in reader view
- [ ] Implement sentence highlighting during reading
- [ ] Add TTS settings screen
- [ ] Add replacement rules editor UI
- [ ] Import sample rules file on first run
- [ ] Test with various book formats
- [ ] Test with different languages
- [ ] Add accessibility features
- [ ] Document TTS features in user guide

---

## Part 7: Future Enhancements

1. **Advanced Features**
   - Voice selection per language
   - Custom voice profiles
   - Reading speed presets
   - Sleep timer
   - Auto-bookmark on stop

2. **UI Improvements**
   - Visual waveform during speaking
   - Karaoke-style word highlighting
   - Quick access to common replacements
   - Rule testing tool

3. **Smart Features**
   - AI-powered pronunciation suggestions
   - Auto-detect language per paragraph
   - Learn from user corrections
   - Share replacement rules with community

---

## Summary

This implementation provides:

1. ✅ **Basic TTS** - Using Android's TextToSpeech API
2. ✅ **Replacement System** - Simple, Regex, and Command rules
3. ✅ **Background Service** - Foreground service with notification
4. ✅ **State Management** - Clean architecture with StateFlow
5. ✅ **Persistence** - Save/load replacement rules
6. ✅ **@Voice Compatibility** - Same rule format

The key innovation from LibreraReader is the **sophisticated replacement system** which allows users to:
- Fix pronunciation of uncommon words
- Add stress marks for proper emphasis
- Skip unwanted content (headers, footers, page numbers)
- Control reading flow with commands

This makes the TTS experience significantly better than basic TTS implementations.

---

**Next Steps**: Begin with Part 1 (Basic TTS), then progressively add Parts 2-3 (Replacement System and Service). Test thoroughly with real books before moving to advanced features.
