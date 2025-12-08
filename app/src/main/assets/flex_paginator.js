/**
 * FlexPaginator - Flex-based pagination for RiftedReader
 * 
 * Alternative to CSS column-based pagination using a node-walking algorithm.
 * 
 * Core Algorithm:
 * 1. Walk DOM tree in document order (depth-first)
 * 2. Measure accumulated height as nodes are processed
 * 3. When height exceeds viewport height, create page boundary
 * 4. Track character offset at each page boundary for bookmarks
 * 5. Detect boundaries at 90% threshold for window transitions
 * 
 * Integration:
 * - Called by FlexPaginator.kt (Kotlin window assembler)
 * - Communicates with Android via AndroidBridge
 * - No chapter streaming (Conveyor handles window lifecycle)
 * 
 * @version 1.0
 * @author RiftedReader
 */
(function() {
    'use strict';
    
    // ========================================================================
    // INJECTION GUARD
    // ========================================================================
    const GUARD_SYMBOL = '__riftedReaderFlexPaginator_v1__';
    if (window[GUARD_SYMBOL] === true) {
        console.log('[FlexPaginator] Already initialized - skipping duplicate injection');
        return;
    }
    window[GUARD_SYMBOL] = true;
    
    // ========================================================================
    // CONSTANTS
    // ========================================================================
    const BOUNDARY_THRESHOLD = 0.9; // Trigger boundary at 90% through window
    const MIN_PAGE_CONTENT = 0.1; // Minimum 10% of viewport height for a page
    
    // ========================================================================
    // STATE
    // ========================================================================
    let config = {
        windowIndex: 0
    };
    
    let state = {
        isInitialized: false,
        isPaginationReady: false,
        viewportHeight: 0,
        viewportWidth: 0,
        pages: [], // Array of {startOffset, endOffset, height}
        charOffsets: [], // Character offset at start of each page
        currentPage: 0,
        totalCharacters: 0
    };
    
    // ========================================================================
    // LOGGING
    // ========================================================================
    function log(level, message) {
        console.log(`[FlexPaginator] [${level}] ${message}`);
    }
    
    // ========================================================================
    // ANDROID BRIDGE
    // ========================================================================
    function callAndroidBridge(method, params) {
        if (typeof window.AndroidBridge !== 'undefined' && 
            typeof window.AndroidBridge[method] === 'function') {
            try {
                const jsonParams = JSON.stringify(params);
                window.AndroidBridge[method](jsonParams);
            } catch (e) {
                log('ERROR', `AndroidBridge.${method} failed: ${e.message}`);
            }
        }
    }
    
    // ========================================================================
    // INITIALIZATION
    // ========================================================================
    
    /**
     * Configure paginator before initialization
     * @param {Object} cfg - Configuration {windowIndex}
     */
    function configure(cfg) {
        config.windowIndex = cfg.windowIndex !== undefined ? cfg.windowIndex : config.windowIndex;
        log('CONFIG', `Configured with windowIndex=${config.windowIndex}`);
    }
    
    /**
     * Initialize paginator with current DOM content
     * @returns {boolean} - True if successful
     */
    function initialize() {
        try {
            // Get viewport dimensions
            state.viewportHeight = window.innerHeight;
            state.viewportWidth = window.innerWidth;
            
            log('INIT', `Viewport: ${state.viewportWidth}x${state.viewportHeight}`);
            
            // Get root container
            const root = document.getElementById('flex-root');
            if (!root) {
                log('ERROR', 'Root container #flex-root not found');
                return false;
            }
            
            // Calculate pages using node-walking algorithm
            const startTime = performance.now();
            calculatePages(root);
            const duration = performance.now() - startTime;
            
            log('INIT', `Calculated ${state.pages.length} pages in ${duration.toFixed(2)}ms`);
            log('INIT', `Total characters: ${state.totalCharacters}`);
            
            state.isInitialized = true;
            state.isPaginationReady = state.pages.length > 0;
            state.currentPage = 0;
            
            // Setup scroll listener for user-initiated navigation
            setupScrollListener();
            
            // Notify Android
            if (state.isPaginationReady) {
                callAndroidBridge('onPaginationReady', {
                    pageCount: state.pages.length,
                    charOffsets: state.charOffsets,
                    windowIndex: config.windowIndex
                });
                
                // Dispatch DOM event
                try {
                    const event = new CustomEvent('paginator-ready', {
                        detail: {
                            pageCount: state.pages.length,
                            windowIndex: config.windowIndex
                        }
                    });
                    document.dispatchEvent(event);
                } catch (e) {
                    log('ERROR', `Failed to dispatch paginator-ready event: ${e.message}`);
                }
            }
            
            return state.isPaginationReady;
        } catch (e) {
            log('ERROR', `Initialize failed: ${e.message}\n${e.stack}`);
            return false;
        }
    }
    
    // ========================================================================
    // PAGE CALCULATION
    // ========================================================================
    
    /**
     * Calculate pages using node-walking algorithm
     * @param {Element} root - Root container element
     */
    function calculatePages(root) {
        const pages = [];
        const charOffsets = [0]; // First page starts at offset 0
        
        let currentPage = {
            startOffset: 0,
            endOffset: 0,
            height: 0
        };
        
        let globalCharOffset = 0;
        
        // Walk all text nodes in document order
        const walker = document.createTreeWalker(
            root,
            NodeFilter.SHOW_TEXT,
            {
                acceptNode: function(node) {
                    // Skip whitespace-only text nodes
                    if (node.textContent.trim().length === 0) {
                        return NodeFilter.FILTER_REJECT;
                    }
                    // Skip script and style content
                    const parent = node.parentElement;
                    if (parent) {
                        const tagName = parent.tagName.toLowerCase();
                        if (tagName === 'script' || tagName === 'style') {
                            return NodeFilter.FILTER_REJECT;
                        }
                    }
                    return NodeFilter.FILTER_ACCEPT;
                }
            },
            false
        );
        
        let node;
        while (node = walker.nextNode()) {
            const textLength = node.textContent.length;
            const nodeHeight = measureTextNodeHeight(node);
            
            // Check if adding this node would exceed viewport
            if (shouldCreatePageBreak(currentPage, nodeHeight)) {
                // Finalize current page
                currentPage.endOffset = globalCharOffset;
                pages.push(currentPage);
                
                // Start new page
                charOffsets.push(globalCharOffset);
                currentPage = {
                    startOffset: globalCharOffset,
                    endOffset: 0,
                    height: 0
                };
            }
            
            // Add node to current page
            currentPage.height += nodeHeight;
            globalCharOffset += textLength;
        }
        
        // Finalize last page
        if (currentPage.height > 0 || pages.length === 0) {
            currentPage.endOffset = globalCharOffset;
            pages.push(currentPage);
        }
        
        // Update state
        state.pages = pages;
        state.charOffsets = charOffsets;
        state.totalCharacters = globalCharOffset;
        
        log('CALC', `Pages: ${pages.length}, Total chars: ${globalCharOffset}`);
    }
    
    /**
     * Measure a text node's rendered height
     * @param {Node} node - Text node to measure
     * @returns {number} - Height in pixels
     */
    function measureTextNodeHeight(node) {
        const parent = node.parentElement;
        if (!parent) return 0;
        
        // Use parent's line-height for estimation
        const style = getComputedStyle(parent);
        const lineHeight = parseFloat(style.lineHeight) || parseFloat(style.fontSize) * 1.2;
        const text = node.textContent;
        
        // Rough estimation: count line breaks and estimate wrapped lines
        const explicitLines = (text.match(/\n/g) || []).length + 1;
        const charsPerLine = Math.floor(state.viewportWidth / (parseFloat(style.fontSize) * 0.5));
        const estimatedWrappedLines = Math.ceil(text.length / Math.max(charsPerLine, 1));
        
        return Math.max(explicitLines, estimatedWrappedLines) * lineHeight;
    }
    
    /**
     * Determine if a page break should be created
     * @param {Object} currentPage - Current page object
     * @param {number} nodeHeight - Height of node to add
     * @returns {boolean} - True if should create page break
     */
    function shouldCreatePageBreak(currentPage, nodeHeight) {
        // Don't break if page is empty
        if (currentPage.height === 0) {
            return false;
        }
        
        // Don't create page if it would be too small
        const minPageHeight = state.viewportHeight * MIN_PAGE_CONTENT;
        if (currentPage.height < minPageHeight) {
            return false;
        }
        
        // Break if adding node would exceed viewport
        const projectedHeight = currentPage.height + nodeHeight;
        if (projectedHeight > state.viewportHeight) {
            return true;
        }
        
        return false;
    }
    
    // ========================================================================
    // NAVIGATION
    // ========================================================================
    
    /**
     * Navigate to specific page
     * @param {number} pageIndex - Target page index
     * @returns {boolean} - True if successful
     */
    function goToPage(pageIndex) {
        if (!state.isPaginationReady) {
            log('WARN', 'goToPage called before pagination ready');
            return false;
        }
        
        if (pageIndex < 0 || pageIndex >= state.pages.length) {
            log('WARN', `goToPage: Invalid page index ${pageIndex} (total: ${state.pages.length})`);
            return false;
        }
        
        // Calculate scroll offset based on accumulated page heights
        let scrollOffset = 0;
        for (let i = 0; i < pageIndex; i++) {
            scrollOffset += state.pages[i].height;
        }
        
        // Apply scroll
        window.scrollTo(0, scrollOffset);
        
        state.currentPage = pageIndex;
        
        // Check boundaries
        checkBoundary();
        
        // Notify Android
        callAndroidBridge('onPageChanged', {
            page: pageIndex,
            offset: state.charOffsets[pageIndex] || 0,
            pageCount: state.pages.length,
            windowIndex: config.windowIndex
        });
        
        log('NAV', `Navigated to page ${pageIndex} (offset: ${scrollOffset}px, char: ${state.charOffsets[pageIndex]})`);
        
        return true;
    }
    
    /**
     * Navigate to next page
     * @returns {boolean} - True if successful
     */
    function nextPage() {
        return goToPage(state.currentPage + 1);
    }
    
    /**
     * Navigate to previous page
     * @returns {boolean} - True if successful
     */
    function previousPage() {
        return goToPage(state.currentPage - 1);
    }
    
    // ========================================================================
    // BOUNDARY DETECTION
    // ========================================================================
    
    /**
     * Check if at boundary and notify Android
     */
    function checkBoundary() {
        const atStart = state.currentPage === 0;
        const atEnd = state.currentPage === state.pages.length - 1;
        const progress = state.currentPage / Math.max(1, state.pages.length - 1);
        
        if (atStart) {
            callAndroidBridge('onReachedStartBoundary', {
                windowIndex: config.windowIndex
            });
        } else if (atEnd || progress >= BOUNDARY_THRESHOLD) {
            callAndroidBridge('onReachedEndBoundary', {
                windowIndex: config.windowIndex
            });
        }
    }
    
    /**
     * Setup scroll listener to detect user-initiated page changes
     */
    function setupScrollListener() {
        let scrollTimeout = null;
        
        window.addEventListener('scroll', function() {
            if (scrollTimeout) {
                clearTimeout(scrollTimeout);
            }
            
            scrollTimeout = setTimeout(function() {
                // Determine current page based on scroll position
                const scrollY = window.scrollY;
                let accumulatedHeight = 0;
                
                for (let i = 0; i < state.pages.length; i++) {
                    accumulatedHeight += state.pages[i].height;
                    if (scrollY < accumulatedHeight) {
                        if (state.currentPage !== i) {
                            state.currentPage = i;
                            checkBoundary();
                            callAndroidBridge('onPageChanged', {
                                page: i,
                                offset: state.charOffsets[i] || 0,
                                pageCount: state.pages.length,
                                windowIndex: config.windowIndex
                            });
                        }
                        break;
                    }
                }
            }, 150);
        });
    }
    
    // ========================================================================
    // PUBLIC API
    // ========================================================================
    
    window.flexPaginator = {
        // Configuration
        configure: configure,
        
        // Initialization
        initialize: initialize,
        isReady: function() { return state.isPaginationReady; },
        
        // Navigation
        goToPage: goToPage,
        nextPage: nextPage,
        previousPage: previousPage,
        
        // State queries
        getCurrentPage: function() { return state.currentPage; },
        getPageCount: function() { return state.pages.length; },
        getCharacterOffset: function() { return state.charOffsets[state.currentPage] || 0; },
        getAllCharOffsets: function() { return state.charOffsets; },
        getTotalCharacters: function() { return state.totalCharacters; },
        
        // Diagnostics
        getPageInfo: function(pageIndex) {
            if (pageIndex < 0 || pageIndex >= state.pages.length) return null;
            const page = state.pages[pageIndex];
            return {
                startOffset: page.startOffset,
                endOffset: page.endOffset,
                height: page.height
            };
        },
        
        getAllPages: function() {
            return state.pages.map((page, index) => ({
                index: index,
                startOffset: page.startOffset,
                endOffset: page.endOffset,
                height: page.height
            }));
        }
    };
    
    log('LOADED', 'FlexPaginator loaded successfully');
    
})();
