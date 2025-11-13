package com.rifters.riftedreader.ui.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import android.content.pm.ApplicationInfo
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.database.BookDatabase
import com.rifters.riftedreader.data.preferences.ReaderPreferences
import com.rifters.riftedreader.data.preferences.ReaderSettings
import com.rifters.riftedreader.data.preferences.ReaderTheme
import com.rifters.riftedreader.data.preferences.TTSPreferences
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.databinding.ActivityReaderBinding
import com.rifters.riftedreader.domain.parser.ParserFactory
import com.rifters.riftedreader.domain.tts.TTSPlaybackState
import com.rifters.riftedreader.domain.tts.TTSService
import com.rifters.riftedreader.ui.tts.TTSControlsBottomSheet
import kotlinx.coroutines.launch
import java.io.File

class ReaderActivity : AppCompatActivity(), ReaderPreferencesOwner {
    
    private lateinit var binding: ActivityReaderBinding
    private lateinit var viewModel: ReaderViewModel
    private lateinit var gestureDetector: GestureDetector
    private lateinit var controlsManager: ReaderControlsManager
    override lateinit var readerPreferences: ReaderPreferences
    private var tapActions: Map<ReaderTapZone, ReaderTapAction> = ReaderPreferences.defaultTapActions()
    private lateinit var ttsPreferences: TTSPreferences
    private var currentPageText: String = ""
    private var currentPageHtml: String? = null
    private var sentenceBoundaries: List<IntRange> = emptyList()
    private var highlightedSentenceIndex: Int = -1
    private val ttsStatusIntentFilter = IntentFilter(TTSService.ACTION_STATUS)
    private val ttsStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TTSService.ACTION_STATUS) return
            val stateName = intent.getStringExtra(TTSService.EXTRA_STATE) ?: return
            val playbackState = runCatching { TTSPlaybackState.valueOf(stateName) }.getOrNull() ?: return
            val sentenceIndex = intent.getIntExtra(TTSService.EXTRA_SENTENCE_INDEX, -1)

            when (playbackState) {
                TTSPlaybackState.PLAYING, TTSPlaybackState.PAUSED -> handleTtsHighlight(sentenceIndex)
                TTSPlaybackState.STOPPED, TTSPlaybackState.IDLE -> clearHighlightedSentence()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Get book information from intent
        val bookId = intent.getStringExtra("BOOK_ID") ?: ""
        val bookPath = intent.getStringExtra("BOOK_PATH") ?: ""
        val bookTitle = intent.getStringExtra("BOOK_TITLE") ?: ""
        
        if (bookPath.isEmpty()) {
            finish()
            return
        }
        
        // Initialize ViewModel
        val database = BookDatabase.getDatabase(this)
        val repository = BookRepository(database.bookMetaDao())
        readerPreferences = ReaderPreferences(this)
        ttsPreferences = TTSPreferences(this)
        tapActions = readerPreferences.tapActions.value
        val bookFile = File(bookPath)
        val parser = ParserFactory.getParser(bookFile)

        if (parser == null) {
            finish()
            return
        }

        val factory = ReaderViewModel.Factory(bookId, bookFile, parser, repository, readerPreferences)
        viewModel = ViewModelProvider(this, factory)[ReaderViewModel::class.java]
        setupControls(bookTitle)
        setupGestures()
        observeViewModel()
        
        // Load initial page
        viewModel.loadCurrentPage()
    }
    
    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val tapZone = ReaderTapZoneDetector.detect(
                    e.x,
                    e.y,
                    binding.contentScrollView.width,
                    binding.contentScrollView.height
                )
                handleTapZone(tapZone)
                return true
            }
        })
        
        binding.contentScrollView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }
    
    private fun setupControls(bookTitle: String) {
        controlsManager = ReaderControlsManager(binding.controlsContainer, lifecycleScope)
        if (isDebugBuild()) {
            controlsManager.setAutoHideEnabled(false)
        }

        binding.topBar.title = bookTitle.ifBlank { getString(R.string.reader_title_placeholder) }
        binding.topBar.setNavigationOnClickListener { finish() }
        binding.topBar.inflateMenu(R.menu.reader_toolbar_menu)
        binding.topBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_reader_settings -> {
                    openReaderSettings()
                    true
                }
                R.id.action_reader_tap_zones -> {
                    openTapZones()
                    true
                }

                else -> false
            }
        }

        binding.prevButton.setOnClickListener {
            viewModel.previousPage()
            controlsManager.onUserInteraction()
        }
        
        binding.nextButton.setOnClickListener {
            viewModel.nextPage()
            controlsManager.onUserInteraction()
        }
        
        binding.ttsButton.setOnClickListener {
            controlsManager.showControls()
            TTSControlsBottomSheet.show(supportFragmentManager, viewModel.content.value.text)
        }

        binding.pageSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.goToPage(value.toInt())
                controlsManager.onUserInteraction()
            }
        }

        // Ensure controls are available briefly after layout to provide context to the reader.
        binding.root.doOnLayout {
            controlsManager.showControls()
        }
    }
    
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.content.collect { pageContent ->
                        currentPageText = pageContent.text
                        currentPageHtml = pageContent.html
                        sentenceBoundaries = buildSentenceBoundaries(pageContent.text)
                        highlightedSentenceIndex = -1
                        if (pageContent.html.isNullOrBlank()) {
                            binding.contentTextView.text = pageContent.text
                        } else {
                            val spanned = HtmlCompat.fromHtml(pageContent.html, HtmlCompat.FROM_HTML_MODE_LEGACY)
                            binding.contentTextView.text = spanned
                        }
                    }
                }
                
                launch {
                    viewModel.currentPage.collect { page ->
                        updatePageIndicator(page)
                    }
                }
                
                launch {
                    viewModel.totalPages.collect { total ->
                        binding.pageSlider.valueTo = total.toFloat().coerceAtLeast(1f)
                    }
                }

                launch {
                    viewModel.readerSettings.collect { settings ->
                        applyReaderSettings(settings)
                    }
                }

                launch {
                    readerPreferences.tapActions.collect { actions ->
                        tapActions = actions
                    }
                }
            }
        }
    }
    
    private fun updatePageIndicator(page: Int) {
        val total = viewModel.totalPages.value
        binding.pageIndicator.text = getString(R.string.reader_page_indicator, page + 1, total)
        binding.pageSlider.value = page.toFloat()
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(ttsStatusReceiver, ttsStatusIntentFilter)
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(ttsStatusReceiver)
        super.onStop()
    }
    
    override fun onPause() {
        super.onPause()
        viewModel.saveProgress()
    }

    private fun handleTapZone(zone: ReaderTapZone) {
        val action = tapActions[zone] ?: ReaderTapAction.NONE
        performTapAction(action)
    }

    private fun applyReaderSettings(settings: ReaderSettings) {
        binding.contentTextView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, settings.textSizeSp)
        binding.contentTextView.setLineSpacing(0f, settings.lineHeightMultiplier)

        val (backgroundColor, textColor) = when (settings.theme) {
            ReaderTheme.DARK -> R.color.reader_background_dark to R.color.reader_text_dark
            ReaderTheme.SEPIA -> R.color.reader_background_sepia to R.color.reader_text_sepia
            ReaderTheme.BLACK -> R.color.reader_background_black to R.color.reader_text_black
            else -> R.color.reader_background_light to R.color.reader_text_light
        }

        binding.readerRoot.setBackgroundColor(ContextCompat.getColor(this, backgroundColor))
        binding.contentTextView.setTextColor(ContextCompat.getColor(this, textColor))
    }

    private fun openReaderSettings() {
        controlsManager.showControls()
        ReaderTextSettingsBottomSheet.show(supportFragmentManager)
    }

    private fun openTapZones() {
        controlsManager.showControls()
        ReaderTapZonesBottomSheet.show(supportFragmentManager)
    }

    private fun isDebugBuild(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun performTapAction(action: ReaderTapAction) {
        when (action) {
            ReaderTapAction.BACK -> finish()
            ReaderTapAction.TOGGLE_CONTROLS -> controlsManager.toggleControls()
            ReaderTapAction.NEXT_PAGE -> {
                viewModel.nextPage()
                controlsManager.onUserInteraction()
            }
            ReaderTapAction.PREVIOUS_PAGE -> {
                viewModel.previousPage()
                controlsManager.onUserInteraction()
            }
            ReaderTapAction.OPEN_SETTINGS -> openReaderSettings()
            ReaderTapAction.START_TTS -> {
                controlsManager.showControls()
                controlsManager.onUserInteraction()
                TTSControlsBottomSheet.show(supportFragmentManager, viewModel.content.value.text)
            }
            ReaderTapAction.NONE -> controlsManager.onUserInteraction()
        }
    }

    private fun handleTtsHighlight(targetIndex: Int) {
        if (!ttsPreferences.highlightSentence || currentPageText.isEmpty()) {
            clearHighlightedSentence()
            return
        }

        if (targetIndex == highlightedSentenceIndex) {
            return
        }

        if (targetIndex !in sentenceBoundaries.indices) {
            clearHighlightedSentence()
            return
        }

        val range = sentenceBoundaries[targetIndex]
        val spannable = SpannableString(currentPageText)
        val endExclusive = (range.last + 1).coerceAtMost(spannable.length)
        val highlightColor = ContextCompat.getColor(this, R.color.reader_tts_highlight)
        spannable.setSpan(
            BackgroundColorSpan(highlightColor),
            range.first,
            endExclusive,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.contentTextView.text = spannable
        highlightedSentenceIndex = targetIndex

        if (ttsPreferences.autoScroll) {
            ensureSentenceVisible(range.first)
        }
    }

    private fun ensureSentenceVisible(startOffset: Int) {
        if (currentPageText.isEmpty()) return

        binding.contentTextView.post {
            val layout = binding.contentTextView.layout ?: return@post
            val textLength = layout.text.length
            if (textLength == 0) return@post
            val maxOffset = (textLength - 1).coerceAtLeast(0)
            val clampedOffset = startOffset.coerceIn(0, maxOffset)
            val line = layout.getLineForOffset(clampedOffset)
            val lineTop = layout.getLineTop(line)
            val offset = (binding.contentTextView.lineHeight * 2)
            val targetY = (lineTop - offset).coerceAtLeast(0)
            binding.contentScrollView.smoothScrollTo(0, targetY)
        }
    }

    private fun clearHighlightedSentence() {
        if (highlightedSentenceIndex == -1) {
            return
        }
        highlightedSentenceIndex = -1
        val html = currentPageHtml
        if (!html.isNullOrBlank()) {
            binding.contentTextView.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
        } else {
            binding.contentTextView.text = currentPageText
        }
    }

    private fun buildSentenceBoundaries(text: String): List<IntRange> {
        if (text.isBlank()) return emptyList()

        val sentences = text.split(SENTENCE_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (sentences.isEmpty()) return emptyList()

        val ranges = mutableListOf<IntRange>()
        var searchStart = 0
        for (sentence in sentences) {
            val index = text.indexOf(sentence, searchStart)
            if (index >= 0) {
                val start = index
                val end = (index + sentence.length - 1).coerceAtMost(text.lastIndex)
                ranges.add(start..end)
                searchStart = index + sentence.length
            }
        }
        return ranges
    }

}

private val SENTENCE_REGEX = Regex("(?<=[.!?])\\s+")

