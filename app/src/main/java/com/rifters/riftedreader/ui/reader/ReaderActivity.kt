package com.rifters.riftedreader.ui.reader

import android.content.Intent
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
import com.rifters.riftedreader.ui.reader.conveyor.ConveyorBeltSystemViewModel
import com.rifters.riftedreader.ui.reader.conveyor.ConveyorDebugActivity
import com.rifters.riftedreader.ui.reader.conveyor.ConveyorPhase
import com.rifters.riftedreader.ui.tts.TTSControlsBottomSheet
import com.rifters.riftedreader.util.AppLogger
import com.rifters.riftedreader.util.BufferLogger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import com.rifters.riftedreader.BuildConfig

class ReaderActivity : AppCompatActivity(), ReaderPreferencesOwner {
    
    private lateinit var binding: ActivityReaderBinding
    private lateinit var viewModel: ReaderViewModel
    private lateinit var conveyorBeltSystem: ConveyorBeltSystemViewModel
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
    internal var readerMode: ReaderMode = ReaderMode.SCROLL
    private var autoContinueTts: Boolean = false
    private var pendingTtsResume: Boolean = false
    private var usingWebViewSlider: Boolean = false
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var snapHelper: PagerSnapHelper
    private var currentPagerPosition: Int = 0
    private var isUserScrolling: Boolean = false
    // Flag to prevent circular navigation updates during programmatic scrolls
    private var programmaticScrollInProgress: Boolean = false
    // Flag to track if initial buffer-to-UI sync has been performed
    private var initialBufferSyncCompleted: Boolean = false

    // Prevent duplicate end-of-book completion handling from repeated boundary events.
    private var endOfBookCloseInProgress: Boolean = false

    // Prevent window-skips when a key/tap arrives before the WebView paginator has initialized.
    // We queue a single navigation attempt and execute it once the paginator is ready.
    private var pendingPagedNavJob: Job? = null
    private var pendingPagedNavToken: Long = 0L

    private fun queuePagedNavigationUntilReady(windowId: Int, isNext: Boolean, source: String): Boolean {
        if (viewModel.paginationMode != PaginationMode.CONTINUOUS) return false
        if (readerMode != ReaderMode.PAGE) return false

        val fragTag = "w$windowId"
        val frag = supportFragmentManager.findFragmentByTag(fragTag) as? ReaderPageFragment

        // If the fragment is already ready, don't queue.
        if (frag != null && frag.isWebViewReady() && frag.isPaginatorInitialized()) return false

        pendingPagedNavJob?.cancel()
        pendingPagedNavToken += 1
        val token = pendingPagedNavToken

        AppLogger.d(
            "ReaderActivity",
            "[$source] Queueing navigation until paginator ready: windowId=$windowId isNext=$isNext token=$token [NAV_QUEUE]"
        )

        pendingPagedNavJob = lifecycleScope.launch {
            val deadlineMs = 1200L
            val pollMs = 50L
            val startMs = System.currentTimeMillis()

            while (System.currentTimeMillis() - startMs < deadlineMs) {
                // If a newer request came in, abandon this one.
                if (token != pendingPagedNavToken) return@launch

                // Only execute if we're still on the same active window.
                if (viewModel.currentWindowIndex.value != windowId) {
                    AppLogger.d(
                        "ReaderActivity",
                        "[$source] Aborting queued nav: activeWindow changed (expected=$windowId actual=${viewModel.currentWindowIndex.value}) token=$token [NAV_QUEUE]"
                    )
                    return@launch
                }

                val currentFrag = supportFragmentManager.findFragmentByTag(fragTag) as? ReaderPageFragment
                if (currentFrag != null && currentFrag.isWebViewReady() && currentFrag.isPaginatorInitialized()) {
                    val handled = currentFrag.handleHardwarePageKey(isNext = isNext)
                    AppLogger.d(
                        "ReaderActivity",
                        "[$source] Executed queued nav: handled=$handled windowId=$windowId isNext=$isNext token=$token [NAV_QUEUE]"
                    )
                    return@launch
                }

                delay(pollMs)
            }

            AppLogger.w(
                "ReaderActivity",
                "[$source] Dropping queued nav: paginator not ready after timeout windowId=$windowId isNext=$isNext token=$token [NAV_QUEUE_TIMEOUT]"
            )
        }

        // Consume the event so we don't fall back to window navigation.
        return true
    }

    private fun syncRecyclerViewToWindowId(windowId: Int, reason: String) {
        if (readerMode != ReaderMode.PAGE || viewModel.paginationMode != PaginationMode.CONTINUOUS) return

        val position = pagerAdapter.getPositionForWindowId(windowId)
        if (position >= 0) {
            if (currentPagerPosition != position) {
                AppLogger.d(
                    "ReaderActivity",
                    "Window=$windowId mapped to position=$position, syncing RecyclerView ($reason) [WINDOW_NAVIGATION]"
                )
                programmaticScrollInProgress = true
                setCurrentItem(position, false)
            }
        } else {
            // IMPORTANT: Do NOT fallback to slot 2/center here.
            // Buffer updates are async; a fallback can land on the wrong windowId (e.g., win1 request -> snaps to win2).
            AppLogger.w(
                "ReaderActivity",
                "Window=$windowId not found in adapter list; will retry after buffer update ($reason) [WINDOW_NOT_FOUND]"
            )
        }
    }
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
        if (BuildConfig.DEBUG) {
            // TEMP: force-enable window rendering debug tools in debug builds
            readerPreferences.updateSettings { settings ->
                settings.copy(debugWindowRenderingEnabled = true)
            }
        }
        if (parser == null) {
            AppLogger.e("ReaderActivity", "No parser found for book: $bookPath")
            finish()
            return
        }

        AppLogger.d("ReaderActivity", "Parser loaded: ${parser::class.simpleName}")
        
        val factory = ReaderViewModel.Factory(bookId, bookFile, parser, repository, readerPreferences)
        viewModel = ViewModelProvider(this, factory)[ReaderViewModel::class.java]
        
        // Instantiate and wire ConveyorBeltSystemViewModel for minimal paginator integration
        conveyorBeltSystem = ConveyorBeltSystemViewModel()
        viewModel.setConveyorBeltSystem(conveyorBeltSystem)
        
        // DIAGNOSTICS: Log ConveyorPrimary status at startup
        // Conveyor is now always enabled - minimal paginator is the only system
        AppLogger.d(
            "ReaderActivity",
            "[CONVEYOR_ACTIVE] CONVEYOR_PRIMARY=true - Conveyor is authoritative window manager (minimal paginator always enabled)"
        )
        
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

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                // In continuous PAGE mode, swipes should behave like hardware page keys:
                // advance within the current window; only at edges should windows change.
                if (readerMode != ReaderMode.PAGE || viewModel.paginationMode != PaginationMode.CONTINUOUS) {
                    return false
                }

                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y

                val absDx = kotlin.math.abs(dx)
                val absDy = kotlin.math.abs(dy)

                // Basic horizontal fling thresholds
                val minDistancePx = 64f
                val minVelocityPxPerSec = 800f
                if (absDx < minDistancePx || absDx < absDy || kotlin.math.abs(velocityX) < minVelocityPxPerSec) {
                    return false
                }

                // Fling left = next, fling right = previous
                val isNext = dx < 0

                val windowId = viewModel.currentWindowIndex.value
                val fragTag = "w$windowId"
                val frag = supportFragmentManager.findFragmentByTag(fragTag) as? ReaderPageFragment

                AppLogger.d(
                    "ReaderActivity",
                    "SWIPE_FLING: isNext=$isNext, windowId=$windowId, fragTag=$fragTag, fragFound=${frag != null} [SWIPE_AS_PAGE]"
                )

                val handled = frag?.handleHardwarePageKey(isNext = isNext) == true
                if (!handled) {
                    AppLogger.w(
                        "ReaderActivity",
                        "SWIPE_FLING not handled (paginator not ready or fragment missing). Blocking window scroll per spec. [SWIPE_AS_PAGE]"
                    )
                } else {
                    controlsManager.onUserInteraction()
                }

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

            // In continuous PAGE mode, consume touch so RecyclerView cannot scroll windows directly.
            // Navigation is driven by WebView paginator; edges trigger window changes.
            if (readerMode == ReaderMode.PAGE && viewModel.paginationMode == PaginationMode.CONTINUOUS) {
                true
            } else {
                // Don't consume the event - let RecyclerView handle paging
                false
            }
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
                R.id.debug_conveyor -> {
                    // Temporary debug button
                    startActivity(Intent(this, ConveyorDebugActivity::class.java))
                    true
                }
                else -> false
            }
        }

        pagerAdapter = ReaderPagerAdapter(this, viewModel)
        
        // NO LONGER register adapter with conveyor - we don't need invalidatePositionDueToBufferShift anymore
        // ListAdapter + DiffUtil handles all UI updates via submitList()
        
        // NOTE: Buffer changes are now observed in observeViewModel() and trigger submitList()
        
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
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    
                    // Conveyor-belt buffer shift logic
                    // Detect visible center position and trigger shifts
                    val lm = this@ReaderActivity.layoutManager
                    // Only shift the buffer on genuine user drags.
                    // Programmatic scrolls (edge navigation, TOC jumps) must not cause buffer shifts.
                    if (
                        viewModel.isConveyorPrimary &&
                        conveyorBeltSystem.phase.value == ConveyorPhase.STEADY &&
                        isUserScrolling &&
                        !programmaticScrollInProgress
                    ) {
                        val first = lm.findFirstVisibleItemPosition()
                        val last = lm.findLastVisibleItemPosition()
                        
                        if (first == RecyclerView.NO_POSITION) return
                        
                        // Calculate center position
                        val center = (first + last) / 2
                        
                        AppLogger.d(
                            "ReaderActivity",
                            "[BUFFER_SHIFT] Scroll detected: first=$first, last=$last, center=$center, " +
                            "offset=${conveyorBeltSystem.getOffset()}, buffer=${conveyorBeltSystem.buffer.value}"
                        )
                        
                        // Shift forward if center is at slots[3] or slots[4]
                        if (center >= 3) {
                            AppLogger.d("ReaderActivity", "[BUFFER_SHIFT] Triggering forward shift (center=$center)")
                            conveyorBeltSystem.shiftForward(1)
                        }
                        // Shift backward if center is at slots[1] or slots[0]
                        else if (center <= 1) {
                            AppLogger.d("ReaderActivity", "[BUFFER_SHIFT] Triggering backward shift (center=$center)")
                            conveyorBeltSystem.shiftBackward(1)
                        }
                    }
                }
                
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
                        // Clear programmatic scroll flag when scroll settles
                        val wasProgrammatic = programmaticScrollInProgress
                        programmaticScrollInProgress = false
                        
                        // Get the current snapped position
                        val snapView = snapHelper.findSnapView(this@ReaderActivity.layoutManager)
                        val position = if (snapView != null) {
                            this@ReaderActivity.layoutManager.getPosition(snapView)
                        } else {
                            // Fallback: try to get position from first visible item
                            val firstVisible = this@ReaderActivity.layoutManager.findFirstVisibleItemPosition()
                            if (firstVisible != RecyclerView.NO_POSITION) firstVisible else currentPagerPosition
                        }
                        
                        AppLogger.d(
                            "ReaderActivity",
                            "RecyclerView settled: position=$position, currentPagerPosition=$currentPagerPosition, " +
                            "wasProgrammatic=$wasProgrammatic, vmWindow=${viewModel.currentWindowIndex.value} [SCROLL_SETTLE]"
                        )

                        // In continuous mode, adapter positions are ephemeral (buffer shifts).
                        // Always map position -> windowId before updating the ViewModel.
                        val settledWindowId = pagerAdapter.getWindowIdAtPosition(position)
                        if (position >= 0 && settledWindowId == -1) {
                            AppLogger.w(
                                "ReaderActivity",
                                "[SCROLL_SETTLE] No windowId for settled position=$position; itemCount=${pagerAdapter.itemCount}"
                            )
                        }
                        
                        // Debug: Log window navigation coherence (if debug window rendering is enabled)
                        val debugSettings = viewModel.readerSettings.value
                        com.rifters.riftedreader.util.WindowRenderingDebug.logWindowNavigationCoherence(
                            tag = "ReaderActivity",
                            eventType = "SCROLL_SETTLE",
                            requestedWindow = if (wasProgrammatic) currentPagerPosition else null,
                            settledPosition = position,
                            viewModelWindowIndex = viewModel.currentWindowIndex.value,
                            isProgrammatic = wasProgrammatic,
                            additionalInfo = "paginationMode=${viewModel.paginationMode}",
                            enabled = debugSettings.debugWindowRenderingEnabled
                        )
                        
                        if (position >= 0 && position != currentPagerPosition) {
                            val previousWindow = currentPagerPosition
                            currentPagerPosition = position

                            val previousWindowId = pagerAdapter.getWindowIdAtPosition(previousWindow)
                            val currentWindowId = pagerAdapter.getWindowIdAtPosition(position)
                            
                            // Determine navigation direction for logging
                            val direction = if (position > previousWindow) "NEXT" else "PREV"
                            
                            AppLogger.d(
                                "ReaderActivity",
                                "WINDOW_ENTER: adapterPos=$position, windowId=$currentWindowId, previousAdapterPos=$previousWindow, previousWindowId=$previousWindowId, direction=$direction, " +
                                        "mode=${if (wasProgrammatic) "PROGRAMMATIC" else "USER_GESTURE"}, " +
                                        "paginationMode=${viewModel.paginationMode} [WINDOW_CHANGE]"
                            )
                            
                            // Only update ViewModel if position changed AND not during programmatic scroll
                            // (programmatic scrolls already updated ViewModel before scrolling)
                            if (
                                readerMode == ReaderMode.PAGE &&
                                currentWindowId != -1 &&
                                viewModel.currentWindowIndex.value != currentWindowId &&
                                !wasProgrammatic
                            ) {
                                AppLogger.d(
                                    "ReaderActivity",
                                    "Updating ViewModel window: $previousWindowId -> $currentWindowId (triggered by user gesture) [WINDOW_SWITCH_REASON]"
                                )
                                viewModel.goToWindow(currentWindowId)
                            } else if (wasProgrammatic) {
                                AppLogger.d(
                                    "ReaderActivity",
                                    "Skipping ViewModel update (programmatic scroll) - ViewModel already at window ${viewModel.currentWindowIndex.value} [SCROLL_GUARD]"
                                )
                            }
                            controlsManager.onUserInteraction()
                        }
                        
                        // Note: Window visibility tracking is now handled internally by ConveyorBeltSystemViewModel
                        // No need to manually notify via onWindowBecameVisible() as it has been removed
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
        
        // Sync RecyclerView to initial buffer window on startup
        // This ensures the visible window matches where WindowBufferManager is initialized
        // to prevent phase transition stalls and band sliding failures
        syncRecyclerViewToInitialBufferWindow()
    }
    
    /**
     * Sync RecyclerView to the initial buffer window on startup.
     * 
     * This ensures the visible window matches where WindowBufferManager is initialized,
     * preventing phase transition stalls ("not win5" bug) and band sliding failures.
     * 
     * Only performs the sync once at startup, for CONTINUOUS pagination mode.
     */
    private fun syncRecyclerViewToInitialBufferWindow() {
        AppLogger.d("ReaderActivity", "[BUFFER_SYNC] ENTRY: syncRecyclerViewToInitialBufferWindow() called")
        // Early exit: only sync for continuous mode
        // Check mode before launching coroutine to avoid unnecessary waiting
        if (viewModel.paginationMode != PaginationMode.CONTINUOUS) {
            AppLogger.d("ReaderActivity", "[BUFFER_SYNC] Skipping sync: not in CONTINUOUS mode")
            return
        }
        
        // Observer to sync RecyclerView once windowCount becomes available
        // Use first { } with timeout to properly terminate collection after finding valid window count
        lifecycleScope.launch {
            try {
                // TASK 2: CONVEYOR AUTHORITATIVE TAKEOVER - Deferred initial sync
                // When conveyor is primary, wait for conveyor readiness before syncing
                if (viewModel.isConveyorPrimary && viewModel.conveyorBeltSystem != null) {
                    AppLogger.d("ReaderActivity", "[CONVEYOR_ACTIVE] Waiting for conveyor readiness before initial sync")
                    
                    // Timeout after 10 seconds to avoid hanging indefinitely
                    withTimeout(10_000L) {
                        // Wait for both windowCount > 0 AND isInitialized to be true
                        viewModel.conveyorBeltSystem!!.windowCount.first { it > 0 }
                        viewModel.conveyorBeltSystem!!.isInitialized.first { it }
                    }
                    
                    AppLogger.d("ReaderActivity", "[CONVEYOR_ACTIVE] Conveyor ready: windowCount=${viewModel.conveyorBeltSystem!!.windowCount.value}")
                } else {
                    // LEGACY PATH: Wait for windowCount from ViewModel
                    AppLogger.d("ReaderActivity", "[LEGACY_ACTIVE] Waiting for window count before initial sync")
                    
                    // Timeout after 10 seconds to avoid hanging indefinitely
                    // Book parsing typically completes within seconds, 10s is generous for edge cases
                    withTimeout(10_000L) {
                        viewModel.windowCount.first { windowCount ->
                            // Wait for window count to be available
                            windowCount > 0
                        }
                    }
                }
                
                // Guard: double-check conditions before sync (mode could have changed)
                if (viewModel.paginationMode != PaginationMode.CONTINUOUS) return@launch
                if (initialBufferSyncCompleted) return@launch
                
                // Post to ensure RecyclerView is laid out and adapter has items
                // Note: performInitialBufferSync() has its own guard check for initialBufferSyncCompleted
                // to handle any race conditions from the post { } delay
                binding.pageRecyclerView.post {
                    performInitialBufferSync()
                }
            } catch (e: TimeoutCancellationException) {
                AppLogger.w("ReaderActivity", 
                    "[BUFFER_SYNC] Timed out waiting for window count - book may have failed to load")
            } catch (e: Exception) {
                AppLogger.e("ReaderActivity", "[BUFFER_SYNC] Error during initial sync", e)
            }
        }
    }
    
    /**
     * Perform the actual initial buffer sync.
     * 
     * Scrolls RecyclerView to the initial window index from ViewModel,
     * ensuring UI and buffer state are aligned on startup.
     */
    private fun performInitialBufferSync() {
        AppLogger.d("ReaderActivity", "[BUFFER_SYNC] ENTRY: performInitialBufferSync() called")
        // Guard: only sync once
        if (initialBufferSyncCompleted) {
            AppLogger.d("ReaderActivity", "[BUFFER_SYNC] EXIT: Sync already completed")
            return
        }
        
        // Guard: only for continuous mode
        if (viewModel.paginationMode != PaginationMode.CONTINUOUS) {
            AppLogger.d("ReaderActivity", "[BUFFER_SYNC] EXIT: Not in CONTINUOUS mode")
            return
        }
        
        val initialWindow = viewModel.currentWindowIndex.value
        val adapterItemCount = pagerAdapter.itemCount
        AppLogger.d("ReaderActivity", "[BUFFER_SYNC] Sync parameters: currentWindowIndex=$initialWindow, adapterItemCount=$adapterItemCount")
        
        // TASK 2: CONVEYOR AUTHORITATIVE TAKEOVER - Log when using conveyor
        if (viewModel.isConveyorPrimary && viewModel.conveyorBeltSystem != null) {
            AppLogger.d("ReaderActivity", "[CONVEYOR_ACTIVE] Scrolling to initialWindow=$initialWindow (conveyor buffer=${viewModel.conveyorBeltSystem!!.buffer.value})")
        }
        
        // Validate adapter has items
        if (adapterItemCount <= 0) {
            AppLogger.w(
                "ReaderActivity",
                "[BUFFER_SYNC] Deferred initial sync: adapter has no items yet " +
                "(windowCount=${viewModel.windowCount.value})"
            )
            return
        }
        
        // Validate initial window is within adapter bounds
        if (initialWindow < 0 || initialWindow >= adapterItemCount) {
            AppLogger.e(
                "ReaderActivity",
                "[BUFFER_SYNC] Initial window $initialWindow out of bounds " +
                "(adapterItemCount=$adapterItemCount) - defaulting to 0"
            )
            currentPagerPosition = 0
            // Scroll to position 0 to ensure consistency between tracked position and UI
            setCurrentItem(0, false)
            initialBufferSyncCompleted = true
            return
        }
        
        // Set position before scrolling for consistency
        currentPagerPosition = initialWindow
        
        AppLogger.d(
            "ReaderActivity",
            "[BUFFER_SYNC] Syncing RecyclerView to initial buffer window $initialWindow " +
            "(adapterItemCount=$adapterItemCount, paginationMode=${viewModel.paginationMode})"
        )
        
        // Perform the scroll (non-animated for instant positioning)
        AppLogger.d("ReaderActivity", "[BUFFER_SYNC] Scrolling to initial window: $initialWindow")
        setCurrentItem(initialWindow, false)
        
        // Note: ConveyorBeltSystemViewModel handles window visibility tracking internally
        // No manual notification needed - the conveyor system manages phase transitions automatically
        
        // Log buffer state for debugging
        logBufferSyncDiagnostics(initialWindow)
        
        // Mark sync as completed after all sync operations are done
        // This prevents race conditions with scroll listeners that check the flag
        initialBufferSyncCompleted = true
        AppLogger.d("ReaderActivity", "[BUFFER_SYNC] EXIT: Initial buffer sync completed")
    }
    
    /**
     * Log diagnostics after initial buffer sync for debugging phase transitions.
     * 
     * WindowBufferManager has been removed - ConveyorBeltSystemViewModel now handles
     * all buffer management internally.
     */
    private fun logBufferSyncDiagnostics(syncedWindow: Int) {
        AppLogger.d(
            "ReaderActivity",
            "[BUFFER_SYNC] Sync completed for window $syncedWindow. " +
            "ConveyorBeltSystemViewModel handles buffer management internally."
        )
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
                        // FIX #2: Gate CONTENT_LOADED on proper conditions
                        // Only process when: (1) text is non-empty AND
                        // (2) paginator is fully initialized AND  
                        // (3) WebView is ready
                        // CRITICAL: Look up fragment by windowId (w0-wN), not by position!
                        // The RecyclerView now uses windowId-based tags
                        // We need to find the fragment for the active window
                        val activeWindowId = viewModel.currentWindowIndex.value
                        val fragmentTag = "w$activeWindowId"
                        val fragmentRef = supportFragmentManager.findFragmentByTag(fragmentTag)
                        val fragment = (fragmentRef as? ReaderPageFragment)
                        val isWebViewReady = fragment?.isWebViewReady() ?: false
                        val isPaginatorInitialized = fragment?.isPaginatorInitialized() ?: false
                        val textNonEmpty = pageContent.text.isNotBlank()
                        
                        // Log all relevant states for debugging content loading
                        if (pageContent.text.isNotEmpty() || pageContent.html?.isNotBlank() == true) {
                            AppLogger.d(
                                "ReaderActivity",
                                "[CONTENT_LOADED] Checking gates: page=${viewModel.currentPage.value} " +
                                        "textLength=${pageContent.text.length} hasHtml=${!pageContent.html.isNullOrBlank()} " +
                                        "isWebViewReady=$isWebViewReady isPaginatorInit=$isPaginatorInitialized " +
                                        "textNonEmpty=$textNonEmpty pendingTtsResume=$pendingTtsResume"
                            )
                        }
                        
                        // Skip if conditions not met
                        if (!isWebViewReady || !isPaginatorInitialized || !textNonEmpty) {
                            if (pageContent.text.isNotBlank()) {
                                AppLogger.d(
                                    "ReaderActivity",
                                    "[CONTENT_LOADED] SKIPPED - gates not met: " +
                                    "webViewReady=$isWebViewReady paginatorInit=$isPaginatorInitialized textNonEmpty=$textNonEmpty"
                                )
                            }
                            return@collect
                        }
                        
                        // NOW emit [CONTENT_LOADED] - all gates passed
                        AppLogger.d(
                            "ReaderActivity",
                            "[CONTENT_LOADED] EMITTED - page=${viewModel.currentPage.value} textLength=${pageContent.text.length} " +
                                    "hasHtml=${!pageContent.html.isNullOrBlank()} All gates passed."
                        )
                        
                        // Buffer shift is now applied immediately in ConveyorBeltSystem,
                        // not deferred until CONTENT_LOADED. No need to apply pending shift here.
                        
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
                        syncRecyclerViewToWindowId(windowIndex, reason = "currentWindowIndex.collect")
                    }
                }
                
                // NEW: Observe conveyor buffer changes and submit to adapter
                launch {
                    viewModel.conveyorBeltSystem?.buffer?.collect { bufferWindowIds ->
                        AppLogger.d(
                            "ReaderActivity",
                            "[BUFFER_UPDATE] Submitting buffer to adapter: $bufferWindowIds"
                        )
                        pagerAdapter.submitList(bufferWindowIds) {
                            // Callback after list update completes
                            AppLogger.d(
                                "ReaderActivity",
                                "[BUFFER_UPDATE] Adapter list updated: itemCount=${pagerAdapter.itemCount}"
                            )

                            // Retry syncing to the currently requested window after the list updates.
                            syncRecyclerViewToWindowId(
                                viewModel.currentWindowIndex.value,
                                reason = "buffer.submitList.callback"
                            )
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
                        
                        // Use visible chapter count for window calculation validation
                        // This accounts for hidden chapters (cover, NAV, non-linear) based on visibility settings
                        val visibleChapterCount = viewModel.visibleChapterCount
                        val spineCount = viewModel.chapterIndexProvider.spineCount
                        
                        AppLogger.d("ReaderActivity", 
                            "[PAGINATION_DEBUG] windowCount.collect triggered: " +
                            "windowCount=$windowCount, " +
                            "visibleChapters=$visibleChapterCount, " +
                            "spineAll=$spineCount, " +
                            "chaptersPerWindow=${viewModel.chaptersPerWindow}, " +
                            "adapterItemCount=$adapterItemCountBefore, " +
                            "paginationMode=${viewModel.paginationMode}")
                        
                        AppLogger.d("ReaderActivity", 
                            "[PAGINATION_DEBUG] RecyclerView state: " +
                            "width=$recyclerViewWidth, measuredWidth=$recyclerViewMeasuredWidth, " +
                            "height=$recyclerViewHeight, isMeasured=$isMeasured")
                        
                        // Validate window count against expected calculation using visible chapters
                        // This uses visibleChapterCount to avoid WINDOW_COUNT_MISMATCH errors
                        if (visibleChapterCount > 0 && viewModel.isContinuousMode) {
                            val expectedWindows = kotlin.math.ceil(visibleChapterCount.toDouble() / viewModel.chaptersPerWindow).toInt()
                            if (windowCount != expectedWindows && windowCount > 0) {
                                AppLogger.e("ReaderActivity", 
                                    "[PAGINATION_DEBUG] WINDOW_COUNT_MISMATCH: " +
                                    "received=$windowCount, expected=$expectedWindows " +
                                    "(visibleChapters=$visibleChapterCount, spineAll=$spineCount)")
                            }
                        }
                        
                        // [FALLBACK] If zero windows, log warning
                        if (windowCount == 0 && visibleChapterCount > 0) {
                            AppLogger.e("ReaderActivity", 
                                "[PAGINATION_DEBUG] ERROR: windowCount=0 but visibleChapters=$visibleChapterCount - " +
                                "book may have failed to load or pagination failed")
                        }
                        
                        // NOTE: No need to call notifyDataSetChanged() anymore
                        // Buffer updates are handled via submitList() in the buffer observer
                        
                        // [PAGINATION_DEBUG] Log final state
                        val adapterItemCountAfter = pagerAdapter.itemCount
                        AppLogger.d("ReaderActivity", 
                            "[PAGINATION_DEBUG] Window count updated: " +
                            "windowCount=$windowCount, " +
                            "adapterItemCount=$adapterItemCountAfter")
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
        // Handle hardware volume keys for page navigation in PAGE mode
        // Also enabled for SCROLL mode when using CONTINUOUS pagination (window-based navigation)
        val enableVolumeKeys = readerMode == ReaderMode.PAGE || 
                              (readerMode == ReaderMode.SCROLL && viewModel.paginationMode == PaginationMode.CONTINUOUS)
        
        if (enableVolumeKeys) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    // Look up fragment by windowId (not position)
                    val windowId = if (viewModel.paginationMode == PaginationMode.CONTINUOUS) {
                        viewModel.currentWindowIndex.value
                    } else {
                        viewModel.currentPage.value
                    }
                    val fragTag = "w$windowId"
                    val frag = supportFragmentManager.findFragmentByTag(fragTag) as? ReaderPageFragment
                    
                    AppLogger.d(
                        "ReaderActivity",
                        "VOLUME_DOWN pressed: paginationMode=${viewModel.paginationMode}, windowId=$windowId, fragTag=$fragTag, fragFound=${frag != null} [HARDWARE_KEY_NAV]"
                    )

                    // Only delegate to the fragment in PAGE mode. In SCROLL mode we want window-based
                    // navigation and should not trigger in-window horizontal paging.
                    if (readerMode == ReaderMode.PAGE) {
                        if (frag?.handleHardwarePageKey(isNext = true) == true) {
                            return true
                        }

                        // In PAGE+CONTINUOUS, never fall back to window jumps when the paginator is not ready.
                        // Queue briefly and consume the key.
                        if (queuePagedNavigationUntilReady(windowId = windowId, isNext = true, source = "HARDWARE_KEY_NAV")) {
                            return true
                        }

                        AppLogger.w(
                            "ReaderActivity",
                            "VOLUME_DOWN ignored: fragment/paginator not ready; blocking fallback window jump (mode=PAGE) [HARDWARE_KEY_NAV]"
                        )
                        return true
                    }

                    // Fallback: navigate chapters/windows
                    AppLogger.d(
                        "ReaderActivity",
                        "VOLUME_DOWN: fragment did not handle, falling back to navigateToNextPage [HARDWARE_KEY_NAV]"
                    )
                    navigateToNextPage(animated = true)
                    // Return true to consume the event and prevent volume change
                    return true
                }
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                    // Look up fragment by windowId (not position)
                    val windowId = if (viewModel.paginationMode == PaginationMode.CONTINUOUS) {
                        viewModel.currentWindowIndex.value
                    } else {
                        viewModel.currentPage.value
                    }
                    val fragTag = "w$windowId"
                    val frag = supportFragmentManager.findFragmentByTag(fragTag) as? ReaderPageFragment
                    
                    AppLogger.d(
                        "ReaderActivity",
                        "VOLUME_UP pressed: paginationMode=${viewModel.paginationMode}, windowId=$windowId, fragTag=$fragTag, fragFound=${frag != null} [HARDWARE_KEY_NAV]"
                    )

                    if (readerMode == ReaderMode.PAGE) {
                        if (frag?.handleHardwarePageKey(isNext = false) == true) {
                            return true
                        }

                        if (queuePagedNavigationUntilReady(windowId = windowId, isNext = false, source = "HARDWARE_KEY_NAV")) {
                            return true
                        }

                        AppLogger.w(
                            "ReaderActivity",
                            "VOLUME_UP ignored: fragment/paginator not ready; blocking fallback window jump (mode=PAGE) [HARDWARE_KEY_NAV]"
                        )
                        return true
                    }

                    // Fallback: navigate chapters/windows
                    AppLogger.d(
                        "ReaderActivity",
                        "VOLUME_UP: fragment did not handle, falling back to navigateToPreviousPage [HARDWARE_KEY_NAV]"
                    )
                    navigateToPreviousPage(animated = true)
                    return true
                }
            }
        }
        // If volume keys not enabled for current mode, use default behavior
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
        // Use visibleTableOfContents to filter out hidden chapters based on visibility settings
        val chapters = viewModel.visibleTableOfContents
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
                // In PAGE mode with CONTINUOUS pagination, delegate to fragment for edge-aware navigation
                if (readerMode == ReaderMode.PAGE) {
                    val windowId = if (viewModel.paginationMode == PaginationMode.CONTINUOUS) {
                        viewModel.currentWindowIndex.value
                    } else {
                        viewModel.currentPage.value
                    }
                    val fragTag = "w$windowId"
                    val frag = supportFragmentManager.findFragmentByTag(fragTag) as? ReaderPageFragment
                    
                    AppLogger.d(
                        "ReaderActivity",
                        "TAP_ZONE NEXT_PAGE: paginationMode=${viewModel.paginationMode}, windowId=$windowId, fragTag=$fragTag, fragFound=${frag != null} [TAP_ZONE_NAV]"
                    )
                    
                    if (frag?.handleHardwarePageKey(isNext = true) == true) {
                        // Fragment will handle edge-aware navigation
                        controlsManager.onUserInteraction()
                        return
                    }

                    if (queuePagedNavigationUntilReady(windowId = windowId, isNext = true, source = "TAP_ZONE_NAV")) {
                        controlsManager.onUserInteraction()
                        return
                    }
                    
                    // Fallback: direct navigation
                    AppLogger.d(
                        "ReaderActivity",
                        "TAP_ZONE NEXT_PAGE: fragment did not handle, falling back to navigateToNextPage [TAP_ZONE_NAV]"
                    )
                }
                navigateToNextPage()
                controlsManager.onUserInteraction()
            }
            ReaderTapAction.PREVIOUS_PAGE -> {
                // In PAGE mode with CONTINUOUS pagination, delegate to fragment for edge-aware navigation
                if (readerMode == ReaderMode.PAGE) {
                    val windowId = if (viewModel.paginationMode == PaginationMode.CONTINUOUS) {
                        viewModel.currentWindowIndex.value
                    } else {
                        viewModel.currentPage.value
                    }
                    val fragTag = "w$windowId"
                    val frag = supportFragmentManager.findFragmentByTag(fragTag) as? ReaderPageFragment
                    
                    AppLogger.d(
                        "ReaderActivity",
                        "TAP_ZONE PREVIOUS_PAGE: paginationMode=${viewModel.paginationMode}, windowId=$windowId, fragTag=$fragTag, fragFound=${frag != null} [TAP_ZONE_NAV]"
                    )
                    
                    if (frag?.handleHardwarePageKey(isNext = false) == true) {
                        // Fragment will handle edge-aware navigation
                        controlsManager.onUserInteraction()
                        return
                    }

                    if (queuePagedNavigationUntilReady(windowId = windowId, isNext = false, source = "TAP_ZONE_NAV")) {
                        controlsManager.onUserInteraction()
                        return
                    }
                    
                    // Fallback: direct navigation
                    AppLogger.d(
                        "ReaderActivity",
                        "TAP_ZONE PREVIOUS_PAGE: fragment did not handle, falling back to navigateToPreviousPage [TAP_ZONE_NAV]"
                    )
                }
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
            val totalWindows = viewModel.windowCount.value
            val adapterItemCount = pagerAdapter.itemCount

            BufferLogger.log(
                event = "NAV_NEXT",
                message = "navigateToNextPage window mode",
                details = mapOf(
                    "paginationMode" to viewModel.paginationMode.name,
                    "readerMode" to readerMode.name,
                    "currentWindow" to currentWindow.toString(),
                    "nextWindow" to nextWindow.toString(),
                    "totalWindows" to totalWindows.toString(),
                    "adapterItemCount" to adapterItemCount.toString(),
                    "pid" to android.os.Process.myPid().toString()
                )
            )
            
            AppLogger.d(
                "ReaderActivity",
                "navigateToNextPage (window mode): currentWindow=$currentWindow -> nextWindow=$nextWindow, " +
                        "totalWindows=$totalWindows, adapterItemCount=$adapterItemCount, mode=$readerMode " +
                        "[WINDOW_SWITCH_REASON:USER_TAP_OR_BUTTON]"
            )
            
            // Validate target window is within bounds before attempting navigation
            if (nextWindow >= totalWindows) {
                // End-of-book: persist completion and close.
                if (!endOfBookCloseInProgress) {
                    endOfBookCloseInProgress = true
                    AppLogger.i(
                        "ReaderActivity",
                        "End of book reached: window=$currentWindow totalWindows=$totalWindows. Marking complete and closing. [BOOK_COMPLETE]"
                    )
                    BufferLogger.log(
                        level = "END",
                        event = "BOOK_COMPLETE",
                        message = "End of book reached; persisting completion and closing",
                        details = mapOf(
                            "currentWindow" to currentWindow.toString(),
                            "totalWindows" to totalWindows.toString(),
                            "pid" to android.os.Process.myPid().toString()
                        )
                    )
                    lifecycleScope.launch {
                        try {
                            viewModel.persistBookCompleted()
                        } catch (e: Exception) {
                            AppLogger.e("ReaderActivity", "Failed to persist completion at end-of-book", e)
                        } finally {
                            finish()
                        }
                    }
                } else {
                    AppLogger.d(
                        "ReaderActivity",
                        "End-of-book close already in progress; ignoring duplicate NEXT boundary. [BOOK_COMPLETE_DUP]"
                    )
                }
                return
            }
            
            val moved = viewModel.nextWindow()
            if (moved) {
                // RecyclerView syncing is handled centrally by currentWindowIndex collector + buffer submitList callback.
            } else if (!moved) {
                AppLogger.w(
                    "ReaderActivity",
                    "ViewModel.nextWindow() returned false - navigation blocked [NAV_FAILED]"
                )
            }
        } else {
            val currentIndex = viewModel.currentPage.value
            val nextIndex = currentIndex + 1

            BufferLogger.log(
                event = "NAV_NEXT",
                message = "navigateToNextPage page mode",
                details = mapOf(
                    "paginationMode" to viewModel.paginationMode.name,
                    "readerMode" to readerMode.name,
                    "currentPage" to currentIndex.toString(),
                    "nextPage" to nextIndex.toString(),
                    "pid" to android.os.Process.myPid().toString()
                )
            )

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
            val totalWindows = viewModel.windowCount.value
            val adapterItemCount = pagerAdapter.itemCount

            BufferLogger.log(
                event = "NAV_PREV",
                message = "navigateToPreviousPage window mode",
                details = mapOf(
                    "paginationMode" to viewModel.paginationMode.name,
                    "readerMode" to readerMode.name,
                    "currentWindow" to currentWindow.toString(),
                    "previousWindow" to previousWindow.toString(),
                    "totalWindows" to totalWindows.toString(),
                    "adapterItemCount" to adapterItemCount.toString(),
                    "pid" to android.os.Process.myPid().toString()
                )
            )
            
            AppLogger.d(
                "ReaderActivity",
                "navigateToPreviousPage (window mode): currentWindow=$currentWindow -> previousWindow=$previousWindow, " +
                        "totalWindows=$totalWindows, adapterItemCount=$adapterItemCount, mode=$readerMode " +
                        "[WINDOW_SWITCH_REASON:USER_TAP_OR_BUTTON]"
            )
            
            // Validate target window is within bounds before attempting navigation
            if (previousWindow < 0) {
                AppLogger.w(
                    "ReaderActivity",
                    "Cannot navigate to previous window: previousWindow=$previousWindow is negative [NAV_BLOCKED]"
                )
                return
            }
            
            val moved = viewModel.previousWindow()
            if (moved) {
                // RecyclerView syncing is handled centrally by currentWindowIndex collector + buffer submitList callback.
            } else if (!moved) {
                AppLogger.w(
                    "ReaderActivity",
                    "ViewModel.previousWindow() returned false - navigation blocked [NAV_FAILED]"
                )
            }
        } else {
            val currentIndex = viewModel.currentPage.value
            val previousIndex = currentIndex - 1

            BufferLogger.log(
                event = "NAV_PREV",
                message = "navigateToPreviousPage page mode",
                details = mapOf(
                    "paginationMode" to viewModel.paginationMode.name,
                    "readerMode" to readerMode.name,
                    "currentPage" to currentIndex.toString(),
                    "previousPage" to previousIndex.toString(),
                    "pid" to android.os.Process.myPid().toString()
                )
            )

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
            val totalWindows = viewModel.windowCount.value
            val adapterItemCount = pagerAdapter.itemCount
            
            AppLogger.d(
                "ReaderActivity",
                "navigateToPreviousChapterToLastPage (window mode): currentWindow=$currentWindow -> previousWindow=$previousWindow, " +
                        "totalWindows=$totalWindows, adapterItemCount=$adapterItemCount, mode=$readerMode " +
                        "[WINDOW_SWITCH_REASON:BACKWARD_WINDOW_NAVIGATION]"
            )
            
            // Validate target window is within bounds before attempting navigation
            if (previousWindow < 0) {
                AppLogger.w(
                    "ReaderActivity",
                    "Cannot navigate to previous window with jump-to-last: previousWindow=$previousWindow is negative [NAV_BLOCKED]"
                )
                return
            }
            
            val moved = viewModel.previousWindow()
            if (moved) {
                // In CONTINUOUS mode, always update RecyclerView position regardless of readerMode
                // Set flag only after navigation succeeds to avoid race condition
                viewModel.setJumpToLastPageFlag()
                // RecyclerView syncing is handled centrally by currentWindowIndex collector + buffer submitList callback.
            } else if (!moved) {
                AppLogger.w(
                    "ReaderActivity",
                    "ViewModel.previousWindow() returned false - backward navigation blocked [NAV_FAILED]"
                )
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
            // In CONTINUOUS pagination mode, always use RecyclerView (for sliding windows)
            // In CHAPTER_BASED mode, use RecyclerView in PAGE mode, ScrollView in SCROLL mode
            val useRecyclerView = viewModel.paginationMode == PaginationMode.CONTINUOUS || readerMode == ReaderMode.PAGE
            
            if (useRecyclerView) {
                binding.contentScrollView.isVisible = false
                binding.pageRecyclerView.isVisible = true
                //binding.pageRecyclerView.bringToFront()  // Ensure RecyclerView is on top
                // RecyclerView touch handling is managed by gesture detection in fragments
                AppLogger.d("ReaderActivity", "RecyclerView visible for paginationMode=${viewModel.paginationMode}, readerMode=$readerMode")
                viewModel.publishHighlight(viewModel.currentPage.value, currentHighlightRange)
            } else {
                // Switching to SCROLL mode with CHAPTER_BASED pagination
                binding.pageRecyclerView.isVisible = false
                binding.contentScrollView.isVisible = true
                //binding.contentScrollView.bringToFront()  // Ensure ScrollView is on top
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
                    // Use direct JavaScript to navigate to the target page
                    val webView = frag.view?.findViewById<android.webkit.WebView>(
                        R.id.pageWebView
                    )
                    if (webView != null) {
                        webView.evaluateJavascript(
                            "if (window.minimalPaginator && window.minimalPaginator.isReady()) { window.minimalPaginator.goToPage($pageIndex, true); }",
                            null
                        )
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
        
        val adapterItemCount = pagerAdapter.itemCount
        val recyclerViewWidth = binding.pageRecyclerView.width
        val recyclerViewHeight = binding.pageRecyclerView.height
        
        AppLogger.d(
            "ReaderActivity",
            "setCurrentItem: position=$position, smoothScroll=$smoothScroll, adapterItemCount=$adapterItemCount, " +
            "recyclerViewSize=${recyclerViewWidth}x${recyclerViewHeight}, " +
            "layoutManagerInitialized=${::layoutManager.isInitialized} [SCROLL_REQUEST]"
        )
        
        // Validate position is within adapter bounds
        if (position < 0 || position >= adapterItemCount) {
            AppLogger.e(
                "ReaderActivity",
                "setCurrentItem ABORTED: position=$position out of bounds (itemCount=$adapterItemCount) [SCROLL_ERROR]"
            )
            // Clear flag if set
            programmaticScrollInProgress = false
            return
        }
        
        // Warn if RecyclerView not measured yet
        if (recyclerViewWidth == 0 || recyclerViewHeight == 0) {
            AppLogger.w(
                "ReaderActivity",
                "setCurrentItem WARNING: RecyclerView not measured (${recyclerViewWidth}x${recyclerViewHeight}) - " +
                "scroll may not work correctly [SCROLL_WARNING]"
            )
        }
        
        if (smoothScroll) {
            AppLogger.d(
                "ReaderActivity",
                "Initiating smooth scroll to position $position [SMOOTH_SCROLL_START]"
            )
            binding.pageRecyclerView.smoothScrollToPosition(position)
        } else {
            AppLogger.d(
                "ReaderActivity",
                "Initiating instant scroll to position $position [INSTANT_SCROLL]"
            )
            layoutManager.scrollToPositionWithOffset(position, 0)
            // For non-smooth scrolls, update position immediately since there's no animation
            currentPagerPosition = position
            // Also clear programmatic flag immediately for instant scrolls
            programmaticScrollInProgress = false
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

