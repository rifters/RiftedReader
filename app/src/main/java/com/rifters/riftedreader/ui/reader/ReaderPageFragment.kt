package com.rifters.riftedreader.ui.reader

import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewAssetLoader
import com.rifters.riftedreader.R
import com.rifters.riftedreader.databinding.FragmentReaderPageBinding
import com.rifters.riftedreader.domain.pagination.PaginationMode
import com.rifters.riftedreader.domain.parser.PageContent
import com.rifters.riftedreader.util.EpubImageAssetHelper
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.abs

class ReaderPageFragment : Fragment() {

    private var _binding: FragmentReaderPageBinding? = null
    private val binding get() = _binding!!

    private val readerViewModel: ReaderViewModel by activityViewModels()

    /**
     * The window index (in continuous mode) or chapter index (in chapter-based mode).
     * This represents the position in the RecyclerView, which corresponds to:
     * - Continuous mode: window index (each window contains 5 chapters)
     * - Chapter-based mode: chapter index (each window contains 1 chapter)
     */
    private val windowIndex: Int by lazy {
        requireArguments().getInt(ARG_PAGE_INDEX)
    }
    
    // Alias for backward compatibility with existing code
    private val pageIndex: Int get() = windowIndex

    private var latestPageText: String = ""
    private var latestPageHtml: String? = null
    private var highlightedRange: IntRange? = null
    private var isWebViewReady = false
    private var targetInPageIndex: Int = 0
    private var pendingInitialInPageIndex: Int? = null
    private var resolvedChapterIndex: Int? = null
    private var skipNextBoundaryDirection: BoundaryDirection? = null
    private var streamingInFlightDirection: BoundaryDirection? = null
    private var lastStreamingFailureToastAt: Long = 0L
    private var lastStreamingErrorMessage: String? = null
    
    // Track current in-page position to preserve during reloads
    private var currentInPageIndex: Int = 0
    
    // Track window transitions to prevent inappropriate buffer shifts
    // When entering a new window at page 0 via forward navigation, we should NOT shift backward
    private var lastKnownWindowIndex: Int? = null
    private var windowTransitionTimestamp: Long = 0
    private val WINDOW_TRANSITION_COOLDOWN_MS = 300L
    
    // Track if paginator has completed initialization (onPaginationReady callback received)
    // This prevents navigation logic from using stale/default values before paginator is ready
    private var isPaginatorInitialized: Boolean = false
    
    // Track previous settings to detect what changed
    private var previousSettings: com.rifters.riftedreader.data.preferences.ReaderSettings? = null
    
    // WebViewAssetLoader for serving cached EPUB images via virtual HTTPS domain
    private var assetLoader: WebViewAssetLoader? = null
    
    // TTS chunk mapping for WebView highlighting
    private data class TtsChunk(val index: Int, val text: String, val startPosition: Int, val endPosition: Int)
    private var ttsChunks: List<TtsChunk> = emptyList()

    private enum class BoundaryDirection {
        NEXT,
        PREVIOUS;

        companion object {
            fun fromRaw(raw: String?): BoundaryDirection? {
                return values().firstOrNull { it.name.equals(raw ?: "", ignoreCase = true) }
            }
        }
    }
    
    // Scroll distance tracking for onScroll-based interception
    private var cumulativeScrollX: Float = 0f
    private var scrollIntercepted: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReaderPageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        resolvePageLocation()
        com.rifters.riftedreader.util.AppLogger.event("ReaderPageFragment", "onViewCreated for windowIndex $windowIndex", "ui/webview/lifecycle")
        
        // Set up WebViewAssetLoader for serving cached EPUB images
        // This allows images to load via virtual HTTPS domain instead of blocked file:// URLs
        val imageCacheRoot = readerViewModel.getImageCacheRoot()
        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(EpubImageAssetHelper.ASSET_HOST)
            .addPathHandler(
                EpubImageAssetHelper.EPUB_IMAGES_PATH,
                EpubImagePathHandler(imageCacheRoot)
            )
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(requireContext()))
            .build()
        
        // Log asset loader initialization for diagnostics
        com.rifters.riftedreader.util.AppLogger.d(
            "ReaderPageFragment",
            "[ASSET_LOADER_INIT] root=${imageCacheRoot.absolutePath} domain=${EpubImageAssetHelper.ASSET_HOST} prefix=${EpubImageAssetHelper.EPUB_IMAGES_PATH}"
        )
        
        // Configure WebView for EPUB rendering with column-based layout support
        binding.pageWebView.apply {
            settings.javaScriptEnabled = true
            // CRITICAL: useWideViewPort=true enables proper column width computation
            settings.useWideViewPort = true
            // CRITICAL: loadWithOverviewMode=false prevents automatic zooming that breaks column layout
            settings.loadWithOverviewMode = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            
            // Add JavaScript interface for TTS communication
            addJavascriptInterface(TtsWebBridge(), "AndroidTtsBridge")
            
            // Add JavaScript interface for logging from JavaScript
            addJavascriptInterface(com.rifters.riftedreader.util.AppLoggerBridge(), "AppLogger")
            
            // Pagination System: Uses minimal_paginator.js with PaginatorBridge
            // Legacy WebViewPaginatorBridge and PaginationBridge inner class have been removed
            // All pagination logic now flows through PaginatorBridge callbacks:
            //   - onPaginationReady: When JS paginator completes initialization
            //   - onBoundary: When user reaches window edge (triggers window navigation)
            val minimalPaginatorBridge = PaginatorBridge(
                windowIndex = windowIndex,
                onPaginationReady = { wIdx, totalPages ->
                    // Set paginator initialized flag
                    isPaginatorInitialized = true
                    com.rifters.riftedreader.util.AppLogger.d(
                        "ReaderPageFragment",
                        "[MIN_PAGINATOR] isPaginatorInitialized set to true for windowIndex=$wIdx"
                    )
                    // Forward to ViewModel for state updates and conveyor integration
                    readerViewModel.onWindowPaginationReady(wIdx, totalPages)
                },
                onBoundary = { wIdx, direction ->
                    handleMinimalPaginatorBoundary(wIdx, direction)
                }
            )
            addJavascriptInterface(minimalPaginatorBridge, "PaginatorBridge")
            com.rifters.riftedreader.util.AppLogger.d(
                "ReaderPageFragment",
                "[MIN_PAGINATOR] Registered PaginatorBridge for windowIndex=$windowIndex"
            )
            
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    // Let WebViewAssetLoader handle requests to the virtual HTTPS domain
                    if (request != null && view != null) {
                        val response = assetLoader?.shouldInterceptRequest(request.url)
                        if (response != null) {
                            com.rifters.riftedreader.util.AppLogger.d(
                                "ReaderPageFragment",
                                "[ASSET_LOADER] Intercepted request: ${request.url}"
                            )
                            return response
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
                
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // [PAGINATION_DEBUG] Enhanced logging for onPageFinished
                    val webViewWidth = view?.width ?: 0
                    val webViewHeight = view?.height ?: 0
                    com.rifters.riftedreader.util.AppLogger.d("ReaderPageFragment", 
                        "[PAGINATION_DEBUG] onPageFinished fired: windowIndex=$pageIndex, " +
                        "url=$url, webViewSize=${webViewWidth}x${webViewHeight}"
                    )
                    com.rifters.riftedreader.util.AppLogger.event("ReaderPageFragment", "WebView onPageFinished for page $pageIndex", "ui/webview/lifecycle")
                    isWebViewReady = true
                    
                    // Configure minimal paginator
                    val settings = readerViewModel.readerSettings.value
                    val mode = when (readerViewModel.paginationMode) {
                        PaginationMode.CONTINUOUS -> "window"
                        PaginationMode.CHAPTER_BASED -> "chapter"
                    }
                    binding.pageWebView.evaluateJavascript(
                        """
                        if (window.minimalPaginator) {
                            window.minimalPaginator.configure({
                                mode: '$mode',
                                windowIndex: $windowIndex
                            });
                        }
                        """.trimIndent(),
                        null
                    )
                    
                    // Call window.initPaginator() to initialize the minimal paginator
                    binding.pageWebView.evaluateJavascript(
                        "if (window.initPaginator) { window.initPaginator('#window-root'); }",
                        null
                    )
                    com.rifters.riftedreader.util.AppLogger.d(
                        "ReaderPageFragment",
                        "[MIN_PAGINATOR] Configured and initialized for windowIndex=$windowIndex, mode=$mode"
                    )
                    
                    // Set font size directly via JavaScript
                    com.rifters.riftedreader.util.AppLogger.d("ReaderPageFragment", "[PAGINATION_DEBUG] Setting font size via JS: ${settings.textSizeSp}px")
                    binding.pageWebView.evaluateJavascript(
                        "if (window.minimalPaginator) { window.minimalPaginator.setFontSize(${settings.textSizeSp.toInt()}); }",
                        null
                    )
                    
                    // Position restoration now handled by minimal_paginator.js automatically
                    applyPendingInitialInPage()
                    
                    // Initialize TTS chunks when page is loaded
                    prepareTtsChunks()
                    
                    // Check if we should jump to the last internal page (backward navigation)
                    if (readerViewModel.shouldJumpToLastPage.value) {
                        com.rifters.riftedreader.util.AppLogger.d(
                            "ReaderPageFragment",
                            "Detected shouldJumpToLastPage flag - will jump to last page after pagination"
                        )
                        // Clear the flag immediately to prevent re-triggering
                        readerViewModel.clearJumpToLastPageFlag()
                        
                        // Jump to last page via PaginatorBridge callback
                        // The minimal_paginator will call onPaginationReady when ready
                        // At that point we can jump to the last page
                        viewLifecycleOwner.lifecycleScope.launch {
                            kotlinx.coroutines.delay(200) // Wait for paginator initialization
                            try {
                                binding.pageWebView.evaluateJavascript(
                                    """
                                    (function() {
                                        if (window.minimalPaginator && window.minimalPaginator.isReady()) {
                                            var pageCount = window.minimalPaginator.getPageCount();
                                            if (pageCount > 0) {
                                                window.minimalPaginator.goToPage(pageCount - 1, false);
                                            }
                                        }
                                    })();
                                    """.trimIndent(),
                                    null
                                )
                                com.rifters.riftedreader.util.AppLogger.userAction(
                                    "ReaderPageFragment",
                                    "Jumping to last internal page of chapter $pageIndex after backward navigation",
                                    "ui/webview/pagination"
                                )
                            } catch (e: Exception) {
                                com.rifters.riftedreader.util.AppLogger.e(
                                    "ReaderPageFragment",
                                    "Error jumping to last page: ${e.message}",
                                    e
                                )
                            }
                        }
                    }
                }
                
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    // Log error but don't crash
                    android.util.Log.e("ReaderPageFragment", "WebView error: $description")
                    com.rifters.riftedreader.util.AppLogger.e("ReaderPageFragment", "WebView error: $description", Exception("WebView error code: $errorCode"))
                }
            }
            
            // Set up gesture detection for in-page horizontal swipes
            setupWebViewSwipeHandling()
        }
        
        // Separate content loading based on pagination mode
        if (readerViewModel.paginationMode == PaginationMode.CONTINUOUS) {
            // Continuous mode: directly render content from window buffer
            // Fragment creation → renderBaseContent() → getWindowHtml(windowIndex) → buffer → HTML → WebView
            com.rifters.riftedreader.util.AppLogger.d(
                "ReaderPageFragment",
                "[CONTENT_LOAD] Continuous mode: directly rendering window $windowIndex from buffer"
            )
            renderBaseContent()
        } else {
            // Chapter-based mode: use PageContent observables as before
            // Still uses _pages, pageContentCache, and observePageContent
            com.rifters.riftedreader.util.AppLogger.d(
                "ReaderPageFragment",
                "[CONTENT_LOAD] Chapter-based mode: setting up PageContent observer for chapter $windowIndex"
            )
            setupChapterBasedContentObserver()
        }

        // Set up highlight observer (used by both modes)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                readerViewModel.highlight.collect { highlight ->
                    if (highlight?.pageIndex == pageIndex) {
                        highlightedRange = highlight.range
                        applyHighlight(highlight.range)
                    } else if (highlightedRange != null && highlight?.pageIndex != pageIndex) {
                        highlightedRange = null
                        renderBaseContent()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                readerViewModel.readerSettings.collect { settings ->
                    // Always update TextView settings
                    binding.pageTextView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, settings.textSizeSp)
                    binding.pageTextView.setLineSpacing(0f, settings.lineHeightMultiplier)
                    
                    // Determine what changed
                    val themeChanged = previousSettings?.theme != settings.theme
                    val fontSizeChanged = previousSettings?.textSizeSp != settings.textSizeSp
                    val lineHeightChanged = previousSettings?.lineHeightMultiplier != settings.lineHeightMultiplier
                    val modeChanged = previousSettings?.mode != settings.mode
                    
                    // Log settings changes
                    if (previousSettings != null) {
                        val changes = mutableListOf<String>()
                        if (themeChanged) changes.add("theme=${settings.theme}")
                        if (fontSizeChanged) changes.add("fontSize=${settings.textSizeSp}px")
                        if (lineHeightChanged) changes.add("lineHeight=${settings.lineHeightMultiplier}")
                        if (modeChanged) changes.add("mode=${settings.mode}")
                        if (changes.isNotEmpty()) {
                            com.rifters.riftedreader.util.AppLogger.event(
                                "ReaderPageFragment", 
                                "Settings changed on page $pageIndex: ${changes.joinToString(", ")}", 
                                "ui/settings/change"
                            )
                        }
                    }
                    
                    // Update theme-related properties
                    val palette = ReaderThemePaletteResolver.resolve(requireContext(), settings.theme)
                    binding.root.setBackgroundColor(palette.backgroundColor)
                    binding.pageTextView.setTextColor(palette.textColor)
                    binding.pageWebView.setBackgroundColor(palette.backgroundColor)
                    
                    // Apply debug window background overlay if enabled
                    com.rifters.riftedreader.util.WindowRenderingDebug.applyWindowDebugBackground(
                        windowIndex = windowIndex,
                        rootView = binding.root,
                        enabled = settings.debugWindowRenderingEnabled
                    )
                    
                    // Handle WebView content updates based on what changed
                    if (latestPageText.isNotEmpty() || !latestPageHtml.isNullOrEmpty()) {
                        if (!latestPageHtml.isNullOrEmpty() && fontSizeChanged && !themeChanged && !lineHeightChanged) {
                            // For HTML content with font size change only, use paginator API
                            // This preserves reading position without reloading
                            com.rifters.riftedreader.util.AppLogger.d("ReaderPageFragment", "Applying font size change without reload")
                            if (isWebViewReady && binding.pageWebView.visibility == View.VISIBLE) {
                                // Apply font size change directly via JavaScript
                                // minimal_paginator.js handles position preservation automatically
                                viewLifecycleOwner.lifecycleScope.launch {
                                    try {
                                        com.rifters.riftedreader.util.AppLogger.d(
                                            "ReaderPageFragment",
                                            "Applying font size change via JS: ${settings.textSizeSp}px"
                                        )
                                        
                                        // setFontSize in minimal_paginator automatically preserves position via character offsets
                                        binding.pageWebView.evaluateJavascript(
                                            "if (window.minimalPaginator) { window.minimalPaginator.setFontSize(${settings.textSizeSp.toInt()}); }",
                                            null
                                        )
                                    } catch (e: Exception) {
                                        com.rifters.riftedreader.util.AppLogger.e(
                                            "ReaderPageFragment",
                                            "Error applying font size change via JS",
                                            e
                                        )
                                    }
                                }
                            }
                        } else if (themeChanged || lineHeightChanged) {
                            // Theme or line height change requires full reload to update styles
                            com.rifters.riftedreader.util.AppLogger.d("ReaderPageFragment", "Reloading content due to theme or line height change")
                            if (highlightedRange == null) {
                                renderBaseContent()
                            } else {
                                applyHighlight(highlightedRange)
                            }
                        }
                        // For plain text content, TextView handles updates automatically
                    }
                    
                    // Store current settings for next comparison
                    previousSettings = settings
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        com.rifters.riftedreader.util.AppLogger.d("ReaderPageFragment", "onDestroyView called for page $pageIndex - beginning WebView cleanup")
        
        // Properly clean up WebView to prevent memory leaks and crashes
        // Fix: Reset isWebViewReady FIRST to prevent any JavaScript execution during cleanup
        isWebViewReady = false
        isPaginatorInitialized = false
        pendingInitialInPageIndex = null
        skipNextBoundaryDirection = null
        streamingInFlightDirection = null
        
        try {
            binding.pageWebView.apply {
                // Stop any loading
                stopLoading()
                // Fix: Replace webViewClient BEFORE calling loadUrl to prevent onPageFinished callback
                // This prevents race condition where onPageFinished could trigger prepareTtsChunks
                webViewClient = WebViewClient()
                // Remove JavaScript interfaces to clean up
                removeJavascriptInterface("AndroidTtsBridge")
                removeJavascriptInterface("PaginatorBridge")
                // Call paginatorStop to cleanup JS state
                evaluateJavascript("if (window.paginatorStop) { window.paginatorStop(); }", null)
                // Load blank page to clear memory
                loadUrl("about:blank")
                // Clear history and cache
                clearHistory()
                clearCache(true)
                // Remove WebView from parent
                (parent as? ViewGroup)?.removeView(this)
                // Destroy the WebView
                destroy()
            }
            com.rifters.riftedreader.util.AppLogger.d("ReaderPageFragment", "WebView destroyed successfully for page $pageIndex")
        } catch (e: Exception) {
            com.rifters.riftedreader.util.AppLogger.e("ReaderPageFragment", "Exception during WebView destruction for page $pageIndex", e)
        }
        _binding = null
    }

    /**
     * Set up chapter-based content observer.
     * Used in chapter-based pagination mode where each fragment corresponds to a single chapter.
     * Still uses _pages, pageContentCache, and observePageContent.
     */
    private fun setupChapterBasedContentObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                readerViewModel.observePageContent(pageIndex).collect { page ->
                    // Skip empty pages in continuous mode (shouldn't happen here but safety check)
                    if (page == PageContent.EMPTY && readerViewModel.paginationMode == PaginationMode.CONTINUOUS) {
                        return@collect
                    }
                    
                    com.rifters.riftedreader.util.AppLogger.d(
                        "ReaderPageFragment",
                        "[CHAPTER_CONTENT] Received PageContent for chapter $pageIndex: text=${page.text.length} chars, hasHtml=${page.html != null}"
                    )
                    
                    latestPageText = page.text
                    latestPageHtml = page.html

                    // Clear chunks when content changes - they'll be rebuilt by prepareTtsChunks
                    ttsChunks = emptyList()
                    // Reset in-page position for new content (always 0 in chapter-based mode)
                    currentInPageIndex = 0
                    pendingInitialInPageIndex = null
                    // Reset paginator initialization flag for new chapter content
                    isPaginatorInitialized = false
                    
                    if (highlightedRange == null) {
                        renderBaseContent()
                    } else {
                        applyHighlight(highlightedRange)
                    }
                }
            }
        }
    }

    /**
     * Set up swipe gesture handling for in-page horizontal pagination.
     * This handles navigation within the current chapter/window.
     */
    private fun setupWebViewSwipeHandling() {
        com.rifters.riftedreader.util.AppLogger.d(
            "ReaderPageFragment", 
            "setupWebViewSwipeHandling() called for page $pageIndex"
        )
        
        val gestureDetector = GestureDetectorCompat(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                
                override fun onDown(e: MotionEvent): Boolean {
                    // CRITICAL: Return true so GestureDetector tracks the sequence
                    // Without this, onFling/onScroll won't be called
                    // Reset scroll tracking on each touch down
                    cumulativeScrollX = 0f
                    scrollIntercepted = false
                    
                    val settings = readerViewModel.readerSettings.value
                    com.rifters.riftedreader.util.AppLogger.d(
                        "ReaderPageFragment",
                        "onDown: page=$pageIndex x=${e.x} y=${e.y} mode=${settings.mode} isWebViewReady=$isWebViewReady visibility=${binding.pageWebView.visibility} [GESTURE_START]"
                    )
                    return true
                }
                
                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    com.rifters.riftedreader.util.AppLogger.d(
                        "ReaderPageFragment",
                        "onScroll: page=$pageIndex distanceX=$distanceX distanceY=$distanceY cumulative=$cumulativeScrollX"
                    )
                    
                    // Only handle if WebView is visible and ready AND paginator is initialized
                    if (!isWebViewReady || binding.pageWebView.visibility != View.VISIBLE || !isPaginatorInitialized) {
                        com.rifters.riftedreader.util.AppLogger.d(
                            "ReaderPageFragment", 
                            "onScroll ignored on page $pageIndex: isWebViewReady=$isWebViewReady, visibility=${binding.pageWebView.visibility}, isPaginatorInitialized=$isPaginatorInitialized"
                        )
                        return false
                    }
                    
                    // Check if this is primarily a horizontal scroll (not vertical)
                    if (abs(distanceX) > abs(distanceY)) {
                        // Accumulate horizontal scroll distance
                        // distanceX is positive when scrolling right-to-left (next page direction)
                        // distanceX is negative when scrolling left-to-right (prev page direction)
                        cumulativeScrollX += distanceX
                        
                        val viewportWidth = binding.pageWebView.width
                        val threshold = viewportWidth * SCROLL_DISTANCE_THRESHOLD_RATIO
                        val absCumulativeScrollX = abs(cumulativeScrollX)
                        
                        com.rifters.riftedreader.util.AppLogger.d(
                            "ReaderPageFragment",
                            "onScroll horizontal detected: page=$pageIndex cumulativeX=$cumulativeScrollX threshold=$threshold viewportWidth=$viewportWidth (${(absCumulativeScrollX / viewportWidth * 100).toInt()}% of viewport)"
                        )
                        
                        // If cumulative scroll exceeds threshold and we haven't intercepted yet
                        if (!scrollIntercepted && absCumulativeScrollX > threshold) {
                            com.rifters.riftedreader.util.AppLogger.d(
                                "ReaderPageFragment",
                                "Scroll threshold exceeded: page=$pageIndex cumulativeX=$cumulativeScrollX threshold=$threshold - attempting navigation"
                            )
                            
                            // Mark as intercepted to prevent multiple triggers
                            scrollIntercepted = true
                            
                            // Determine direction: positive cumulativeScrollX means next page
                            val isNext = cumulativeScrollX > 0
                            
                            // Use unified edge-aware navigation helper
                            viewLifecycleOwner.lifecycleScope.launch {
                                handlePagedNavigation(isNext, "SCROLL")
                            }
                            
                            return true // Consumed the scroll gesture
                        }
                    }
                    
                    return false
                }
                
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    val settings = readerViewModel.readerSettings.value
                    com.rifters.riftedreader.util.AppLogger.d(
                        "ReaderPageFragment",
                        "onFling: page=$pageIndex vx=$velocityX vy=$velocityY mode=${settings.mode}"
                    )
                    
                    // Only handle if WebView is visible and ready AND paginator is initialized
                    if (!isWebViewReady || binding.pageWebView.visibility != View.VISIBLE || !isPaginatorInitialized) {
                        com.rifters.riftedreader.util.AppLogger.d(
                            "ReaderPageFragment", 
                            "Fling ignored on page $pageIndex: isWebViewReady=$isWebViewReady, visibility=${binding.pageWebView.visibility}, isPaginatorInitialized=$isPaginatorInitialized"
                        )
                        return false
                    }
                    
                    // Check if this is a horizontal fling (not vertical scroll)
                    if (abs(velocityX) > abs(velocityY) && abs(velocityX) > FLING_THRESHOLD) {
                        com.rifters.riftedreader.util.AppLogger.d(
                            "ReaderPageFragment",
                            "Detected horizontal fling: page=$pageIndex vx=$velocityX (threshold=$FLING_THRESHOLD)"
                        )
                        
                        // Determine direction: negative velocityX means fling left (next page)
                        val isNext = velocityX < 0
                        
                        // Use unified edge-aware navigation helper
                        viewLifecycleOwner.lifecycleScope.launch {
                            handlePagedNavigation(isNext, "FLING")
                        }
                        
                        return true // Consumed the horizontal fling
                    }
                    
                    return false // Don't consume - let parent handle if not consumed
                }
            }
        )
        
        // Set touch listener on WebView to intercept swipes
        binding.pageWebView.setOnTouchListener { _, event ->
            val actionMasked = event.actionMasked
            val actionName = when (actionMasked) {
                MotionEvent.ACTION_DOWN -> "DOWN"
                MotionEvent.ACTION_MOVE -> "MOVE"
                MotionEvent.ACTION_UP -> {
                    // Reset scroll tracking on touch up
                    com.rifters.riftedreader.util.AppLogger.d(
                        "ReaderPageFragment",
                        "Touch UP: page=$pageIndex finalCumulativeX=$cumulativeScrollX intercepted=$scrollIntercepted [GESTURE_END]"
                    )
                    cumulativeScrollX = 0f
                    scrollIntercepted = false
                    "UP"
                }
                MotionEvent.ACTION_CANCEL -> {
                    // Reset scroll tracking on touch cancel
                    com.rifters.riftedreader.util.AppLogger.d(
                        "ReaderPageFragment",
                        "Touch CANCEL from Fragment.onTouch: page=$pageIndex finalCumulativeX=$cumulativeScrollX intercepted=$scrollIntercepted [GESTURE_CANCELLED_BY_PARENT]"
                    )
                    cumulativeScrollX = 0f
                    scrollIntercepted = false
                    "CANCEL"
                }
                MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
                MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
                else -> "OTHER(${event.actionMasked})"
            }
            
            // Get pointer information for multi-touch debugging
            val pointerCount = event.pointerCount
            val pointerIndex = event.actionIndex
            val pointerId = if (pointerCount > pointerIndex) event.getPointerId(pointerIndex) else -1
            
            // DEBUG-ONLY: Log all touch events including MOVE for gesture tracing
            com.rifters.riftedreader.util.AppLogger.d(
                "ReaderPageFragment",
                "DEBUG-ONLY: Fragment.onTouch: page=$pageIndex action=$actionName(masked=$actionMasked) " +
                        "x=${event.x} y=${event.y} pointerCount=$pointerCount pointerIndex=$pointerIndex pointerId=$pointerId"
            )
            
            val handled = gestureDetector.onTouchEvent(event)
            
            // DEBUG-ONLY: Log gesture detector result
            com.rifters.riftedreader.util.AppLogger.d(
                "ReaderPageFragment",
                "DEBUG-ONLY: Fragment.onTouch RETURNED=$handled for page=$pageIndex action=$actionName"
            )
            
            // Return the handled value - consume only if gesture detector handled it
            handled
        }
    }

    private fun resolvePageLocation() {
        if (readerViewModel.paginationMode == PaginationMode.CHAPTER_BASED) {
            resolvedChapterIndex = pageIndex
            targetInPageIndex = 0
            pendingInitialInPageIndex = null
            currentInPageIndex = 0
            return
        }

        // For CONTINUOUS mode, get window metadata which has the correct entry position
        viewLifecycleOwner.lifecycleScope.launch {
            val windowPayload = readerViewModel.getWindowHtml(windowIndex)
            if (windowPayload != null) {
                // Use the window's entry chapter and page (where reading should start in this window)
                val chapterIndex = windowPayload.chapterIndex
                val inPage = windowPayload.inPageIndex
                resolvedChapterIndex = chapterIndex
                targetInPageIndex = inPage
                pendingInitialInPageIndex = inPage.takeIf { it > 0 }
                currentInPageIndex = inPage
                com.rifters.riftedreader.util.AppLogger.d("ReaderPageFragment",
                    "[WINDOW_INIT] Window $windowIndex initialized: chapter=$chapterIndex, inPage=$inPage"
                )
                if (isWebViewReady) {
                    applyPendingInitialInPage()
                }
            } else {
                // Fallback if window payload unavailable
                com.rifters.riftedreader.util.AppLogger.w("ReaderPageFragment",
                    "[WINDOW_INIT] No window payload for window $windowIndex, using defaults"
                )
                resolvedChapterIndex = pageIndex
                targetInPageIndex = 0
                pendingInitialInPageIndex = null
                currentInPageIndex = 0
            }
        }
    }

    private fun applyPendingInitialInPage() {
        val target = pendingInitialInPageIndex ?: return
        if (_binding == null || !isWebViewReady) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                kotlinx.coroutines.delay(200) // Wait for paginator initialization
                binding.pageWebView.evaluateJavascript(
                    "if (window.minimalPaginator && window.minimalPaginator.isReady()) { window.minimalPaginator.goToPage($target, false); }",
                    null
                )
                currentInPageIndex = target
                pendingInitialInPageIndex = null
            } catch (e: Exception) {
                com.rifters.riftedreader.util.AppLogger.e(
                    "ReaderPageFragment",
                    "Error applying initial in-page index",
                    e
                )
            }
        }
    }

    // Deprecated: Chapter context managed by Conveyor Belt system
    // Phase 3 bridge handles pagination only, not chapters
    

    private fun applyHighlight(range: IntRange?) {
        if (_binding == null) return
        if (range == null) {
            // Clear all highlights
            clearTtsHighlights()
            highlightedRange = null
            return
        }
        
        val html = latestPageHtml
        if (!html.isNullOrBlank()) {
            // For HTML content, use JavaScript-based highlighting in WebView
            if (!isWebViewReady || binding.pageWebView.visibility != View.VISIBLE) {
                // WebView not ready yet, just render content and return
                renderBaseContent()
                return
            }
            
            // Find all chunks that overlap with the given range
            val chunksToHighlight = findChunksInRange(range)
            
            if (chunksToHighlight.isEmpty()) {
                // No chunks found, clear highlights
                clearTtsHighlights()
            } else {
                // Highlight all overlapping chunks using JavaScript
                highlightTtsChunks(chunksToHighlight)
            }
        } else {
            // Plain text highlighting using TextView
            binding.pageWebView.visibility = View.GONE
            binding.pageTextView.visibility = View.VISIBLE
            
            if (latestPageText.isBlank()) {
                binding.pageTextView.text = latestPageText
                return
            }
            if (range.first < 0 || range.first >= latestPageText.length) {
                binding.pageTextView.text = latestPageText
                return
            }
            val spannable = SpannableString(latestPageText)
            val endExclusive = (range.last + 1).coerceAtMost(spannable.length)
            val highlightColor = ContextCompat.getColor(requireContext(), R.color.reader_tts_highlight)
            spannable.setSpan(
                BackgroundColorSpan(highlightColor),
                range.first,
                endExclusive,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            binding.pageTextView.text = spannable
        }
    }
    
    /**
     * Find all TTS chunks that overlap with the given character range
     */
    private fun findChunksInRange(range: IntRange): List<Int> {
        return ttsChunks.filter { chunk ->
            // Check if chunk overlaps with the range using efficient range comparison
            val chunkRange = chunk.startPosition..chunk.endPosition
            chunkRange.first <= range.last && range.first <= chunkRange.last
        }.map { it.index }
    }
    
    /**
     * Highlight multiple TTS chunks in the WebView
     */
    private fun highlightTtsChunks(chunkIndices: List<Int>) {
        if (_binding == null) return
        if (!isWebViewReady || binding.pageWebView.visibility != View.VISIBLE) {
            return
        }
        
        // Build JavaScript selector for all chunks to highlight
        val selectors = chunkIndices.joinToString(", ") { "[data-tts-chunk=\"$it\"]" }
        
        binding.pageWebView.evaluateJavascript(
            """
            (function() {
                try {
                    // Clear all existing highlights
                    var allNodes = document.querySelectorAll('[data-tts-chunk]');
                    allNodes.forEach(function(node) {
                        node.classList.remove('tts-highlight');
                    });
                    
                    // Highlight selected chunks
                    var targets = document.querySelectorAll('$selectors');
                    targets.forEach(function(node) {
                        node.classList.add('tts-highlight');
                    });
                    
                    // Scroll to first highlighted chunk
                    if (targets.length > 0) {
                        targets[0].scrollIntoView({ behavior: 'smooth', block: 'center' });
                    }
                } catch(e) {
                    console.error('highlightTtsChunks error:', e);
                }
            })();
            """.trimIndent(),
            null
        )
    }

    private fun renderBaseContent() {
        if (_binding == null) return
        
        // In continuous mode, we don't rely on latestPageHtml - we fetch directly from buffer
        // In chapter-based mode, latestPageHtml is populated by observePageContent
        val html = if (readerViewModel.paginationMode == PaginationMode.CONTINUOUS) {
            // For continuous mode, we'll fetch from buffer later, so use a marker
            "CONTINUOUS_MODE_PLACEHOLDER"
        } else {
            latestPageHtml
        }
        
        // [PAGINATION_DEBUG] Log render request details
        com.rifters.riftedreader.util.AppLogger.d("ReaderPageFragment", 
            "[PAGINATION_DEBUG] renderBaseContent called: windowIndex=$windowIndex, " +
            "hasHtml=${!html.isNullOrBlank()}, htmlLength=${html?.length ?: 0}, " +
            "paginationMode=${readerViewModel.paginationMode}"
        )
        
        if (!html.isNullOrBlank()) {
            // Use WebView for rich HTML content (EPUB)
            binding.pageWebView.visibility = View.VISIBLE
            binding.pageTextView.visibility = View.GONE
            
            val settings = readerViewModel.readerSettings.value
            val palette = ReaderThemePaletteResolver.resolve(requireContext(), settings.theme)
            
            // In continuous mode with streaming enabled, use window HTML
            // Otherwise use the single chapter HTML as before
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val contentHtml = if (readerViewModel.paginationMode == PaginationMode.CONTINUOUS) {
                        // Get window HTML containing multiple chapters
                        // windowIndex is the RecyclerView position, directly used as window index
                        com.rifters.riftedreader.util.AppLogger.d("ReaderPageFragment", 
                            "[WINDOW_HTML] Requesting window HTML for windowIndex=$windowIndex [BEFORE_CALL]"
                        )
                        val windowPayload = readerViewModel.getWindowHtml(windowIndex)
                        com.rifters.riftedreader.util.AppLogger.d("ReaderPageFragment", 
                            "[WINDOW_HTML] Received window payload: windowIndex=$windowIndex, payload=${if (windowPayload != null) "NOT_NULL" else "NULL"} [AFTER_CALL]"
                        )
                        
                        // Debug: Log WebView state before loading HTML (if debug window rendering is enabled)
                        com.rifters.riftedreader.util.WindowRenderingDebug.logWebViewState(
                            tag = "ReaderPageFragment",
                            windowIndex = windowIndex,
                            webViewWidth = binding.pageWebView.width,
                            webViewHeight = binding.pageWebView.height,
                            webViewVisibility = binding.pageWebView.visibility,
                            webViewAlpha = binding.pageWebView.alpha,
                            htmlLength = windowPayload?.html?.length,
                            isPayloadNull = windowPayload == null,
                            enabled = settings.debugWindowRenderingEnabled
                        )
                        
                        if (windowPayload != null) {
                            com.rifters.riftedreader.util.AppLogger.d("ReaderPageFragment", 
                                "[PAGINATION_DEBUG] Using window HTML for windowIndex=$windowIndex: window=${windowPayload.windowIndex}, " +
                                "firstChapter=${windowPayload.chapterIndex} (${windowPayload.windowSize} chapters per window, totalChapters=${windowPayload.totalChapters}), " +
                                "htmlLength=${windowPayload.html.length}"
                            )
                            windowPayload.html
                        } else {
                            com.rifters.riftedreader.util.AppLogger.w("ReaderPageFragment", "[WINDOW_HTML] NULL PAYLOAD: Failed to get window HTML for windowIndex=$windowIndex, falling back to single chapter")
                            html
                        }
                    } else {
                        // Chapter-based mode: use single chapter HTML as before
                        // In this mode, windowIndex equals chapter index
                        com.rifters.riftedreader.util.AppLogger.d("ReaderPageFragment", 
                            "[PAGINATION_DEBUG] Using chapter HTML for chapterIndex=$windowIndex, htmlLength=${html.length}"
                        )
                        html
                    }
                    
                    // Prepare debug window info if enabled (for HTML debug banner)
                    val debugWindowInfo = if (settings.debugWindowRenderingEnabled && readerViewModel.paginationMode == PaginationMode.CONTINUOUS) {
                        val windowPayload = readerViewModel.getWindowHtml(windowIndex)
                        windowPayload?.let { payload ->
                            val firstChapter = payload.chapterIndex
                            val lastChapter = firstChapter + payload.windowSize - 1
                            com.rifters.riftedreader.domain.reader.DebugWindowInfo(
                                windowIndex = windowIndex,
                                firstChapterIndex = firstChapter,
                                lastChapterIndex = lastChapter.coerceAtMost(payload.totalChapters - 1)
                            )
                        }
                    } else if (settings.debugWindowRenderingEnabled) {
                        // Chapter-based mode: window = chapter
                        com.rifters.riftedreader.domain.reader.DebugWindowInfo(
                            windowIndex = windowIndex,
                            firstChapterIndex = windowIndex,
                            lastChapterIndex = windowIndex
                        )
                    } else {
                        null
                    }
                    
                    // Wrap HTML with proper styling using ReaderHtmlWrapper
                    val config = com.rifters.riftedreader.domain.reader.ReaderHtmlConfig(
                        textSizePx = settings.textSizeSp,
                        lineHeightMultiplier = settings.lineHeightMultiplier,
                        palette = palette,
                        enableDiagnostics = settings.paginationDiagnosticsEnabled,
                        debugWindowInfo = debugWindowInfo
                    )
                    val wrappedHtml = com.rifters.riftedreader.domain.reader.ReaderHtmlWrapper.wrap(contentHtml, config)
                    
                    // [PAGINATION_DEBUG] Log wrapped HTML size
                    com.rifters.riftedreader.util.AppLogger.d("ReaderPageFragment", 
                        "[PAGINATION_DEBUG] Wrapped HTML prepared: windowIndex=$windowIndex, wrappedLength=${wrappedHtml.length}, diagnostics=${settings.paginationDiagnosticsEnabled}"
                    )
                    
                    // Log the wrapped HTML for debugging
                    val chapterIndex = resolvedChapterIndex ?: windowIndex
                    readerViewModel.bookId?.let { bookId ->
                        com.rifters.riftedreader.util.HtmlDebugLogger.logWrappedHtml(
                            bookId = bookId,
                            chapterIndex = chapterIndex,
                            wrappedHtml = wrappedHtml,
                            metadata = mapOf(
                                "windowIndex" to windowIndex.toString(),
                                "textSize" to settings.textSizeSp.toString(),
                                "lineHeight" to settings.lineHeightMultiplier.toString(),
                                "theme" to settings.theme.name,
                                "paginationMode" to readerViewModel.paginationMode.name,
                                "diagnosticsEnabled" to settings.paginationDiagnosticsEnabled.toString()
                            )
                        )
                    }
                    
                    // Bug Fix 2: Reset isWebViewReady flag when loading new content to prevent race conditions
                    isWebViewReady = false
                    // Reset paginator initialization flag - will be set to true in onPaginationReady callback
                    isPaginatorInitialized = false
                    
                    // MEASUREMENT DISCIPLINE: Use doOnLayout to defer HTML loading until WebView is measured
                    ensureMeasuredAndLoadHtml(wrappedHtml)
                    
                } catch (e: Exception) {
                    com.rifters.riftedreader.util.AppLogger.e("ReaderPageFragment", "[PAGINATION_DEBUG] Error loading window HTML for windowIndex=$windowIndex, using fallback", e)
                    // Fallback to old method if there's any error
                    val wrappedHtml = wrapHtmlForWebView(html, settings.textSizeSp, settings.lineHeightMultiplier, palette)
                    isWebViewReady = false
                    isPaginatorInitialized = false
                    val baseUrl = "https://${EpubImageAssetHelper.ASSET_HOST}/"
                    binding.pageWebView.loadDataWithBaseURL(
                        baseUrl, 
                        wrappedHtml, 
                        "text/html", 
                        "UTF-8", 
                        null
                    )
                }
            }
        } else {
            // Use TextView for plain text content (TXT)
            com.rifters.riftedreader.util.AppLogger.d("ReaderPageFragment", 
                "[PAGINATION_DEBUG] Using TextView for plain text: windowIndex=$windowIndex, textLength=${latestPageText.length}"
            )
            binding.pageWebView.visibility = View.GONE
            binding.pageTextView.visibility = View.VISIBLE
            binding.pageTextView.text = latestPageText
        }
    }
    
    /**
     * Ensure WebView is measured (width/height > 0) before loading HTML.
     * Uses doOnLayout guard to defer loading until layout is complete.
     * This prevents column width computation issues caused by loading into unmeasured WebViews.
     */
    private fun ensureMeasuredAndLoadHtml(wrappedHtml: String) {
        if (_binding == null) return
        
        val webView = binding.pageWebView
        val webViewWidth = webView.width
        val webViewHeight = webView.height
        
        if (webViewWidth > 0 && webViewHeight > 0) {
            // WebView is already measured, load immediately
            com.rifters.riftedreader.util.AppLogger.d("ReaderPageFragment", 
                "[PAGINATION_DEBUG] WebView already measured: ${webViewWidth}x${webViewHeight}, loading HTML immediately"
            )
            loadHtmlIntoWebView(wrappedHtml)
        } else {
            // WebView not yet measured, defer loading until after layout
            com.rifters.riftedreader.util.AppLogger.d("ReaderPageFragment", 
                "[PAGINATION_DEBUG] WebView not yet measured (${webViewWidth}x${webViewHeight}), deferring HTML load"
            )
            webView.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    // Guard against fragment being destroyed or view being detached
                    if (_binding == null || !isAdded || view == null) {
                        try {
                            webView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        } catch (e: IllegalStateException) {
                            // ViewTreeObserver is not alive, ignore
                        }
                        return
                    }
                    
                    val newWidth = webView.width
                    val newHeight = webView.height
                    
                    if (newWidth > 0 && newHeight > 0) {
                        try {
                            webView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        } catch (e: IllegalStateException) {
                            // ViewTreeObserver is not alive, ignore
                        }
                        com.rifters.riftedreader.util.AppLogger.d("ReaderPageFragment", 
                            "[PAGINATION_DEBUG] WebView measured after layout: ${newWidth}x${newHeight}, loading HTML"
                        )
                        loadHtmlIntoWebView(wrappedHtml)
                    }
                }
            })
        }
    }
    
    /**
     * Load HTML content into WebView after measurement is confirmed.
     */
    private fun loadHtmlIntoWebView(wrappedHtml: String) {
        if (_binding == null) return
        
        // [PAGINATION_DEBUG] Log before WebView load
        com.rifters.riftedreader.util.AppLogger.d("ReaderPageFragment", 
            "[PAGINATION_DEBUG] Loading HTML into WebView: windowIndex=$windowIndex, isWebViewReady=false, " +
            "webViewSize=${binding.pageWebView.width}x${binding.pageWebView.height}"
        )
        
        // Use the asset loader's domain as base URL for consistent URL resolution
        // Images will use https://appassets.androidplatform.net/epub-images/... URLs
        // Script loading from file:///android_asset/ still works as it's an absolute path
        val baseUrl = "https://${EpubImageAssetHelper.ASSET_HOST}/"
        binding.pageWebView.loadDataWithBaseURL(
            baseUrl, 
            wrappedHtml, 
            "text/html", 
            "UTF-8", 
            null
        )
    }
    
    /**
     * Wrap HTML content with proper styling for WebView display
     */
    private fun wrapHtmlForWebView(
        content: String,
        textSize: Float,
        lineHeight: Float,
        palette: ReaderThemePalette
    ): String {
        val backgroundColor = String.format("#%06X", 0xFFFFFF and palette.backgroundColor)
        val textColor = String.format("#%06X", 0xFFFFFF and palette.textColor)
        
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8"/>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
                <style>
                    html, body {
                        margin: 0;
                        padding: 0;
                        background-color: $backgroundColor;
                        color: $textColor;
                        font-size: ${textSize}px;
                        line-height: $lineHeight;
                        font-family: serif;
                    }
                    body {
                        padding: 16px;
                        word-wrap: break-word;
                        overflow-wrap: break-word;
                    }
                    /* Preserve formatting for all block elements */
                    p, div, section, article {
                        margin: 0.8em 0;
                    }
                    h1, h2, h3, h4, h5, h6 {
                        margin: 1em 0 0.5em 0;
                        font-weight: bold;
                        line-height: 1.3;
                    }
                    h1 { font-size: 2em; }
                    h2 { font-size: 1.75em; }
                    h3 { font-size: 1.5em; }
                    h4 { font-size: 1.25em; }
                    h5 { font-size: 1.1em; }
                    h6 { font-size: 1em; }
                    blockquote {
                        margin: 1em 0;
                        padding-left: 1em;
                        border-left: 3px solid $textColor;
                        font-style: italic;
                    }
                    ul, ol {
                        margin: 0.5em 0;
                        padding-left: 2em;
                    }
                    li {
                        margin: 0.3em 0;
                    }
                    img {
                        max-width: 100% !important;
                        height: auto !important;
                        display: block;
                        margin: 1em auto;
                    }
                    pre, code {
                        font-family: monospace;
                        background-color: rgba(128, 128, 128, 0.1);
                        padding: 0.2em 0.4em;
                        border-radius: 3px;
                    }
                    pre {
                        padding: 1em;
                        overflow-x: auto;
                    }
                    /* TTS highlighting */
                    [data-tts-chunk] {
                        cursor: pointer;
                        transition: background-color 0.2s ease-in-out;
                    }
                    .tts-highlight {
                        background-color: rgba(255, 213, 79, 0.4) !important;
                    }
                </style>
                <script src="https://${EpubImageAssetHelper.ASSET_HOST}/assets/minimal_paginator.js"></script>
            </head>
            <body>
                $content
            </body>
            </html>
        """.trimIndent()
    }
    
    /**
     * Prepare TTS chunks by marking up content in WebView
     * This enables tap-to-position and highlighting functionality
     * 
     * ISSUE 2 FIX: Added waitForElement guard with retry logic to ensure #tts-root exists
     * before attempting to append to DOM nodes. Max 5 attempts with 50ms delay.
     */
    private fun prepareTtsChunks() {
        // Bug Fix 3: Check binding is not null before accessing WebView
        if (_binding == null) return
        if (!isWebViewReady || binding.pageWebView.visibility != View.VISIBLE) {
            return
        }
        
        // ISSUE 2 FIX: JavaScript with waitForElement guard and retry logic
        binding.pageWebView.evaluateJavascript(
            """
            (function() {
                // ISSUE 2 FIX: Wait for element with retry logic
                // Max 5 attempts with 50ms delay before executing TTS chunk preparation
                var MAX_ATTEMPTS = 5;
                var RETRY_DELAY_MS = 50;
                var attempt = 0;
                
                function waitForBodyAndExecute() {
                    attempt++;
                    
                    // Check if document.body exists - this is the target container for TTS operations
                    if (!document.body) {
                        if (attempt < MAX_ATTEMPTS) {
                            console.log('[TTS] prepareTtsChunks: document.body not ready, retrying (attempt ' + attempt + '/' + MAX_ATTEMPTS + ')');
                            setTimeout(waitForBodyAndExecute, RETRY_DELAY_MS);
                            return;
                        } else {
                            console.error('[TTS] prepareTtsChunks: document.body still null after ' + MAX_ATTEMPTS + ' attempts, aborting');
                            return;
                        }
                    }
                    
                    // Body is ready, proceed with TTS chunk preparation
                    try {
                        // Add TTS chunk style if not already present
                        var styleId = 'tts-chunk-style';
                        if (!document.getElementById(styleId)) {
                            var style = document.createElement('style');
                            style.id = styleId;
                            style.innerHTML = '[data-tts-chunk]{cursor:pointer;transition:background-color 0.2s ease-in-out;} .tts-highlight{background-color: rgba(255, 213, 79, 0.4) !important;}';
                            document.head.appendChild(style);
                        }
                        
                        // Clear any existing TTS markup
                        var existing = document.querySelectorAll('[data-tts-chunk]');
                        existing.forEach(function(node) {
                            node.classList.remove('tts-highlight');
                            node.removeAttribute('data-tts-chunk');
                        });
                        
                        // Mark up text blocks for TTS
                        var selectors = 'p, li, blockquote, h1, h2, h3, h4, h5, h6, pre, article, section';
                        var nodes = document.querySelectorAll(selectors);
                        var chunks = [];
                        var index = 0;
                        var currentPos = 0;
                        
                        nodes.forEach(function(node) {
                            if (!node) { return; }
                            var text = node.innerText || '';
                            text = text.replace(/\s+/g, ' ').trim();
                            if (!text) { return; }
                            
                            node.setAttribute('data-tts-chunk', index);
                            chunks.push({ 
                                index: index, 
                                text: text,
                                startPosition: currentPos
                            });
                            
                            currentPos += text.length + 1; // +1 for space between chunks
                            index++;
                        });
                        
                        // Attach click handler for tap-to-position
                        if (!window.__ttsTapHandlerAttached) {
                            document.addEventListener('click', function(event) {
                                var target = event.target.closest('[data-tts-chunk]');
                                if (!target) { return; }
                                var idx = parseInt(target.getAttribute('data-tts-chunk'));
                                if (isNaN(idx)) { return; }
                                if (window.AndroidTtsBridge && AndroidTtsBridge.onChunkTapped) {
                                    AndroidTtsBridge.onChunkTapped(idx);
                                }
                            }, false);
                            window.__ttsTapHandlerAttached = true;
                        }
                        
                        // Send chunks back to Android
                        if (window.AndroidTtsBridge && AndroidTtsBridge.onChunksPrepared) {
                            AndroidTtsBridge.onChunksPrepared(JSON.stringify(chunks));
                        }
                        
                        console.log('[TTS] prepareTtsChunks: completed with ' + chunks.length + ' chunks (attempt ' + attempt + ')');
                    } catch(e) {
                        console.error('[TTS] prepareTtsChunks error:', e);
                    }
                }
                
                // Start the wait-and-execute loop
                waitForBodyAndExecute();
            })();
            """.trimIndent(),
            null
        )
    }
    
    /**
     * Highlight a specific TTS chunk in the WebView
     */
    fun highlightTtsChunk(chunkIndex: Int, scrollToCenter: Boolean = false) {
        // Bug Fix 5: Check binding is not null before accessing WebView
        if (_binding == null) return
        if (!isWebViewReady || binding.pageWebView.visibility != View.VISIBLE) {
            return
        }
        
        val scrollCommand = if (scrollToCenter) {
            "target.scrollIntoView({ behavior: 'smooth', block: 'center' });"
        } else {
            ""
        }
        
        binding.pageWebView.evaluateJavascript(
            """
            (function() {
                try {
                    var nodes = document.querySelectorAll('[data-tts-chunk]');
                    nodes.forEach(function(node) {
                        node.classList.remove('tts-highlight');
                    });
                    var target = document.querySelector('[data-tts-chunk="$chunkIndex"]');
                    if (target) {
                        target.classList.add('tts-highlight');
                        $scrollCommand
                    }
                } catch(e) {
                    console.error('highlightTtsChunk error:', e);
                }
            })();
            """.trimIndent(),
            null
        )
    }
    
    /**
     * Remove all TTS highlights from the WebView
     */
    fun clearTtsHighlights() {
        // Bug Fix 6: Check binding is not null before accessing WebView
        if (_binding == null) return
        if (!isWebViewReady || binding.pageWebView.visibility != View.VISIBLE) {
            return
        }
        
        binding.pageWebView.evaluateJavascript(
            """
            (function() {
                try {
                    var nodes = document.querySelectorAll('[data-tts-chunk].tts-highlight');
                    nodes.forEach(function(node) {
                        node.classList.remove('tts-highlight');
                    });
                } catch(e) {
                    console.error('clearTtsHighlights error:', e);
                }
            })();
            """.trimIndent(),
            null
        )
    }

    /**
     * Handle boundary events from the minimal paginator.
     * Called when user attempts to navigate past window boundaries.
     * 
     * @param windowIndex The window index that triggered the boundary
     * @param direction The boundary direction ("NEXT" or "PREVIOUS")
     */
    private fun handleMinimalPaginatorBoundary(windowIndex: Int, direction: String) {
        com.rifters.riftedreader.util.AppLogger.d(
            "ReaderPageFragment",
            "[MIN_PAGINATOR] Boundary reached: windowIndex=$windowIndex, direction=$direction"
        )
        
        // TASK 4: CONVEYOR AUTHORITATIVE TAKEOVER - Forward boundary events to conveyor
        if (readerViewModel.isConveyorPrimary && readerViewModel.conveyorBeltSystem != null) {
            com.rifters.riftedreader.util.AppLogger.d(
                "ReaderPageFragment",
                "[CONVEYOR_ACTIVE] Boundary event forwarded to conveyor: window=$windowIndex direction=$direction"
            )
            // Note: The actual window navigation will trigger onWindowEntered via the scroll listener
            // This log serves as a diagnostic marker for boundary detection in conveyor mode
        }
        
        // Convert direction string to BoundaryDirection enum
        val boundaryDir = when (direction.uppercase()) {
            "NEXT" -> BoundaryDirection.NEXT
            "PREVIOUS" -> BoundaryDirection.PREVIOUS
            else -> {
                com.rifters.riftedreader.util.AppLogger.w(
                    "ReaderPageFragment",
                    "[MIN_PAGINATOR] Unknown boundary direction: $direction"
                )
                return
            }
        }
        
        // Forward to existing boundary handling logic
        // This reuses the navigation code that the old paginator used
        val readerActivity = activity as? ReaderActivity ?: return
        when (boundaryDir) {
            BoundaryDirection.NEXT -> {
                com.rifters.riftedreader.util.AppLogger.d(
                    "ReaderPageFragment",
                    "[MIN_PAGINATOR] Navigating to next page from windowIndex=$windowIndex"
                )
                readerActivity.navigateToNextPage(animated = true)
            }
            BoundaryDirection.PREVIOUS -> {
                com.rifters.riftedreader.util.AppLogger.d(
                    "ReaderPageFragment",
                    "[MIN_PAGINATOR] Navigating to previous page from windowIndex=$windowIndex"
                )
                if (readerViewModel.paginationMode == PaginationMode.CONTINUOUS) {
                    readerActivity.navigateToPreviousPage(animated = true)
                } else {
                    readerActivity.navigateToPreviousChapterToLastPage(animated = true)
                }
            }
        }
    }

    private fun handleChapterBoundary(direction: BoundaryDirection, boundaryPage: Int, totalPages: Int) {
        val readerActivity = activity as? ReaderActivity ?: return
        when (direction) {
            BoundaryDirection.NEXT -> {
                com.rifters.riftedreader.util.AppLogger.d(
                    "ReaderPageFragment",
                    "Boundary NEXT reached on page=$pageIndex (webViewPage=$boundaryPage/$totalPages)"
                )
                readerActivity.navigateToNextPage(animated = true)
            }
            BoundaryDirection.PREVIOUS -> {
                com.rifters.riftedreader.util.AppLogger.d(
                    "ReaderPageFragment",
                    "Boundary PREVIOUS reached on page=$pageIndex (webViewPage=$boundaryPage/$totalPages)"
                )
                if (readerViewModel.paginationMode == PaginationMode.CONTINUOUS) {
                    readerActivity.navigateToPreviousPage(animated = true)
                } else {
                    readerActivity.navigateToPreviousChapterToLastPage(animated = true)
                }
            }
        }
    }

    /**
     * Handle a streaming request from the WebView paginator when a boundary is reached.
     * 
     * This method is called when the user reaches the edge of the current window and
     * the WebView paginator requests streaming of adjacent chapter content.
     * 
     * In continuous pagination mode, streaming allows seamless navigation across chapter
     * boundaries within a single WebView instance, reducing ViewPager/RecyclerView churn.
     * 
     * Note: The `continuousStreamingEnabled` setting is currently checked via the pagination
     * mode. When `paginationMode == CONTINUOUS`, streaming is implicitly enabled.
     * 
     * @param direction The direction of the boundary (NEXT or PREVIOUS)
     * @param boundaryPage The page number at the boundary
     * @param totalPages Total pages in the current window
     */
    private fun handleStreamingRequest(direction: BoundaryDirection, boundaryPage: Int, totalPages: Int) {
        // [PAGINATION_DEBUG] Log streaming request details
        com.rifters.riftedreader.util.AppLogger.d(
            "ReaderPageFragment",
            "[PAGINATION_DEBUG] handleStreamingRequest: direction=$direction, boundaryPage=$boundaryPage/$totalPages, " +
            "windowIndex=$pageIndex, paginationMode=${readerViewModel.paginationMode}"
        )
        
        if (readerViewModel.paginationMode != PaginationMode.CONTINUOUS) {
            com.rifters.riftedreader.util.AppLogger.d(
                "ReaderPageFragment",
                "[PAGINATION_DEBUG] Not in CONTINUOUS mode - falling back to chapter boundary handling"
            )
            readerViewModel.updateWebViewPageState(boundaryPage, totalPages)
            handleChapterBoundary(direction, boundaryPage, totalPages)
            return
        }

        if (streamingInFlightDirection != null) {
            com.rifters.riftedreader.util.AppLogger.d(
                "ReaderPageFragment",
                "Streaming already in flight for direction=$streamingInFlightDirection - ignoring new request"
            )
            return
        }

        val totalPagesValue = readerViewModel.totalPages.value
        if (totalPagesValue <= 0) {
            readerViewModel.updateWebViewPageState(boundaryPage, totalPages)
            handleChapterBoundary(direction, boundaryPage, totalPages)
            return
        }

        val targetGlobalIndex = when (direction) {
            BoundaryDirection.NEXT -> pageIndex + 1
            BoundaryDirection.PREVIOUS -> pageIndex - 1
        }

        if (targetGlobalIndex !in 0 until totalPagesValue) {
            com.rifters.riftedreader.util.AppLogger.d(
                "ReaderPageFragment",
                "Streaming target out of range: target=$targetGlobalIndex total=$totalPagesValue"
            )
            readerViewModel.updateWebViewPageState(boundaryPage, totalPages)
            handleChapterBoundary(direction, boundaryPage, totalPages)
            return
        }

        skipNextBoundaryDirection = direction
        streamingInFlightDirection = direction
        lastStreamingErrorMessage = null
        viewLifecycleOwner.lifecycleScope.launch {
            com.rifters.riftedreader.util.AppLogger.event(
                "ReaderPageFragment",
                "[STREAM_START] direction=$direction target=$targetGlobalIndex page=$pageIndex",
                "ui/webview/streaming"
            )
            val sessionStartMs = SystemClock.elapsedRealtime()
            val maxAttempts = 2
            var attempt = 0
            var result: StreamingAttemptResult? = null
            while (attempt < maxAttempts && result == null) {
                result = streamChapter(direction, targetGlobalIndex, attempt + 1)
                if (result == null && attempt + 1 < maxAttempts) {
                    kotlinx.coroutines.delay(120)
                }
                attempt++
            }
            streamingInFlightDirection = null
            val durationMs = SystemClock.elapsedRealtime() - sessionStartMs
            if (result != null) {
                com.rifters.riftedreader.util.AppLogger.d(
                    "ReaderPageFragment",
                    "Streaming success for direction=$direction target=$targetGlobalIndex attempts=$attempt pages=${result.measuredPages} duration=${durationMs}ms"
                )
                com.rifters.riftedreader.util.AppLogger.event(
                    "ReaderPageFragment",
                    "[STREAM_SUCCESS] direction=$direction target=$targetGlobalIndex attempts=$attempt durationMs=$durationMs chapter=${result.chapterIndex} pages=${result.measuredPages}",
                    "ui/webview/streaming"
                )
            } else {
                com.rifters.riftedreader.util.AppLogger.d(
                    "ReaderPageFragment",
                    "Streaming failed for direction=$direction after $attempt attempts (duration=${durationMs}ms) reason=${lastStreamingErrorMessage ?: "unknown"}"
                )
                com.rifters.riftedreader.util.AppLogger.event(
                    "ReaderPageFragment",
                    "[STREAM_FAIL] direction=$direction target=$targetGlobalIndex attempts=$attempt durationMs=$durationMs reason=${lastStreamingErrorMessage ?: "unknown"}",
                    "ui/webview/streaming"
                )
                showStreamingFailureToast()
                skipNextBoundaryDirection = null
                readerViewModel.updateWebViewPageState(boundaryPage, totalPages)
                handleChapterBoundary(direction, boundaryPage, totalPages)
            }
        }
    }

    private suspend fun streamChapter(direction: BoundaryDirection, targetGlobalIndex: Int, attemptNumber: Int): StreamingAttemptResult? {
        if (_binding == null || !isWebViewReady) {
            lastStreamingErrorMessage = "webview_not_ready"
            return null
        }
        return try {
            val payload = readerViewModel.getStreamingChapterPayload(targetGlobalIndex) ?: run {
                lastStreamingErrorMessage = "empty_payload"
                return null
            }
            val html = payload.html
            if (html.isBlank()) {
                lastStreamingErrorMessage = "blank_html"
                return null
            }
            
            // Phase 3: Chapter streaming diagnostics now handled by Conveyor Belt
            // Log at this level for high-level navigation events
            com.rifters.riftedreader.util.AppLogger.event(
                "ReaderPageFragment",
                "[STREAMING] Attempt #$attemptNumber: ${direction.name} chapter ${payload.chapterIndex} " +
                "(target=$targetGlobalIndex)",
                "ui/webview/streaming"
            )
            
            // Phase 3 Update: Chapter streaming now handled by Conveyor Belt system
            // The bridge no longer manages chapter append/prepend operations
            // Chapter streaming happens at the Conveyor level in the window layer
            
            var measuredPages = 1
            
            // Phase 3: Chapter tracking now managed by Conveyor Belt
            com.rifters.riftedreader.util.AppLogger.event(
                "ReaderPageFragment",
                "[STREAMING] SUCCESS: Chapter ${payload.chapterIndex} added with $measuredPages pages",
                "ui/webview/streaming"
            )
            
            lastStreamingErrorMessage = null
            StreamingAttemptResult(payload.chapterIndex, measuredPages)
        } catch (e: Exception) {
            com.rifters.riftedreader.util.AppLogger.e(
                "ReaderPageFragment",
                "Error streaming chapter for direction=$direction target=$targetGlobalIndex",
                e
            )
            lastStreamingErrorMessage = e.message ?: "exception"
            null
        }
    }

    private data class StreamingAttemptResult(
        val chapterIndex: Int,
        val measuredPages: Int
    )

    private fun showStreamingFailureToast() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStreamingFailureToastAt < 3000) {
            return
        }
        val appContext = context?.applicationContext ?: return
        lastStreamingFailureToastAt = now
        val message = getString(R.string.reader_streaming_failed_toast)
        Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
    }
    
    
    /**
     * JavaScript interface for TTS communication between WebView and Android
     */
    private inner class TtsWebBridge {
        @JavascriptInterface
        fun onChunksPrepared(payload: String) {
            // Parse and store chunk data for highlighting
            activity?.runOnUiThread {
                try {
                    val chunks = mutableListOf<TtsChunk>()
                    val jsonArray = JSONArray(payload)
                    
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val index = obj.getInt("index")
                        val text = obj.getString("text")
                        val startPosition = obj.getInt("startPosition")
                        val endPosition = startPosition + text.length - 1
                        
                        chunks.add(TtsChunk(index, text, startPosition, endPosition))
                    }
                    
                    ttsChunks = chunks
                    
                    // If there's a pending highlight, apply it now that chunks are ready
                    highlightedRange?.let { range ->
                        applyHighlight(range)
                    }
                    
                    // TODO: Notify TTS system that chunks are ready
                    // readerViewModel.onTtsChunksReady(pageIndex, payload)
                } catch (e: Exception) {
                    android.util.Log.e("ReaderPageFragment", "Error parsing TTS chunks", e)
                    ttsChunks = emptyList()
                }
            }
        }
        
        @JavascriptInterface
        fun onChunkTapped(chunkIndex: Int) {
            // Handle tap on a text chunk - jump to that position for TTS
            activity?.runOnUiThread {
                // TODO: Notify TTS system to start from this chunk
                // readerViewModel.onTtsChunkTapped(pageIndex, chunkIndex)
            }
        }
    }
    
    /**
     * Handle a hardware page key (volume key) coming from the Activity.
     *
     * Returns true if the fragment will consume the key (suppresses the volume change).
     * The actual navigation may happen asynchronously: if an in-page navigation is possible
     * we call the WebView paginator; otherwise we fall back to activity chapter navigation.
     */
    fun handleHardwarePageKey(isNext: Boolean): Boolean {
        // If WebView is not ready or not visible, or paginator not initialized, don't consume
        if (!isWebViewReady || binding.pageWebView.visibility != View.VISIBLE || !isPaginatorInitialized) {
            com.rifters.riftedreader.util.AppLogger.d(
                "ReaderPageFragment",
                "handleHardwarePageKey BLOCKED: windowIndex=$pageIndex, isWebViewReady=$isWebViewReady, " +
                "webViewVisibility=${binding.pageWebView.visibility} (VISIBLE=${View.VISIBLE}), " +
                "isPaginatorInitialized=$isPaginatorInitialized, readerMode=${(activity as? ReaderActivity)?.readerMode}, " +
                "paginationMode=${readerViewModel.paginationMode} [EDGE_DEBUG]"
            )
            return false
        }

        // Consume the key immediately to suppress volume change; resolve navigation asynchronously.
        com.rifters.riftedreader.util.AppLogger.d(
            "ReaderPageFragment",
            "handleHardwarePageKey ACCEPTED: windowIndex=$pageIndex, launching navigation for isNext=$isNext [EDGE_DEBUG]"
        )
        viewLifecycleOwner.lifecycleScope.launch {
            handlePagedNavigation(isNext, "HARDWARE_KEY")
        }

        return true
    }
    
    /**
     * Unified edge-aware navigation helper for PAGE + CONTINUOUS mode.
     * 
     * This method centralizes the logic for:
     * 1. In-page navigation (when not at edge)
     * 2. Edge detection and handover to window navigation
     * 
     * CRITICAL: Re-reads pageCount and currentPage immediately before navigation
     * to ensure we're using fresh, stable values from the JavaScript paginator.
     * Guards against unstable paginator state (pageCount <= 0).
     * 
     * @param isNext true for next/forward navigation, false for previous/backward
     * @param source Logging tag to identify the navigation source (e.g., "HARDWARE_KEY", "FLING", "SCROLL")
     * @return true if navigation was attempted (either in-page or edge handover), false if paginator not ready
     */
    private suspend fun handlePagedNavigation(isNext: Boolean, source: String): Boolean {
        // Safety check: paginator must be ready
        if (!isWebViewReady || !isPaginatorInitialized) {
            com.rifters.riftedreader.util.AppLogger.d(
                "ReaderPageFragment",
                "$source navigation ignored: isWebViewReady=$isWebViewReady, isPaginatorInitialized=$isPaginatorInitialized [EDGE_AWARE_NAV]"
            )
            return false
        }
        
        try {
            // CRITICAL FIX: Re-read page info immediately before navigation
            // Force a fresh sync from JavaScript to get the most current values
            // This prevents using stale cached values during rapid navigation
            val freshPageCount = binding.pageWebView.evaluateJavascriptSuspend(
                "window.minimalPaginator ? window.minimalPaginator.getPageCount() : -1"
            ).toIntOrNull() ?: -1
            
            val freshCurrentPage = binding.pageWebView.evaluateJavascriptSuspend(
                "window.minimalPaginator ? window.minimalPaginator.getCurrentPage() : 0"
            ).toIntOrNull() ?: 0
            
            // GUARD: Bail if paginator not ready or page count is invalid
            if (freshPageCount <= 0) {
                com.rifters.riftedreader.util.AppLogger.w(
                    "ReaderPageFragment",
                    "$source navigation BLOCKED: freshPageCount=$freshPageCount (paginator not ready or unstable) [NAV_GUARD]"
                )
                return false
            }
            
            // Validate currentPage is within bounds
            val validCurrentPage = freshCurrentPage.coerceIn(0, freshPageCount - 1)
            
            val paginationMode = readerViewModel.paginationMode
            val currentWindow = readerViewModel.currentWindowIndex.value
            
            com.rifters.riftedreader.util.AppLogger.d(
                "ReaderPageFragment",
                "$source navigation: windowIndex=$pageIndex, inPage=$validCurrentPage/$freshPageCount (fresh read), " +
                "paginationMode=$paginationMode, currentWindow=$currentWindow, isNext=$isNext [EDGE_AWARE_NAV]"
            )
            
            if (isNext) {
                if (validCurrentPage < freshPageCount - 1) {
                    // Not at last in-page, navigate within WebView
                    com.rifters.riftedreader.util.AppLogger.userAction(
                        "ReaderPageFragment",
                        "$source: next in-page (${validCurrentPage + 1}/$freshPageCount) within window $pageIndex [IN_PAGE_NAV]",
                        "ui/webview/pagination"
                    )
                    binding.pageWebView.evaluateJavascript(
                        "if (window.minimalPaginator && window.minimalPaginator.isReady()) { window.minimalPaginator.nextPage(); }",
                        null
                    )
                    
                    // Capture position with character offset after navigation
                    viewLifecycleOwner.lifecycleScope.launch {
                        captureAndPersistPosition()
                    }
                    return true
                } else {
                    // At last in-page, edge handover to next window
                    com.rifters.riftedreader.util.AppLogger.d(
                        "ReaderPageFragment",
                        "EDGE_HIT: last in-page ($validCurrentPage/$freshPageCount), requesting next window from window $pageIndex [EDGE_FORWARD]"
                    )
                    // Capture position before window transition
                    captureAndPersistPosition()
                    navigateToNextWindow()
                    return true
                }
            } else {
                if (validCurrentPage > 0) {
                    // Not at first in-page, navigate within WebView
                    com.rifters.riftedreader.util.AppLogger.userAction(
                        "ReaderPageFragment",
                        "$source: prev in-page (${validCurrentPage - 1}/$freshPageCount) within window $pageIndex [IN_PAGE_NAV]",
                        "ui/webview/pagination"
                    )
                    binding.pageWebView.evaluateJavascript(
                        "if (window.minimalPaginator && window.minimalPaginator.isReady()) { window.minimalPaginator.prevPage(); }",
                        null
                    )
                    
                    // Capture position with character offset after navigation
                    viewLifecycleOwner.lifecycleScope.launch {
                        captureAndPersistPosition()
                    }
                    return true
                } else {
                    // At first in-page, edge handover to previous window with jump-to-last
                    com.rifters.riftedreader.util.AppLogger.d(
                        "ReaderPageFragment",
                        "EDGE_HIT: first in-page ($validCurrentPage/$freshPageCount), requesting previous window+last page from window $pageIndex [EDGE_BACKWARD]"
                    )
                    // Capture position before window transition
                    captureAndPersistPosition()
                    navigateToPreviousWindowLastPage()
                    return true
                }
            }
        } catch (e: Exception) {
            com.rifters.riftedreader.util.AppLogger.e(
                "ReaderPageFragment",
                "ERROR in $source navigation for window $pageIndex: ${e.message} [EDGE_AWARE_NAV_ERROR]",
                e
            )
            // Fallback: try activity navigation as a best-effort recovery
            // Return true since we attempted navigation (activity methods handle their own errors)
            if (isNext) {
                navigateToNextWindow()
            } else {
                navigateToPreviousWindowLastPage()
            }
            return true
        }
    }
    
    /**
     * Navigate to the next window via ReaderActivity.
     * This is called when at the last in-page of the current window.
     */
    private fun navigateToNextWindow() {
        val readerActivity = activity as? ReaderActivity
        if (readerActivity == null) {
            com.rifters.riftedreader.util.AppLogger.w(
                "ReaderPageFragment",
                "navigateToNextWindow: activity is null or not ReaderActivity [NAV_ERROR]"
            )
            return
        }
        
        val currentWindow = readerViewModel.currentWindowIndex.value
        val totalWindows = readerViewModel.windowCount.value
        val targetWindow = currentWindow + 1
        
        com.rifters.riftedreader.util.AppLogger.d(
            "ReaderPageFragment",
            "WINDOW_EXIT: windowIndex=$currentWindow, direction=NEXT, target=$targetWindow, " +
            "totalWindows=$totalWindows [WINDOW_NAV]"
        )
        
        // Validate target window exists before requesting navigation
        if (targetWindow >= totalWindows) {
            com.rifters.riftedreader.util.AppLogger.w(
                "ReaderPageFragment",
                "Cannot navigate to next window: target=$targetWindow >= totalWindows=$totalWindows [NAV_BLOCKED]"
            )
            return
        }
        
        com.rifters.riftedreader.util.AppLogger.d(
            "ReaderPageFragment",
            "Calling ReaderActivity.navigateToNextPage() for window transition $currentWindow -> $targetWindow [HANDOFF_TO_ACTIVITY]"
        )
        readerActivity.navigateToNextPage(animated = true)
    }
    
    /**
     * Navigate to the previous window and jump to its last internal page.
     * This is called when at the first in-page of the current window and navigating backward.
     */
    private fun navigateToPreviousWindowLastPage() {
        val readerActivity = activity as? ReaderActivity
        if (readerActivity == null) {
            com.rifters.riftedreader.util.AppLogger.w(
                "ReaderPageFragment",
                "navigateToPreviousWindowLastPage: activity is null or not ReaderActivity [NAV_ERROR]"
            )
            return
        }
        
        val currentWindow = readerViewModel.currentWindowIndex.value
        val targetWindow = currentWindow - 1
        
        com.rifters.riftedreader.util.AppLogger.d(
            "ReaderPageFragment",
            "WINDOW_EXIT: windowIndex=$currentWindow, direction=PREV, target=$targetWindow, " +
            "jumpToLast=true [WINDOW_NAV]"
        )
        
        // Validate target window exists before requesting navigation
        if (targetWindow < 0) {
            com.rifters.riftedreader.util.AppLogger.w(
                "ReaderPageFragment",
                "Cannot navigate to previous window: target=$targetWindow is negative [NAV_BLOCKED]"
            )
            return
        }
        
        com.rifters.riftedreader.util.AppLogger.d(
            "ReaderPageFragment",
            "Calling ReaderActivity.navigateToPreviousChapterToLastPage() for window transition $currentWindow -> $targetWindow [HANDOFF_TO_ACTIVITY]"
        )
        readerActivity.navigateToPreviousChapterToLastPage(animated = true)
    }
    
    /**
     * Safely get the current in-page position.
     * If paginator is not initialized yet, returns the cached Kotlin value instead of querying JS.
     * This prevents using stale/default values (0) before paginator is ready.
     */
    private suspend fun getSafeCurrentPage(): Int {
        return if (isPaginatorInitialized) {
            try {
                // Query via evaluateJavascript
                suspendCancellableCoroutine { cont ->
                    binding.pageWebView.evaluateJavascript(
                        "window.minimalPaginator && window.minimalPaginator.isReady() ? window.minimalPaginator.getCurrentPage() : 0"
                    ) { result ->
                        val page = result?.toIntOrNull() ?: 0
                        cont.resume(page)
                    }
                }
            } catch (e: Exception) {
                com.rifters.riftedreader.util.AppLogger.e(
                    "ReaderPageFragment",
                    "Error getting current page, using cached value: $currentInPageIndex",
                    e
                )
                currentInPageIndex
            }
        } else {
            // Paginator not initialized, use last known Kotlin value
            com.rifters.riftedreader.util.AppLogger.d(
                "ReaderPageFragment",
                "Paginator not initialized, using cached currentInPageIndex: $currentInPageIndex"
            )
            currentInPageIndex
        }
    }

    /**
     * Public accessors for checking fragment readiness state.
     * Used by ReaderActivity to gate CONTENT_LOADED emissions.
     */
    fun isWebViewReady(): Boolean = isWebViewReady
    fun isPaginatorInitialized(): Boolean = isPaginatorInitialized

    /**
     * Capture and persist the current reading position with character offset.
     * Called after navigation to save progress with stable positioning information.
     */
    private suspend fun captureAndPersistPosition() {
        try {
            if (!isPaginatorInitialized || !isWebViewReady) {
                return
            }

            // Query current page and page count via evaluateJavascript
            val currentPage = suspendCancellableCoroutine { cont ->
                binding.pageWebView.evaluateJavascript(
                    "window.minimalPaginator && window.minimalPaginator.isReady() ? window.minimalPaginator.getCurrentPage() : 0"
                ) { result ->
                    cont.resume(result?.toIntOrNull() ?: 0)
                }
            }
            
            val pageCount = suspendCancellableCoroutine { cont ->
                binding.pageWebView.evaluateJavascript(
                    "window.minimalPaginator && window.minimalPaginator.isReady() ? window.minimalPaginator.getPageCount() : 0"
                ) { result ->
                    cont.resume(result?.toIntOrNull() ?: 0)
                }
            }
            
            val characterOffset = suspendCancellableCoroutine { cont ->
                binding.pageWebView.evaluateJavascript(
                    "window.minimalPaginator && window.minimalPaginator.isReady() ? window.minimalPaginator.getCharacterOffsetForPage($currentPage) : 0"
                ) { result ->
                    cont.resume(result?.toIntOrNull() ?: 0)
                }
            }

            com.rifters.riftedreader.util.AppLogger.d(
                "ReaderPageFragment",
                "[CHARACTER_OFFSET] Captured position: page=$currentPage/$pageCount, offset=$characterOffset"
            )

            // Update ViewModel with current position and character offset
            readerViewModel.updateReadingPosition(
                windowIndex = pageIndex,
                pageInWindow = currentPage,
                characterOffset = characterOffset
            )
        } catch (e: Exception) {
            com.rifters.riftedreader.util.AppLogger.e(
                "ReaderPageFragment",
                "[CHARACTER_OFFSET] Error capturing position",
                e
            )
        }
    }

    /**
     * Restore reading position using character offset for stability.
     * Character offsets persist across font size changes and reflows.
     */
    private suspend fun restorePositionWithCharacterOffset() {
        try {
            if (!isPaginatorInitialized || !isWebViewReady) {
                return
            }

            // Check if ViewModel has a saved character offset for this window
            val savedCharOffset = readerViewModel.getSavedCharacterOffset(pageIndex)
            
            if (savedCharOffset > 0) {
                com.rifters.riftedreader.util.AppLogger.d(
                    "ReaderPageFragment",
                    "[CHARACTER_OFFSET] Restoring to saved offset=$savedCharOffset"
                )

                binding.pageWebView.evaluateJavascript(
                    "if (window.minimalPaginator) { window.minimalPaginator.goToPageWithCharacterOffset($savedCharOffset); }",
                    null
                )

                com.rifters.riftedreader.util.AppLogger.d(
                    "ReaderPageFragment",
                    "[CHARACTER_OFFSET] Navigation complete via character offset"
                )
            }
        } catch (e: Exception) {
            com.rifters.riftedreader.util.AppLogger.e(
                "ReaderPageFragment",
                "[CHARACTER_OFFSET] Error restoring position with character offset",
                e
            )
        }
    }
    
    /**
     * Extension function to evaluate JavaScript and suspend until result is available.
     * Returns the result as a String with quotes removed.
     */
    private suspend fun WebView.evaluateJavascriptSuspend(script: String): String =
        suspendCancellableCoroutine { continuation ->
            post {
                try {
                    evaluateJavascript(script) { result ->
                        val cleanResult = result?.trim()?.removeSurrounding("\"") ?: "null"
                        continuation.resume(cleanResult)
                    }
                } catch (e: Exception) {
                    continuation.cancel(e)
                }
            }
        }

    companion object {
        private const val ARG_PAGE_INDEX = "arg_page_index" // This is window index in continuous mode
        private const val FLING_THRESHOLD = 1000f // Minimum velocity for horizontal fling detection
        private const val SCROLL_DISTANCE_THRESHOLD_RATIO = 0.25f // 25% of viewport width triggers in-page navigation

        /**
         * Create a new ReaderPageFragment for the given window/page index.
         * 
         * @param windowIndex The window index (in continuous mode) or chapter index (in chapter-based mode)
         */
        fun newInstance(windowIndex: Int): ReaderPageFragment {
            return ReaderPageFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_PAGE_INDEX, windowIndex)
                }
            }
        }
    }
}
