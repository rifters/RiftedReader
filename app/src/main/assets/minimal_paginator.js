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
        contentWrapper: null,
        isNavigating: false,  // Flag to prevent scroll listener interference during programmatic navigation
        lastRecomputeTime: null,  // Track last recompute for debouncing
        mutationObserver: null  // MutationObserver for dynamic content changes
    };
    
    // Character offset tracking (NEW)
    let charOffsets = [];  // [page0_offset, page1_offset, page2_offset, ...]
    
    // Boundary detection state
    let lastBoundaryDirection = null;
    let boundaryCheckInProgress = false;
    
    // Scroll completion tracking
    let scrollEndFired = false;
    let scrollEndTimeout = null;
    
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
     * @param {string} htmlContent - Pre-wrapped HTML with <section> tags (optional, HTML may already be in DOM)
     * @returns {boolean} - True if successful
     */
    function initialize(htmlContent) {
        try {
            // ARCHITECTURE FIX: Create dedicated container divs like inpage_paginator
            // This isolates scroll handling and prevents window-level scroll interference
            
            const body = document.body;
            if (!body) {
                log('ERROR', 'No body element found');
                return false;
            }
            
            // Normalize html/body CSS for full viewport with no scrolling
            const html = document.documentElement;
            if (html) {
                html.style.margin = '0';
                html.style.padding = '0';
                html.style.width = '100%';
                html.style.height = '100%';
                html.style.overflow = 'hidden';
            }
            
            body.style.margin = '0';
            body.style.padding = '0';
            body.style.width = '100%';
            body.style.height = '100%';
            body.style.overflow = 'hidden';
            body.style.webkitTapHighlightColor = 'transparent';
            
            // Create dedicated column container with scroll capability
            state.columnContainer = document.createElement('div');
            state.columnContainer.id = 'paginator-container';
            state.columnContainer.style.cssText = `
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
            state.contentWrapper = document.createElement('div');
            state.contentWrapper.id = 'paginator-content';
            
            // Move all body children to content wrapper OR inject htmlContent
            if (htmlContent !== undefined && htmlContent !== null && htmlContent.length > 0) {
                state.contentWrapper.innerHTML = htmlContent;
                log('INIT', 'HTML content injected from parameter');
            } else {
                // Move existing body content to wrapper
                while (body.firstChild) {
                    const child = body.firstChild;
                    if (child) {
                        state.contentWrapper.appendChild(child);
                    } else {
                        break;
                    }
                }
                log('INIT', 'Moved existing body content to wrapper');
            }
            
            // Add wrappers to DOM
            state.columnContainer.appendChild(state.contentWrapper);
            body.appendChild(state.columnContainer);
            
            // Wrap content in <section data-chapter-index> tags if not already wrapped
            // This is required for column layout to work correctly
            wrapExistingContentAsSegment();
            
            // Use window.innerWidth as viewport width (container will be full width)
            state.viewportWidth = window.innerWidth;
            state.appliedColumnWidth = state.viewportWidth;
            
            log('INIT', `Using measured content width: ${state.viewportWidth}px (clientWidth=${state.contentWrapper.clientWidth}, boundingWidth=${state.contentWrapper.getBoundingClientRect().width})`);
            
            // Apply CSS columns
            applyColumnLayout();
            
            // Calculate initial page count and character offsets
            calculatePageCountAndOffsets();
            
            // DIAGNOSTIC: Log initialization complete with measurements
            log('INIT_COMPLETE', 
                `viewportWidth=${state.viewportWidth}px, ` +
                `scrollWidth=${state.contentWrapper.scrollWidth}px, ` +
                `clientWidth=${state.contentWrapper.clientWidth}px, ` +
                `appliedColumnWidth=${state.appliedColumnWidth}px, ` +
                `calculated pageCount=${state.pageCount}`
            );
            
            state.isInitialized = true;
            state.isPaginationReady = state.pageCount > 0;
            state.currentPage = 0;
            
            // Add scroll listener to detect boundary changes during user scrolling
            setupScrollListener();
            
            if (state.isPaginationReady) {
                log('INIT_SUCCESS', `pageCount=${state.pageCount}, charOffsets=${charOffsets.length}, pageWidth=${state.appliedColumnWidth}px`);
                
                // DIAGNOSTIC: Warn if only 1 page for content that should paginate
                if (state.pageCount === 1 && state.contentWrapper.scrollWidth === state.contentWrapper.clientWidth) {
                    log('WARN_SINGLE_PAGE', 
                        'Content calculated as 1 page. ' +
                        'scrollWidth equals clientWidth - columns may not be wrapping. ' +
                        'Check if content is long enough or if CSS column layout is working correctly.'
                    );
                }
                
                callAndroidBridge('onPaginationReady', { pageCount: state.pageCount });
                
                // Dispatch DOM CustomEvent for other consumers
                try {
                    const event = new CustomEvent('paginator-ready', {
                        detail: { pageCount: state.pageCount, windowIndex: config.windowIndex }
                    });
                    document.dispatchEvent(event);
                } catch (e) {
                    log('ERROR', `Failed to dispatch paginator-ready event: ${e.message}`);
                }
                
                // Schedule post-initialization recompute to handle dynamic content (images, fonts)
                schedulePostInitRecompute();
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
        
        // Set isNavigating flag to prevent scroll listener from interfering
        state.isNavigating = true;
        state.currentPage = validIndex;
        
        // Clear any existing scroll end timeout
        if (scrollEndTimeout !== null) {
            clearTimeout(scrollEndTimeout);
            scrollEndTimeout = null;
        }
        scrollEndFired = false;
        
        // Set up one-time scrollend event listener (modern browsers)
        const onScrollEnd = function() {
            if (scrollEndFired) return; // Prevent double-execution
            scrollEndFired = true;
            
            // Clear fallback timeout
            if (scrollEndTimeout !== null) {
                clearTimeout(scrollEndTimeout);
                scrollEndTimeout = null;
            }
            
            // Reset isNavigating flag now that scroll animation is complete
            state.isNavigating = false;
            log('NAV', `scrollend event fired - navigation complete`);
            
            // Remove the event listener
            state.columnContainer.removeEventListener('scrollend', onScrollEnd);
        };
        
        // Attach scrollend listener to container (not window)
        state.columnContainer.addEventListener('scrollend', onScrollEnd);
        
        // Fallback timeout for browsers without scrollend support (300ms to cover smooth animations)
        scrollEndTimeout = setTimeout(function() {
            if (!scrollEndFired) {
                scrollEndFired = true;
                state.isNavigating = false;
                log('NAV', `fallback timeout fired (300ms) - navigation complete`);
                state.columnContainer.removeEventListener('scrollend', onScrollEnd);
            }
        }, 300);
        
        // Use columnContainer.scrollTo() instead of window.scrollTo()
        state.columnContainer.scrollTo({
            left: scrollPos,
            top: 0,
            behavior: smooth ? 'smooth' : 'auto'
        });
        
        // REMOVED: checkBoundary() call - let scroll listener handle boundary detection
        // after scroll animation completes and state is properly updated
        log('NAV', `goToPage(${pageIndex}) -> ${validIndex}, smooth=${smooth}`);
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
        
        // Use reflow() to handle layout recalculation and position preservation
        const result = reflow(true);
        
        log('FONT_CHANGE', `${px}px, pageCount=${result.pageCount}, currentPage=${result.currentPage}`);
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
     * Wrap existing content in <section data-chapter-index> tags if not already wrapped.
     * This is required for the column layout to work correctly.
     * 
     * Based on inpage_paginator.js wrapExistingContentAsSegment function.
     * Adapted for minimal_paginator which uses state.contentWrapper.
     */
    function wrapExistingContentAsSegment() {
        if (!state.contentWrapper) {
            return;
        }
        
        // Check if content already has section elements with data-chapter-index
        // This happens in window mode when HTML is pre-wrapped with multiple chapters
        const existingSections = state.contentWrapper.querySelectorAll('section[data-chapter-index]');
        
        if (existingSections.length > 0) {
            // Validate that sections have valid chapter indices
            let validSectionCount = 0;
            for (let i = 0; i < existingSections.length; i++) {
                const section = existingSections[i];
                const chapterIndexAttr = section.getAttribute('data-chapter-index');
                const chapterIndex = parseInt(chapterIndexAttr, 10);
                
                // Validate that chapter index is a valid non-negative integer
                if (!isNaN(chapterIndex) && chapterIndex >= 0) {
                    validSectionCount++;
                } else {
                    log('WARN', `Ignoring section with invalid data-chapter-index: ${chapterIndexAttr}`);
                }
            }
            
            if (validSectionCount > 0) {
                // Content is already wrapped in valid chapter sections (window mode with pre-wrapped HTML)
                log('WRAP', `Found ${validSectionCount} pre-wrapped chapter sections`);
                return;
            }
        }
        
        // No valid pre-wrapped sections found - wrap all content in a single segment
        // This is the behavior for non-wrapped window mode or single chapter content
        log('WRAP', 'No valid pre-wrapped sections found, wrapping content as single segment');
        const segment = document.createElement('section');
        segment.className = 'chapter-segment';
        
        // Use windowIndex from config as the chapter index for this segment
        const chapterIndex = config.windowIndex !== undefined ? config.windowIndex : 0;
        
        segment.setAttribute('data-chapter-index', chapterIndex);
        const fragment = document.createDocumentFragment();
        while (state.contentWrapper.firstChild) {
            fragment.appendChild(state.contentWrapper.firstChild);
        }
        segment.appendChild(fragment);
        state.contentWrapper.appendChild(segment);
        
        log('WRAP', `Wrapped content as chapter ${chapterIndex}`);
    }
    
    /**
     * Reflow the paginator - recalculate columns and page count.
     * Called when layout changes (font size, content updates, orientation).
     * 
     * Based on inpage_paginator.js reflow function.
     * Adapted for minimal_paginator state structure.
     * 
     * @param {boolean} preservePosition - Whether to maintain current page position (default: true)
     * @returns {Object} - {success: boolean, pageCount: number, currentPage: number}
     */
    function reflow(preservePosition) {
        if (preservePosition === undefined) {
            preservePosition = true;
        }
        
        if (!state.isInitialized || !state.columnContainer) {
            log('WARN', 'Reflow called before initialization');
            return { success: false, pageCount: 0 };
        }
        
        log('REFLOW', `Triggered (preservePosition=${preservePosition})`);
        
        try {
            // Save current state before reflow
            let currentPageBeforeReflow = 0;
            let currentCharOffset = 0;
            
            if (preservePosition) {
                try {
                    currentPageBeforeReflow = getCurrentPage();
                    currentCharOffset = charOffsets[currentPageBeforeReflow] || 0;
                    log('REFLOW', `Saving position - page=${currentPageBeforeReflow}, charOffset=${currentCharOffset}`);
                } catch (e) {
                    log('WARN', `Error saving position during reflow: ${e.message}`);
                    preservePosition = false;
                }
            }
            
            if (!state.contentWrapper) {
                log('ERROR', 'Content wrapper not found during reflow');
                return { success: false, pageCount: 0 };
            }
            
            // Force reflow by temporarily changing display property
            const oldDisplay = state.columnContainer.style.display;
            state.columnContainer.style.display = 'none';
            state.columnContainer.offsetHeight; // Force reflow
            state.columnContainer.style.display = oldDisplay || '';
            
            // Reapply column layout
            applyColumnLayout();
            
            // Recalculate page count and character offsets
            calculatePageCountAndOffsets();
            
            const pageCount = state.pageCount;
            log('REFLOW', `Calculated new pageCount=${pageCount}`);
            
            // Restore position if requested
            if (preservePosition && pageCount > 0) {
                // Try to restore by character offset for better accuracy
                const targetPage = findPageByCharOffset(currentCharOffset);
                const clampedPage = Math.max(0, Math.min(targetPage, pageCount - 1));
                log('REFLOW', `Restoring to page=${clampedPage} (via charOffset=${currentCharOffset})`);
                goToPage(clampedPage, false);
            } else {
                // If not preserving position, sync from actual scroll
                const scrollLeft = state.columnContainer.scrollLeft;
                const newPage = Math.floor(scrollLeft / state.appliedColumnWidth);
                state.currentPage = Math.max(0, Math.min(newPage, pageCount - 1));
            }
            
            log('REFLOW', `Complete - pageCount=${pageCount}, currentPage=${state.currentPage}`);
            
            // Mark pagination as temporarily not ready, then restore after a brief delay
            // This prevents race conditions with getPageCount() during reflow
            state.isPaginationReady = false;
            log('REFLOW', 'isPaginationReady temporarily set to false');
            
            setTimeout(() => {
                state.isPaginationReady = true;
                log('REFLOW', 'isPaginationReady set back to true');
                
                // Notify Android if callback exists
                callAndroidBridge('onPaginationReady', { pageCount: pageCount });
            }, 0); // Execute ASAP but after current call stack
            
            return { success: true, pageCount: pageCount, currentPage: state.currentPage };
        } catch (e) {
            log('ERROR', `Reflow failed: ${e.message}`);
            state.isPaginationReady = false;
            return { success: false, pageCount: 0, error: e.message };
        }
    }
    
    /**
     * Apply column styles with a specific width.
     * This is the complete, working implementation from inpage_paginator.js
     * that properly handles:
     * - Font size preservation
     * - Complete CSS properties with -webkit- prefixes
     * - Image constraint handling
     * - Accurate page width calculation
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
        
        // DIAGNOSTIC: Verify layout after setting width
        log('LAYOUT_VERIFY', 
            `columnWidth=${columnWidth}px set, ` +
            `wrapper.style.columnWidth='${wrapper.style.columnWidth}', ` +
            `computed scrollWidth=${wrapper.scrollWidth}px after layout`
        );
        
        log('LAYOUT', `Set wrapper width=${exactWidth}px (pageCount=${pageCount}, scrollWidth=${scrollWidth}, columnWidth=${columnWidth})`);
    }
    
    /**
     * Apply CSS column layout
     */
    function applyColumnLayout() {
        applyColumnStylesWithWidth(state.contentWrapper, state.appliedColumnWidth);
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
            
            // DIAGNOSTIC: Log raw measurements
            log('CALC_PAGES_RAW', 
                `scrollWidth=${scrollWidth}px, ` +
                `clientWidth=${clientWidth}px, ` +
                `appliedColumnWidth=${state.appliedColumnWidth}px`
            );
            
            if (clientWidth <= 0) {
                log('WARN', 'clientWidth <= 0, cannot calculate pages');
                state.pageCount = -1;
                charOffsets = [];
                return;
            }
            
            // Page count = how many column widths fit in scroll width
            const pageCount = Math.ceil(scrollWidth / clientWidth);
            state.pageCount = Math.max(1, pageCount);
            
            // DIAGNOSTIC: Log calculation result
            log('CALC_PAGES_RESULT', 
                `Math.ceil(${scrollWidth} / ${clientWidth}) = ${pageCount}, ` +
                `final pageCount=${state.pageCount}`
            );
            
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
     * Setup scroll event listener to detect boundary changes and snap to pages
     */
    function setupScrollListener() {
        let scrollEndTimeout = null;
        
        // Use columnContainer.scroll instead of window.scroll
        state.columnContainer.addEventListener('scroll', function() {
            if (!state.isPaginationReady) return;
            
            // Skip state updates during programmatic navigation to prevent interference
            if (state.isNavigating) {
                log('SCROLL', 'Scroll event ignored during programmatic navigation');
                return;
            }
            
            // Update current page based on container scroll position
            const currentScrollLeft = state.columnContainer.scrollLeft;
            const newPage = Math.round(currentScrollLeft / state.appliedColumnWidth);
            const prevPage = state.currentPage;  // ← TRACK PREVIOUS
            state.currentPage = Math.max(0, Math.min(newPage, state.pageCount - 1));
            
            // ✅ ADD THIS: Notify Android when page actually changes
            if (state.currentPage !== prevPage) {
                log('PAGE_CHANGE', `Manual scroll: ${prevPage} → ${state.currentPage}`);
            }
            
            // Check if boundary was reached
            checkBoundary();
            
            // Clear existing timeout
            if (scrollEndTimeout) {
                clearTimeout(scrollEndTimeout);
            }
            
            // Set new timeout for scroll end (fallback for browsers without scrollend)
            scrollEndTimeout = setTimeout(function() {
                snapToNearestPage();
            }, 150);
            
        }, false);
        
        // Modern browsers: use scrollend event for better performance on container
        state.columnContainer.addEventListener('scrollend', function() {
            if (!state.isPaginationReady || state.isNavigating) return;
            
            // Clear fallback timeout since scrollend fired
            if (scrollEndTimeout) {
                clearTimeout(scrollEndTimeout);
                scrollEndTimeout = null;
            }
            
            snapToNearestPage();
        }, false);
        
        log('SCROLL_LISTENER', 'Scroll event listener with snap-to-page attached to columnContainer');
    }
    
    /**
     * Snap to nearest page boundary after scroll ends
     * Ensures clean page alignment and recalculates if layout changed
     */
    function snapToNearestPage() {
        if (!state.isPaginationReady) return;
        
        // Use columnContainer.scrollLeft instead of window scroll
        const currentScrollLeft = state.columnContainer.scrollLeft;
        const targetPage = Math.floor(currentScrollLeft / state.appliedColumnWidth);
        const clampedPage = Math.max(0, Math.min(targetPage, state.pageCount - 1));
        const targetScrollPos = clampedPage * state.appliedColumnWidth;
        
        // Check if we need to snap (allow small tolerance)
        const tolerance = 5; // pixels
        if (Math.abs(currentScrollLeft - targetScrollPos) > tolerance) {
            log('SNAP', `Snapping to page ${clampedPage} (scroll: ${currentScrollLeft.toFixed(1)} → ${targetScrollPos})`);
            
            // Snap without smooth animation to be instant - use columnContainer.scrollTo()
            state.columnContainer.scrollTo({
                left: targetScrollPos,
                top: 0,
                behavior: 'auto'
            });
            
            // Update state
            state.currentPage = clampedPage;
        }
        
        // After snap, verify page count is still accurate (images may have loaded)
        // Use a short delay to allow any layout changes to settle
        setTimeout(function() {
            recomputeIfNeeded();
        }, 50);
    }
    
    /**
     * Schedule post-initialization recompute to handle dynamic content
     * Images, web fonts, and other async resources may load after initial pagination
     */
    function schedulePostInitRecompute() {
        // Short delay after initial pagination to catch early layout changes
        setTimeout(function() {
            log('POST_INIT_RECOMPUTE', 'Running scheduled recompute after 300ms');
            recomputeIfNeeded();
        }, 300);
        
        // Set up MutationObserver to detect image loads and layout changes
        if (typeof MutationObserver !== 'undefined') {
            const observer = new MutationObserver(function(mutations) {
                // Debounce: only recompute if we haven't recomputed recently
                const now = Date.now();
                if (!state.lastRecomputeTime || (now - state.lastRecomputeTime) > 500) {
                    log('MUTATION_DETECTED', 'Layout changed, scheduling recompute');
                    setTimeout(function() {
                        recomputeIfNeeded();
                    }, 100);
                }
            });
            
            observer.observe(state.contentWrapper, {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ['style', 'class', 'width', 'height']
            });
            
            log('MUTATION_OBSERVER', 'Watching for layout changes');
            
            // Store observer reference for cleanup
            state.mutationObserver = observer;
        }
        
        // Also listen for image load events
        const images = state.contentWrapper.querySelectorAll('img');
        let loadedImages = 0;
        const totalImages = images.length;
        
        if (totalImages > 0) {
            log('IMAGE_LOADING', `Detected ${totalImages} images, monitoring load events`);
            
            images.forEach(function(img) {
                // Check if already loaded
                if (img.complete && img.naturalHeight > 0) {
                    loadedImages++;
                } else {
                    img.addEventListener('load', function() {
                        loadedImages++;
                        log('IMAGE_LOADED', `Image ${loadedImages}/${totalImages} loaded`);
                        
                        // When all images loaded, do final recompute
                        if (loadedImages === totalImages) {
                            setTimeout(function() {
                                log('ALL_IMAGES_LOADED', 'Final recompute after all images loaded');
                                recomputeIfNeeded();
                            }, 100);
                        }
                    });
                    
                    img.addEventListener('error', function() {
                        loadedImages++;
                        log('IMAGE_ERROR', `Image ${loadedImages}/${totalImages} failed to load`);
                        
                        if (loadedImages === totalImages) {
                            setTimeout(function() {
                                log('ALL_IMAGES_PROCESSED', 'Final recompute after all images processed');
                                recomputeIfNeeded();
                            }, 100);
                        }
                    });
                }
            });
            
            // Check if all images were already loaded
            if (loadedImages === totalImages) {
                log('ALL_IMAGES_ALREADY_LOADED', 'All images already loaded, recomputing now');
                setTimeout(function() {
                    recomputeIfNeeded();
                }, 100);
            }
        }
    }
    
    /**
     * Recompute page count if layout has changed
     * Preserves current reading position by calculating nearest page
     */
    function recomputeIfNeeded() {
        if (!state.isPaginationReady) return;
        
        const oldPageCount = state.pageCount;
        const oldCurrentPage = state.currentPage;
        const oldScrollPos = state.columnContainer.scrollLeft;
        
        // Recalculate page count
        calculatePageCountAndOffsets();
        
        // Check if page count changed
        if (state.pageCount !== oldPageCount) {
            log('RECOMPUTE', `Page count changed: ${oldPageCount} → ${state.pageCount}`);
            
            // Calculate new page based on scroll position
            const newPage = Math.round(oldScrollPos / state.appliedColumnWidth);
            state.currentPage = Math.max(0, Math.min(newPage, state.pageCount - 1));
            
            // Snap to the new page boundary
            const targetScrollPos = state.currentPage * state.appliedColumnWidth;
            if (Math.abs(oldScrollPos - targetScrollPos) > 5) {
                log('RECOMPUTE_SNAP', `Snapping to page ${state.currentPage} after recompute`);
                state.columnContainer.scrollTo({
                    left: targetScrollPos,
                    top: 0,
                    behavior: 'auto'
                });
            }
            
            // Notify Android of the change
            callAndroidBridge('onPaginationReady', { pageCount: state.pageCount });
            
            log('RECOMPUTE_COMPLETE', `Page ${oldCurrentPage}/${oldPageCount} → ${state.currentPage}/${state.pageCount}`);
        }
        
        // Track recompute time for debouncing
        state.lastRecomputeTime = Date.now();
    }
    
    /**
     * Check if we've reached a boundary (next/prev window)
     */
    function checkBoundary() {
        if (boundaryCheckInProgress || !state.isPaginationReady) return;
        
        const currentProgress = state.currentPage / Math.max(1, state.pageCount - 1);
        
        if (currentProgress >= BOUNDARY_THRESHOLD && lastBoundaryDirection !== 'FORWARD') {
            // Call onBoundary with JSON format for PaginatorBridge
            callAndroidBridge('onBoundary', { direction: 'NEXT' });
            // Dispatch DOM CustomEvent for other consumers
            try {
                const event = new CustomEvent('paginator-boundary', {
                    detail: { direction: 'NEXT', windowIndex: config.windowIndex }
                });
                document.dispatchEvent(event);
            } catch (e) {
                log('ERROR', `Failed to dispatch boundary event: ${e.message}`);
            }
            lastBoundaryDirection = 'FORWARD';
            log('BOUNDARY', 'Reached FORWARD boundary');
        } else if (currentProgress <= (1 - BOUNDARY_THRESHOLD) && lastBoundaryDirection !== 'BACKWARD') {
            // Call onBoundary with JSON format for PaginatorBridge
            callAndroidBridge('onBoundary', { direction: 'PREVIOUS' });
            // Dispatch DOM CustomEvent for other consumers
            try {
                const event = new CustomEvent('paginator-boundary', {
                    detail: { direction: 'PREVIOUS', windowIndex: config.windowIndex }
                });
                document.dispatchEvent(event);
            } catch (e) {
                log('ERROR', `Failed to dispatch boundary event: ${e.message}`);
            }
            lastBoundaryDirection = 'BACKWARD';
            log('BOUNDARY', 'Reached BACKWARD boundary');
        } else if (currentProgress > (1 - BOUNDARY_THRESHOLD) && currentProgress < BOUNDARY_THRESHOLD) {
            lastBoundaryDirection = null;
        }
    }
    
    /**
     * Call Android bridge
     * @param {string} method - Method name
     * @param {Object} data - Data object
     */
    function callAndroidBridge(method, data) {
        try {
            if (window.PaginatorBridge && typeof window.PaginatorBridge[method] === 'function') {
                window.PaginatorBridge[method](JSON.stringify(data));
                log('BRIDGE', `Called PaginatorBridge.${method}(${JSON.stringify(data)})`);
            } else {
                log('ERROR', `PaginatorBridge.${method} not available - pagination callbacks will fail`);
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
        isReady,
        reflow
    };
    
    // Add 'inpagePaginator' alias for backward compatibility during transition
    window.inpagePaginator = window.minimalPaginator;
    
    /**
     * Convenience wrapper for initializing the paginator.
     * This is called from Kotlin after HTML is fully loaded.
     * @param {string} rootSelector - CSS selector for root element (optional, defaults to body)
     * @returns {boolean} - True if initialization succeeded
     */
    window.initPaginator = function(rootSelector) {
        log('INIT', `initPaginator called with selector: ${rootSelector || 'default'}`);
        // The selector is for future extensibility; currently we always use document.body
        return initialize();
    };
    
    /**
     * Request paginator to recheck page count (for dynamic content changes)
     */
    window.paginatorRecheck = function() {
        log('RECHECK', 'Pagination recheck requested');
        calculatePageCountAndOffsets();
        if (state.isPaginationReady) {
            callAndroidBridge('onPaginationReady', { pageCount: state.pageCount });
        }
    };
    
    /**
     * Stop paginator (cleanup)
     */
    window.paginatorStop = function() {
        log('STOP', 'Paginator stopped');
        state.isInitialized = false;
        state.isPaginationReady = false;
    };
    
    log('INIT', 'Minimal Paginator v2 injected');
    
})();
