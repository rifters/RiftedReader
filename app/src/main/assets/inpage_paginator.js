/**
 * InPage Paginator - CSS Column-based Horizontal Pagination for WebView
 * 
 * This script provides a dynamic pagination approach using CSS columns that reduces
 * WebView reloads and preserves reading position across font size changes.
 */

(function() {
    'use strict';
    
    // Pagination state
    let currentPage = 0;
    let pageWidth = 0;
    let totalPages = 0;
    let fontSize = 16; // Default font size in pixels
    let isInitialized = false;
    
    /**
     * Initialize the paginator by setting up CSS columns
     */
    function init() {
        if (isInitialized) {
            return;
        }
        
        // Get viewport width
        pageWidth = window.innerWidth || document.documentElement.clientWidth;
        
        // Apply column-based pagination styles
        const body = document.body;
        const html = document.documentElement;
        
        // Set up CSS for horizontal pagination
        body.style.columnWidth = pageWidth + 'px';
        body.style.columnGap = '0px';
        body.style.columnFill = 'auto';
        body.style.height = '100vh';
        body.style.overflow = 'hidden';
        
        html.style.height = '100vh';
        html.style.overflow = 'hidden';
        
        // Prevent text from breaking across columns at bad points
        body.style.columnBreakInside = 'avoid';
        
        isInitialized = true;
        
        // Calculate initial page count
        calculatePageCount();
    }
    
    /**
     * Calculate the total number of pages based on content width
     */
    function calculatePageCount() {
        if (!isInitialized) {
            return 0;
        }
        
        const body = document.body;
        const contentWidth = body.scrollWidth;
        
        if (pageWidth > 0) {
            totalPages = Math.ceil(contentWidth / pageWidth);
        } else {
            totalPages = 1;
        }
        
        return totalPages;
    }
    
    /**
     * Reflow the content after changes (e.g., font size, orientation)
     */
    function reflow() {
        // Recalculate page width
        pageWidth = window.innerWidth || document.documentElement.clientWidth;
        
        if (pageWidth > 0) {
            document.body.style.columnWidth = pageWidth + 'px';
        }
        
        // Recalculate page count
        const oldPageCount = totalPages;
        calculatePageCount();
        
        // Try to maintain approximate reading position
        if (oldPageCount > 0 && totalPages > 0) {
            const ratio = currentPage / oldPageCount;
            const newPage = Math.floor(ratio * totalPages);
            goToPage(newPage, false);
        } else {
            goToPage(0, false);
        }
    }
    
    /**
     * Set the font size and reflow content
     * @param {number} px - Font size in pixels
     */
    function setFontSize(px) {
        if (px <= 0) {
            return;
        }
        
        fontSize = px;
        document.body.style.fontSize = fontSize + 'px';
        
        // Reflow after font size change
        setTimeout(reflow, 100);
    }
    
    /**
     * Get the current number of pages
     * @returns {number} Total page count
     */
    function getPageCount() {
        calculatePageCount();
        return totalPages;
    }
    
    /**
     * Navigate to a specific page
     * @param {number} index - Zero-based page index
     * @param {boolean} smooth - Whether to use smooth scrolling
     */
    function goToPage(index, smooth) {
        if (index < 0 || !isInitialized) {
            index = 0;
        }
        
        if (index >= totalPages && totalPages > 0) {
            index = totalPages - 1;
        }
        
        currentPage = index;
        const scrollX = currentPage * pageWidth;
        
        if (smooth === true) {
            window.scrollTo({
                left: scrollX,
                top: 0,
                behavior: 'smooth'
            });
        } else {
            window.scrollTo(scrollX, 0);
        }
    }
    
    /**
     * Navigate to the next page
     * @returns {number} The new page index
     */
    function nextPage() {
        if (currentPage < totalPages - 1) {
            goToPage(currentPage + 1, true);
        }
        return currentPage;
    }
    
    /**
     * Navigate to the previous page
     * @returns {number} The new page index
     */
    function prevPage() {
        if (currentPage > 0) {
            goToPage(currentPage - 1, true);
        }
        return currentPage;
    }
    
    /**
     * Get the page index for an element matching the given selector
     * @param {string} selector - CSS selector for the target element
     * @returns {number} The page index containing the element, or -1 if not found
     */
    function getPageForSelector(selector) {
        try {
            const element = document.querySelector(selector);
            if (!element) {
                return -1;
            }
            
            // Get element's horizontal position
            const rect = element.getBoundingClientRect();
            const elementX = rect.left + window.scrollX;
            
            // Calculate which page the element is on
            if (pageWidth > 0) {
                const pageIndex = Math.floor(elementX / pageWidth);
                return pageIndex >= 0 && pageIndex < totalPages ? pageIndex : -1;
            }
            
            return -1;
        } catch (e) {
            console.error('getPageForSelector error:', e);
            return -1;
        }
    }
    
    /**
     * Get the current page index
     * @returns {number} Current zero-based page index
     */
    function getCurrentPage() {
        return currentPage;
    }
    
    // Initialize on load
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function() {
            setTimeout(init, 50);
        });
    } else {
        setTimeout(init, 50);
    }
    
    // Handle window resize events
    let resizeTimer;
    window.addEventListener('resize', function() {
        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(reflow, 250);
    });
    
    // Expose public API
    window.inpagePaginator = {
        reflow: reflow,
        setFontSize: setFontSize,
        getPageCount: getPageCount,
        goToPage: goToPage,
        nextPage: nextPage,
        prevPage: prevPage,
        getPageForSelector: getPageForSelector,
        getCurrentPage: getCurrentPage
    };
    
    // Log initialization
    console.log('InPage Paginator initialized');
})();
