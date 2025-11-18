package com.rifters.riftedreader.ui.reader

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rifters.riftedreader.R
import com.rifters.riftedreader.databinding.FragmentReaderPageBinding
import kotlinx.coroutines.launch
import org.json.JSONArray
import kotlin.math.abs

class ReaderPageFragment : Fragment() {

    private var _binding: FragmentReaderPageBinding? = null
    private val binding get() = _binding!!

    private val readerViewModel: ReaderViewModel by activityViewModels()

    private val pageIndex: Int by lazy {
        requireArguments().getInt(ARG_PAGE_INDEX)
    }

    private var latestPageText: String = ""
    private var latestPageHtml: String? = null
    private var highlightedRange: IntRange? = null
    private var isWebViewReady = false
    
    // Track previous settings to detect what changed
    private var previousSettings: com.rifters.riftedreader.data.preferences.ReaderSettings? = null
    
    // TTS chunk mapping for WebView highlighting
    private data class TtsChunk(val index: Int, val text: String, val startPosition: Int, val endPosition: Int)
    private var ttsChunks: List<TtsChunk> = emptyList()
    
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
        com.rifters.riftedreader.util.AppLogger.event("ReaderPageFragment", "onViewCreated for page $pageIndex", "ui/webview/lifecycle")
        
        // Configure WebView for EPUB rendering
        binding.pageWebView.apply {
            settings.javaScriptEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            
            // Add JavaScript interface for TTS communication
            addJavascriptInterface(TtsWebBridge(), "AndroidTtsBridge")
            
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    com.rifters.riftedreader.util.AppLogger.event("ReaderPageFragment", "WebView onPageFinished for page $pageIndex", "ui/webview/lifecycle")
                    isWebViewReady = true
                    
                    // Initialize the in-page paginator
                    val settings = readerViewModel.readerSettings.value
                    com.rifters.riftedreader.util.AppLogger.d("ReaderPageFragment", "Initializing paginator with fontSize=${settings.textSizeSp}px")
                    WebViewPaginatorBridge.setFontSize(binding.pageWebView, settings.textSizeSp.toInt())
                    
                    // Initialize TTS chunks when page is loaded
                    prepareTtsChunks()
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
        
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                readerViewModel.pages.collect { pages ->
                    val page = pages.getOrNull(pageIndex)
                    if (page != null) {
                        latestPageText = page.text
                        latestPageHtml = page.html
                        // Clear chunks when content changes - they'll be rebuilt by prepareTtsChunks
                        ttsChunks = emptyList()
                        if (highlightedRange == null) {
                            renderBaseContent()
                        } else {
                            applyHighlight(highlightedRange)
                        }
                    } else {
                        latestPageText = ""
                        latestPageHtml = null
                        highlightedRange = null
                        ttsChunks = emptyList()
                        binding.pageTextView.text = ""
                        binding.pageWebView.loadUrl("about:blank")
                    }
                }
            }
        }

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
                    
                    // Handle WebView content updates based on what changed
                    if (latestPageText.isNotEmpty() || !latestPageHtml.isNullOrEmpty()) {
                        if (!latestPageHtml.isNullOrEmpty() && fontSizeChanged && !themeChanged && !lineHeightChanged) {
                            // For HTML content with font size change only, use paginator API
                            // This preserves reading position without reloading
                            com.rifters.riftedreader.util.AppLogger.d("ReaderPageFragment", "Applying font size change without reload")
                            if (isWebViewReady && binding.pageWebView.visibility == View.VISIBLE) {
                                WebViewPaginatorBridge.setFontSize(binding.pageWebView, settings.textSizeSp.toInt())
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
        
        try {
            binding.pageWebView.apply {
                // Stop any loading
                stopLoading()
                // Fix: Replace webViewClient BEFORE calling loadUrl to prevent onPageFinished callback
                // This prevents race condition where onPageFinished could trigger prepareTtsChunks
                webViewClient = WebViewClient()
                // Remove JavaScript interface
                removeJavascriptInterface("AndroidTtsBridge")
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
     * Set up swipe gesture handling for in-page horizontal pagination.
     * This prevents ViewPager2 from intercepting swipes when there are more pages
     * within the current chapter.
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
                    
                    // Only handle if WebView is visible and ready
                    if (!isWebViewReady || binding.pageWebView.visibility != View.VISIBLE) {
                        com.rifters.riftedreader.util.AppLogger.d(
                            "ReaderPageFragment", 
                            "onScroll ignored on page $pageIndex: isWebViewReady=$isWebViewReady, visibility=${binding.pageWebView.visibility}"
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
                                "Scroll threshold exceeded: page=$pageIndex cumulativeX=$cumulativeScrollX threshold=$threshold - attempting interception"
                            )
                            
                            // Mark as intercepted to prevent multiple triggers
                            scrollIntercepted = true
                            
                            // Prevent parent from intercepting this gesture while we handle it
                            (binding.pageWebView.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                            
                            // Launch coroutine to check page boundaries and navigate
                            viewLifecycleOwner.lifecycleScope.launch {
                                try {
                                    val currentPage = WebViewPaginatorBridge.getCurrentPage(binding.pageWebView)
                                    val pageCount = WebViewPaginatorBridge.getPageCount(binding.pageWebView)
                                    
                                    com.rifters.riftedreader.util.AppLogger.d(
                                        "ReaderPageFragment", 
                                        "Scroll-based navigation: page=$pageIndex currentPage=$currentPage/$pageCount, cumulativeX=$cumulativeScrollX"
                                    )
                                    
                                    if (cumulativeScrollX > 0) {
                                        // Scrolling right-to-left (next page)
                                        if (currentPage < pageCount - 1) {
                                            // Not at last page, navigate within chapter
                                            com.rifters.riftedreader.util.AppLogger.userAction(
                                                "ReaderPageFragment", 
                                                "SCROLL_INTERCEPT: Navigating to next in-page (${currentPage + 1}/$pageCount) within chapter page $pageIndex [THRESHOLD_EXCEEDED]", 
                                                "ui/webview/pagination"
                                            )
                                            WebViewPaginatorBridge.nextPage(binding.pageWebView)
                                        } else {
                                            com.rifters.riftedreader.util.AppLogger.d(
                                                "ReaderPageFragment", 
                                                "SCROLL_FALLTHROUGH: At last in-page ($currentPage/$pageCount), allowing ViewPager2 to handle chapter navigation [EDGE_REACHED]"
                                            )
                                            // At last page, allow ViewPager2 to handle (go to next chapter)
                                            (binding.pageWebView.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                                        }
                                    } else {
                                        // Scrolling left-to-right (previous page)
                                        if (currentPage > 0) {
                                            // Not at first page, navigate within chapter
                                            com.rifters.riftedreader.util.AppLogger.userAction(
                                                "ReaderPageFragment", 
                                                "SCROLL_INTERCEPT: Navigating to previous in-page (${currentPage - 1}/$pageCount) within chapter page $pageIndex [THRESHOLD_EXCEEDED]", 
                                                "ui/webview/pagination"
                                            )
                                            WebViewPaginatorBridge.prevPage(binding.pageWebView)
                                        } else {
                                            com.rifters.riftedreader.util.AppLogger.d(
                                                "ReaderPageFragment", 
                                                "SCROLL_FALLTHROUGH: At first in-page ($currentPage/$pageCount), allowing ViewPager2 to handle chapter navigation [EDGE_REACHED]"
                                            )
                                            // At first page, allow ViewPager2 to handle (go to previous chapter)
                                            (binding.pageWebView.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                                        }
                                    }
                                } catch (e: Exception) {
                                    // If anything goes wrong, let ViewPager2 handle it
                                    com.rifters.riftedreader.util.AppLogger.e(
                                        "ReaderPageFragment", 
                                        "ERROR in scroll-based navigation for page $pageIndex: ${e.message} [FALLBACK_TO_VIEWPAGER]", 
                                        e
                                    )
                                    (binding.pageWebView.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                                }
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
                    
                    // Only handle if WebView is visible and ready
                    if (!isWebViewReady || binding.pageWebView.visibility != View.VISIBLE) {
                        com.rifters.riftedreader.util.AppLogger.d(
                            "ReaderPageFragment", 
                            "Fling ignored on page $pageIndex: isWebViewReady=$isWebViewReady, visibility=${binding.pageWebView.visibility}"
                        )
                        return false
                    }
                    
                    // Check if this is a horizontal fling (not vertical scroll)
                    if (abs(velocityX) > abs(velocityY) && abs(velocityX) > FLING_THRESHOLD) {
                        com.rifters.riftedreader.util.AppLogger.d(
                            "ReaderPageFragment",
                            "Detected horizontal fling: page=$pageIndex vx=$velocityX (threshold=$FLING_THRESHOLD)"
                        )
                        
                        // Prevent parent from intercepting this gesture while we handle it
                        (binding.pageWebView.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                        
                        // Launch coroutine to check page boundaries
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                val currentPage = WebViewPaginatorBridge.getCurrentPage(binding.pageWebView)
                                val pageCount = WebViewPaginatorBridge.getPageCount(binding.pageWebView)
                                
                                com.rifters.riftedreader.util.AppLogger.d(
                                    "ReaderPageFragment", 
                                    "FLING_BASED navigation: page=$pageIndex currentPage=$currentPage/$pageCount, velocityX=$velocityX"
                                )
                                
                                if (velocityX < 0) {
                                    // Fling left (next page)
                                    if (currentPage < pageCount - 1) {
                                        // Not at last page, navigate within chapter
                                        com.rifters.riftedreader.util.AppLogger.userAction(
                                            "ReaderPageFragment", 
                                            "FLING_INTERCEPT: Navigating to next in-page (${currentPage + 1}/$pageCount) within chapter page $pageIndex [FAST_SWIPE]", 
                                            "ui/webview/pagination"
                                        )
                                        WebViewPaginatorBridge.nextPage(binding.pageWebView)
                                        return@launch
                                    }
                                    com.rifters.riftedreader.util.AppLogger.d(
                                        "ReaderPageFragment", 
                                        "FLING_FALLTHROUGH: At last in-page ($currentPage/$pageCount), swipe falls through to ViewPager2 for chapter navigation [EDGE_REACHED]"
                                    )
                                    // At last page, allow ViewPager2 to handle (go to next chapter)
                                    (binding.pageWebView.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                                } else {
                                    // Fling right (previous page)
                                    if (currentPage > 0) {
                                        // Not at first page, navigate within chapter
                                        com.rifters.riftedreader.util.AppLogger.userAction(
                                            "ReaderPageFragment", 
                                            "FLING_INTERCEPT: Navigating to previous in-page (${currentPage - 1}/$pageCount) within chapter page $pageIndex [FAST_SWIPE]", 
                                            "ui/webview/pagination"
                                        )
                                        WebViewPaginatorBridge.prevPage(binding.pageWebView)
                                        return@launch
                                    }
                                    com.rifters.riftedreader.util.AppLogger.d(
                                        "ReaderPageFragment", 
                                        "FLING_FALLTHROUGH: At first in-page ($currentPage/$pageCount), swipe falls through to ViewPager2 for chapter navigation [EDGE_REACHED]"
                                    )
                                    // At first page, allow ViewPager2 to handle (go to previous chapter)
                                    (binding.pageWebView.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                                }
                            } catch (e: Exception) {
                                // If anything goes wrong, let ViewPager2 handle it
                                com.rifters.riftedreader.util.AppLogger.e(
                                    "ReaderPageFragment", 
                                    "ERROR in fling handling for page $pageIndex: ${e.message} [FALLBACK_TO_VIEWPAGER]", 
                                    e
                                )
                                (binding.pageWebView.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        return true // Consumed the horizontal fling
                    }
                    
                    return false // Don't consume - let parent handle if not consumed
                }
            }
        )
        
        // Set touch listener on WebView to intercept swipes
        binding.pageWebView.setOnTouchListener { _, event ->
            val actionName = when (event.actionMasked) {
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
                        "Touch CANCEL: page=$pageIndex finalCumulativeX=$cumulativeScrollX intercepted=$scrollIntercepted [GESTURE_CANCELLED]"
                    )
                    cumulativeScrollX = 0f
                    scrollIntercepted = false
                    "CANCEL"
                }
                else -> "OTHER(${event.actionMasked})"
            }
            
            // Only log MOVE events at a reduced rate to avoid spam
            if (event.actionMasked != MotionEvent.ACTION_MOVE) {
                com.rifters.riftedreader.util.AppLogger.d(
                    "ReaderPageFragment",
                    "pageWebView.onTouch: page=$pageIndex action=$actionName x=${event.x} y=${event.y}"
                )
            }
            
            val handled = gestureDetector.onTouchEvent(event)
            
            if (event.actionMasked != MotionEvent.ACTION_MOVE) {
                com.rifters.riftedreader.util.AppLogger.d(
                    "ReaderPageFragment",
                    "gestureDetector.onTouchEvent returned $handled for page $pageIndex action=$actionName"
                )
            }
            
            // Return the handled value - consume only if gesture detector handled it
            handled
        }
    }

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
        val html = latestPageHtml
        
        if (!html.isNullOrBlank()) {
            // Use WebView for rich HTML content (EPUB)
            binding.pageWebView.visibility = View.VISIBLE
            binding.pageTextView.visibility = View.GONE
            
            val settings = readerViewModel.readerSettings.value
            val palette = ReaderThemePaletteResolver.resolve(requireContext(), settings.theme)
            
            // Wrap HTML with proper styling
            val wrappedHtml = wrapHtmlForWebView(html, settings.textSizeSp, settings.lineHeightMultiplier, palette)
            // Bug Fix 2: Reset isWebViewReady flag when loading new content to prevent race conditions
            isWebViewReady = false
            // Use file:///android_asset/ as base URL to allow loading of inpage_paginator.js
            binding.pageWebView.loadDataWithBaseURL(
                "file:///android_asset/", 
                wrappedHtml, 
                "text/html", 
                "UTF-8", 
                null
            )
        } else {
            // Use TextView for plain text content (TXT)
            binding.pageWebView.visibility = View.GONE
            binding.pageTextView.visibility = View.VISIBLE
            binding.pageTextView.text = latestPageText
        }
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
                <script src="file:///android_asset/inpage_paginator.js"></script>
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
     */
    private fun prepareTtsChunks() {
        // Bug Fix 3: Check binding is not null before accessing WebView
        if (_binding == null) return
        if (!isWebViewReady || binding.pageWebView.visibility != View.VISIBLE) {
            return
        }
        
        binding.pageWebView.evaluateJavascript(
            """
            (function() {
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
                } catch(e) {
                    console.error('prepareTtsChunks error:', e);
                }
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

    companion object {
        private const val ARG_PAGE_INDEX = "arg_page_index"
        private const val FLING_THRESHOLD = 1000f // Minimum velocity for horizontal fling detection
        private const val SCROLL_DISTANCE_THRESHOLD_RATIO = 0.25f // 25% of viewport width triggers in-page navigation

        fun newInstance(pageIndex: Int): ReaderPageFragment {
            return ReaderPageFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_PAGE_INDEX, pageIndex)
                }
            }
        }
    }
}
