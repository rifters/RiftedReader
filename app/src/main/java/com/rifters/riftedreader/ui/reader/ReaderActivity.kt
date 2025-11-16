package com.rifters.riftedreader.ui.reader

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import android.content.pm.ApplicationInfo
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.database.BookDatabase
import androidx.viewpager2.widget.ViewPager2
import com.rifters.riftedreader.data.preferences.ReaderMode
import com.rifters.riftedreader.data.preferences.ReaderPreferences
import com.rifters.riftedreader.data.preferences.ReaderSettings
import com.rifters.riftedreader.data.preferences.ReaderTheme
import com.rifters.riftedreader.data.preferences.TTSPreferences
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.databinding.ActivityReaderBinding
import com.rifters.riftedreader.domain.parser.ParserFactory
import com.rifters.riftedreader.domain.tts.TTSConfiguration
import com.rifters.riftedreader.domain.tts.TTSPlaybackState
import com.rifters.riftedreader.domain.tts.TTSService
import com.rifters.riftedreader.domain.tts.TTSStatusNotifier
import com.rifters.riftedreader.domain.tts.TTSStatusSnapshot
import com.rifters.riftedreader.ui.reader.ReaderThemePaletteResolver
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
    private lateinit var pagerAdapter: ReaderPagerAdapter
    private var currentPageText: String = ""
    private var currentPageHtml: String? = null
    private var sentenceBoundaries: List<IntRange> = emptyList()
    private var highlightedSentenceIndex: Int = -1
    private var currentHighlightRange: IntRange? = null
    private var readerMode: ReaderMode = ReaderMode.SCROLL
    private var autoContinueTts: Boolean = false
    private var pendingTtsResume: Boolean = false
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
    }
    
    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Convert screen-absolute coordinates (rawX, rawY) to view-relative coordinates
                // to fix coordinate system mismatch with tap zone detection
                val location = IntArray(2)
                binding.readerRoot.getLocationOnScreen(location)
                val viewX = e.rawX - location[0]
                val viewY = e.rawY - location[1]
                
                val width = binding.readerRoot.width
                val height = binding.readerRoot.height
                val tapZone = ReaderTapZoneDetector.detect(
                    viewX,
                    viewY,
                    width,
                    height
                )
                handleTapZone(tapZone)
                return true
            }
        })
        
        // Set up touch listeners that coordinate gestures with scrolling/paging
        val scrollTouchListener = View.OnTouchListener { _, event ->
            // Always let gesture detector see the event
            gestureDetector.onTouchEvent(event)
            // Don't consume the event - let ScrollView handle scrolling
            false
        }
        
        val pagerTouchListener = View.OnTouchListener { _, event ->
            // Always let gesture detector see the event
            gestureDetector.onTouchEvent(event)
            // Don't consume the event - let ViewPager handle paging
            false
        }
        
        // Set up touch listener on controls container to enable tap zones even when controls are visible
        // The container is set to clickable=false in XML, but we need to intercept taps in the 
        // empty space between top bar and bottom controls
        var isInMiddleArea = false
        val controlsTouchListener = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Check if tap starts in the empty middle area (not on topBar or bottomControls)
                    val topBarBottom = binding.topBar.bottom
                    val bottomControlsTop = binding.bottomControls.top
                    val yInContainer = event.y.toInt()
                    
                    // If tap is in the transparent middle area, handle it for tap zones
                    isInMiddleArea = yInContainer > topBarBottom && yInContainer < bottomControlsTop
                    if (isInMiddleArea) {
                        // Let gesture detector handle this tap
                        gestureDetector.onTouchEvent(event)
                        return@OnTouchListener true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL -> {
                    // Continue gesture detection for events that started in middle area
                    if (isInMiddleArea) {
                        gestureDetector.onTouchEvent(event)
                        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                            isInMiddleArea = false
                        }
                        return@OnTouchListener true
                    }
                }
            }
            // For taps on actual controls or other events, don't consume
            false
        }
        
        binding.contentScrollView.setOnTouchListener(scrollTouchListener)
        binding.pageViewPager.setOnTouchListener(pagerTouchListener)
        binding.controlsContainer.setOnTouchListener(controlsTouchListener)
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
                R.id.action_reader_chapters -> {
                    openChapters()
                    true
                }
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

        pagerAdapter = ReaderPagerAdapter(this)
        binding.pageViewPager.adapter = pagerAdapter
        binding.pageViewPager.offscreenPageLimit = 1
        binding.pageViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if (readerMode == ReaderMode.PAGE && viewModel.currentPage.value != position) {
                    viewModel.goToPage(position)
                }
                controlsManager.onUserInteraction()
            }
        })

        binding.prevButton.setOnClickListener {
            navigateToPreviousPage()
            controlsManager.onUserInteraction()
        }

        binding.nextButton.setOnClickListener {
            navigateToNextPage()
            controlsManager.onUserInteraction()
        }
        
        // TTS Play/Pause button
        binding.ttsPlayPauseButton.setOnClickListener {
            handleTtsPlayPause()
            controlsManager.onUserInteraction()
        }
        
        // TTS Play/Pause button long press - open TTS settings
        binding.ttsPlayPauseButton.setOnLongClickListener {
            controlsManager.showControls()
            TTSControlsBottomSheet.show(supportFragmentManager, viewModel.content.value.text)
            true
        }
        
        // TTS Stop button
        binding.ttsStopButton.setOnClickListener {
            TTSService.stop(this)
            controlsManager.onUserInteraction()
        }
        
        // TTS Stop button long press - open TTS settings
        binding.ttsStopButton.setOnLongClickListener {
            controlsManager.showControls()
            TTSControlsBottomSheet.show(supportFragmentManager, viewModel.content.value.text)
            true
        }

        binding.pageSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val target = value.toInt()
                if (readerMode == ReaderMode.PAGE) {
                    binding.pageViewPager.setCurrentItem(target, true)
                } else {
                    viewModel.goToPage(target)
                }
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
                    TTSStatusNotifier.status.collect { snapshot: TTSStatusSnapshot ->
                        handleTtsStatus(snapshot)
                        when (snapshot.state) {
                            TTSPlaybackState.PLAYING, TTSPlaybackState.PAUSED ->
                                handleTtsHighlight(snapshot.sentenceIndex)
                            TTSPlaybackState.STOPPED, TTSPlaybackState.IDLE ->
                                clearHighlightedSentence()
                        }
                    }
                }

                launch {
                    viewModel.content.collect { pageContent ->
                        currentPageText = pageContent.text
                        currentPageHtml = pageContent.html
                        sentenceBoundaries = buildSentenceBoundaries(pageContent.text)
                        highlightedSentenceIndex = -1
                        currentHighlightRange = null
                        viewModel.publishHighlight(viewModel.currentPage.value, null)
                        if (pageContent.html.isNullOrBlank()) {
                            binding.contentTextView.text = pageContent.text
                        } else {
                            val spanned = HtmlCompat.fromHtml(pageContent.html, HtmlCompat.FROM_HTML_MODE_LEGACY)
                            binding.contentTextView.text = spanned
                        }
                        binding.contentScrollView.scrollTo(0, 0)
                        if (pendingTtsResume && autoContinueTts && currentPageText.isNotBlank()) {
                            pendingTtsResume = false
                            resumeTtsForCurrentPage()
                        }
                    }
                }
                
                launch {
                    viewModel.currentPage.collect { page ->
                        updatePageIndicator(page)
                        if (readerMode == ReaderMode.PAGE && binding.pageViewPager.currentItem != page) {
                            binding.pageViewPager.setCurrentItem(page, false)
                        }
                    }
                }
                
                launch {
                    viewModel.totalPages.collect { total ->
                        val maxValue = (total - 1).coerceAtLeast(0)
                        // Ensure valueTo is always greater than valueFrom (0) to avoid IllegalStateException
                        val safeValueTo = maxValue.toFloat().coerceAtLeast(1f)
                        binding.pageSlider.valueTo = safeValueTo
                        if (binding.pageSlider.value > maxValue) {
                            binding.pageSlider.value = maxValue.toFloat()
                        }
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

                launch {
                    viewModel.pages.collect { pages ->
                        pagerAdapter.submitPageCount(pages.size)
                    }
                }
            }
        }
    }
    
    private fun updatePageIndicator(page: Int) {
        val total = viewModel.totalPages.value
        val safeTotal = total.coerceAtLeast(0)
        val displayPage = if (safeTotal == 0) 0 else (page + 1).coerceAtMost(safeTotal)
        binding.pageIndicator.text = getString(R.string.reader_page_indicator, displayPage, safeTotal)
        if (binding.pageSlider.value != page.toFloat()) {
            binding.pageSlider.value = page.toFloat()
        }
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
        val palette = ReaderThemePaletteResolver.resolve(this, settings.theme)
        binding.readerRoot.setBackgroundColor(palette.backgroundColor)
        binding.contentTextView.setTextColor(palette.textColor)
        binding.pageViewPager.setBackgroundColor(palette.backgroundColor)
        readerMode = settings.mode
        updateReaderModeUi()
    }

    private fun openReaderSettings() {
        controlsManager.showControls()
        ReaderTextSettingsBottomSheet.show(supportFragmentManager)
    }

    private fun openTapZones() {
        controlsManager.showControls()
        ReaderTapZonesBottomSheet.show(supportFragmentManager)
    }
    
    private fun openChapters() {
        controlsManager.showControls()
        val chapters = viewModel.tableOfContents.value
        if (chapters.isEmpty()) {
            // No chapters available
            return
        }
        ChaptersBottomSheet.show(supportFragmentManager, chapters) { pageNumber ->
            viewModel.goToPage(pageNumber)
        }
    }

    private fun isDebugBuild(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun performTapAction(action: ReaderTapAction) {
        when (action) {
            ReaderTapAction.BACK -> finish()
            ReaderTapAction.TOGGLE_CONTROLS -> controlsManager.toggleControls()
            ReaderTapAction.NEXT_PAGE -> {
                navigateToNextPage()
                controlsManager.onUserInteraction()
            }
            ReaderTapAction.PREVIOUS_PAGE -> {
                navigateToPreviousPage()
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

    private fun navigateToNextPage(animated: Boolean = true) {
        val nextIndex = viewModel.currentPage.value + 1
        val moved = viewModel.goToPage(nextIndex)
        if (readerMode == ReaderMode.PAGE && moved) {
            binding.pageViewPager.setCurrentItem(nextIndex, animated)
        }
    }

    private fun navigateToPreviousPage(animated: Boolean = true) {
        val previousIndex = viewModel.currentPage.value - 1
        val moved = viewModel.goToPage(previousIndex)
        if (readerMode == ReaderMode.PAGE && moved) {
            binding.pageViewPager.setCurrentItem(previousIndex, animated)
        }
    }

    private fun handleTtsStatus(snapshot: TTSStatusSnapshot) {
        // Update button UI based on TTS state
        updateTtsButtonStates(snapshot.state)
        
        when (snapshot.state) {
            TTSPlaybackState.PLAYING -> {
                autoContinueTts = true
                pendingTtsResume = false
            }
            TTSPlaybackState.PAUSED -> {
                autoContinueTts = false
                pendingTtsResume = false
            }
            TTSPlaybackState.STOPPED -> {
                val reachedEnd = snapshot.sentenceTotal > 0 && snapshot.sentenceIndex >= snapshot.sentenceTotal
                if (autoContinueTts && reachedEnd) {
                    pendingTtsResume = true
                    val advanced = viewModel.nextPage()
                    if (!advanced) {
                        autoContinueTts = false
                        pendingTtsResume = false
                    }
                } else {
                    autoContinueTts = false
                    pendingTtsResume = false
                }
            }
            TTSPlaybackState.IDLE -> {
                autoContinueTts = false
                pendingTtsResume = false
            }
        }
    }

    private fun resumeTtsForCurrentPage() {
        if (currentPageText.isBlank()) return
        val configuration = TTSConfiguration(
            speed = ttsPreferences.speed,
            pitch = ttsPreferences.pitch,
            autoScroll = ttsPreferences.autoScroll,
            highlightSentence = ttsPreferences.highlightSentence,
            languageTag = ttsPreferences.languageTag
        )
        TTSService.start(this, currentPageText, configuration)
    }

    private fun updateReaderModeUi() {
        binding.root.post {
            if (readerMode == ReaderMode.PAGE) {
                binding.contentScrollView.isVisible = false
                binding.pageViewPager.isVisible = true
                viewModel.publishHighlight(viewModel.currentPage.value, currentHighlightRange)
            } else {
                binding.pageViewPager.isVisible = false
                binding.contentScrollView.isVisible = true
                currentHighlightRange?.let { applyScrollHighlight(it) }
            }
        }
    }

    private fun handleTtsHighlight(targetIndex: Int) {
        if (!ttsPreferences.highlightSentence || currentPageText.isEmpty()) {
            currentHighlightRange = null
            viewModel.publishHighlight(viewModel.currentPage.value, null)
            clearHighlightedSentence()
            return
        }

        if (targetIndex == highlightedSentenceIndex && readerMode != ReaderMode.PAGE) {
            return
        }

        if (targetIndex !in sentenceBoundaries.indices) {
            currentHighlightRange = null
            viewModel.publishHighlight(viewModel.currentPage.value, null)
            clearHighlightedSentence()
            return
        }

        val range = sentenceBoundaries[targetIndex]
        if (readerMode == ReaderMode.PAGE) {
            if (currentHighlightRange != range || highlightedSentenceIndex != targetIndex) {
                currentHighlightRange = range
                highlightedSentenceIndex = targetIndex
                viewModel.publishHighlight(viewModel.currentPage.value, range)
            }
            return
        }

        currentHighlightRange = range
        applyScrollHighlight(range)
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

        private fun applyScrollHighlight(range: IntRange) {
            if (currentPageText.isBlank()) return
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
        }

    private fun clearHighlightedSentence() {
        if (highlightedSentenceIndex == -1 && currentHighlightRange == null) {
            return
        }
        highlightedSentenceIndex = -1
        currentHighlightRange = null
        viewModel.publishHighlight(viewModel.currentPage.value, null)
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
    
    private fun handleTtsPlayPause() {
        val snapshot = TTSStatusNotifier.status.value
        when (snapshot.state) {
            TTSPlaybackState.IDLE, TTSPlaybackState.STOPPED -> {
                // Start TTS playback
                if (currentPageText.isNotBlank()) {
                    val configuration = TTSConfiguration(
                        speed = ttsPreferences.speed,
                        pitch = ttsPreferences.pitch,
                        autoScroll = ttsPreferences.autoScroll,
                        highlightSentence = ttsPreferences.highlightSentence,
                        languageTag = ttsPreferences.languageTag
                    )
                    TTSService.start(this, currentPageText, configuration)
                }
            }
            TTSPlaybackState.PLAYING -> {
                // Pause TTS playback
                TTSService.pause(this)
            }
            TTSPlaybackState.PAUSED -> {
                // Resume TTS playback
                TTSService.resume(this)
            }
        }
    }
    
    private fun updateTtsButtonStates(state: TTSPlaybackState) {
        binding.root.post {
            when (state) {
                TTSPlaybackState.IDLE, TTSPlaybackState.STOPPED -> {
                    binding.ttsPlayPauseButton.text = getString(R.string.tts_action_play)
                    binding.ttsPlayPauseButton.setIconResource(R.drawable.ic_tts_play_24)
                    binding.ttsStopButton.isVisible = false
                }
                TTSPlaybackState.PLAYING -> {
                    binding.ttsPlayPauseButton.text = getString(R.string.tts_action_pause)
                    binding.ttsPlayPauseButton.setIconResource(R.drawable.ic_tts_pause_24)
                    binding.ttsStopButton.isVisible = true
                }
                TTSPlaybackState.PAUSED -> {
                    binding.ttsPlayPauseButton.text = getString(R.string.tts_action_play)
                    binding.ttsPlayPauseButton.setIconResource(R.drawable.ic_tts_play_24)
                    binding.ttsStopButton.isVisible = true
                }
            }
        }
    }

}

private val SENTENCE_REGEX = Regex("(?<=[.!?])\\s+")

