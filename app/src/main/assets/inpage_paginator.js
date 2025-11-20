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
 */
(function() {
    'use strict';
    
    // Configuration
    const COLUMN_GAP = 0; // No gap between columns for seamless pages
    const SCROLL_BEHAVIOR_SMOOTH = 'smooth';
    const SCROLL_BEHAVIOR_AUTO = 'auto';
    
    // State
    let currentFontSize = 16; // Default font size in pixels
    let columnContainer = null;
    let viewportWidth = 0;
    let isInitialized = false;
    
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
        const contentWrapper = document.createElement('div');
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
     */
    function reflow() {
        if (!isInitialized || !columnContainer) {
            init();
            return;
        }
        
        console.log('inpage_paginator: Reflow triggered');
        
        // Save current page before reflow
        const currentPageBeforeReflow = getCurrentPage();
        console.log('inpage_paginator: Current page before reflow: ' + currentPageBeforeReflow);
        
        const contentWrapper = document.getElementById('paginator-content');
        if (contentWrapper) {
            updateColumnStyles(contentWrapper);
        }
        
        // Force reflow by temporarily changing a property
        columnContainer.style.display = 'none';
        columnContainer.offsetHeight; // Force reflow
        columnContainer.style.display = '';
        
        // Restore to same page (not same scroll position, because width may have changed)
        const pageCount = getPageCount();
        const targetPage = Math.min(currentPageBeforeReflow, pageCount - 1);
        if (targetPage >= 0) {
            goToPage(targetPage, false);
        }
        
        const currentPage = getCurrentPage();
        console.log('inpage_paginator: Reflow complete - pageCount=' + pageCount + ', currentPage=' + currentPage);
        
        // Notify Android if callback exists
        if (window.AndroidBridge && window.AndroidBridge.onPaginationReady) {
            console.log('inpage_paginator: Calling AndroidBridge.onPaginationReady after reflow with pageCount=' + pageCount);
            window.AndroidBridge.onPaginationReady(pageCount);
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
        return false;
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
        scrollToAnchor: scrollToAnchor
    };
    
})();
