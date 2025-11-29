package com.rifters.riftedreader.ui.reader

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.Log
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.rifters.riftedreader.data.preferences.ReaderMode
import com.rifters.riftedreader.data.preferences.ReaderPreferences
import com.rifters.riftedreader.data.preferences.ReaderSettings
import com.rifters.riftedreader.data.preferences.ReaderTheme
import com.rifters.riftedreader.data.preferences.TTSPreferences
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.databinding.ActivityReaderBinding
import com.rifters.riftedreader.domain.pagination.PaginationMode
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
    private var usingWebViewSlider: Boolean = false
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var snapHelper: PagerSnapHelper
    private var currentPagerPosition: Int = 0
    private var isUserScrolling: Boolean = false
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
        
        // Debug log: assert initial pagination mode and window count
        AppLogger.d(
            "ReaderActivity",
            "[STARTUP_ASSERT] Initial paginationMode=${viewModel.paginationMode}, " +
                    "windowCount=${viewModel.windowCount.value}, " +
                    "totalPages=${viewModel.totalPages.value}, " +
                    "bookId=${viewModel.bookId}"
        )
        
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
                "RecyclerView.onTouch: action=$actionName(masked=$actionMasked) x=${event.x} y=${event.y} " +
                        "pointerCount=$pointerCount pointerIndex=$pointerIndex pointerId=$pointerId mode=$readerMode currentPage=${viewModel.currentPage.value}"
            )
            val result = gestureDetector.onTouchEvent(event)
            AppLogger.d(
                "ReaderActivity",
                "RecyclerView.onTouch RETURNED=$result for action=$actionName"
            )
            // Don't consume the event - let RecyclerView handle paging
            false
        }
        
        binding.contentScrollView.setOnTouchListener(scrollTouchListener)
        binding.pageRecyclerView.setOnTouchListener(pagerTouchListener)
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

        pagerAdapter = ReaderPagerAdapter(this, viewModel)
        
        // Set up RecyclerView with horizontal LinearLayoutManager and PagerSnapHelper
        layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        snapHelper = PagerSnapHelper()
        
        binding.pageRecyclerView.apply {
            this.layoutManager = this@ReaderActivity.layoutManager
            adapter = pagerAdapter
            // Attach snap helper for page snapping behavior (one page at a time)
            snapHelper.attachToRecyclerView(this)
            
            // [PAGINATION_DEBUG] Add layout listener to log RecyclerView measurements
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                val newWidth = right - left
                val newHeight = bottom - top
                val oldWidth = oldRight - oldLeft
                val oldHeight = oldBottom - oldTop
                
                if (newWidth != oldWidth || newHeight != oldHeight) {
                    AppLogger.d(
                        "ReaderActivity",
                        "[PAGINATION_DEBUG] RecyclerView layout changed: " +
                        "width=$newWidth (was $oldWidth), height=$newHeight (was $oldHeight), " +
                        "adapterItemCount=${pagerAdapter.itemCount}, windowCount=${viewModel.windowCount.value}"
                    )
                    
                    // Log if RecyclerView has been measured and is ready for pagination
                    if (newWidth > 0 && newHeight > 0) {
                        AppLogger.d(
                            "ReaderActivity",
                            "[PAGINATION_DEBUG] RecyclerView measured and ready: ${newWidth}x${newHeight}"
                        )
                    }
                }
            }
            
            // Set up scroll listener to detect page changes
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    val stateName = when (newState) {
                        RecyclerView.SCROLL_STATE_IDLE -> "IDLE"
                        RecyclerView.SCROLL_STATE_DRAGGING -> "DRAGGING"
                        RecyclerView.SCROLL_STATE_SETTLING -> "SETTLING"
                        else -> "UNKNOWN($newState)"
                    }
                    AppLogger.d(
                        "ReaderActivity",
                        "RecyclerView.onScrollStateChanged: state=$stateName currentWindow=${viewModel.currentWindowIndex.value} [PAGE_SCROLL_STATE]"
                    )
                    
                    isUserScrolling = newState == RecyclerView.SCROLL_STATE_DRAGGING
                    
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        // Get the current snapped position
                        val snapView = snapHelper.findSnapView(this@ReaderActivity.layoutManager)
                        val position = if (snapView != null) {
                            this@ReaderActivity.layoutManager.getPosition(snapView)
                        } else {
                            // Fallback: try to get position from first visible item
                            val firstVisible = this@ReaderActivity.layoutManager.findFirstVisibleItemPosition()
                            if (firstVisible != RecyclerView.NO_POSITION) firstVisible else currentPagerPosition
                        }
                        
                        if (position >= 0 && position != currentPagerPosition) {
                            val previousWindow = currentPagerPosition
                            currentPagerPosition = position
                            
                            AppLogger.d(
                                "ReaderActivity",
                                "RecyclerView.onPageSelected: position=$position (windowIndex) previousWindow=$previousWindow " +
                                        "mode=$readerMode paginationMode=${viewModel.paginationMode} [PAGE_CHANGE_FROM_RECYCLERVIEW]"
                            )
                            
                            if (readerMode == ReaderMode.PAGE && viewModel.currentWindowIndex.value != position) {
                                AppLogger.d(
                                    "ReaderActivity",
                                    "Updating ViewModel window: $previousWindow -> $position (triggered by RecyclerView gesture/animation) [WINDOW_SWITCH_REASON]"
                                )
                                viewModel.goToWindow(position)
                            }
                            controlsManager.onUserInteraction()
                        }
                    }
                }
            })
        }

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
            if (!fromUser) {
                return@addOnChangeListener
            }

            val target = value.toInt()
            when {
                shouldUseWebViewSlider() && usingWebViewSlider -> {
                    navigateToWebViewPage(target)
                }
                readerMode == ReaderMode.PAGE -> {
                    viewModel.goToPage(target)
                    setCurrentItem(target, true)
                }
                else -> viewModel.goToPage(target)
            }
            controlsManager.onUserInteraction()
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
                        AppLogger.d(
                            "ReaderActivity",
                            "[CONTENT_LOADED] page=${viewModel.currentPage.value} textLength=${pageContent.text.length} " +
                                    "hasHtml=${!pageContent.html.isNullOrBlank()} pendingTtsResume=$pendingTtsResume " +
                                    "autoContinueTts=$autoContinueTts"
                        )
                        currentPageText = pageContent.text
                        currentPageHtml = pageContent.html
                        sentenceBoundaries = buildSentenceBoundaries(pageContent.text)
                        AppLogger.d(
                            "ReaderActivity",
                            "[CONTENT_LOADED] Built ${sentenceBoundaries.size} sentence boundaries for page ${viewModel.currentPage.value}"
                        )
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
                            AppLogger.d(
                                "ReaderActivity",
                                "[TTS_RESUME_TRIGGERED] Conditions met: pendingTtsResume=$pendingTtsResume " +
                                        "autoContinueTts=$autoContinueTts textNotBlank=${currentPageText.isNotBlank()}. " +
                                        "Calling resumeTtsForCurrentPage()"
                            )
                            pendingTtsResume = false
                            resumeTtsForCurrentPage()
                        } else {
                            AppLogger.d(
                                "ReaderActivity",
                                "[TTS_RESUME_SKIPPED] Conditions not met: pendingTtsResume=$pendingTtsResume " +
                                        "autoContinueTts=$autoContinueTts textNotBlank=${currentPageText.isNotBlank()}"
                            )
                        }
                    }
                }
                
                launch {
                    viewModel.currentWindowIndex.collect { windowIndex ->
                        // Update RecyclerView position when window index changes
                        if (readerMode == ReaderMode.PAGE && currentPagerPosition != windowIndex) {
                            AppLogger.d("ReaderActivity", "Syncing RecyclerView to window index: $windowIndex")
                            setCurrentItem(windowIndex, false)
                        }
                    }
                }
                
                launch {
                    viewModel.currentPage.collect { page ->
                        updatePageIndicator(page)
                        // For chapter-based mode, sync RecyclerView with page
                        // For continuous mode, RecyclerView is synced via currentWindowIndex
                        if (readerMode == ReaderMode.PAGE && viewModel.paginationMode == PaginationMode.CHAPTER_BASED) {
                            if (currentPagerPosition != page) {
                                setCurrentItem(page, false)
                            }
                        }
                    }
                }
                
                launch {
                    viewModel.windowCount.collect { windowCount ->
                        // [PAGINATION_DEBUG] Enhanced logging for window count changes
                        val recyclerViewWidth = binding.pageRecyclerView.width
                        val recyclerViewMeasuredWidth = binding.pageRecyclerView.measuredWidth
                        val recyclerViewHeight = binding.pageRecyclerView.height
                        val isMeasured = recyclerViewWidth > 0 && recyclerViewHeight > 0
                        val adapterItemCountBefore = pagerAdapter.itemCount
                        val totalChapters = viewModel.tableOfContents.value.size
                        
                        AppLogger.d("ReaderActivity", 
                            "[PAGINATION_DEBUG] windowCount.collect triggered: " +
                            "windowCount=$windowCount, " +
                            "totalChapters=$totalChapters, " +
                            "chaptersPerWindow=${viewModel.chaptersPerWindow}, " +
                            "adapterItemCount=$adapterItemCountBefore, " +
                            "paginationMode=${viewModel.paginationMode}")
                        
                        AppLogger.d("ReaderActivity", 
                            "[PAGINATION_DEBUG] RecyclerView state: " +
                            "width=$recyclerViewWidth, measuredWidth=$recyclerViewMeasuredWidth, " +
                            "height=$recyclerViewHeight, isMeasured=$isMeasured")
                        
                        // Validate window count against expected calculation
                        if (totalChapters > 0 && viewModel.isContinuousMode) {
                            val expectedWindows = kotlin.math.ceil(totalChapters.toDouble() / viewModel.chaptersPerWindow).toInt()
                            if (windowCount != expectedWindows && windowCount > 0) {
                                AppLogger.e("ReaderActivity", 
                                    "[PAGINATION_DEBUG] WINDOW_COUNT_MISMATCH: " +
                                    "received=$windowCount, expected=$expectedWindows")
                            }
                        }
                        
                        // [FALLBACK] If zero windows, log warning but still notify adapter
                        if (windowCount == 0 && totalChapters > 0) {
                            AppLogger.e("ReaderActivity", 
                                "[PAGINATION_DEBUG] ERROR: windowCount=0 but totalChapters=$totalChapters - " +
                                "book may have failed to load or pagination failed")
                        }
                        
                        pagerAdapter.notifyDataSetChanged()
                        
                        // [PAGINATION_DEBUG] Log adapter state after notifyDataSetChanged
                        val adapterItemCountAfter = pagerAdapter.itemCount
                        AppLogger.d("ReaderActivity", 
                            "[PAGINATION_DEBUG] Adapter updated: " +
                            "itemCount=$adapterItemCountBefore->$adapterItemCountAfter, " +
                            "windowCount=$windowCount")
                        
                        pagerAdapter.logAdapterStateAfterUpdate("windowCount.collect")
                    }
                }
                
                launch {
                    viewModel.totalPages.collect { total ->
                        if (!usingWebViewSlider) {
                            val maxValue = (total - 1).coerceAtLeast(0)
                            val safeValueTo = maxValue.toFloat().coerceAtLeast(1f)

                            val currentValue = binding.pageSlider.value
                            if (safeValueTo < currentValue) {
                                binding.pageSlider.valueTo = currentValue.coerceAtLeast(safeValueTo)
                                binding.pageSlider.value = safeValueTo
                                binding.pageSlider.valueTo = safeValueTo
                            } else {
                                binding.pageSlider.valueTo = safeValueTo
                            }
                        }
                        
                        // Update page indicator when total pages changes (e.g., during window shifts)
                        // This ensures the display is always accurate and instant
                        updatePageIndicator(viewModel.currentPage.value)
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
                    viewModel.currentWebViewPage.collect { webViewPage ->
                        updateSliderForWebViewPage()
                    }
                }
                
                launch {
                    viewModel.totalWebViewPages.collect { totalWebViewPages ->
                        updateSliderForWebViewPage()
                    }
                }

            }
        }
    }
    
    private fun updatePageIndicator(page: Int) {
        val total = viewModel.totalPages.value
        val safeTotal = total.coerceAtLeast(0)
        val displayPage = if (safeTotal == 0) 0 else (page + 1).coerceAtMost(safeTotal)

        if (viewModel.paginationMode == PaginationMode.CONTINUOUS && safeTotal > 0) {
            lifecycleScope.launch {
                val location = viewModel.getPageLocation(page)
                val chapterNumber = (location?.chapterIndex ?: page) + 1
                val inPageNumber = (location?.inPageIndex ?: 0) + 1
                val chapterPageCount = if (location != null) {
                    viewModel.getChapterPageCount(location.chapterIndex)
                } else {
                    1
                }.coerceAtLeast(1)
                val percent = if (safeTotal == 0) 0 else ((displayPage.toFloat() / safeTotal) * 100f).toInt()

                binding.pageIndicator.text = getString(
                    R.string.reader_page_indicator_with_chapter,
                    chapterNumber,
                    inPageNumber,
                    chapterPageCount,
                    displayPage,
                    safeTotal,
                    percent
                )
            }
        } else {
            binding.pageIndicator.text = getString(R.string.reader_page_indicator, displayPage, safeTotal)
        }

        if (!usingWebViewSlider && binding.pageSlider.value != page.toFloat()) {
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
        binding.pageRecyclerView.setBackgroundColor(palette.backgroundColor)
        readerMode = settings.mode
        AppLogger.d(
            "ReaderActivity",
            "Reader settings applied: mode=${settings.mode}, paginationMode=${settings.paginationMode}, " +
                    "continuousStreaming=${settings.continuousStreamingEnabled} [SETTINGS_APPLIED]"
        )
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
            return
        }
        ChaptersBottomSheet.show(supportFragmentManager, chapters) { chapterIndex ->
            if (viewModel.paginationMode == PaginationMode.CONTINUOUS) {
                lifecycleScope.launch {
                    // Navigate to chapter and get the target global page
                    val targetGlobalPage = viewModel.navigateToChapter(chapterIndex) ?: return@launch
                    
                    // Calculate the window index using ViewModel's SlidingWindowManager for consistency
                    val windowIndex = viewModel.getWindowIndexForChapter(chapterIndex)
                    
                    AppLogger.d("ReaderActivity", "TOC navigation: chapterIndex=$chapterIndex -> windowIndex=$windowIndex, globalPage=$targetGlobalPage")
                    
                    // Update RecyclerView to show the correct window
                    setCurrentItem(windowIndex, true)
                }
            } else {
                // Chapter-based mode: window index equals chapter index
                viewModel.goToPage(chapterIndex)
                setCurrentItem(chapterIndex, true)
            }
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
        // In continuous mode, navigate windows; in chapter-based mode, navigate pages
        if (viewModel.paginationMode == PaginationMode.CONTINUOUS) {
            val currentWindow = viewModel.currentWindowIndex.value
            val nextWindow = currentWindow + 1
            AppLogger.d(
                "ReaderActivity",
                "navigateToNextPage (window mode): currentWindow=$currentWindow -> nextWindow=$nextWindow mode=$readerMode " +
                        "[WINDOW_SWITCH_REASON:USER_TAP_OR_BUTTON]"
            )
            val moved = viewModel.nextWindow()
            if (readerMode == ReaderMode.PAGE && moved) {
                AppLogger.d(
                    "ReaderActivity",
                    "Programmatically setting RecyclerView to window $nextWindow (user navigation) [PROGRAMMATIC_WINDOW_CHANGE]"
                )
                setCurrentItem(nextWindow, animated)
            }
        } else {
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
                    "Programmatically setting RecyclerView to page $nextIndex (user navigation) [PROGRAMMATIC_PAGE_CHANGE]"
                )
                setCurrentItem(nextIndex, animated)
            }
        }
    }

    internal fun navigateToPreviousPage(animated: Boolean = true) {
        // In continuous mode, navigate windows; in chapter-based mode, navigate pages
        if (viewModel.paginationMode == PaginationMode.CONTINUOUS) {
            val currentWindow = viewModel.currentWindowIndex.value
            val previousWindow = currentWindow - 1
            AppLogger.d(
                "ReaderActivity",
                "navigateToPreviousPage (window mode): currentWindow=$currentWindow -> previousWindow=$previousWindow mode=$readerMode " +
                        "[WINDOW_SWITCH_REASON:USER_TAP_OR_BUTTON]"
            )
            val moved = viewModel.previousWindow()
            if (readerMode == ReaderMode.PAGE && moved) {
                AppLogger.d(
                    "ReaderActivity",
                    "Programmatically setting RecyclerView to window $previousWindow (user navigation) [PROGRAMMATIC_WINDOW_CHANGE]"
                )
                setCurrentItem(previousWindow, animated)
            }
        } else {
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
                    "Programmatically setting RecyclerView to page $previousIndex (user navigation) [PROGRAMMATIC_PAGE_CHANGE]"
                )
                setCurrentItem(previousIndex, animated)
            }
        }
    }

    /**
     * Navigate to the previous chapter/window and jump to its last internal page.
     * Used when at the first page of current chapter/window and navigating backward.
     */
    internal fun navigateToPreviousChapterToLastPage(animated: Boolean = true) {
        if (viewModel.paginationMode == PaginationMode.CONTINUOUS) {
            val currentWindow = viewModel.currentWindowIndex.value
            val previousWindow = currentWindow - 1
            AppLogger.d(
                "ReaderActivity",
                "navigateToPreviousChapterToLastPage (window mode): currentWindow=$currentWindow -> previousWindow=$previousWindow mode=$readerMode " +
                        "[WINDOW_SWITCH_REASON:BACKWARD_WINDOW_NAVIGATION]"
            )
            val moved = viewModel.previousWindow()
            if (readerMode == ReaderMode.PAGE && moved) {
                // Set flag only after navigation succeeds to avoid race condition
                viewModel.setJumpToLastPageFlag()
                AppLogger.d(
                    "ReaderActivity",
                    "Programmatically setting RecyclerView to window $previousWindow with jump-to-last-page flag [PROGRAMMATIC_WINDOW_CHANGE]"
                )
                setCurrentItem(previousWindow, animated)
            }
        } else {
            val currentIndex = viewModel.currentPage.value
            val previousIndex = currentIndex - 1
            AppLogger.d(
                "ReaderActivity",
                "navigateToPreviousChapterToLastPage called: currentPage=$currentIndex -> previousPage=$previousIndex mode=$readerMode " +
                        "[PAGE_SWITCH_REASON:BACKWARD_CHAPTER_NAVIGATION]"
            )
            val moved = viewModel.previousChapterToLastPage()
            if (readerMode == ReaderMode.PAGE && moved) {
                AppLogger.d(
                    "ReaderActivity",
                    "Programmatically setting RecyclerView to page $previousIndex with jump-to-last-page flag [PROGRAMMATIC_PAGE_CHANGE]"
                )
                setCurrentItem(previousIndex, animated)
            }
        }
    }

    private fun handleTtsStatus(snapshot: TTSStatusSnapshot) {
        AppLogger.d(
            "ReaderActivity",
            "[TTS_STATUS_CHANGE] state=${snapshot.state} sentenceIndex=${snapshot.sentenceIndex} " +
                    "sentenceTotal=${snapshot.sentenceTotal} currentPage=${viewModel.currentPage.value} " +
                    "autoContinueTts=$autoContinueTts pendingTtsResume=$pendingTtsResume"
        )
        
        // Update button UI based on TTS state
        updateTtsButtonStates(snapshot.state)
        
        when (snapshot.state) {
            TTSPlaybackState.PLAYING -> {
                AppLogger.d(
                    "ReaderActivity",
                    "[TTS_STATE_PLAYING] Setting autoContinueTts=true, pendingTtsResume=false"
                )
                autoContinueTts = true
                pendingTtsResume = false
            }
            TTSPlaybackState.PAUSED -> {
                AppLogger.d(
                    "ReaderActivity",
                    "[TTS_STATE_PAUSED] Setting autoContinueTts=false, pendingTtsResume=false"
                )
                autoContinueTts = false
                pendingTtsResume = false
            }
            TTSPlaybackState.STOPPED -> {
                val reachedEnd = snapshot.sentenceTotal > 0 && snapshot.sentenceIndex >= snapshot.sentenceTotal
                AppLogger.d(
                    "ReaderActivity",
                    "[TTS_STATE_STOPPED] reachedEnd=$reachedEnd (sentenceIndex=${snapshot.sentenceIndex} >= sentenceTotal=${snapshot.sentenceTotal}) " +
                            "autoContinueTts=$autoContinueTts"
                )
                if (autoContinueTts && reachedEnd) {
                    AppLogger.d(
                        "ReaderActivity",
                        "[TTS_CHAPTER_ADVANCE] Attempting to advance to next chapter/page. Setting pendingTtsResume=true"
                    )
                    pendingTtsResume = true
                    val advanced = viewModel.nextPage()
                    if (!advanced) {
                        AppLogger.w(
                            "ReaderActivity",
                            "[TTS_CHAPTER_ADVANCE_FAILED] No more pages available. Setting autoContinueTts=false, pendingTtsResume=false"
                        )
                        autoContinueTts = false
                        pendingTtsResume = false
                    } else {
                        AppLogger.d(
                            "ReaderActivity",
                            "[TTS_CHAPTER_ADVANCE_SUCCESS] Successfully advanced to next page. Waiting for content to load and trigger resume."
                        )
                    }
                } else {
                    AppLogger.d(
                        "ReaderActivity",
                        "[TTS_STOPPED_NO_ADVANCE] Not advancing (autoContinueTts=$autoContinueTts reachedEnd=$reachedEnd). " +
                                "Setting autoContinueTts=false, pendingTtsResume=false"
                    )
                    autoContinueTts = false
                    pendingTtsResume = false
                }
            }
            TTSPlaybackState.IDLE -> {
                AppLogger.d(
                    "ReaderActivity",
                    "[TTS_STATE_IDLE] Setting autoContinueTts=false, pendingTtsResume=false"
                )
                autoContinueTts = false
                pendingTtsResume = false
            }
        }
    }

    private fun resumeTtsForCurrentPage() {
        AppLogger.d(
            "ReaderActivity",
            "[TTS_RESUME_REQUEST] Starting TTS for page ${viewModel.currentPage.value} with textLength=${currentPageText.length}"
        )
        if (currentPageText.isBlank()) {
            AppLogger.w(
                "ReaderActivity",
                "[TTS_RESUME_ABORTED] Current page text is blank, cannot resume TTS"
            )
            return
        }
        val configuration = TTSConfiguration(
            speed = ttsPreferences.speed,
            pitch = ttsPreferences.pitch,
            autoScroll = ttsPreferences.autoScroll,
            highlightSentence = ttsPreferences.highlightSentence,
            languageTag = ttsPreferences.languageTag
        )
        AppLogger.d(
            "ReaderActivity",
            "[TTS_RESUME_REQUEST] Configuration: speed=${configuration.speed} pitch=${configuration.pitch} " +
                    "autoScroll=${configuration.autoScroll} highlightSentence=${configuration.highlightSentence} " +
                    "languageTag=${configuration.languageTag}. Calling TTSService.start()"
        )
        TTSService.start(this, currentPageText, configuration)
    }

    private fun updateReaderModeUi() {
        binding.root.post {
            if (readerMode == ReaderMode.PAGE) {
                binding.contentScrollView.isVisible = false
                binding.pageRecyclerView.isVisible = true
                // RecyclerView touch handling is managed by gesture detection in fragments
                AppLogger.d("ReaderActivity", "RecyclerView visible for PAGE mode")
                viewModel.publishHighlight(viewModel.currentPage.value, currentHighlightRange)
            } else {
                // Switching to SCROLL mode
                binding.pageRecyclerView.isVisible = false
                binding.contentScrollView.isVisible = true
                currentHighlightRange?.let { applyScrollHighlight(it) }
                
                // Reset WebView page tracking and restore slider to chapter navigation
                viewModel.resetWebViewPageState()
                restoreSliderToChapterNavigation()
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
                    binding.ttsPlayPauseButton.contentDescription = getString(R.string.tts_action_play)
                    binding.ttsPlayPauseButton.setIconResource(R.drawable.ic_tts_play_24)
                    binding.ttsStopButton.isVisible = false
                }
                TTSPlaybackState.PLAYING -> {
                    binding.ttsPlayPauseButton.contentDescription = getString(R.string.tts_action_pause)
                    binding.ttsPlayPauseButton.setIconResource(R.drawable.ic_tts_pause_24)
                    binding.ttsStopButton.isVisible = true
                }
                TTSPlaybackState.PAUSED -> {
                    binding.ttsPlayPauseButton.contentDescription = getString(R.string.tts_action_play)
                    binding.ttsPlayPauseButton.setIconResource(R.drawable.ic_tts_play_24)
                    binding.ttsStopButton.isVisible = true
                }
            }
        }
    }
    
    /**
     * Update slider configuration and position based on WebView page state.
     * Called when WebView page count or current page changes.
     */
    private fun updateSliderForWebViewPage() {
        if (!shouldUseWebViewSlider()) {
            if (usingWebViewSlider) {
                restoreSliderToChapterNavigation()
            }
            return
        }

        val totalWebViewPages = viewModel.totalWebViewPages.value
        val currentWebViewPage = viewModel.currentWebViewPage.value

        if (totalWebViewPages <= 0) {
            if (usingWebViewSlider) {
                restoreSliderToChapterNavigation()
            }
            return
        }

        usingWebViewSlider = true

        val maxValue = (totalWebViewPages - 1).coerceAtLeast(0)
        val safeValueTo = maxValue.toFloat().coerceAtLeast(1f)
        val safeCurrentPage = currentWebViewPage.coerceIn(0, maxValue)

        val currentValue = binding.pageSlider.value
        if (safeValueTo < currentValue) {
            binding.pageSlider.valueTo = currentValue.coerceAtLeast(safeValueTo)
            binding.pageSlider.value = safeCurrentPage.toFloat()
            binding.pageSlider.valueTo = safeValueTo
        } else {
            if (binding.pageSlider.valueTo != safeValueTo) {
                binding.pageSlider.valueTo = safeValueTo
            }

            if (binding.pageSlider.value != safeCurrentPage.toFloat()) {
                binding.pageSlider.value = safeCurrentPage.toFloat()
            }
        }

        updatePageIndicatorForWebView(currentWebViewPage, totalWebViewPages)
    }
    
    /**
     * Update page indicator to show WebView page within current chapter.
     */
    private fun updatePageIndicatorForWebView(currentPage: Int, totalPages: Int) {
        val displayPage = (currentPage + 1).coerceAtMost(totalPages.coerceAtLeast(1))
        val safeTotal = totalPages.coerceAtLeast(1)
        binding.pageIndicator.text = getString(R.string.reader_page_indicator, displayPage, safeTotal)
    }
    
    /**
     * Navigate to a specific WebView page within the current chapter.
     * Called when user moves the slider in PAGE mode.
     */
    private fun navigateToWebViewPage(pageIndex: Int) {
        val currentPosition = viewModel.currentPage.value
        val fragTag = "f$currentPosition"
        val frag = supportFragmentManager.findFragmentByTag(fragTag) as? ReaderPageFragment
        
        if (frag != null) {
            AppLogger.d(
                "ReaderActivity",
                "Navigating to WebView page $pageIndex within chapter $currentPosition via slider"
            )
            // Get the WebView from the fragment and navigate
            lifecycleScope.launch {
                try {
                    // Use the bridge to navigate to the target page
                    val webView = frag.view?.findViewById<android.webkit.WebView>(
                        R.id.pageWebView
                    )
                    if (webView != null) {
                        WebViewPaginatorBridge.goToPage(webView, pageIndex, smooth = true)
                    }
                } catch (e: Exception) {
                    AppLogger.e(
                        "ReaderActivity",
                        "Error navigating to WebView page $pageIndex",
                        e
                    )
                }
            }
        }
    }
    
    /**
     * Restore slider to chapter-level navigation (used when switching to SCROLL mode).
     */
    private fun restoreSliderToChapterNavigation() {
        usingWebViewSlider = false
        val total = viewModel.totalPages.value
        val currentPage = viewModel.currentPage.value
        
        val maxValue = (total - 1).coerceAtLeast(0)
        val safeValueTo = maxValue.toFloat().coerceAtLeast(1f)
        
        // Restore slider range to chapter count
        if (binding.pageSlider.valueTo != safeValueTo) {
            binding.pageSlider.valueTo = safeValueTo
        }
        
        // Restore slider position to current chapter
        if (binding.pageSlider.value != currentPage.toFloat()) {
            binding.pageSlider.value = currentPage.toFloat()
        }
        
        // Restore page indicator to show chapter numbers
        updatePageIndicator(currentPage)
    }

    private fun shouldUseWebViewSlider(): Boolean {
        return readerMode == ReaderMode.PAGE && viewModel.paginationMode == PaginationMode.CHAPTER_BASED
    }
    
    /**
     * Set the current item in the RecyclerView with optional smooth scrolling.
     * Enables page-based navigation behavior.
     */
    private fun setCurrentItem(position: Int, smoothScroll: Boolean) {
        // Note: currentPagerPosition is updated in the OnScrollListener when scroll settles
        // to ensure consistency with actual scroll state
        if (smoothScroll) {
            binding.pageRecyclerView.smoothScrollToPosition(position)
        } else {
            layoutManager.scrollToPositionWithOffset(position, 0)
            // For non-smooth scrolls, update position immediately since there's no animation
            currentPagerPosition = position
        }
    }
    
    /**
     * Get the current item position in the RecyclerView.
     * Enables page position tracking for page-based scrolling.
     */
    private fun getCurrentItem(): Int {
        val snapView = snapHelper.findSnapView(layoutManager)
        return snapView?.let { layoutManager.getPosition(it) } ?: currentPagerPosition
    }
    
    override fun onDestroy() {
        // Clean up adapter fragments
        pagerAdapter.cleanUp()
        super.onDestroy()
    }

}

private val SENTENCE_REGEX = Regex("(?<=[.!?])\\s+")

