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
import com.rifters.riftedreader.util.AppLogger
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
        AppLogger.event("ReaderActivity", "onCreate started", "ui/ReaderActivity/lifecycle")
        
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Get book information from intent
        val bookId = intent.getStringExtra("BOOK_ID") ?: ""
        val bookPath = intent.getStringExtra("BOOK_PATH") ?: ""
        val bookTitle = intent.getStringExtra("BOOK_TITLE") ?: ""
        
        AppLogger.d("ReaderActivity", "Opening book: $bookTitle at path: $bookPath")
        
        if (bookPath.isEmpty()) {
            AppLogger.w("ReaderActivity", "Empty book path, finishing activity")
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
            AppLogger.e("ReaderActivity", "No parser found for book: $bookPath")
            finish()
            return
        }

        AppLogger.d("ReaderActivity", "Parser loaded: ${parser::class.simpleName}")
        
        val factory = ReaderViewModel.Factory(bookId, bookFile, parser, repository, readerPreferences)
        viewModel = ViewModelProvider(this, factory)[ReaderViewModel::class.java]
        setupControls(bookTitle)
        setupGestures()
        observeViewModel()
        
        AppLogger.event("ReaderActivity", "onCreate completed", "ui/ReaderActivity/lifecycle")
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
        // Always pass events to gesture detector to ensure tap zones work
        val scrollTouchListener = View.OnTouchListener { _, event ->
            val actionMasked = event.actionMasked
            val actionName = when (actionMasked) {
                MotionEvent.ACTION_DOWN -> "DOWN"
                MotionEvent.ACTION_MOVE -> "MOVE"
                MotionEvent.ACTION_UP -> "UP"
                MotionEvent.ACTION_CANCEL -> "CANCEL"
                MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
                MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
                else -> "OTHER($actionMasked)"
            }
            
            val pointerCount = event.pointerCount
            val pointerIndex = event.actionIndex
            val pointerId = if (pointerCount > pointerIndex) event.getPointerId(pointerIndex) else -1
            
            AppLogger.d(
                "ReaderActivity",
                "ScrollView.onTouch: action=$actionName(masked=$actionMasked) x=${event.x} y=${event.y} " +
                        "pointerCount=$pointerCount pointerIndex=$pointerIndex pointerId=$pointerId mode=$readerMode"
            )
            val result = gestureDetector.onTouchEvent(event)
            AppLogger.d(
                "ReaderActivity",
                "ScrollView.onTouch RETURNED=$result for action=$actionName"
            )
            // Don't consume the event - let ScrollView handle scrolling
            false
        }
        
        val pagerTouchListener = View.OnTouchListener { _, event ->
            val actionMasked = event.actionMasked
            val actionName = when (actionMasked) {
                MotionEvent.ACTION_DOWN -> "DOWN"
                MotionEvent.ACTION_MOVE -> "MOVE"
                MotionEvent.ACTION_UP -> "UP"
                MotionEvent.ACTION_CANCEL -> "CANCEL"
                MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
                MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
                else -> "OTHER($actionMasked)"
            }
            
            val pointerCount = event.pointerCount
            val pointerIndex = event.actionIndex
            val pointerId = if (pointerCount > pointerIndex) event.getPointerId(pointerIndex) else -1
            
            AppLogger.d(
                "ReaderActivity",
                "ViewPager2.onTouch: action=$actionName(masked=$actionMasked) x=${event.x} y=${event.y} " +
                        "pointerCount=$pointerCount pointerIndex=$pointerIndex pointerId=$pointerId mode=$readerMode currentPage=${viewModel.currentPage.value}"
            )
            val result = gestureDetector.onTouchEvent(event)
            AppLogger.d(
                "ReaderActivity",
                "ViewPager2.onTouch RETURNED=$result for action=$actionName"
            )
            // Don't consume the event - let ViewPager handle paging
            false
        }
        
        binding.contentScrollView.setOnTouchListener(scrollTouchListener)
        binding.pageViewPager.setOnTouchListener(pagerTouchListener)
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
        
        // DEBUG-ONLY: Instrument ViewPager2's internal RecyclerView for gesture tracing
        // ViewPager2 contains a RecyclerView as its direct child at index 0
        binding.pageViewPager.post {
            if (binding.pageViewPager.childCount > 0) {
                val recyclerView = binding.pageViewPager.getChildAt(0)
                recyclerView.setOnTouchListener { _, event ->
                    val actionMasked = event.actionMasked
                    val actionName = when (actionMasked) {
                        MotionEvent.ACTION_DOWN -> "DOWN"
                        MotionEvent.ACTION_MOVE -> "MOVE"
                        MotionEvent.ACTION_UP -> "UP"
                        MotionEvent.ACTION_CANCEL -> "CANCEL"
                        MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
                        MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
                        else -> "OTHER($actionMasked)"
                    }
                    val timestamp = System.currentTimeMillis()
                    val currentPage = viewModel.currentPage.value
                    val pointerCount = event.pointerCount
                    val pointerIndex = event.actionIndex
                    val pointerId = if (pointerCount > pointerIndex) event.getPointerId(pointerIndex) else -1
                    
                    AppLogger.d(
                        "ReaderActivity",
                        "DEBUG-ONLY: ViewPager2.RecyclerView.onTouch: action=$actionName(masked=$actionMasked) " +
                                "x=${event.x} y=${event.y} pointerCount=$pointerCount pointerIndex=$pointerIndex " +
                                "pointerId=$pointerId timestamp=$timestamp currentPage=$currentPage"
                    )
                    // Don't consume - let RecyclerView handle its touch events
                    false
                }
                AppLogger.d("ReaderActivity", "DEBUG-ONLY: ViewPager2 RecyclerView instrumentation attached")
            } else {
                AppLogger.w("ReaderActivity", "DEBUG-ONLY: ViewPager2 has no children to instrument")
            }
        }
        
        binding.pageViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val previousPage = viewModel.currentPage.value
                AppLogger.d(
                    "ReaderActivity",
                    "ViewPager2.onPageSelected: position=$position previousPage=$previousPage " +
                            "mode=$readerMode [PAGE_CHANGE_FROM_VIEWPAGER2]"
                )
                if (readerMode == ReaderMode.PAGE && viewModel.currentPage.value != position) {
                    AppLogger.d(
                        "ReaderActivity",
                        "Updating ViewModel page: $previousPage -> $position (triggered by ViewPager2 gesture/animation) [PAGE_SWITCH_REASON]"
                    )
                    viewModel.goToPage(position)
                }
                controlsManager.onUserInteraction()
            }
            
            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)
                val stateName = when (state) {
                    ViewPager2.SCROLL_STATE_IDLE -> "IDLE"
                    ViewPager2.SCROLL_STATE_DRAGGING -> "DRAGGING"
                    ViewPager2.SCROLL_STATE_SETTLING -> "SETTLING"
                    else -> "UNKNOWN($state)"
                }
                AppLogger.d(
                    "ReaderActivity",
                    "ViewPager2.onPageScrollStateChanged: state=$stateName currentPage=${viewModel.currentPage.value} [PAGE_SCROLL_STATE]"
                )
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

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        // Handle hardware volume keys for page navigation in PAGE mode only
        if (readerMode == ReaderMode.PAGE) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    // Try to let the current fragment handle in-page navigation first.
                    val currentPosition = viewModel.currentPage.value
                    val fragTag = "f$currentPosition"
                    val frag = supportFragmentManager.findFragmentByTag(fragTag) as? ReaderPageFragment
                    if (frag?.handleHardwarePageKey(isNext = true) == true) {
                        // Fragment will handle (consumed)
                        return true
                    }

                    // Fallback: navigate chapters
                    AppLogger.d(
                        "ReaderActivity",
                        "VOLUME_DOWN pressed in PAGE mode - navigating to next page [HARDWARE_KEY_NAV]"
                    )
                    navigateToNextPage(animated = true)
                    // Return true to consume the event and prevent volume change
                    return true
                }
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                    val currentPosition = viewModel.currentPage.value
                    val fragTag = "f$currentPosition"
                    val frag = supportFragmentManager.findFragmentByTag(fragTag) as? ReaderPageFragment
                    if (frag?.handleHardwarePageKey(isNext = false) == true) {
                        return true
                    }

                    AppLogger.d(
                        "ReaderActivity",
                        "VOLUME_UP pressed in PAGE mode - navigating to previous page [HARDWARE_KEY_NAV]"
                    )
                    navigateToPreviousPage(animated = true)
                    return true
                }
            }
        }
        // If not in PAGE mode or not a volume key, use default behavior
        return super.onKeyDown(keyCode, event)
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

    internal fun navigateToNextPage(animated: Boolean = true) {
        val currentIndex = viewModel.currentPage.value
        val nextIndex = currentIndex + 1
        AppLogger.d(
            "ReaderActivity",
            "navigateToNextPage called: currentPage=$currentIndex -> nextPage=$nextIndex mode=$readerMode " +
                    "[PAGE_SWITCH_REASON:USER_TAP_OR_BUTTON]"
        )
        val moved = viewModel.goToPage(nextIndex)
        if (readerMode == ReaderMode.PAGE && moved) {
            AppLogger.d(
                "ReaderActivity",
                "Programmatically setting ViewPager2 to page $nextIndex (user navigation) [PROGRAMMATIC_PAGE_CHANGE]"
            )
            binding.pageViewPager.setCurrentItem(nextIndex, animated)
        }
    }

    internal fun navigateToPreviousPage(animated: Boolean = true) {
        val currentIndex = viewModel.currentPage.value
        val previousIndex = currentIndex - 1
        AppLogger.d(
            "ReaderActivity",
            "navigateToPreviousPage called: currentPage=$currentIndex -> previousPage=$previousIndex mode=$readerMode " +
                    "[PAGE_SWITCH_REASON:USER_TAP_OR_BUTTON]"
        )
        val moved = viewModel.goToPage(previousIndex)
        if (readerMode == ReaderMode.PAGE && moved) {
            AppLogger.d(
                "ReaderActivity",
                "Programmatically setting ViewPager2 to page $previousIndex (user navigation) [PROGRAMMATIC_PAGE_CHANGE]"
            )
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
                // Disable ViewPager2 user input to let WebView handle swipes
                // and enable tap zones to work properly
                binding.pageViewPager.isUserInputEnabled = false
                AppLogger.d("ReaderActivity", "ViewPager2 user input disabled for PAGE mode")
                viewModel.publishHighlight(viewModel.currentPage.value, currentHighlightRange)
            } else {
                binding.pageViewPager.isVisible = false
                binding.pageViewPager.isUserInputEnabled = true
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

