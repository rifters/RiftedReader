/**
 * In-page horizontal paginator using CSS columns and scroll-snap.
 * 
 * This script wraps chapter HTML in a horizontal, column-based container
 * and exposes a global API for page navigation and font size adjustments.
 * 
 * Features:
 * - CSS column-based pagination (column-width = viewport width)
 * - Scroll-snap for precise horizontal paging
 * - Dynamic font size adjustment with position preservation
 * - Anchor-based reading position mapping
 * - Chapter streaming with append/prepend/remove operations
 * - TOC-based navigation with chapter jump callbacks
 * - Robust reflow and repagination
 * - Memory-efficient segment management
 * - Pre-wrapped HTML detection (sections with data-chapter-index used directly)
 * 
 * Robustness Features (v2):
 * - Apply provisional column styles even when clientWidth < MIN_CLIENT_WIDTH
 * - Final fallback after retries using FALLBACK_WIDTH
 * - Gate isPaginationReady until appliedColumnWidth > 0 AND getPageCount() > 0
 * - Normalize html/body CSS in init for safety
 * - forceHorizontal() debug API for testing horizontal paging
 * - checkColumnsApplied() diagnostic helper
 * 
 * Injection Protection:
 * - Global guard prevents duplicate script execution
 * - reconfigure() allows updating settings without reinjection
 */
(function() {
    'use strict';
    
    // ========================================================================
    // INJECTION GUARD - Prevent duplicate initialization
    // Using a unique, namespaced symbol for robustness against accidental modification
    // ========================================================================
    var GUARD_SYMBOL = '__riftedReaderPaginatorInitialized_v1__';
    if (window[GUARD_SYMBOL] === true) {
        console.warn('inpage_paginator: [GUARD] Script already initialized - skipping duplicate injection. Use reconfigure() to update settings.');
        return;
    }
    // Also check for the paginator object existence as a secondary guard
    if (window.inpagePaginator && typeof window.inpagePaginator.isReady === 'function') {
        console.warn('inpage_paginator: [GUARD] Paginator object already exists - skipping duplicate injection.');
        return;
    }
    window[GUARD_SYMBOL] = true;
    
    // Configuration
    const COLUMN_GAP = 0; // No gap between columns for seamless pages
    const SCROLL_BEHAVIOR_SMOOTH = 'smooth';
    const SCROLL_BEHAVIOR_AUTO = 'auto';
    const MAX_CHAPTER_SEGMENTS = 5; // Limit DOM growth when streaming chapters
    const UNKNOWN_WINDOW_INDEX = -1; // Used when windowIndex is unknown or invalid
    const MIN_CLIENT_WIDTH = 10; // Minimum valid clientWidth for column computation
    const COLUMN_RETRY_DELAY_MS = 50; // Delay before retrying column width computation
    const MAX_COLUMN_RETRIES = 5; // Maximum retries for column width computation
    const FALLBACK_WIDTH = 360; // Fallback width when all retries exhausted (mobile default)
    
    // Paginator configuration - set via configure() before init()
    let paginatorConfig = {
        mode: 'window',        // 'window' or 'chapter'
        windowIndex: 0,        // ViewPager2 page index
        chapterIndex: null,    // Logical chapter index (optional for window mode, required for chapter mode)
        rootSelector: null     // Optional CSS selector for pagination root
    };
    
    // State
    let currentFontSize = 16; // Default font size in pixels
    let columnContainer = null;
    let contentWrapper = null;
    let viewportWidth = 0;
    let appliedColumnWidth = 0; // Track the actually applied column width for diagnostics
    let isInitialized = false;
    let isPaginationReady = false; // CRITICAL: Only true after onPaginationReady callback fires
    let chapterSegments = [];
    let initialChapterIndex = 0;
    let currentPage = 0; // CRITICAL: Track current page explicitly to avoid async scrollTo issues
    let isProgrammaticScroll = false; // Prevent scroll event from overwriting currentPage during goToPage()
    
    // ISSUE 4 FIX: Track last known page count for returning during reflow when isPaginationReady=false
    let lastKnownPageCount = -1;
    
    // ISSUE 4 FIX: Hysteresis tracking for segment eviction - prevent evicting just-appended segments
    let lastAppendedChapterIndex = -1;
    let lastAppendedTimestamp = 0;
    const EVICTION_HYSTERESIS_MS = 500; // Don't evict segment within 500ms of append
    
    // Window mode discipline - CONSTRUCTION vs ACTIVE
    let windowMode = 'CONSTRUCTION'; // 'CONSTRUCTION' or 'ACTIVE'
    
    // Diagnostics (toggleable via enableDiagnostics())
    let diagnosticsEnabled = false;
    
    // Pending column retry timeout ID (for cancellation)
    let pendingColumnRetryTimeoutId = null;
    
    // Injection tracking to prevent duplicate initialization
    let injectionAttemptCount = 0;
    
    /**
     * Configure the paginator before initialization.
     * This method must be called before init() to set the pagination mode and context.
     * 
     * @param {Object} config - Configuration object
     * @param {string} config.mode - Pagination mode: 'window' or 'chapter'
     * @param {number} config.windowIndex - ViewPager2 window/page index
     * @param {number} [config.chapterIndex] - Logical chapter index (required for chapter mode)
     * @param {string} [config.rootSelector] - CSS selector for pagination root (optional)
     * 
     * Mode behavior:
     * - 'window': Paginate across the entire window (multiple chapters).
     *   Use full document or #window-root as pagination root.
     *   Ignore chapterIndex for scroll calculations.
     * 
     * - 'chapter': Paginate within a single chapter section.
     *   Use section[data-chapter-index] as pagination root.
     *   Require chapterIndex to locate the chapter.
     */
    function configure(config) {
        if (isInitialized) {
            console.warn('inpage_paginator: configure() called after initialization - this may have no effect');
        }
        
        if (!config) {
            console.error('inpage_paginator: configure() called with no config');
            return;
        }
        
        // Validate mode
        if (config.mode !== 'window' && config.mode !== 'chapter') {
            console.error('inpage_paginator: Invalid mode: ' + config.mode + ', must be "window" or "chapter"');
            return;
        }
        
        // Validate chapter mode requirements
        if (config.mode === 'chapter' && (config.chapterIndex === null || config.chapterIndex === undefined)) {
            console.error('inpage_paginator: chapter mode requires chapterIndex');
            return;
        }
        
        // Update configuration
        paginatorConfig = {
            mode: config.mode,
            windowIndex: config.windowIndex !== undefined ? config.windowIndex : 0,
            chapterIndex: config.chapterIndex !== undefined ? config.chapterIndex : null,
            rootSelector: config.rootSelector || null
        };
        
        console.log('inpage_paginator: [CONFIG] Configured with mode=' + paginatorConfig.mode + 
                    ', windowIndex=' + paginatorConfig.windowIndex + 
                    ', chapterIndex=' + paginatorConfig.chapterIndex +
                    ', rootSelector=' + paginatorConfig.rootSelector);
    }
    
    /**
     * Reconfigure display settings without full reinitialization.
     * Use this for theme changes, font size, or line height updates.
     * Avoids reinjecting the entire script.
     * 
     * @param {Object} opts - Reconfiguration options
     * @param {number} [opts.fontSize] - New font size in pixels
     * @param {number} [opts.lineHeight] - New line height multiplier
     * @param {string} [opts.backgroundColor] - New background color (CSS value)
     * @param {string} [opts.textColor] - New text color (CSS value)
     * @param {boolean} [opts.preservePosition] - Whether to preserve reading position (default: true)
     */
    function reconfigure(opts) {
        injectionAttemptCount++;
        console.log('inpage_paginator: [RECONFIGURE] Attempt #' + injectionAttemptCount + ', opts=' + JSON.stringify(opts));
        
        if (!isInitialized || !contentWrapper) {
            console.warn('inpage_paginator: reconfigure() called before initialization');
            return;
        }
        
        if (!opts || typeof opts !== 'object') {
            console.warn('inpage_paginator: reconfigure() called with invalid options');
            return;
        }
        
        // Save current position if requested
        var currentPageBeforeReconfig = getCurrentPage();
        var preservePosition = opts.preservePosition !== false;
        
        // Apply font size change
        if (typeof opts.fontSize === 'number' && opts.fontSize > 0) {
            currentFontSize = opts.fontSize;
            contentWrapper.style.fontSize = opts.fontSize + 'px';
            console.log('inpage_paginator: [RECONFIGURE] Applied fontSize=' + opts.fontSize + 'px');
        }
        
        // Apply line height change
        if (typeof opts.lineHeight === 'number' && opts.lineHeight > 0) {
            contentWrapper.style.lineHeight = opts.lineHeight;
            console.log('inpage_paginator: [RECONFIGURE] Applied lineHeight=' + opts.lineHeight);
        }
        
        // Apply background color
        if (typeof opts.backgroundColor === 'string') {
            document.body.style.backgroundColor = opts.backgroundColor;
            if (columnContainer) {
                columnContainer.style.backgroundColor = opts.backgroundColor;
            }
            console.log('inpage_paginator: [RECONFIGURE] Applied backgroundColor=' + opts.backgroundColor);
        }
        
        // Apply text color
        if (typeof opts.textColor === 'string') {
            document.body.style.color = opts.textColor;
            contentWrapper.style.color = opts.textColor;
            console.log('inpage_paginator: [RECONFIGURE] Applied textColor=' + opts.textColor);
        }
        
        // Reflow columns after style changes
        reapplyColumns();
        
        // Restore position if requested
        if (preservePosition) {
            requestAnimationFrame(function() {
                var pageCount = getPageCount();
                if (pageCount > 0 && currentPageBeforeReconfig >= 0) {
                    var targetPage = Math.min(currentPageBeforeReconfig, pageCount - 1);
                    goToPage(targetPage, false);
                    console.log('inpage_paginator: [RECONFIGURE] Restored position to page ' + targetPage + ' (was ' + currentPageBeforeReconfig + ')');
                }
            });
        }
        
        // Notify Android of successful reconfigure
        if (window.AndroidBridge && window.AndroidBridge.onReconfigureComplete) {
            try {
                window.AndroidBridge.onReconfigureComplete(JSON.stringify({
                    fontSize: currentFontSize,
                    pageCount: getPageCount()
                }));
            } catch (e) {
                console.warn('inpage_paginator: [RECONFIGURE] Failed to notify Android', e);
            }
        }
    }
    
    // ========================================================================
    // Diagnostics Functions
    // ========================================================================
    
    /**
     * Enable or disable pagination diagnostics logging.
     * When enabled, detailed pagination state is logged after key events.
     * 
     * @param {boolean} enable - Whether to enable diagnostics
     */
    function enableDiagnostics(enable) {
        diagnosticsEnabled = enable !== false;
        console.log('inpage_paginator: [DIAG] Diagnostics ' + (diagnosticsEnabled ? 'enabled' : 'disabled'));
    }
    
    /**
     * Log a diagnostics message with payload (only when diagnostics enabled).
     * 
     * @param {string} context - The context or event name
     * @param {Object} payload - Additional data to log
     */
    function logDiagnostics(context, payload) {
        if (!diagnosticsEnabled) return;
        
        console.log('inpage_paginator: [DIAG] ' + context + ' ' + JSON.stringify(payload || {}));
        
        // Notify Android if callback exists
        if (window.AndroidBridge && window.AndroidBridge.onDiagnosticsLog) {
            try {
                window.AndroidBridge.onDiagnosticsLog(context, JSON.stringify(payload || {}));
            } catch (e) {
                console.warn('inpage_paginator: [DIAG] Failed to send to Android', e);
            }
        }
    }
    
    /**
     * Log the current pagination state for diagnostics.
     * Called after onPaginationReady and after each streaming append/evict.
     * 
     * @param {string} trigger - What triggered this state log
     */
    function logPaginationState(trigger) {
        if (!diagnosticsEnabled && !console.log) return; // Skip if no logging needed
        
        const state = {
            trigger: trigger,
            clientWidth: computeClientWidth(),
            appliedColumnWidth: appliedColumnWidth,
            pageCount: getPageCount(),
            currentPage: getCurrentPage(),
            currentChapter: getCurrentChapter(),
            loadedSegments: getSegmentDiagnostics(),
            windowMode: windowMode,
            isPaginationReady: isPaginationReady,
            isInitialized: isInitialized
        };
        
        // Optionally include outerHTML hash/length
        if (diagnosticsEnabled) {
            const windowRoot = document.getElementById('window-root');
            if (windowRoot) {
                state.windowRootHtmlLength = windowRoot.outerHTML.length;
            }
        }
        
        console.log('inpage_paginator: [STATE_LOG] ' + JSON.stringify(state));
        
        // Notify Android if callback exists and diagnostics enabled
        if (diagnosticsEnabled && window.AndroidBridge && window.AndroidBridge.onPaginationStateLog) {
            try {
                window.AndroidBridge.onPaginationStateLog(JSON.stringify(state));
            } catch (e) {
                console.warn('inpage_paginator: [DIAG] Failed to send state to Android', e);
            }
        }
        
        return state;
    }
    
    /**
     * Get diagnostics info for all loaded segments.
     * 
     * @returns {Array} Array of segment info objects
     */
    function getSegmentDiagnostics() {
        if (!contentWrapper || !columnContainer) {
            return [];
        }
        
        try {
            const pageWidth = viewportWidth || window.innerWidth;
            if (pageWidth === 0) {
                return [];
            }
            
            const contentRect = contentWrapper.getBoundingClientRect();
            
            return chapterSegments.map(function(seg) {
                const chapterIndex = parseInt(seg.getAttribute('data-chapter-index'), 10);
                const segmentRect = seg.getBoundingClientRect();
                const offsetLeft = segmentRect.left - contentRect.left + columnContainer.scrollLeft;
                const startPage = Math.floor(Math.max(0, offsetLeft) / pageWidth);
                const endPage = Math.ceil((offsetLeft + segmentRect.width) / pageWidth);
                const pageCount = Math.max(1, endPage - startPage);
                
                return {
                    chapterIndex: chapterIndex,
                    startPage: startPage,
                    endPage: endPage,
                    pageCount: pageCount
                };
            });
        } catch (e) {
            console.error('inpage_paginator: getSegmentDiagnostics error', e);
            return [];
        }
    }
    
    /**
     * Get image diagnostics - enumerate all images and their load status.
     * Useful for debugging broken images under sliding window.
     * 
     * @returns {Array} Array of image info objects
     */
    function getImageDiagnostics() {
        const images = document.querySelectorAll('img');
        const diagnostics = [];
        
        for (var i = 0; i < images.length; i++) {
            var img = images[i];
            diagnostics.push({
                index: i,
                src: img.src,
                complete: img.complete,
                naturalWidth: img.naturalWidth,
                naturalHeight: img.naturalHeight,
                failed: img.complete && img.naturalWidth === 0
            });
        }
        
        return diagnostics;
    }
    
    /**
     * Log failed images for debugging.
     * Called post-load to identify images that failed to load.
     */
    function logFailedImages() {
        const diagnostics = getImageDiagnostics();
        const failed = diagnostics.filter(function(img) { return img.failed; });
        
        if (failed.length > 0) {
            console.warn('inpage_paginator: [IMAGES] ' + failed.length + ' images failed to load:', 
                        JSON.stringify(failed));
            
            // Notify Android if callback exists
            if (window.AndroidBridge && window.AndroidBridge.onImagesFailedToLoad) {
                try {
                    window.AndroidBridge.onImagesFailedToLoad(JSON.stringify(failed));
                } catch (e) {
                    console.warn('inpage_paginator: Failed to notify Android of failed images', e);
                }
            }
        } else {
            console.log('inpage_paginator: [IMAGES] All ' + diagnostics.length + ' images loaded successfully');
        }
        
        return diagnostics;
    }
    
    // ========================================================================
    // Vertical Fallback Prevention / Robustness Functions
    // ========================================================================
    
    /**
     * Normalize html/body CSS to ensure full viewport with no scrolling.
     * This prevents vertical scrolling that can occur before paginator takes over.
     * Called during init() before column styles are applied.
     */
    function normalizeHtmlBodyCss() {
        try {
            var html = document.documentElement;
            var body = document.body;
            
            if (html) {
                html.style.margin = '0';
                html.style.padding = '0';
                html.style.width = '100%';
                html.style.height = '100%';
                html.style.overflow = 'hidden';
            }
            
            if (body) {
                body.style.margin = '0';
                body.style.padding = '0';
                body.style.width = '100%';
                body.style.height = '100%';
                body.style.overflow = 'hidden';
                // Disable tap highlight that causes purple circles
                body.style.webkitTapHighlightColor = 'transparent';
            }
            
            console.log('inpage_paginator: [INIT] Normalized html/body CSS for full viewport');
        } catch (e) {
            console.warn('inpage_paginator: [INIT] Failed to normalize html/body CSS', e);
        }
    }
    
    /**
     * Check if columns are applied correctly (horizontal layout, not vertical).
     * This is a diagnostic helper that can be called from Android.
     * 
     * @returns {boolean} true if columns appear to be working correctly
     */
    function checkColumnsApplied() {
        if (!contentWrapper || !columnContainer) {
            console.log('inpage_paginator: [COLUMN_CHECK] contentWrapper or columnContainer not available');
            return false;
        }
        
        var containerHeight = columnContainer.offsetHeight;
        var contentHeight = contentWrapper.scrollHeight;
        var contentWidth = contentWrapper.scrollWidth;
        var effectiveViewportWidth = viewportWidth || window.innerWidth || FALLBACK_WIDTH;
        
        // If content is wider than viewport, columns are working (horizontal layout)
        var isHorizontal = contentWidth > effectiveViewportWidth;
        // If content is taller than container and not wider, it may be vertical fallback
        var isVerticalFallback = contentHeight > containerHeight && contentWidth <= effectiveViewportWidth;
        
        var result = isHorizontal || (contentHeight <= containerHeight);
        
        console.log('inpage_paginator: [COLUMN_CHECK] containerHeight=' + containerHeight + 
                   ', contentHeight=' + contentHeight + ', contentWidth=' + contentWidth + 
                   ', viewportWidth=' + effectiveViewportWidth + ', isHorizontal=' + isHorizontal + 
                   ', isVerticalFallback=' + isVerticalFallback + ', result=' + result);
        
        return result;
    }
    
    /**
     * Dispatch custom paginationReady event for listeners.
     * This allows post-init hooks like padding injection.
     */
    function dispatchPaginationReadyEvent(pageCount) {
        try {
            var event = new CustomEvent('paginationReady', {
                detail: {
                    pageCount: pageCount,
                    appliedColumnWidth: appliedColumnWidth,
                    viewportWidth: viewportWidth
                }
            });
            document.dispatchEvent(event);
            console.log('inpage_paginator: [EVENT] Dispatched paginationReady event');
        } catch (e) {
            console.warn('inpage_paginator: [EVENT] Failed to dispatch paginationReady event', e);
        }
    }
    
    /**
     * Debug API: Force horizontal paging layout.
     * 
     * This method can be called from Android console or debugger to:
     * 1. Re-normalize html/body CSS to prevent vertical scroll
     * 2. Force recomputation of column styles
     * 3. Ensure scroll container is properly configured
     * 4. Log detailed diagnostics
     * 
     * Use for debugging when pagination appears stuck in vertical scroll mode.
     * 
     * @returns {Object} Diagnostics info about the forced layout
     */
    function forceHorizontal() {
        console.log('inpage_paginator: [DEBUG] forceHorizontal() called');
        
        var diagnostics = {
            before: {
                isPaginationReady: isPaginationReady,
                appliedColumnWidth: appliedColumnWidth,
                viewportWidth: viewportWidth,
                pageCount: isPaginationReady ? getPageCount() : -1,
                currentPage: isPaginationReady ? getCurrentPage() : -1
            },
            actions: [],
            after: null
        };
        
        try {
            // 1. Re-normalize html/body CSS
            normalizeHtmlBodyCss();
            diagnostics.actions.push('normalizeHtmlBodyCss');
            
            // 2. Force column container overflow settings
            if (columnContainer) {
                columnContainer.style.overflowX = 'auto';
                columnContainer.style.overflowY = 'hidden';
                columnContainer.style.scrollSnapType = 'x mandatory';
                diagnostics.actions.push('fixColumnContainerOverflow');
            }
            
            // 3. Force recomputation of column styles
            if (contentWrapper) {
                updateColumnStyles(contentWrapper, 0);
                diagnostics.actions.push('updateColumnStyles');
            }
            
            // 4. If paginator wasn't ready, check again
            if (!isPaginationReady) {
                var pageCount = getPageCount();
                if (appliedColumnWidth > 0 && pageCount > 0) {
                    isPaginationReady = true;
                    console.log('inpage_paginator: [DEBUG] forceHorizontal - set isPaginationReady=true');
                    diagnostics.actions.push('setPaginationReady');
                    
                    // Dispatch event and notify Android
                    dispatchPaginationReadyEvent(pageCount);
                    if (window.AndroidBridge && window.AndroidBridge.onPaginationReady) {
                        window.AndroidBridge.onPaginationReady(pageCount);
                        diagnostics.actions.push('notifyAndroid');
                    }
                }
            }
            
            // 5. Collect after diagnostics
            diagnostics.after = {
                isPaginationReady: isPaginationReady,
                appliedColumnWidth: appliedColumnWidth,
                viewportWidth: viewportWidth,
                pageCount: isPaginationReady ? getPageCount() : -1,
                currentPage: isPaginationReady ? getCurrentPage() : -1,
                computedClientWidth: computeClientWidth(),
                columnsApplied: checkColumnsApplied()
            };
            
            console.log('inpage_paginator: [DEBUG] forceHorizontal completed:', JSON.stringify(diagnostics));
            
            // Log full pagination state
            logPaginationState('[DEBUG] forceHorizontal');
            
        } catch (e) {
            console.error('inpage_paginator: [DEBUG] forceHorizontal error:', e);
            diagnostics.error = e.message;
        }
        
        return diagnostics;
    }
    
    /**
     * Initialize the paginator by wrapping content in a column container
     */
    function init() {
        if (isInitialized) {
            console.log('inpage_paginator: Already initialized, skipping');
            return;
        }
        
        console.log('inpage_paginator: [STATE] Starting initialization');
        
        // ROBUSTNESS FIX: Normalize html/body CSS before any other operations
        // This prevents vertical scrolling and ensures column layout works
        normalizeHtmlBodyCss();
        
        // Get the body content
        const body = document.body;
        if (!body) {
            console.error('inpage_paginator: document.body not found');
            return;
        }
        
        // Create column container
        columnContainer = document.createElement('div');
        columnContainer.id = 'paginator-container';
        columnContainer.style.cssText = `
            position: absolute;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            overflow-x: auto;
            overflow-y: hidden;
            scroll-snap-type: x mandatory;
            -webkit-overflow-scrolling: touch;
        `;
        
        // Create content wrapper for columns
        contentWrapper = document.createElement('div');
        contentWrapper.id = 'paginator-content';
        
        // Move all body children to content wrapper
        // Add null check to prevent errors on empty body
        while (body.firstChild) {
            const child = body.firstChild;
            if (child) {
                contentWrapper.appendChild(child);
            } else {
                break;
            }
        }

        wrapExistingContentAsSegment();
        
        // Set up column CSS for content wrapper
        updateColumnStyles(contentWrapper);
        
        // Add wrappers to DOM
        columnContainer.appendChild(contentWrapper);
        body.appendChild(columnContainer);
        
        // Update viewport width
        viewportWidth = window.innerWidth;
        
        // Handle window resize
        window.addEventListener('resize', handleResize);
        
        // Handle orientation changes - reapply columns
        window.addEventListener('orientationchange', function() {
            console.log('inpage_paginator: [EVENT] orientationchange detected');
            setTimeout(function() {
                reapplyColumns();
            }, 100); // Small delay to allow layout to settle
        });
        
        isInitialized = true;
        console.log('inpage_paginator: [STATE] isInitialized set to true');
        
        // Add scroll listener to sync currentPage when user manually scrolls
        columnContainer.addEventListener('scroll', function() {
            syncCurrentPageFromScroll();
        });
        
        // CRITICAL FIX: Wait for layout to complete before calling onPaginationReady
        // Use requestAnimationFrame to ensure DOM has been laid out and measured
        requestAnimationFrame(function() {
            // Force a reflow to ensure accurate measurements
            contentWrapper.offsetHeight;
            
            const pageCount = getPageCount();
            
            // ROBUSTNESS FIX: Gate isPaginationReady until appliedColumnWidth > 0 and pageCount > 0
            const ready = appliedColumnWidth > 0 && pageCount > 0;
            
            console.log('inpage_paginator: [STATE] Initialization complete after layout - pageCount=' + pageCount + 
                       ', viewportWidth=' + viewportWidth + ', appliedColumnWidth=' + appliedColumnWidth + ', ready=' + ready);
            
            if (!ready) {
                console.warn('inpage_paginator: [STATE] Not ready yet - appliedColumnWidth=' + appliedColumnWidth + ', pageCount=' + pageCount);
                // Schedule retry
                setTimeout(function() {
                    checkAndFirePaginationReady();
                }, 100);
                return;
            }
            
            // CRITICAL: Set pagination ready flag AFTER we have valid page count and column width
            isPaginationReady = true;
            console.log('inpage_paginator: [STATE] isPaginationReady set to true');
            
            // Dispatch custom event for post-init hooks (e.g., padding injection)
            dispatchPaginationReadyEvent(pageCount);
            
            // Log pagination state for diagnostics
            logPaginationState('[INIT] onPaginationReady');
            
            // Check for failed images after load
            setTimeout(function() {
                logFailedImages();
            }, 500);
            
            // Only notify Android if we have a valid page count
            if (pageCount > 0 && window.AndroidBridge && window.AndroidBridge.onPaginationReady) {
                console.log('inpage_paginator: [CALLBACK] Calling AndroidBridge.onPaginationReady with pageCount=' + pageCount);
                window.AndroidBridge.onPaginationReady(pageCount);
            } else {
                console.warn('inpage_paginator: [STATE] Invalid state - pageCount=' + pageCount + ', AndroidBridge=' + (window.AndroidBridge ? 'present' : 'missing'));
            }
        });
    }
    
    /**
     * Check conditions and fire pagination ready if not already done.
     * Used as a retry mechanism when initial check fails.
     */
    function checkAndFirePaginationReady() {
        if (isPaginationReady) {
            return; // Already ready
        }
        
        const pageCount = getPageCount();
        const ready = appliedColumnWidth > 0 && pageCount > 0;
        
        console.log('inpage_paginator: [STATE] checkAndFirePaginationReady - pageCount=' + pageCount + 
                   ', appliedColumnWidth=' + appliedColumnWidth + ', ready=' + ready);
        
        if (ready) {
            isPaginationReady = true;
            console.log('inpage_paginator: [STATE] isPaginationReady set to true (retry)');
            
            // Emit custom event
            dispatchPaginationReadyEvent(pageCount);
            
            // Log pagination state
            logPaginationState('[INIT_RETRY] onPaginationReady');
            
            // Notify Android
            if (pageCount > 0 && window.AndroidBridge && window.AndroidBridge.onPaginationReady) {
                console.log('inpage_paginator: [CALLBACK] Calling AndroidBridge.onPaginationReady with pageCount=' + pageCount + ' (retry)');
                window.AndroidBridge.onPaginationReady(pageCount);
            }
        }
    }
    
    /**
     * Compute the effective client width for column calculation.
     * Uses multiple fallbacks to ensure we get a valid width.
     * 
     * @returns {number} The computed client width
     */
    function computeClientWidth() {
        // Primary: document.documentElement.clientWidth (most accurate)
        let clientWidth = document.documentElement.clientWidth;
        
        // Fallback 1: window.innerWidth
        if (!clientWidth || clientWidth < MIN_CLIENT_WIDTH) {
            clientWidth = window.innerWidth;
        }
        
        // Fallback 2: #window-root.clientWidth if present
        if (!clientWidth || clientWidth < MIN_CLIENT_WIDTH) {
            const windowRoot = document.getElementById('window-root');
            if (windowRoot) {
                clientWidth = windowRoot.clientWidth;
            }
        }
        
        // Fallback 3: body.clientWidth
        if (!clientWidth || clientWidth < MIN_CLIENT_WIDTH) {
            clientWidth = document.body ? document.body.clientWidth : 0;
        }
        
        return clientWidth || 0;
    }
    
    /**
     * Update column styles on the content wrapper
     * Preserves existing fontSize to avoid breaking dynamic font size adjustments
     * 
     * CRITICAL FIX: Sets wrapper width to an exact multiple of viewport width
     * to ensure horizontal paging aligns correctly and prevents vertical stacking.
     * 
     * ROBUSTNESS FIX: Apply provisional styles even when clientWidth < MIN_CLIENT_WIDTH,
     * then retry. After MAX_COLUMN_RETRIES, use FALLBACK_WIDTH.
     * 
     * @param {HTMLElement} wrapper - The content wrapper element
     * @param {number} retryCount - Number of retry attempts (for guarding against clientWidth < 10)
     */
    function updateColumnStyles(wrapper, retryCount) {
        if (retryCount === undefined) {
            retryCount = 0;
            // Cancel any pending retry timeout when starting fresh
            if (pendingColumnRetryTimeoutId !== null) {
                clearTimeout(pendingColumnRetryTimeoutId);
                pendingColumnRetryTimeoutId = null;
            }
        }
        
        // Compute client width with fallbacks
        var clientWidth = computeClientWidth();
        var usedFallback = false;
        
        // Guard against invalid client width - apply provisional styles once and retry
        if (clientWidth < MIN_CLIENT_WIDTH) {
            if (retryCount < MAX_COLUMN_RETRIES) {
                console.warn('inpage_paginator: [STYLES] clientWidth=' + clientWidth + ' < ' + MIN_CLIENT_WIDTH + 
                            ', retrying in ' + COLUMN_RETRY_DELAY_MS + 'ms (attempt ' + (retryCount + 1) + '/' + MAX_COLUMN_RETRIES + ')');
                
                // ROBUSTNESS FIX: Apply provisional styles only on first attempt (retryCount=0)
                // This prevents layout thrashing from repeated style applications
                if (retryCount === 0) {
                    console.log('inpage_paginator: [STYLES] Applying provisional styles with fallback width: ' + FALLBACK_WIDTH + 'px');
                    applyColumnStylesWithWidth(wrapper, FALLBACK_WIDTH);
                }
                
                pendingColumnRetryTimeoutId = setTimeout(function() {
                    pendingColumnRetryTimeoutId = null;
                    updateColumnStyles(wrapper, retryCount + 1);
                }, COLUMN_RETRY_DELAY_MS);
                return;
            } else {
                // ROBUSTNESS FIX: Final fallback after all retries exhausted
                console.warn('inpage_paginator: [STYLES] All ' + MAX_COLUMN_RETRIES + ' retries exhausted, using fallback width: ' + FALLBACK_WIDTH + 'px');
                clientWidth = FALLBACK_WIDTH;
                usedFallback = true;
            }
        }
        
        viewportWidth = clientWidth || window.innerWidth || FALLBACK_WIDTH;
        var columnWidth = viewportWidth;
        
        // Track applied column width for diagnostics
        appliedColumnWidth = columnWidth;
        
        logDiagnostics('[STYLES] updateColumnStyles called', {
            clientWidth: clientWidth,
            viewportWidth: viewportWidth,
            columnWidth: columnWidth,
            retryCount: retryCount,
            usedFallback: usedFallback
        });
        
        console.log('inpage_paginator: [STYLES] updateColumnStyles called, clientWidth=' + clientWidth + 
                   ', viewportWidth=' + viewportWidth + ', usedFallback=' + usedFallback);
        
        applyColumnStylesWithWidth(wrapper, columnWidth);
    }
    
    /**
     * Apply column styles with a specific width.
     * Separated from updateColumnStyles for reuse in provisional application.
     * 
     * @param {HTMLElement} wrapper - The content wrapper element
     * @param {number} columnWidth - The column width to apply
     */
    function applyColumnStylesWithWidth(wrapper, columnWidth) {
        // Preserve the current font size before updating styles
        var preservedFontSize = wrapper.style.fontSize;
        
        // Set up column CSS with explicit -webkit prefixes for better Android WebView support
        wrapper.style.cssText = `
            display: block;
            column-width: ${columnWidth}px;
            -webkit-column-width: ${columnWidth}px;
            column-gap: ${COLUMN_GAP}px;
            -webkit-column-gap: ${COLUMN_GAP}px;
            column-fill: auto;
            -webkit-column-fill: auto;
            height: 100%;
            scroll-snap-align: start;
        `;
        
        // Restore the preserved font size if it existed
        if (preservedFontSize) {
            wrapper.style.fontSize = preservedFontSize;
        }
        
        // CRITICAL FIX: Force reflow before measurement to get accurate scrollWidth
        wrapper.offsetHeight; // Force reflow
        
        // CRITICAL FIX: Compute page count and set wrapper width to an exact multiple
        // of viewport width. This guarantees horizontal grid alignment and prevents
        // vertical stacking that breaks horizontal paging.
        var useWidth = columnWidth > 0 ? columnWidth : FALLBACK_WIDTH;
        var scrollWidth = wrapper.scrollWidth;
        var pageCount = Math.max(1, Math.ceil(scrollWidth / useWidth));
        var exactWidth = pageCount * useWidth;
        
        wrapper.style.width = exactWidth + 'px';
        
        // Force another reflow after setting width to ensure layout is stable
        wrapper.offsetHeight;
        
        console.log('inpage_paginator: [STYLES] Set wrapper width=' + exactWidth + 'px (pageCount=' + pageCount + 
                   ', scrollWidth=' + scrollWidth + ', columnWidth=' + columnWidth + ')');
    }
    
    /**
     * Recompute and reapply column width.
     * Called on resize, orientation changes, font/line-height changes, and after streaming appends.
     * This is the public API exposed as window.reapplyColumns().
     */
    function reapplyColumns() {
        if (!contentWrapper || !isInitialized) {
            console.warn('inpage_paginator: [REAPPLY] reapplyColumns called before initialization');
            return;
        }
        
        console.log('inpage_paginator: [REAPPLY] Reapplying column styles');
        
        // Recompute column width and apply
        updateColumnStyles(contentWrapper);
        
        // Force reflow after reapply
        if (columnContainer) {
            columnContainer.offsetHeight;
        }
        
        // Log diagnostics after reapply
        logPaginationState('[REAPPLY] Column reapply complete');
    }

    function wrapExistingContentAsSegment() {
        if (!contentWrapper) {
            return;
        }
        
        // Check if content already has section elements with data-chapter-index
        // This happens in window mode when HTML is pre-wrapped with multiple chapters
        const existingSections = contentWrapper.querySelectorAll('section[data-chapter-index]');
        
        if (existingSections.length > 0) {
            // Validate that sections have valid chapter indices
            const validSections = [];
            for (let i = 0; i < existingSections.length; i++) {
                const section = existingSections[i];
                const chapterIndexAttr = section.getAttribute('data-chapter-index');
                const chapterIndex = parseInt(chapterIndexAttr, 10);
                
                // Validate that chapter index is a valid non-negative integer
                if (!isNaN(chapterIndex) && chapterIndex >= 0) {
                    validSections.push(section);
                } else {
                    console.warn('inpage_paginator: [CONFIG] Ignoring section with invalid data-chapter-index: ' + chapterIndexAttr);
                }
            }
            
            if (validSections.length > 0) {
                // Content is already wrapped in valid chapter sections (window mode with pre-wrapped HTML)
                console.log('inpage_paginator: [CONFIG] Found ' + validSections.length + ' pre-wrapped chapter sections');
                chapterSegments = validSections;
                
                // Log the chapter indices for debugging
                const chapterIndices = chapterSegments.map(function(seg) {
                    return seg.getAttribute('data-chapter-index');
                }).join(', ');
                console.log('inpage_paginator: [CONFIG] Pre-wrapped chapters: ' + chapterIndices);
                
                return;
            }
        }
        
        // No valid pre-wrapped sections found - wrap all content in a single segment
        // This is the legacy behavior for chapter mode or non-wrapped window mode
        console.log('inpage_paginator: [CONFIG] No valid pre-wrapped sections found, wrapping content as single segment');
        const segment = document.createElement('section');
        segment.className = 'chapter-segment';
        
        // Use chapterIndex from config if in chapter mode, otherwise use initialChapterIndex
        const chapterIndex = paginatorConfig.mode === 'chapter' && paginatorConfig.chapterIndex !== null
            ? paginatorConfig.chapterIndex
            : initialChapterIndex;
        
        segment.setAttribute('data-chapter-index', chapterIndex);
        const fragment = document.createDocumentFragment();
        while (contentWrapper.firstChild) {
            fragment.appendChild(contentWrapper.firstChild);
        }
        segment.appendChild(fragment);
        contentWrapper.appendChild(segment);
        chapterSegments = [segment];
        
        console.log('inpage_paginator: [CONFIG] Wrapped content as chapter ' + chapterIndex);
    }
    
    /**
     * Handle window resize - reflow columns
     */
    function handleResize() {
        if (!columnContainer) return;
        
        const oldWidth = viewportWidth;
        viewportWidth = window.innerWidth;
        
        if (oldWidth !== viewportWidth) {
            reflow();
        }
    }
    
    /**
     * Reflow the content - recalculate columns and page count
     * Enhanced with better error handling and position preservation
     * @param {boolean} preservePosition - Whether to try to preserve reading position (default: true)
     * @returns {Object} - Result object with success status and new page count
     */
    function reflow(preservePosition) {
        if (preservePosition === undefined) {
            preservePosition = true;
        }
        
        if (!isInitialized || !columnContainer) {
            console.warn('inpage_paginator: Reflow called before initialization, attempting init');
            init();
            return { success: false, pageCount: 0 };
        }
        
        console.log('inpage_paginator: Reflow triggered (preservePosition=' + preservePosition + ')');
        
        try {
            // Save current state before reflow
            let currentPageBeforeReflow = 0;
            let currentChapterBeforeReflow = -1;
            
            if (preservePosition) {
                try {
                    currentPageBeforeReflow = getCurrentPage();
                    currentChapterBeforeReflow = getCurrentChapter();
                    console.log('inpage_paginator: Saving position - page=' + currentPageBeforeReflow + ', chapter=' + currentChapterBeforeReflow);
                } catch (e) {
                    console.warn('inpage_paginator: Error saving position during reflow', e);
                    preservePosition = false;
                }
            }
            
            const contentWrapper = document.getElementById('paginator-content');
            if (!contentWrapper) {
                console.error('inpage_paginator: Content wrapper not found during reflow');
                return { success: false, pageCount: 0 };
            }
            
            // Update column styles
            updateColumnStyles(contentWrapper);
            
            // Force reflow by temporarily changing a property
            const oldDisplay = columnContainer.style.display;
            columnContainer.style.display = 'none';
            columnContainer.offsetHeight; // Force reflow
            columnContainer.style.display = oldDisplay || '';
            
            // Additional force reflow for all segments
            chapterSegments.forEach(function(seg) {
                if (seg) {
                    seg.offsetHeight; // Force reflow on each segment
                }
            });
            
            // Get new page count
            const pageCount = getPageCount();
            console.log('inpage_paginator: Reflow calculated new pageCount=' + pageCount);
            
            // Restore position if requested
            if (preservePosition && pageCount > 0) {
                const targetPage = Math.max(0, Math.min(currentPageBeforeReflow, pageCount - 1));
                console.log('inpage_paginator: Restoring to page=' + targetPage);
                goToPage(targetPage, false);
            } else {
                // If not preserving position, sync from actual scroll
                syncCurrentPageFromScroll();
            }
            
            console.log('inpage_paginator: [STATE] Reflow complete - pageCount=' + pageCount + ', currentPage=' + currentPage);
            
            // CRITICAL: Reset and set isPaginationReady after reflow completes
            // Use requestAnimationFrame to ensure layout is stable before notifying
            isPaginationReady = false;
            console.log('inpage_paginator: [STATE] isPaginationReady temporarily set to false during reflow');
            
            requestAnimationFrame(function() {
                isPaginationReady = true;
                console.log('inpage_paginator: [STATE] isPaginationReady set back to true after reflow layout');
                
                // Log pagination state for diagnostics
                logPaginationState('[REFLOW] reflow complete');
                
                // Notify Android if callback exists
                if (window.AndroidBridge && window.AndroidBridge.onPaginationReady) {
                    console.log('inpage_paginator: [CALLBACK] Calling AndroidBridge.onPaginationReady after reflow with pageCount=' + pageCount);
                    window.AndroidBridge.onPaginationReady(pageCount);
                }
            });
            
            return { success: true, pageCount: pageCount, currentPage: currentPage };
        } catch (e) {
            console.error('inpage_paginator: Reflow error', e);
            isPaginationReady = false;
            return { success: false, pageCount: 0, error: e.message };
        }
    }
    
    /**
     * Set font size in pixels and reflow
     */
    function setFontSize(px) {
        if (px <= 0) {
            console.warn('inpage_paginator: invalid font size', px);
            return;
        }
        
        console.log('inpage_paginator: [STATE] Setting font size to ' + px + 'px - will trigger reflow');
        
        currentFontSize = px;
        
        // Apply font size to content
        const contentWrapper = document.getElementById('paginator-content');
        if (contentWrapper) {
            contentWrapper.style.fontSize = px + 'px';
        }
        
        // Reflow to adjust columns
        reflow();
    }
    
    /**
     * Get the current page count based on scroll width and viewport width
     * 
     * ISSUE 4 FIX: When returning page count during reflow (isPaginationReady=false),
     * return lastKnownPageCount instead of calculating, to prevent page count flips.
     */
    function getPageCount() {
        if (!columnContainer) {
            init();
        }
        
        // Double check after init - if still not ready, return safe default
        if (!columnContainer || !isInitialized) {
            console.warn('inpage_paginator: getPageCount called before initialization complete');
            return lastKnownPageCount > 0 ? lastKnownPageCount : 1;
        }
        
        // ISSUE 4 FIX: When pagination is not ready (e.g., during reflow), 
        // return lastKnownPageCount to prevent page count flips
        if (!isPaginationReady && lastKnownPageCount > 0) {
            console.log('inpage_paginator: getPageCount during reflow, returning lastKnownPageCount=' + lastKnownPageCount);
            return lastKnownPageCount;
        }
        
        const contentWrapper = document.getElementById('paginator-content');
        if (!contentWrapper) {
            console.warn('inpage_paginator: contentWrapper not found in getPageCount');
            return lastKnownPageCount > 0 ? lastKnownPageCount : 1;
        }
        
        const scrollWidth = contentWrapper.scrollWidth;
        const pageWidth = viewportWidth || window.innerWidth;
        
        if (pageWidth === 0) {
            return lastKnownPageCount > 0 ? lastKnownPageCount : 1;
        }
        
        const computedPageCount = Math.max(1, Math.ceil(scrollWidth / pageWidth));
        
        // ISSUE 4 FIX: Update lastKnownPageCount when we have a valid calculation
        if (isPaginationReady && computedPageCount > 0) {
            lastKnownPageCount = computedPageCount;
        }
        
        return computedPageCount;
    }
    
    /**
     * Get the current page index (0-based)
     * CRITICAL: Syncs with DOM scroll position before returning, to avoid stale values
     * when CSS reflows occur due to dynamic chapter loading.
     */
    function getCurrentPage() {
        if (!columnContainer) {
            console.warn('inpage_paginator: getCurrentPage called before initialization');
            return 0;
        }
        
        // Double check initialization
        if (!isInitialized) {
            console.warn('inpage_paginator: getCurrentPage called before initialization complete');
            return 0;
        }
        
        // CRITICAL FIX: Always sync from DOM before returning
        // This prevents returning stale values when CSS reflows reset scroll position
        syncCurrentPageFromScroll();
        
        // Return the synced page
        return currentPage;
    }
    
    /**
     * Sync the currentPage state with the actual scroll position.
     * Called when user manually scrolls or when we need to read the real position.
     * Uses Math.round() to handle sub-pixel scroll positions correctly.
     */
    function syncCurrentPageFromScroll() {
        if (!columnContainer || !isInitialized) {
            return;
        }
        
        // Don't sync during programmatic scrolling - goToPage() already set currentPage
        if (isProgrammaticScroll) {
            console.log('inpage_paginator: syncCurrentPageFromScroll - skipping during programmatic scroll');
            return;
        }
        
        const scrollLeft = columnContainer.scrollLeft;
        const pageWidth = viewportWidth || window.innerWidth;
        
        if (pageWidth === 0) {
            return;
        }
        
        // Use Math.round() instead of Math.floor() to handle sub-pixel positioning
        // When scrollTo() snaps to page boundaries, scrollLeft might be pageWidth * 7.999
        // which would floor to 7 instead of the intended page 8
        const newPage = Math.round(scrollLeft / pageWidth);
        if (newPage !== currentPage) {
            console.log('inpage_paginator: syncCurrentPageFromScroll - updating currentPage from ' + currentPage + ' to ' + newPage);
            currentPage = newPage;
        }
    }
    
    /**
     * Go to a specific page (0-based index)
     * CRITICAL: Updates currentPage state immediately to avoid async race conditions
     */
    function goToPage(index, smooth) {
        if (!columnContainer) {
            console.warn('inpage_paginator: goToPage called before initialization');
            init();
        }
        
        // Double check after init
        if (!columnContainer || !isInitialized) {
            console.error('inpage_paginator: goToPage - paginator not ready, cannot navigate');
            return;
        }
        
        const pageCount = getPageCount();
        const safeIndex = Math.max(0, Math.min(index, pageCount - 1));
        const pageWidth = viewportWidth || window.innerWidth;
        const targetScroll = safeIndex * pageWidth;
        
        console.log('inpage_paginator: goToPage - index=' + index + ', safeIndex=' + safeIndex + ', pageCount=' + pageCount + ', smooth=' + smooth);
        
        // Debug unexpected page resets
        if (index === 0 && currentPage > 0) {
            console.log('inpage_paginator: WARNING - Resetting to page 0 from page ' + currentPage);
            console.log('inpage_paginator: Call stack:', new Error().stack);
        }
        
        // CRITICAL FIX: Update currentPage state IMMEDIATELY before scrolling
        // This ensures that subsequent calls to getCurrentPage() return the correct value
        // even before the scrollTo() animation completes
        const prevPage = currentPage;
        currentPage = safeIndex;
        console.log('inpage_paginator: goToPage internal state - prevPage=' + prevPage + ', newPage=' + currentPage);
        
        // Set flag to prevent scroll event from overwriting currentPage
        isProgrammaticScroll = true;
        
        const behavior = smooth ? SCROLL_BEHAVIOR_SMOOTH : SCROLL_BEHAVIOR_AUTO;
        
        console.log('inpage_paginator: Attempting to scroll to page ' + safeIndex + ' (targetScrollLeft=' + targetScroll + '), current scrollLeft=' + columnContainer.scrollLeft);
        
        columnContainer.scrollTo({
            left: targetScroll,
            behavior: behavior
        });
        
        // Clear the flag after a delay to allow scroll to complete
        // Use a timeout longer than the smooth scroll animation (~300ms)
        setTimeout(function() {
            isProgrammaticScroll = false;
            console.log('inpage_paginator: Programmatic scroll complete, re-enabling scroll sync');
        }, 500);
        
        // Notify Android if callback exists
        if (window.AndroidBridge && window.AndroidBridge.onPageChanged) {
            console.log('inpage_paginator: Calling AndroidBridge.onPageChanged with page=' + safeIndex);
            window.AndroidBridge.onPageChanged(safeIndex);
        }
    }
    
    function notifyBoundary(direction, currentPage, pageCount) {
        if (window.AndroidBridge && window.AndroidBridge.onBoundaryReached) {
            try {
                window.AndroidBridge.onBoundaryReached(direction, currentPage, pageCount);
            } catch (err) {
                console.error('inpage_paginator: onBoundaryReached failed', err);
            }
        }
    }

    function requestStreamingSegment(direction, boundaryPage, totalPages) {
        if (window.AndroidBridge && window.AndroidBridge.onStreamingRequest) {
            try {
                window.AndroidBridge.onStreamingRequest(direction, boundaryPage, totalPages);
            } catch (err) {
                console.error('inpage_paginator: onStreamingRequest failed', err);
            }
        }
    }

    /**
     * Go to next page - with chapter-aware streaming
     */
    function nextPage() {
        if (!columnContainer || !isInitialized) {
            console.warn('inpage_paginator: nextPage called before initialization complete');
            return false;
        }
        
        const currentPage = getCurrentPage();
        const pageCount = getPageCount();
        const currentChapter = getCurrentChapter();
        
        console.log('inpage_paginator: nextPage called - currentPage=' + currentPage + 
                   ', pageCount=' + pageCount + ', currentChapter=' + currentChapter + 
                   ', loadedSegments=' + chapterSegments.length);
        
        if (currentPage < pageCount - 1) {
            goToPage(currentPage + 1, false);
            
            // CHAPTER-AWARE STREAMING: Check if we should proactively load next chapter
            // When we have multiple segments loaded and we're in the middle segment,
            // start loading the next chapter for seamless navigation
            if (chapterSegments.length >= 3) {
                const middleIndex = Math.floor(chapterSegments.length / 2);
                const middleChapterIndex = parseInt(chapterSegments[middleIndex].getAttribute('data-chapter-index'), 10);
                
                if (currentChapter === middleChapterIndex) {
                    console.log('inpage_paginator: [CHAPTER_AWARE] User in middle chapter (' + currentChapter + 
                               '), requesting proactive streaming for next chapter');
                    requestStreamingSegment('NEXT', currentPage, pageCount);
                }
            }
            
            return true;
        }
        
        console.log('inpage_paginator: nextPage - at last page, requesting streaming');
        requestStreamingSegment('NEXT', currentPage, pageCount);
        notifyBoundary('NEXT', currentPage, pageCount);
        return false;
    }
    
    /**
     * Go to previous page - with chapter-aware streaming
     */
    function prevPage() {
        if (!columnContainer || !isInitialized) {
            console.warn('inpage_paginator: prevPage called before initialization complete');
            return false;
        }
        
        const currentPage = getCurrentPage();
        const pageCount = getPageCount();
        const currentChapter = getCurrentChapter();
        
        console.log('inpage_paginator: prevPage called - currentPage=' + currentPage + 
                   ', pageCount=' + pageCount + ', currentChapter=' + currentChapter + 
                   ', loadedSegments=' + chapterSegments.length);
        
        if (currentPage > 0) {
            goToPage(currentPage - 1, false);
            
            // CHAPTER-AWARE STREAMING: Check if we should proactively load previous chapter
            // When we have multiple segments loaded and we're in the middle segment,
            // start loading the previous chapter for seamless navigation
            if (chapterSegments.length >= 3) {
                const middleIndex = Math.floor(chapterSegments.length / 2);
                const middleChapterIndex = parseInt(chapterSegments[middleIndex].getAttribute('data-chapter-index'), 10);
                
                if (currentChapter === middleChapterIndex) {
                    console.log('inpage_paginator: [CHAPTER_AWARE] User in middle chapter (' + currentChapter + 
                               '), requesting proactive streaming for previous chapter');
                    requestStreamingSegment('PREVIOUS', currentPage, pageCount);
                }
            }
            
            return true;
        }
        
        console.log('inpage_paginator: prevPage - at first page, requesting streaming');
        requestStreamingSegment('PREVIOUS', currentPage, pageCount);
        notifyBoundary('PREVIOUS', currentPage, pageCount);
        return false;
    }

    function buildSegmentFromHtml(chapterIndex, rawHtml) {
        const segment = document.createElement('section');
        segment.className = 'chapter-segment';
        segment.setAttribute('data-chapter-index', chapterIndex);
        try {
            const parser = new DOMParser();
            const parsed = parser.parseFromString(rawHtml, 'text/html');
            const body = parsed.body || parsed.documentElement || parsed;
            const fragment = document.createDocumentFragment();
            Array.from(body.childNodes).forEach(node => {
                fragment.appendChild(node.cloneNode(true));
            });
            segment.appendChild(fragment);
        } catch (err) {
            console.error('inpage_paginator: buildSegmentFromHtml failed', err);
            segment.innerHTML = rawHtml;
        }
        return segment;
    }

    function trimSegmentsFromStart() {
        if (chapterSegments.length <= MAX_CHAPTER_SEGMENTS) {
            return;
        }
        
        // ISSUE 4 FIX: Check hysteresis - don't evict segments within EVICTION_HYSTERESIS_MS of last append
        const now = Date.now();
        if (lastAppendedTimestamp > 0 && (now - lastAppendedTimestamp) < EVICTION_HYSTERESIS_MS) {
            console.log('inpage_paginator: [HYSTERESIS] Skipping segment eviction - within ' + EVICTION_HYSTERESIS_MS + 'ms of last append');
            return;
        }
        
        while (chapterSegments.length > MAX_CHAPTER_SEGMENTS) {
            const seg = chapterSegments.shift();
            if (seg && seg.parentNode) {
                const chapterIndexAttr = seg.getAttribute('data-chapter-index');
                // Guard against null attribute - skip segment if no chapter index
                // Use == null which covers both null and undefined in JavaScript
                if (chapterIndexAttr == null) {
                    console.warn('inpage_paginator: Segment missing data-chapter-index, skipping eviction');
                    continue;
                }
                const chapterIndex = parseInt(chapterIndexAttr, 10);
                // Guard against NaN from parseInt
                if (isNaN(chapterIndex)) {
                    console.warn('inpage_paginator: Invalid data-chapter-index value: ' + chapterIndexAttr);
                    continue;
                }
                
                // ISSUE 4 FIX: Check if this is the just-appended segment
                if (chapterIndex === lastAppendedChapterIndex && (now - lastAppendedTimestamp) < EVICTION_HYSTERESIS_MS) {
                    console.log('inpage_paginator: [HYSTERESIS] Skipping eviction of just-appended chapter ' + chapterIndex);
                    chapterSegments.unshift(seg); // Put it back at the start
                    return; // Stop trimming
                }
                
                seg.parentNode.removeChild(seg);
                if (window.AndroidBridge && window.AndroidBridge.onSegmentEvicted) {
                    try {
                        window.AndroidBridge.onSegmentEvicted(chapterIndex);
                    } catch (err) {
                        console.warn('inpage_paginator: onSegmentEvicted callback failed', err);
                    }
                }
            }
        }
    }

    function trimSegmentsFromEnd() {
        if (chapterSegments.length <= MAX_CHAPTER_SEGMENTS) {
            return;
        }
        
        // ISSUE 4 FIX: Check hysteresis - don't evict segments within EVICTION_HYSTERESIS_MS of last append
        const now = Date.now();
        if (lastAppendedTimestamp > 0 && (now - lastAppendedTimestamp) < EVICTION_HYSTERESIS_MS) {
            console.log('inpage_paginator: [HYSTERESIS] Skipping segment eviction - within ' + EVICTION_HYSTERESIS_MS + 'ms of last append');
            return;
        }
        
        while (chapterSegments.length > MAX_CHAPTER_SEGMENTS) {
            const seg = chapterSegments.pop();
            if (seg && seg.parentNode) {
                const chapterIndexAttr = seg.getAttribute('data-chapter-index');
                // Guard against null attribute - skip segment if no chapter index
                // Use == null which covers both null and undefined in JavaScript
                if (chapterIndexAttr == null) {
                    console.warn('inpage_paginator: Segment missing data-chapter-index, skipping eviction');
                    continue;
                }
                const chapterIndex = parseInt(chapterIndexAttr, 10);
                // Guard against NaN from parseInt
                if (isNaN(chapterIndex)) {
                    console.warn('inpage_paginator: Invalid data-chapter-index value: ' + chapterIndexAttr);
                    continue;
                }
                
                // ISSUE 4 FIX: Check if this is the just-appended segment
                if (chapterIndex === lastAppendedChapterIndex && (now - lastAppendedTimestamp) < EVICTION_HYSTERESIS_MS) {
                    console.log('inpage_paginator: [HYSTERESIS] Skipping eviction of just-appended chapter ' + chapterIndex);
                    chapterSegments.push(seg); // Put it back at the end
                    return; // Stop trimming
                }
                
                seg.parentNode.removeChild(seg);
                if (window.AndroidBridge && window.AndroidBridge.onSegmentEvicted) {
                    try {
                        window.AndroidBridge.onSegmentEvicted(chapterIndex);
                    } catch (err) {
                        console.warn('inpage_paginator: onSegmentEvicted callback failed', err);
                    }
                }
            }
        }
    }

    /**
     * Finalize window construction and enter active (immutable) mode.
     * After this, no mutations (append/prepend) are allowed.
     */
    function finalizeWindow() {
        if (windowMode === 'ACTIVE') {
            console.warn('inpage_paginator: [MODE] finalizeWindow called but already in ACTIVE mode');
            return;
        }
        
        windowMode = 'ACTIVE';
        console.log('inpage_paginator: [MODE] Window finalized - entering ACTIVE mode (mutations forbidden)');
        
        // Notify Android
        if (window.AndroidBridge && window.AndroidBridge.onWindowFinalized) {
            const pageCount = getPageCount();
            console.log('inpage_paginator: [CALLBACK] Calling AndroidBridge.onWindowFinalized with pageCount=' + pageCount);
            window.AndroidBridge.onWindowFinalized(pageCount);
        }
    }
    
    /**
     * Append a chapter segment to the end of the content
     * Enhanced with better error handling and scroll position preservation
     * 
     * IMPORTANT: Only allowed during CONSTRUCTION mode, not during active reading.
     * 
     * ISSUE 4 FIX: Tracks lastAppendedChapterIndex and lastAppendedTimestamp for hysteresis.
     * 
     * @param {number} chapterIndex - The chapter index to append
     * @param {string} rawHtml - The HTML content to append
     * @returns {boolean} - True if successful, false otherwise
     */
    function appendChapterSegment(chapterIndex, rawHtml) {
        // MODE ENFORCEMENT: Only allow in CONSTRUCTION mode
        if (windowMode !== 'CONSTRUCTION') {
            console.error('inpage_paginator: [MODE] appendChapterSegment forbidden in ACTIVE mode - use window transition instead');
            throw new Error('Cannot append chapter: window is in ACTIVE mode');
        }
        
        if (!contentWrapper) {
            console.warn('inpage_paginator: appendChapterSegment called before init');
            return false;
        }
        
        if (!rawHtml || rawHtml.trim() === '') {
            console.warn('inpage_paginator: appendChapterSegment called with empty HTML');
            return false;
        }
        
        try {
            console.log('inpage_paginator: [STREAMING] Appending chapter segment ' + chapterIndex + ' (CONSTRUCTION mode), isPaginationReady=' + isPaginationReady);
            
            // ISSUE 4 FIX: Track last appended chapter for hysteresis
            lastAppendedChapterIndex = chapterIndex;
            lastAppendedTimestamp = Date.now();
            
            // Save current scroll position before modification
            const currentPage = getCurrentPage();
            const pageCountBefore = getPageCount();
            
            const segment = buildSegmentFromHtml(chapterIndex, rawHtml);
            contentWrapper.appendChild(segment);
            chapterSegments.push(segment);
            
            // Trim old segments if needed - but avoid simultaneous append+evict in same frame
            // Schedule eviction for next animation frame to reduce oscillation
            requestAnimationFrame(function() {
                trimSegmentsFromStart();
            });
            
            // Reflow to recalculate layout (includes column style update)
            const reflowResult = reflow(true);
            
            // Log pagination state after append
            logPaginationState('[STREAMING_APPEND] chapter=' + chapterIndex);
            
            console.log('inpage_paginator: [STREAMING] Appended chapter ' + chapterIndex + 
                       ' - pages before=' + pageCountBefore + 
                       ', pages after=' + reflowResult.pageCount);
            
            return reflowResult.success;
        } catch (e) {
            console.error('inpage_paginator: appendChapterSegment error', e);
            return false;
        }
    }

    /**
     * Prepend a chapter segment to the beginning of the content
     * Enhanced with better error handling and scroll adjustment
     * 
     * IMPORTANT: Only allowed during CONSTRUCTION mode, not during active reading.
     * 
     * @param {number} chapterIndex - The chapter index to prepend
     * @param {string} rawHtml - The HTML content to prepend
     * @returns {boolean} - True if successful, false otherwise
     */
    function prependChapterSegment(chapterIndex, rawHtml) {
        // MODE ENFORCEMENT: Only allow in CONSTRUCTION mode
        if (windowMode !== 'CONSTRUCTION') {
            console.error('inpage_paginator: [MODE] prependChapterSegment forbidden in ACTIVE mode - use window transition instead');
            throw new Error('Cannot prepend chapter: window is in ACTIVE mode');
        }
        
        if (!contentWrapper) {
            console.warn('inpage_paginator: prependChapterSegment called before init');
            return false;
        }
        
        if (!rawHtml || rawHtml.trim() === '') {
            console.warn('inpage_paginator: prependChapterSegment called with empty HTML');
            return false;
        }
        
        try {
            console.log('inpage_paginator: [STREAMING] Prepending chapter segment ' + chapterIndex + ' (CONSTRUCTION mode), isPaginationReady=' + isPaginationReady);
            
            // ISSUE 4 FIX: Track last appended chapter for hysteresis
            lastAppendedChapterIndex = chapterIndex;
            lastAppendedTimestamp = Date.now();
            
            // Save current page and chapter info
            const currentPage = getCurrentPage();
            const pageCountBefore = getPageCount();
            const currentChapter = getCurrentChapter();
            
            const segment = buildSegmentFromHtml(chapterIndex, rawHtml);
            if (contentWrapper.firstChild) {
                contentWrapper.insertBefore(segment, contentWrapper.firstChild);
            } else {
                contentWrapper.appendChild(segment);
            }
            chapterSegments.unshift(segment);
            
            // Trim old segments from end if needed - but avoid simultaneous append+evict in same frame
            // Schedule eviction for next animation frame to reduce oscillation
            requestAnimationFrame(function() {
                trimSegmentsFromEnd();
            });
            
            // Reflow without preserving position initially (includes column style update)
            const reflowResult = reflow(false);
            
            // Calculate how many pages the new segment added
            const pageCountAfter = reflowResult.pageCount || getPageCount();
            const pagesAdded = pageCountAfter - pageCountBefore;
            
            // Adjust scroll position to account for prepended content
            if (pagesAdded > 0 && currentPage >= 0) {
                const newTargetPage = currentPage + pagesAdded;
                console.log('inpage_paginator: [STREAMING] Adjusting scroll after prepend - old page=' + currentPage + 
                           ', pages added=' + pagesAdded + ', new target=' + newTargetPage);
                goToPage(newTargetPage, false);
            }
            
            // Log pagination state after prepend
            logPaginationState('[STREAMING_PREPEND] chapter=' + chapterIndex);
            
            console.log('inpage_paginator: [STREAMING] Prepended chapter ' + chapterIndex + 
                       ' - pages before=' + pageCountBefore + 
                       ', pages after=' + pageCountAfter +
                       ', pages added=' + pagesAdded);
            
            return reflowResult.success;
        } catch (e) {
            console.error('inpage_paginator: prependChapterSegment error', e);
            return false;
        }
    }

    function getSegmentPageCount(chapterIndex) {
        if (!contentWrapper || !columnContainer) {
            return -1;
        }
        const pageWidth = viewportWidth || window.innerWidth;
        if (pageWidth === 0) {
            return -1;
        }
        const segment = chapterSegments.find(seg => {
            const idx = parseInt(seg.getAttribute('data-chapter-index'), 10);
            return idx === chapterIndex;
        });
        if (!segment) {
            return -1;
        }
        const contentRect = contentWrapper.getBoundingClientRect();
        const segmentRect = segment.getBoundingClientRect();
        const offsetLeft = (segmentRect.left - contentRect.left);
        const startPage = Math.floor(Math.max(0, offsetLeft) / pageWidth);
        const endPage = Math.ceil((offsetLeft + segmentRect.width) / pageWidth);
        return Math.max(1, endPage - startPage);
    }

    function setInitialChapterIndex(chapterIndex) {
        initialChapterIndex = chapterIndex;
        if (chapterSegments.length > 0) {
            chapterSegments[0].setAttribute('data-chapter-index', chapterIndex);
        }
    }
    
    /**
     * Get the page index for a given CSS selector
     * Returns the page containing the first matching element
     */
    function getPageForSelector(selector) {
        if (!selector || !columnContainer) {
            return -1;
        }
        
        try {
            const element = document.querySelector(selector);
            if (!element) {
                return -1;
            }
            
            // Get element's position
            const rect = element.getBoundingClientRect();
            const elementLeft = rect.left + columnContainer.scrollLeft;
            const pageWidth = viewportWidth || window.innerWidth;
            
            if (pageWidth === 0) {
                return 0;
            }
            
            return Math.floor(elementLeft / pageWidth);
        } catch (e) {
            console.error('inpage_paginator: getPageForSelector error', e);
            return -1;
        }
    }
    
    /**
     * Create an anchor element around the viewport top
     * Useful for preserving reading position during font size changes
     */
    function createAnchorAroundViewportTop(anchorId) {
        if (!anchorId || !columnContainer) {
            return false;
        }
        
        try {
            // Find the first visible text node near the top of the viewport
            const scrollLeft = columnContainer.scrollLeft;
            const viewportLeft = scrollLeft;
            const viewportRight = scrollLeft + (viewportWidth || window.innerWidth);
            
            // Get all text nodes in the visible area
            const contentWrapper = document.getElementById('paginator-content');
            if (!contentWrapper) {
                return false;
            }
            
            // Simple approach: find first visible block-level element
            const blocks = contentWrapper.querySelectorAll('p, h1, h2, h3, h4, h5, h6, li, div');
            
            for (let i = 0; i < blocks.length; i++) {
                const block = blocks[i];
                const rect = block.getBoundingClientRect();
                const blockLeft = rect.left + scrollLeft;
                
                if (blockLeft >= viewportLeft && blockLeft < viewportRight) {
                    // Found a visible block, insert anchor
                    const anchor = document.createElement('span');
                    anchor.id = anchorId;
                    anchor.style.position = 'absolute';
                    anchor.style.left = '0';
                    anchor.style.top = '0';
                    
                    block.insertBefore(anchor, block.firstChild);
                    return true;
                }
            }
            
            return false;
        } catch (e) {
            console.error('inpage_paginator: createAnchorAroundViewportTop error', e);
            return false;
        }
    }
    
    /**
     * Scroll to an anchor element by ID
     */
    function scrollToAnchor(anchorId) {
        if (!anchorId) {
            return false;
        }
        
        try {
            const anchor = document.getElementById(anchorId);
            if (!anchor) {
                return false;
            }
            
            // Get anchor position and determine page
            const rect = anchor.getBoundingClientRect();
            const anchorLeft = rect.left + (columnContainer ? columnContainer.scrollLeft : 0);
            const pageWidth = viewportWidth || window.innerWidth;
            
            if (pageWidth === 0) {
                return false;
            }
            
            const pageIndex = Math.floor(anchorLeft / pageWidth);
            goToPage(pageIndex, false);
            
            return true;
        } catch (e) {
            console.error('inpage_paginator: scrollToAnchor error', e);
            return false;
        }
    }
    
    /**
     * Jump to a specific chapter by chapter index
     * Useful for TOC navigation - scrolls to the first page of the specified chapter
     * @param {number} chapterIndex - The chapter index to jump to
     * @param {boolean} smooth - Whether to animate the transition
     * @returns {boolean} - True if successful, false otherwise
     */
    function jumpToChapter(chapterIndex, smooth) {
        if (!contentWrapper || !columnContainer || !isInitialized) {
            console.warn('inpage_paginator: jumpToChapter called before initialization');
            return false;
        }
        
        try {
            // Find the segment with the matching chapter index
            const segment = chapterSegments.find(seg => {
                const idx = parseInt(seg.getAttribute('data-chapter-index'), 10);
                return idx === chapterIndex;
            });
            
            if (!segment) {
                console.warn('inpage_paginator: Chapter segment not found for index', chapterIndex);
                // Notify Android that chapter is not loaded
                if (window.AndroidBridge && window.AndroidBridge.onChapterNotLoaded) {
                    window.AndroidBridge.onChapterNotLoaded(chapterIndex);
                }
                return false;
            }
            
            // Calculate the page index for this segment
            const pageWidth = viewportWidth || window.innerWidth;
            if (pageWidth === 0) {
                return false;
            }
            
            const contentRect = contentWrapper.getBoundingClientRect();
            const segmentRect = segment.getBoundingClientRect();
            const offsetLeft = segmentRect.left - contentRect.left + columnContainer.scrollLeft;
            const pageIndex = Math.floor(Math.max(0, offsetLeft) / pageWidth);
            
            console.log('inpage_paginator: Jumping to chapter', chapterIndex, 'at page', pageIndex);
            goToPage(pageIndex, smooth || false);
            
            // Notify Android of successful chapter jump
            if (window.AndroidBridge && window.AndroidBridge.onChapterJumped) {
                window.AndroidBridge.onChapterJumped(chapterIndex, pageIndex);
            }
            
            return true;
        } catch (e) {
            console.error('inpage_paginator: jumpToChapter error', e);
            return false;
        }
    }
    
    /**
     * Remove a specific chapter segment by chapter index
     * @param {number} chapterIndex - The chapter index to remove
     * @returns {boolean} - True if successful, false otherwise
     */
    function removeChapterSegment(chapterIndex) {
        if (!contentWrapper) {
            console.warn('inpage_paginator: removeChapterSegment called before init');
            return false;
        }
        
        try {
            // Find segment index
            const segmentIndex = chapterSegments.findIndex(seg => {
                const idx = parseInt(seg.getAttribute('data-chapter-index'), 10);
                return idx === chapterIndex;
            });
            
            if (segmentIndex === -1) {
                console.warn('inpage_paginator: Chapter segment not found for removal', chapterIndex);
                return false;
            }
            
            // Save current page before removal
            const currentPage = getCurrentPage();
            
            // Remove from DOM and array
            const segment = chapterSegments[segmentIndex];
            if (segment && segment.parentNode) {
                segment.parentNode.removeChild(segment);
            }
            chapterSegments.splice(segmentIndex, 1);
            
            console.log('inpage_paginator: Removed chapter segment', chapterIndex);
            
            // Reflow after removal
            reflow();
            
            // Try to restore a reasonable page position
            const newPageCount = getPageCount();
            if (newPageCount > 0) {
                const targetPage = Math.min(currentPage, newPageCount - 1);
                goToPage(targetPage, false);
            }
            
            return true;
        } catch (e) {
            console.error('inpage_paginator: removeChapterSegment error', e);
            return false;
        }
    }
    
    /**
     * Clear all chapter segments except the initial one
     * Useful for resetting the view or handling errors
     */
    function clearAllSegments() {
        if (!contentWrapper) {
            return false;
        }
        
        try {
            // Remove all segments from DOM
            chapterSegments.forEach(seg => {
                if (seg && seg.parentNode) {
                    seg.parentNode.removeChild(seg);
                }
            });
            
            // Clear the array
            chapterSegments = [];
            
            console.log('inpage_paginator: Cleared all chapter segments');
            
            // Reflow
            reflow();
            
            return true;
        } catch (e) {
            console.error('inpage_paginator: clearAllSegments error', e);
            return false;
        }
    }
    
    /**
     * Get information about all loaded chapter segments
     * @returns {Array} - Array of objects with chapter info
     */
    function getLoadedChapters() {
        if (!contentWrapper || !columnContainer) {
            return [];
        }
        
        try {
            const pageWidth = viewportWidth || window.innerWidth;
            if (pageWidth === 0) {
                return [];
            }
            
            const contentRect = contentWrapper.getBoundingClientRect();
            
            return chapterSegments.map(seg => {
                const chapterIndex = parseInt(seg.getAttribute('data-chapter-index'), 10);
                const segmentRect = seg.getBoundingClientRect();
                const offsetLeft = segmentRect.left - contentRect.left + columnContainer.scrollLeft;
                const startPage = Math.floor(Math.max(0, offsetLeft) / pageWidth);
                const endPage = Math.ceil((offsetLeft + segmentRect.width) / pageWidth);
                const pageCount = Math.max(1, endPage - startPage);
                
                return {
                    chapterIndex: chapterIndex,
                    startPage: startPage,
                    endPage: endPage,
                    pageCount: pageCount
                };
            });
        } catch (e) {
            console.error('inpage_paginator: getLoadedChapters error', e);
            return [];
        }
    }
    
    /**
     * Get the chapter index for the currently visible page
     * @returns {number} - Chapter index or -1 if not found
     */
    function getCurrentChapter() {
        if (!contentWrapper || !columnContainer || !isInitialized) {
            return -1;
        }
        
        try {
            const currentPage = getCurrentPage();
            const pageWidth = viewportWidth || window.innerWidth;
            if (pageWidth === 0) {
                return -1;
            }
            
            const currentScrollLeft = columnContainer.scrollLeft;
            const contentRect = contentWrapper.getBoundingClientRect();
            
            // Find which segment contains the current scroll position
            for (let i = 0; i < chapterSegments.length; i++) {
                const seg = chapterSegments[i];
                const segmentRect = seg.getBoundingClientRect();
                const offsetLeft = segmentRect.left - contentRect.left + currentScrollLeft;
                const segmentRight = offsetLeft + segmentRect.width;
                
                if (currentScrollLeft >= offsetLeft && currentScrollLeft < segmentRight) {
                    return parseInt(seg.getAttribute('data-chapter-index'), 10);
                }
            }
            
            // Fallback: return the first segment's chapter
            if (chapterSegments.length > 0) {
                return parseInt(chapterSegments[0].getAttribute('data-chapter-index'), 10);
            }
            
            return -1;
        } catch (e) {
            console.error('inpage_paginator: getCurrentChapter error', e);
            return -1;
        }
    }
    
    /**
     * Check if paginator is initialized and ready for operations.
     * CRITICAL: This now checks isPaginationReady flag to ensure we don't return
     * true until AFTER the onPaginationReady callback has fired with accurate page counts.
     */
    function isReady() {
        const ready = isPaginationReady && 
                      columnContainer !== null && 
                      document.getElementById('paginator-content') !== null;
        console.log('inpage_paginator: [STATE] isReady() called - returning ' + ready + 
                   ' (isPaginationReady=' + isPaginationReady + 
                   ', isInitialized=' + isInitialized + ')');
        return ready;
    }
    
    // ========================================================================
    // Window Communication API
    // ========================================================================
    // These functions implement the pseudo-API for Android ↔ JavaScript
    // communication for managing reading windows.
    // See docs/complete/WINDOW_COMMUNICATION_API.md for full documentation.
    
    /**
     * Load a complete window for reading.
     * This is the primary entry point for initializing a window after loading
     * HTML content into the WebView.
     * 
     * CRITICAL FIX: This function now keeps windowMode='CONSTRUCTION' while appending
     * initial chapters from descriptor.chapters, allowing Android-initiated streaming
     * during initial load. It only calls finalizeWindow() after all initial segments
     * are appended and reflowed.
     * 
     * @param {Object} descriptor - WindowDescriptor from Android
     * @param {number} descriptor.windowIndex - Window index
     * @param {number} descriptor.firstChapterIndex - First chapter index
     * @param {number} descriptor.lastChapterIndex - Last chapter index
     * @param {Array} [descriptor.chapters] - Optional array of chapter data (string HTML or {chapterIndex, html} objects)
     * @param {Object} [descriptor.entryPosition] - Optional entry position
     * @param {number} [descriptor.estimatedPageCount] - Optional estimated page count
     */
    function loadWindow(descriptor) {
        if (!descriptor) {
            console.error('inpage_paginator: [WINDOW_API] loadWindow called with null descriptor');
            notifyWindowLoadError(UNKNOWN_WINDOW_INDEX, 'Null descriptor', 'INVALID_DESCRIPTOR');
            return;
        }
        
        console.log('inpage_paginator: [WINDOW_API] loadWindow called for window ' + descriptor.windowIndex +
                   ' with chapters ' + descriptor.firstChapterIndex + '-' + descriptor.lastChapterIndex);
        
        try {
            // 1. Configure paginator for window mode (keeps windowMode = 'CONSTRUCTION')
            configure({
                mode: 'window',
                windowIndex: descriptor.windowIndex,
                rootSelector: '#window-root'
            });
            
            // 2. CRITICAL FIX: Append chapters from descriptor.chapters BEFORE finalizing
            // This allows Android-initiated streaming during initial load while still
            // in CONSTRUCTION mode. Without this, appendChapterSegment would throw.
            if (descriptor.chapters && Array.isArray(descriptor.chapters) && descriptor.chapters.length > 0) {
                console.log('inpage_paginator: [WINDOW_API] Appending ' + descriptor.chapters.length + 
                           ' chapter(s) from descriptor.chapters (windowMode=' + windowMode + ')');
                
                for (let i = 0; i < descriptor.chapters.length; i++) {
                    const chapterData = descriptor.chapters[i];
                    let chapterIndex;
                    let html;
                    
                    // Support both simple string arrays and {chapterIndex, html} objects
                    if (typeof chapterData === 'string') {
                        // String array: use firstChapterIndex + offset as chapterIndex
                        chapterIndex = descriptor.firstChapterIndex + i;
                        html = chapterData;
                    } else if (chapterData && typeof chapterData === 'object') {
                        // Object with chapterIndex and html
                        chapterIndex = chapterData.chapterIndex !== undefined ? chapterData.chapterIndex : (descriptor.firstChapterIndex + i);
                        html = chapterData.html || '';
                    } else {
                        console.warn('inpage_paginator: [WINDOW_API] Skipping invalid chapter data at index ' + i);
                        continue;
                    }
                    
                    if (html && html.trim() !== '') {
                        console.log('inpage_paginator: [WINDOW_API] Building segment for chapter ' + chapterIndex);
                        const segment = buildSegmentFromHtml(chapterIndex, html);
                        contentWrapper.appendChild(segment);
                        chapterSegments.push(segment);
                    }
                }
                
                // 3. Reflow once after appending all initial segments
                console.log('inpage_paginator: [WINDOW_API] Reflowing after appending ' + chapterSegments.length + ' segment(s)');
                reflow(false);
            }
            
            // 4. Now finalize window (lock down for reading - enter ACTIVE mode)
            console.log('inpage_paginator: [WINDOW_API] Finalizing window after initial segment load');
            finalizeWindow();
            
            // 5. Navigate to entry position if specified
            if (descriptor.entryPosition) {
                const entry = descriptor.entryPosition;
                console.log('inpage_paginator: [WINDOW_API] Navigating to entry position: chapter=' + 
                           entry.chapterIndex + ', page=' + entry.inPageIndex);
                
                // Jump to chapter first, then to specific page
                // Note: jumpToChapter and goToPage update currentPage synchronously
                // but scrolling may be animated. For entry position, we use non-smooth
                // scrolling (false) to ensure immediate positioning.
                jumpToChapter(entry.chapterIndex, false);
                if (entry.inPageIndex > 0) {
                    goToPage(entry.inPageIndex, false);
                }
            }
            
            // 6. Report ready to Android with chapter boundaries
            const pageCount = getPageCount();
            const chapterBoundaries = getChapterBoundaries();
            
            console.log('inpage_paginator: [WINDOW_API] Window loaded successfully - pageCount=' + pageCount);
            
            if (window.AndroidBridge && window.AndroidBridge.onWindowLoaded) {
                window.AndroidBridge.onWindowLoaded(JSON.stringify({
                    windowIndex: descriptor.windowIndex,
                    pageCount: pageCount,
                    chapterBoundaries: chapterBoundaries
                }));
            }
        } catch (error) {
            console.error('inpage_paginator: [WINDOW_API] loadWindow error:', error);
            notifyWindowLoadError(descriptor.windowIndex, error.message, 'LOAD_FAILED');
        }
    }
    
    /**
     * Get chapter boundaries with page ranges.
     * Returns structured data for position mapping.
     * 
     * @returns {Array} Array of {chapterIndex, startPage, endPage, pageCount}
     */
    function getChapterBoundaries() {
        if (!contentWrapper || !columnContainer) {
            return [];
        }
        
        try {
            const pageWidth = viewportWidth || window.innerWidth;
            if (pageWidth === 0) {
                return [];
            }
            
            const contentRect = contentWrapper.getBoundingClientRect();
            const totalPages = getPageCount();
            
            return chapterSegments.map(function(seg) {
                const chapterIndex = parseInt(seg.getAttribute('data-chapter-index'), 10);
                const segmentRect = seg.getBoundingClientRect();
                const offsetLeft = segmentRect.left - contentRect.left + columnContainer.scrollLeft;
                const startPage = Math.floor(Math.max(0, offsetLeft) / pageWidth);
                // Clamp endPage to totalPages to avoid exceeding window bounds
                const endPage = Math.min(totalPages, Math.ceil((offsetLeft + segmentRect.width) / pageWidth));
                const pageCount = Math.max(1, endPage - startPage);
                
                return {
                    chapterIndex: chapterIndex,
                    startPage: startPage,
                    endPage: endPage,
                    pageCount: pageCount
                };
            });
        } catch (e) {
            console.error('inpage_paginator: getChapterBoundaries error', e);
            return [];
        }
    }
    
    /**
     * Get detailed page mapping info for current position.
     * Enables accurate position restoration during window transitions.
     * 
     * @returns {Object} PageMappingInfo structure
     */
    function getPageMappingInfo() {
        if (!contentWrapper || !columnContainer || !isInitialized) {
            return null;
        }
        
        try {
            const currentChapter = getCurrentChapter();
            const boundaries = getChapterBoundaries();
            const chapterBoundary = boundaries.find(function(b) {
                return b.chapterIndex === currentChapter;
            });
            
            if (!chapterBoundary) {
                return null;
            }
            
            const currentPageIdx = getCurrentPage();
            const chapterPageIdx = Math.max(0, currentPageIdx - chapterBoundary.startPage);
            
            return {
                windowIndex: paginatorConfig.windowIndex,
                chapterIndex: currentChapter,
                windowPageIndex: currentPageIdx,
                chapterPageIndex: chapterPageIdx,
                totalWindowPages: getPageCount(),
                totalChapterPages: chapterBoundary.pageCount
            };
        } catch (e) {
            console.error('inpage_paginator: getPageMappingInfo error', e);
            return null;
        }
    }
    
    /**
     * Notify Android of window load error.
     * @param {number} windowIndex - The window that failed
     * @param {string} errorMessage - Error description
     * @param {string} errorType - Error type code
     */
    function notifyWindowLoadError(windowIndex, errorMessage, errorType) {
        if (window.AndroidBridge && window.AndroidBridge.onWindowLoadError) {
            try {
                window.AndroidBridge.onWindowLoadError(JSON.stringify({
                    windowIndex: windowIndex,
                    errorMessage: errorMessage,
                    errorType: errorType
                }));
            } catch (err) {
                console.error('inpage_paginator: onWindowLoadError callback failed', err);
            }
        }
    }
    
    // Initialize on DOM ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
    
    // Expose global API
    window.inpagePaginator = {
        // Configuration
        configure: configure,
        reconfigure: reconfigure,
        
        // Window Communication API
        loadWindow: loadWindow,
        finalizeWindow: finalizeWindow,
        getChapterBoundaries: getChapterBoundaries,
        getPageMappingInfo: getPageMappingInfo,
        
        // State queries
        isReady: isReady,
        getPageCount: getPageCount,
        getCurrentPage: getCurrentPage,
        getCurrentChapter: getCurrentChapter,
        getLoadedChapters: getLoadedChapters,
        
        // Navigation
        goToPage: goToPage,
        nextPage: nextPage,
        prevPage: prevPage,
        jumpToChapter: jumpToChapter,
        
        // Font and reflow
        reflow: reflow,
        reapplyColumns: reapplyColumns,
        setFontSize: setFontSize,
        
        // Position preservation
        getPageForSelector: getPageForSelector,
        createAnchorAroundViewportTop: createAnchorAroundViewportTop,
        scrollToAnchor: scrollToAnchor,
        
        // Chapter streaming (CONSTRUCTION mode only)
        appendChapter: appendChapterSegment,
        prependChapter: prependChapterSegment,
        removeChapter: removeChapterSegment,
        clearAllSegments: clearAllSegments,
        setInitialChapter: setInitialChapterIndex,
        getSegmentPageCount: getSegmentPageCount,
        
        // Diagnostics
        enableDiagnostics: enableDiagnostics,
        getImageDiagnostics: getImageDiagnostics,
        logFailedImages: logFailedImages,
        logPaginationState: logPaginationState,
        getSegmentDiagnostics: getSegmentDiagnostics,
        
        // Debug API - Vertical fallback prevention
        forceHorizontal: forceHorizontal,
        checkColumnsApplied: checkColumnsApplied,
        normalizeHtmlBody: normalizeHtmlBodyCss
    };
    
    // Also expose reapplyColumns and forceHorizontal as global functions for easy access from Android
    window.reapplyColumns = reapplyColumns;
    window.forceHorizontal = forceHorizontal;
    
})();
