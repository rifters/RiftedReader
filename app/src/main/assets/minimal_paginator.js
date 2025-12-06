/**
 * Minimal In-Page Paginator v2
 * 
 * Focused implementation for sliding window/conveyor architecture.
 * 
 * Responsibilities:
 * - Accept pre-wrapped HTML (multiple chapters as <section> tags)
 * - Calculate precise page count via CSS column layout
 * - Support in-window navigation (goToPage/getCurrentPage)
 * - Detect and callback on boundary reaches (next/prev window)
 * - Handle font size/reflow operations
 * - Track character offsets for bookmarks & progress (NEW)
 * 
 * NOT handled (moved to Kotlin/Conveyor):
 * - Chapter streaming (append/prepend)
 * - Chapter management
 * - Window mode switching
 * - TOC-based navigation
 * 
 * @author RiftedReader
 * @version 2.0
 */
(function() {
    'use strict';
    
    // ========================================================================
    // INJECTION GUARD
    // ========================================================================
    const GUARD_SYMBOL = '__riftedReaderMinimalPaginator_v2__';
    if (window[GUARD_SYMBOL] === true) {
        console.log('minimal_paginator: Already initialized - skipping duplicate injection');
        return;
    }
    window[GUARD_SYMBOL] = true;
    
    // ========================================================================
    // CONSTANTS
    // ========================================================================
    const COLUMN_GAP = 0;
    const MIN_CLIENT_WIDTH = 10;
    const FALLBACK_WIDTH = 360;
    const BOUNDARY_THRESHOLD = 0.9; // Trigger boundary at 90% through window
    
    // ========================================================================
    // STATE
    // ========================================================================
    let config = {
        mode: 'window',
        windowIndex: 0
    };
    
    let state = {
        isInitialized: false,
        isPaginationReady: false,
        currentFontSize: 16,
        currentPage: 0,
        viewportWidth: 0,
        appliedColumnWidth: 0,
        pageCount: -1,
        columnContainer: null,
        contentWrapper: null
    };
    
    // Character offset tracking (NEW)
    let charOffsets = [];  // [page0_offset, page1_offset, page2_offset, ...]
    
    // Boundary detection state
    let lastBoundaryDirection = null;
    let boundaryCheckInProgress = false;
    
    // ========================================================================
    // PUBLIC API
    // ========================================================================
    
    /**
     * Configure paginator before initialization
     * @param {Object} cfg - Configuration object
     */
    function configure(cfg) {
        config.mode = cfg.mode || config.mode;
        config.windowIndex = cfg.windowIndex !== undefined ? cfg.windowIndex : config.windowIndex;
        log('CONFIGURE', `mode=${config.mode}, windowIndex=${config.windowIndex}`);
    }
    
    /**
     * Initialize paginator with HTML content
     * @param {string} htmlContent - Pre-wrapped HTML with <section> tags
     * @returns {boolean} - True if successful
     */
    function initialize(htmlContent) {
        try {
            // Set up container
            state.columnContainer = document.documentElement;
            state.contentWrapper = document.body;
            
            if (!state.contentWrapper) {
                log('ERROR', 'No body element found');
                return false;
            }
            
            // Insert HTML
            state.contentWrapper.innerHTML = htmlContent;
            
            // Get viewport width
            state.viewportWidth = Math.max(window.innerWidth, MIN_CLIENT_WIDTH);
            state.appliedColumnWidth = state.viewportWidth;
            
            // Apply CSS columns
            applyColumnLayout();
            
            // Calculate initial page count and character offsets
            calculatePageCountAndOffsets();
            
            state.isInitialized = true;
            state.isPaginationReady = state.pageCount > 0;
            state.currentPage = 0;
            
            // Add scroll listener to detect boundary changes during user scrolling
            setupScrollListener();
            
            // Sync pagination state with Android bridge (for synchronized access)
            syncPaginationState();
            
            if (state.isPaginationReady) {
                log('INIT_SUCCESS', `pageCount=${state.pageCount}, charOffsets=${charOffsets.length}`);
                callAndroidBridge('onPaginationReady', { pageCount: state.pageCount });
            } else {
                log('INIT_INCOMPLETE', 'Pagination not ready');
            }
            
            return state.isPaginationReady;
        } catch (e) {
            log('ERROR', `Initialize failed: ${e.message}`);
            return false;
        }
    }
    
    /**
     * Get total page count for current window
     * @returns {number} - Page count
     */
    function getPageCount() {
        return state.pageCount > 0 ? state.pageCount : -1;
    }
    
    /**
     * Get currently displayed page index
     * @returns {number} - Current page (0-indexed)
     */
    function getCurrentPage() {
        if (!state.isPaginationReady) return 0;
        return Math.max(0, Math.min(state.currentPage, state.pageCount - 1));
    }
    
    /**
     * Navigate to specific page
     * @param {number} pageIndex - Page to navigate to
     * @param {boolean} smooth - Use smooth scroll
     */
    function goToPage(pageIndex, smooth = false) {
        if (!state.isPaginationReady) {
            log('WARN', 'goToPage: Pagination not ready');
            return;
        }
        
        const validIndex = Math.max(0, Math.min(pageIndex, state.pageCount - 1));
        const scrollPos = validIndex * state.appliedColumnWidth;
        
        state.currentPage = validIndex;
        
        window.scrollTo({
            left: scrollPos,
            top: 0,
            behavior: smooth ? 'smooth' : 'auto'
        });
        
        // Sync state with Android bridge after page change
        syncPaginationState();
        
        // Also call onPageChanged for compatibility with Kotlin navigation logic
        if (window.AndroidBridge && typeof window.AndroidBridge.onPageChanged === 'function') {
            window.AndroidBridge.onPageChanged(validIndex);
        }
        
        checkBoundary();
        log('NAV', `goToPage(${pageIndex}) -> ${validIndex}`);
    }
    
    /**
     * Navigate to next page
     */
    function nextPage() {
        if (state.currentPage < state.pageCount - 1) {
            goToPage(state.currentPage + 1, true);
        }
    }
    
    /**
     * Navigate to previous page
     */
    function prevPage() {
        if (state.currentPage > 0) {
            goToPage(state.currentPage - 1, true);
        }
    }
    
    /**
     * Set font size and reflow
     * @param {number} px - Font size in pixels
     */
    function setFontSize(px) {
        if (px <= 0) return;
        
        state.currentFontSize = px;
        state.contentWrapper.style.fontSize = px + 'px';
        
        // Recalculate offsets and page count after font change
        calculatePageCountAndOffsets();
        
        // Stay approximately at same character offset
        const currentOffset = charOffsets[state.currentPage] || 0;
        const newPageIndex = findPageByCharOffset(currentOffset);
        state.currentPage = newPageIndex;
        
        goToPage(newPageIndex, false);
        log('FONT_CHANGE', `${px}px, newPage=${newPageIndex}`);
    }
    
    /**
     * Get character offset for a specific page (NEW API)
     * @param {number} pageIndex - Page index
     * @returns {number} - Character offset within window
     */
    function getCharacterOffsetForPage(pageIndex) {
        if (pageIndex < 0 || pageIndex >= charOffsets.length) {
            return 0;
        }
        return charOffsets[pageIndex];
    }
    
    /**
     * Navigate to page containing specific character offset (NEW API)
     * @param {number} offset - Character offset within window
     * @returns {number} - Page navigated to
     */
    function goToPageWithCharacterOffset(offset) {
        const pageIndex = findPageByCharOffset(offset);
        goToPage(pageIndex, false);
        return pageIndex;
    }
    
    /**
     * Check if pagination is ready
     * @returns {boolean}
     */
    function isReady() {
        return state.isPaginationReady;
    }
    
    // ========================================================================
    // INTERNAL HELPERS
    // ========================================================================
    
    /**
     * Apply CSS column layout
     */
    function applyColumnLayout() {
        const styles = {
            'column-width': state.appliedColumnWidth + 'px',
            'column-gap': COLUMN_GAP + 'px',
            'column-fill': 'auto',
            'overflow-x': 'hidden',
            'overflow-y': 'hidden',
            'height': '100vh',
            'width': '100vw'
        };
        
        Object.assign(state.contentWrapper.style, styles);
        log('LAYOUT', `columns applied: ${state.appliedColumnWidth}px width`);
    }
    
    /**
     * Calculate page count and character offsets
     */
    function calculatePageCountAndOffsets() {
        try {
            // Force reflow to ensure accurate measurements
            const dummy = state.contentWrapper.offsetWidth;
            
            const scrollWidth = state.contentWrapper.scrollWidth;
            const clientWidth = state.contentWrapper.clientWidth;
            
            if (clientWidth <= 0) {
                log('WARN', 'clientWidth <= 0, cannot calculate pages');
                state.pageCount = -1;
                charOffsets = [];
                return;
            }
            
            // Page count = how many column widths fit in scroll width
            const pageCount = Math.ceil(scrollWidth / clientWidth);
            state.pageCount = Math.max(1, pageCount);
            
            // Build character offset array
            charOffsets = buildCharacterOffsets();
            
            log('CALC_PAGES', `pageCount=${state.pageCount}, charOffsets=${charOffsets.length}`);
        } catch (e) {
            log('ERROR', `Calculate pages failed: ${e.message}`);
            state.pageCount = -1;
            charOffsets = [];
        }
    }
    
    /**
     * Build character offset array for each page
     * @returns {Array<number>}
     */
    function buildCharacterOffsets() {
        const offsets = [];
        const text = state.contentWrapper.innerText || '';
        
        // For each page, find the character offset at the top of that page
        for (let pageIdx = 0; pageIdx < state.pageCount; pageIdx++) {
            const scrollLeft = pageIdx * state.appliedColumnWidth;
            
            // Find first visible character at this scroll position
            // Simplified: approximate by dividing total text by pages
            const estimatedOffset = Math.floor((text.length / state.pageCount) * pageIdx);
            offsets.push(estimatedOffset);
        }
        
        return offsets;
    }
    
    /**
     * Find page index containing character offset
     * @param {number} targetOffset - Character offset to find
     * @returns {number} - Page index
     */
    function findPageByCharOffset(targetOffset) {
        if (charOffsets.length === 0) return 0;
        
        // Binary search
        let left = 0, right = charOffsets.length - 1;
        while (left < right) {
            const mid = Math.floor((left + right + 1) / 2);
            if (charOffsets[mid] <= targetOffset) {
                left = mid;
            } else {
                right = mid - 1;
            }
        }
        
        return Math.max(0, Math.min(left, state.pageCount - 1));
    }
    
    /**
     * Setup scroll event listener to detect boundary changes
     */
    function setupScrollListener() {
        window.addEventListener('scroll', function() {
            if (!state.isPaginationReady) return;
            
            // Update current page based on scroll position
            const currentScrollLeft = window.scrollX || window.pageXOffset || 0;
            const newPage = Math.round(currentScrollLeft / state.appliedColumnWidth);
            state.currentPage = Math.max(0, Math.min(newPage, state.pageCount - 1));
            
            // Check if boundary was reached
            checkBoundary();
            
        }, false);
        
        log('SCROLL_LISTENER', 'Scroll event listener attached');
    }
    
    /**
     * Check if we've reached a boundary (next/prev window)
     */
    function checkBoundary() {
        if (boundaryCheckInProgress || !state.isPaginationReady) return;
        
        const currentProgress = state.currentPage / Math.max(1, state.pageCount - 1);
        
        if (currentProgress >= BOUNDARY_THRESHOLD && lastBoundaryDirection !== 'FORWARD') {
            // Call onBoundaryReached with direction, currentPage, totalPages (NOT JSON)
            if (window.AndroidBridge && typeof window.AndroidBridge.onBoundaryReached === 'function') {
                window.AndroidBridge.onBoundaryReached('NEXT', state.currentPage, state.pageCount);
            }
            lastBoundaryDirection = 'FORWARD';
            log('BOUNDARY', 'Reached FORWARD boundary');
        } else if (currentProgress <= (1 - BOUNDARY_THRESHOLD) && lastBoundaryDirection !== 'BACKWARD') {
            // Call onBoundaryReached with direction, currentPage, totalPages (NOT JSON)
            if (window.AndroidBridge && typeof window.AndroidBridge.onBoundaryReached === 'function') {
                window.AndroidBridge.onBoundaryReached('PREVIOUS', state.currentPage, state.pageCount);
            }
            lastBoundaryDirection = 'BACKWARD';
            log('BOUNDARY', 'Reached BACKWARD boundary');
        } else if (currentProgress > (1 - BOUNDARY_THRESHOLD) && currentProgress < BOUNDARY_THRESHOLD) {
            lastBoundaryDirection = null;
        }
    }
    
    /**
     * Sync pagination state with Android for synchronized access.
     * Called by Kotlin code via WebViewPaginatorBridge._syncPaginationState()
     * This allows Kotlin to read page state synchronously without async callbacks.
     */
    function syncPaginationState() {
        try {
            if (window.AndroidBridge && typeof window.AndroidBridge._syncPaginationState === 'function') {
                window.AndroidBridge._syncPaginationState(
                    state.pageCount,
                    state.currentPage
                );
                log('SYNC', `Synced with Android: pageCount=${state.pageCount}, currentPage=${state.currentPage}`);
            }
        } catch (e) {
            log('SYNC_ERROR', `Failed to sync: ${e.message}`);
        }
    }
    
    /**
     * Call Android bridge
     * @param {string} method - Method name
     * @param {Object} data - Data object
     */
    function callAndroidBridge(method, data) {
        try {
            if (window.AndroidBridge && typeof window.AndroidBridge[method] === 'function') {
                window.AndroidBridge[method](JSON.stringify(data));
                log('BRIDGE', `Called ${method}(${JSON.stringify(data)})`);
            }
        } catch (e) {
            log('ERROR', `Bridge call failed: ${e.message}`);
        }
    }
    
    /**
     * Logging utility
     * @param {string} tag - Log tag
     * @param {string} message - Log message
     */
    function log(tag, message) {
        const prefix = `[MIN_PAGINATOR:${tag}]`;
        console.log(prefix, message);
    }
    
    // ========================================================================
    // EXPORT PUBLIC API
    // ========================================================================
    
    window.minimalPaginator = {
        configure,
        initialize,
        getPageCount,
        getCurrentPage,
        goToPage,
        nextPage,
        prevPage,
        setFontSize,
        getCharacterOffsetForPage,
        goToPageWithCharacterOffset,
        isReady
    };
    
    // Add 'inpagePaginator' alias for backward compatibility during transition
    window.inpagePaginator = window.minimalPaginator;
    
    log('INIT', 'Minimal Paginator v2 injected');
    
})();
