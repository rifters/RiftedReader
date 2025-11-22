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
 */
(function() {
    'use strict';
    
    // Configuration
    const COLUMN_GAP = 0; // No gap between columns for seamless pages
    const SCROLL_BEHAVIOR_SMOOTH = 'smooth';
    const SCROLL_BEHAVIOR_AUTO = 'auto';
    const MAX_CHAPTER_SEGMENTS = 5; // Limit DOM growth when streaming chapters
    
    // State
    let currentFontSize = 16; // Default font size in pixels
    let columnContainer = null;
    let contentWrapper = null;
    let viewportWidth = 0;
    let isInitialized = false;
    let isPaginationReady = false; // CRITICAL: Only true after onPaginationReady callback fires
    let chapterSegments = [];
    let initialChapterIndex = 0;
    let currentPage = 0; // CRITICAL: Track current page explicitly to avoid async scrollTo issues
    
    // Pagination configuration
    let paginatorConfig = {
        mode: 'window', // 'window' or 'chapter' - default to window mode for sliding-window compatibility
        windowIndex: 0,
        chapterIndex: null,
        rootSelector: null,
        initialInPageIndex: 0
    };
    
    /**
     * Configure the paginator before initialization.
     * This should be called before init() or immediately after page load.
     * 
     * @param {Object} config - Configuration object
     * @param {string} config.mode - "window" or "chapter" mode
     * @param {number} config.windowIndex - Window/ViewPager page index
     * @param {number} [config.chapterIndex] - Chapter index (required for chapter mode)
     * @param {string} [config.rootSelector] - CSS selector for pagination root
     * @param {number} [config.initialInPageIndex] - Initial page to navigate to
     */
    function configure(config) {
        if (!config) {
            console.warn('inpage_paginator: configure() called without config, using defaults');
            return;
        }
        
        // Validate mode
        if (config.mode && (config.mode !== 'window' && config.mode !== 'chapter')) {
            console.error('inpage_paginator: Invalid mode "' + config.mode + '", must be "window" or "chapter"');
            return;
        }
        
        // Update configuration
        paginatorConfig = {
            mode: config.mode || paginatorConfig.mode,
            windowIndex: config.windowIndex !== undefined ? config.windowIndex : paginatorConfig.windowIndex,
            chapterIndex: config.chapterIndex !== undefined ? config.chapterIndex : paginatorConfig.chapterIndex,
            rootSelector: config.rootSelector || paginatorConfig.rootSelector,
            initialInPageIndex: config.initialInPageIndex !== undefined ? config.initialInPageIndex : paginatorConfig.initialInPageIndex
        };
        
        console.log('inpage_paginator: Configured with mode=' + paginatorConfig.mode + 
                    ', windowIndex=' + paginatorConfig.windowIndex + 
                    ', chapterIndex=' + paginatorConfig.chapterIndex +
                    ', rootSelector=' + (paginatorConfig.rootSelector || 'default') +
                    ', initialInPageIndex=' + paginatorConfig.initialInPageIndex);
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
            console.log('inpage_paginator: [STATE] Initialization complete after layout - pageCount=' + pageCount + ', viewportWidth=' + viewportWidth);
            
            // CRITICAL: Set pagination ready flag AFTER we have valid page count
            isPaginationReady = true;
            console.log('inpage_paginator: [STATE] isPaginationReady set to true');
            
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
     * Update column styles on the content wrapper
     * Preserves existing fontSize to avoid breaking dynamic font size adjustments
     */
    function updateColumnStyles(wrapper) {
        viewportWidth = window.innerWidth;
        const columnWidth = viewportWidth;
        
        // Preserve the current font size before updating styles
        const preservedFontSize = wrapper.style.fontSize;
        
        wrapper.style.cssText = `
            column-width: ${columnWidth}px;
            column-gap: ${COLUMN_GAP}px;
            column-fill: auto;
            height: 100%;
            scroll-snap-align: start;
        `;
        
        // Restore the preserved font size if it existed
        if (preservedFontSize) {
            wrapper.style.fontSize = preservedFontSize;
        }
        
        // CRITICAL FIX: Set explicit width on wrapper to enable horizontal scrolling
        // Without this, scrollWidth reports correctly but scrollLeft stays 0
        // Force a layout to get accurate scrollWidth measurement
        wrapper.offsetHeight; // Force reflow
        const totalWidth = wrapper.scrollWidth;
        if (totalWidth > 0) {
            wrapper.style.width = totalWidth + 'px';
            console.log('inpage_paginator: Set wrapper width to ' + totalWidth + 'px for scrolling');
        }
    }

    function wrapExistingContentAsSegment() {
        if (!contentWrapper) {
            return;
        }
        
        // CRITICAL FIX: Check if content already has chapter sections (sliding-window mode)
        // Look for existing <section> elements with data-chapter-index attributes
        const existingSections = contentWrapper.querySelectorAll('section[data-chapter-index]');
        
        if (existingSections.length > 0) {
            // Content is already structured with chapter sections (from sliding-window HTML)
            // This corresponds to WINDOW mode - preserve all sections
            console.log('inpage_paginator: Found ' + existingSections.length + ' existing chapter sections');
            
            if (paginatorConfig.mode === 'chapter' && paginatorConfig.chapterIndex !== null) {
                // CHAPTER mode: Extract only the specific chapter section
                console.log('inpage_paginator: CHAPTER mode - isolating chapter ' + paginatorConfig.chapterIndex);
                const targetSection = Array.from(existingSections).find(function(section) {
                    const idx = parseInt(section.getAttribute('data-chapter-index'), 10);
                    return idx === paginatorConfig.chapterIndex;
                });
                
                if (targetSection) {
                    // Keep only the target chapter section
                    chapterSegments = [targetSection];
                    targetSection.classList.add('chapter-segment');
                    
                    // Remove other sections from DOM
                    existingSections.forEach(function(section) {
                        if (section !== targetSection && section.parentNode) {
                            section.parentNode.removeChild(section);
                        }
                    });
                    
                    initialChapterIndex = paginatorConfig.chapterIndex;
                    console.log('inpage_paginator: Isolated chapter ' + paginatorConfig.chapterIndex + ' for pagination');
                } else {
                    console.warn('inpage_paginator: Chapter ' + paginatorConfig.chapterIndex + ' not found in sections');
                    // Fall back to preserving all sections
                    chapterSegments = Array.from(existingSections);
                    chapterSegments.forEach(function(seg) {
                        if (!seg.classList.contains('chapter-segment')) {
                            seg.classList.add('chapter-segment');
                        }
                    });
                }
            } else {
                // WINDOW mode: Preserve all sections for multi-chapter pagination
                console.log('inpage_paginator: WINDOW mode - preserving all ' + existingSections.length + ' chapter sections');
                chapterSegments = Array.from(existingSections);
                chapterSegments.forEach(function(seg) {
                    // Add the chapter-segment class if not already present
                    if (!seg.classList.contains('chapter-segment')) {
                        seg.classList.add('chapter-segment');
                    }
                });
                
                // Set initialChapterIndex based on the first section if not already set
                if (chapterSegments.length > 0 && initialChapterIndex === 0) {
                    const firstChapterIndex = parseInt(chapterSegments[0].getAttribute('data-chapter-index'), 10);
                    if (!isNaN(firstChapterIndex)) {
                        initialChapterIndex = firstChapterIndex;
                        console.log('inpage_paginator: Set initialChapterIndex to ' + initialChapterIndex + ' from first section');
                    }
                }
            }
            
            return;
        }
        
        // No existing chapter sections - wrap all content as a single segment
        // This is the legacy behavior for chapter-based mode with no pre-structured HTML
        console.log('inpage_paginator: No existing chapter sections found - wrapping all content as single segment (legacy chapter mode)');
        const segment = document.createElement('section');
        segment.className = 'chapter-segment';
        const chapterIdx = paginatorConfig.chapterIndex !== null ? paginatorConfig.chapterIndex : initialChapterIndex;
        segment.setAttribute('data-chapter-index', chapterIdx);
        const fragment = document.createDocumentFragment();
        while (contentWrapper.firstChild) {
            fragment.appendChild(contentWrapper.firstChild);
        }
        segment.appendChild(fragment);
        contentWrapper.appendChild(segment);
        chapterSegments = [segment];
        initialChapterIndex = chapterIdx;
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
     */
    function getPageCount() {
        if (!columnContainer) {
            init();
        }
        
        // Double check after init - if still not ready, return safe default
        if (!columnContainer || !isInitialized) {
            console.warn('inpage_paginator: getPageCount called before initialization complete');
            return 1;
        }
        
        const contentWrapper = document.getElementById('paginator-content');
        if (!contentWrapper) {
            console.warn('inpage_paginator: contentWrapper not found in getPageCount');
            return 1;
        }
        
        const scrollWidth = contentWrapper.scrollWidth;
        const pageWidth = viewportWidth || window.innerWidth;
        
        if (pageWidth === 0) {
            return 1;
        }
        
        return Math.max(1, Math.ceil(scrollWidth / pageWidth));
    }
    
    /**
     * Get the current page index (0-based)
     * CRITICAL: Returns the explicitly tracked currentPage state, not calculated from scrollLeft.
     * This avoids race conditions where scrollTo() hasn't completed yet.
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
        
        // Return the explicitly tracked page
        // This ensures getCurrentPage() returns the intended page immediately after goToPage(),
        // even if the scrollTo() animation hasn't completed yet
        return currentPage;
    }
    
    /**
     * Sync the currentPage state with the actual scroll position.
     * Called when user manually scrolls or when we need to read the real position.
     */
    function syncCurrentPageFromScroll() {
        if (!columnContainer || !isInitialized) {
            return;
        }
        
        const scrollLeft = columnContainer.scrollLeft;
        const pageWidth = viewportWidth || window.innerWidth;
        
        if (pageWidth === 0) {
            return;
        }
        
        currentPage = Math.floor(scrollLeft / pageWidth);
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
        
        const behavior = smooth ? SCROLL_BEHAVIOR_SMOOTH : SCROLL_BEHAVIOR_AUTO;
        
        columnContainer.scrollTo({
            left: targetScroll,
            behavior: behavior
        });
        
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
        while (chapterSegments.length > MAX_CHAPTER_SEGMENTS) {
            const seg = chapterSegments.shift();
            if (seg && seg.parentNode) {
                const chapterIndex = seg.getAttribute('data-chapter-index');
                seg.parentNode.removeChild(seg);
                if (window.AndroidBridge && window.AndroidBridge.onSegmentEvicted) {
                    try {
                        window.AndroidBridge.onSegmentEvicted(parseInt(chapterIndex, 10));
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
        while (chapterSegments.length > MAX_CHAPTER_SEGMENTS) {
            const seg = chapterSegments.pop();
            if (seg && seg.parentNode) {
                const chapterIndex = seg.getAttribute('data-chapter-index');
                seg.parentNode.removeChild(seg);
                if (window.AndroidBridge && window.AndroidBridge.onSegmentEvicted) {
                    try {
                        window.AndroidBridge.onSegmentEvicted(parseInt(chapterIndex, 10));
                    } catch (err) {
                        console.warn('inpage_paginator: onSegmentEvicted callback failed', err);
                    }
                }
            }
        }
    }

    /**
     * Append a chapter segment to the end of the content
     * Enhanced with better error handling and scroll position preservation
     * @param {number} chapterIndex - The chapter index to append
     * @param {string} rawHtml - The HTML content to append
     * @returns {boolean} - True if successful, false otherwise
     */
    function appendChapterSegment(chapterIndex, rawHtml) {
        if (!contentWrapper) {
            console.warn('inpage_paginator: appendChapterSegment called before init');
            return false;
        }
        
        if (!rawHtml || rawHtml.trim() === '') {
            console.warn('inpage_paginator: appendChapterSegment called with empty HTML');
            return false;
        }
        
        try {
            console.log('inpage_paginator: [STREAMING] Appending chapter segment ' + chapterIndex + ', isPaginationReady=' + isPaginationReady);
            
            // Save current scroll position before modification
            const currentPage = getCurrentPage();
            const pageCountBefore = getPageCount();
            
            const segment = buildSegmentFromHtml(chapterIndex, rawHtml);
            contentWrapper.appendChild(segment);
            chapterSegments.push(segment);
            
            // Trim old segments if needed
            trimSegmentsFromStart();
            
            // Reflow to recalculate layout
            const reflowResult = reflow(true);
            
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
     * @param {number} chapterIndex - The chapter index to prepend
     * @param {string} rawHtml - The HTML content to prepend
     * @returns {boolean} - True if successful, false otherwise
     */
    function prependChapterSegment(chapterIndex, rawHtml) {
        if (!contentWrapper) {
            console.warn('inpage_paginator: prependChapterSegment called before init');
            return false;
        }
        
        if (!rawHtml || rawHtml.trim() === '') {
            console.warn('inpage_paginator: prependChapterSegment called with empty HTML');
            return false;
        }
        
        try {
            console.log('inpage_paginator: [STREAMING] Prepending chapter segment ' + chapterIndex + ', isPaginationReady=' + isPaginationReady);
            
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
            
            // Trim old segments from end if needed
            trimSegmentsFromEnd();
            
            // Reflow without preserving position initially
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
        
        // CRITICAL FIX: Use absolute positioning for accurate page calculations
        const currentScrollLeft = columnContainer.scrollLeft;
        const segmentRect = segment.getBoundingClientRect();
        const absoluteLeft = segmentRect.left + currentScrollLeft;
        
        const startPage = Math.floor(Math.max(0, absoluteLeft) / pageWidth);
        const endPage = Math.ceil((absoluteLeft + segmentRect.width) / pageWidth);
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
            
            // Calculate the page index for this segment using absolute positioning
            const pageWidth = viewportWidth || window.innerWidth;
            if (pageWidth === 0) {
                return false;
            }
            
            const currentScrollLeft = columnContainer.scrollLeft;
            const segmentRect = segment.getBoundingClientRect();
            
            // Calculate absolute position of segment within scroll container
            const absoluteLeft = segmentRect.left + currentScrollLeft;
            const pageIndex = Math.max(0, Math.floor(absoluteLeft / pageWidth));
            
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
            
            // CRITICAL FIX: Calculate positions relative to the scroll container, not contentWrapper
            // In column layout, segments are positioned horizontally within the contentWrapper
            const currentScrollLeft = columnContainer.scrollLeft;
            
            return chapterSegments.map(seg => {
                const chapterIndex = parseInt(seg.getAttribute('data-chapter-index'), 10);
                const segmentRect = seg.getBoundingClientRect();
                
                // Calculate absolute position within the scroll container
                // segmentRect.left is relative to viewport, so add currentScrollLeft to get absolute position
                const absoluteLeft = segmentRect.left + currentScrollLeft;
                
                // Calculate which pages this segment spans
                // Use Math.max to prevent negative page indices
                const startPage = Math.max(0, Math.floor(absoluteLeft / pageWidth));
                const endPage = Math.ceil((absoluteLeft + segmentRect.width) / pageWidth);
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
            
            // CRITICAL FIX: Calculate the center of the viewport to determine which chapter is "current"
            // This handles multi-chapter windows where sections are laid out horizontally
            const viewportCenter = currentScrollLeft + (pageWidth / 2);
            
            // Find which segment contains the viewport center
            for (let i = 0; i < chapterSegments.length; i++) {
                const seg = chapterSegments[i];
                const segmentRect = seg.getBoundingClientRect();
                
                // Calculate absolute position of segment in the scroll container
                // segmentRect.left is relative to viewport, so add currentScrollLeft to get absolute position
                const segmentLeft = segmentRect.left + currentScrollLeft;
                const segmentRight = segmentLeft + segmentRect.width;
                
                // Check if viewport center falls within this segment
                if (viewportCenter >= segmentLeft && viewportCenter < segmentRight) {
                    const chapterIndex = parseInt(seg.getAttribute('data-chapter-index'), 10);
                    return chapterIndex;
                }
            }
            
            // Fallback: return the first segment's chapter
            if (chapterSegments.length > 0) {
                const fallbackChapter = parseInt(chapterSegments[0].getAttribute('data-chapter-index'), 10);
                return fallbackChapter;
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
    
    // Initialize on DOM ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
    
    // Expose global API
    window.inpagePaginator = {
        configure: configure,
        isReady: isReady,
        reflow: reflow,
        setFontSize: setFontSize,
        getPageCount: getPageCount,
        getCurrentPage: getCurrentPage,
        goToPage: goToPage,
        nextPage: nextPage,
        prevPage: prevPage,
        getPageForSelector: getPageForSelector,
        createAnchorAroundViewportTop: createAnchorAroundViewportTop,
        scrollToAnchor: scrollToAnchor,
        appendChapter: appendChapterSegment,
        prependChapter: prependChapterSegment,
        removeChapter: removeChapterSegment,
        clearAllSegments: clearAllSegments,
        setInitialChapter: setInitialChapterIndex,
        getSegmentPageCount: getSegmentPageCount,
        jumpToChapter: jumpToChapter,
        getLoadedChapters: getLoadedChapters,
        getCurrentChapter: getCurrentChapter,
        getConfig: function() { return paginatorConfig; }
    };
    
})();
