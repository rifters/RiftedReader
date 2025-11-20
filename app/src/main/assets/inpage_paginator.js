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
    let chapterSegments = [];
    let initialChapterIndex = 0;
    
    /**
     * Initialize the paginator by wrapping content in a column container
     */
    function init() {
        if (isInitialized) {
            console.log('inpage_paginator: Already initialized, skipping');
            return;
        }
        
        console.log('inpage_paginator: Initializing paginator');
        
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
        
        const pageCount = getPageCount();
        console.log('inpage_paginator: Initialization complete - pageCount=' + pageCount + ', viewportWidth=' + viewportWidth);
        
        // Notify Android if callback exists
        if (window.AndroidBridge && window.AndroidBridge.onPaginationReady) {
            console.log('inpage_paginator: Calling AndroidBridge.onPaginationReady with pageCount=' + pageCount);
            window.AndroidBridge.onPaginationReady(pageCount);
        }
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
        const segment = document.createElement('section');
        segment.className = 'chapter-segment';
        segment.setAttribute('data-chapter-index', initialChapterIndex);
        const fragment = document.createDocumentFragment();
        while (contentWrapper.firstChild) {
            fragment.appendChild(contentWrapper.firstChild);
        }
        segment.appendChild(fragment);
        contentWrapper.appendChild(segment);
        chapterSegments = [segment];
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
            }
            
            const currentPage = getCurrentPage();
            console.log('inpage_paginator: Reflow complete - pageCount=' + pageCount + ', currentPage=' + currentPage);
            
            // Notify Android if callback exists
            if (window.AndroidBridge && window.AndroidBridge.onPaginationReady) {
                console.log('inpage_paginator: Calling AndroidBridge.onPaginationReady after reflow with pageCount=' + pageCount);
                window.AndroidBridge.onPaginationReady(pageCount);
            }
            
            return { success: true, pageCount: pageCount, currentPage: currentPage };
        } catch (e) {
            console.error('inpage_paginator: Reflow error', e);
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
        
        console.log('inpage_paginator: Setting font size to ' + px + 'px');
        
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
        
        const scrollLeft = columnContainer.scrollLeft;
        const pageWidth = viewportWidth || window.innerWidth;
        
        if (pageWidth === 0) {
            return 0;
        }
        
        return Math.floor(scrollLeft / pageWidth);
    }
    
    /**
     * Go to a specific page (0-based index)
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
        const currentPage = getCurrentPage();
        if (index === 0 && currentPage > 0) {
            console.log('inpage_paginator: WARNING - Resetting to page 0 from page ' + currentPage);
            console.log('inpage_paginator: Call stack:', new Error().stack);
        }
        
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
     * Go to next page
     */
    function nextPage() {
        if (!columnContainer || !isInitialized) {
            console.warn('inpage_paginator: nextPage called before initialization complete');
            return false;
        }
        
        const currentPage = getCurrentPage();
        const pageCount = getPageCount();
        
        console.log('inpage_paginator: nextPage called - currentPage=' + currentPage + ', pageCount=' + pageCount);
        
        if (currentPage < pageCount - 1) {
            goToPage(currentPage + 1, false);  // Changed from true to false - instant navigation
            return true;
        }
        
        console.log('inpage_paginator: nextPage - already at last page');
        requestStreamingSegment('NEXT', currentPage, pageCount);
        notifyBoundary('NEXT', currentPage, pageCount);
        return false;
    }
    
    /**
     * Go to previous page
     */
    function prevPage() {
        if (!columnContainer || !isInitialized) {
            console.warn('inpage_paginator: prevPage called before initialization complete');
            return false;
        }
        
        const currentPage = getCurrentPage();
        const pageCount = getPageCount();
        
        console.log('inpage_paginator: prevPage called - currentPage=' + currentPage + ', pageCount=' + pageCount);
        
        if (currentPage > 0) {
            goToPage(currentPage - 1, false);  // Changed from true to false - instant navigation
            return true;
        }
        
        console.log('inpage_paginator: prevPage - already at first page');
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
            console.log('inpage_paginator: Appending chapter segment', chapterIndex);
            
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
            
            console.log('inpage_paginator: Appended chapter', chapterIndex, 
                       '- pages before=' + pageCountBefore + 
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
            console.log('inpage_paginator: Prepending chapter segment', chapterIndex);
            
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
                console.log('inpage_paginator: Adjusting scroll after prepend - old page=' + currentPage + 
                           ', pages added=' + pagesAdded + ', new target=' + newTargetPage);
                goToPage(newTargetPage, false);
            }
            
            console.log('inpage_paginator: Prepended chapter', chapterIndex, 
                       '- pages before=' + pageCountBefore + 
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
     * Check if paginator is initialized and ready for operations
     */
    function isReady() {
        return isInitialized && columnContainer !== null && document.getElementById('paginator-content') !== null;
    }
    
    // Initialize on DOM ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
    
    // Expose global API
    window.inpagePaginator = {
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
        getCurrentChapter: getCurrentChapter
    };
    
})();
