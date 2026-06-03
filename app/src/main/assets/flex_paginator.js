/**
 * flex_paginator.js - Clean-slate pagination with node-walking slicing
 * 
 * **Design Philosophy**:
 * - Flex layout (not CSS columns) for simpler, more robust pagination
 * - Node-walking algorithm for precise page breaks
 * - Hard breaks at <section> boundaries (chapter boundaries)
 * - Offscreen pre-slicing for zero-latency display
 * - Character offset tracking for bookmark restoration
 * 
 * **Responsibilities**:
 * - Parse wrapped HTML (sections with data-chapter attributes)
 * - Slice content into viewport-sized .page divs
 * - Measure real viewport heights (fonts, images, line-height)
 * - Build charOffset[] array for each page
 * - Report metadata to Kotlin via AndroidBridge.onSlicingComplete()
 * 
 * **NOT handled** (Conveyor's job):
 * - Chapter streaming (append/prepend/remove)
 * - Window lifecycle management
 * - Window transitions
 * - TOC navigation
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
        console.log('[FLEX] Already initialized - skipping duplicate injection');
        return;
    }
    window[GUARD_SYMBOL] = true;
    
    // ========================================================================
    // CONSTANTS
    // ========================================================================
    const DEFAULT_VIEWPORT_HEIGHT_PX = 600; // Fallback target page height
    const MIN_VIEWPORT_WIDTH = 10;
    const FALLBACK_VIEWPORT_WIDTH = 360;
    
    // Height estimation constants
    const CHARS_PER_LINE = 80;
    let LINE_HEIGHT_PX = 24;
    let PARAGRAPH_HEIGHT_PX = 48;  // ~2 lines
    let HEADING1_HEIGHT_PX = 60;
    let HEADING2_HEIGHT_PX = 48;
    let HEADING3_HEIGHT_PX = 36;
    const IMAGE_HEIGHT_PX = 200;
    let BR_HEIGHT_PX = 24;
    
    // ========================================================================
    // STATE
    // ========================================================================
    let state = {
        windowIndex: window.FLEX_WINDOW_INDEX || 0,
        viewportWidth: 0,
        viewportHeight: DEFAULT_VIEWPORT_HEIGHT_PX,
        slices: [],  // Array of PageSlice objects
        currentPage: 0,
        isInitialized: false
    };
    
    // ========================================================================
    // INITIALIZATION
    // ========================================================================
    
    /**
     * Initialize flex paginator on page load.
     * 
     * This is called automatically when the script loads.
     */
    function initialize() {
        try {
            log('INIT', 'Starting flex paginator initialization');

            const invalidGlobals = getInvalidRequiredGlobals();
            if (invalidGlobals.length > 0) {
                const error = `Missing or invalid FLEX_PAGINATOR globals: ${invalidGlobals.join(', ')}`;
                log('ERROR', error);
                callAndroidBridge('onSlicingError', error);
                return;
            }

            // Apply injected viewport + typography config when provided.
            const injectedHeight = (typeof window.FLEX_VIEWPORT_HEIGHT === 'number') ? window.FLEX_VIEWPORT_HEIGHT : null;
            if (injectedHeight && injectedHeight > 0) {
                state.viewportHeight = injectedHeight;
            }

            const injectedWidth = (typeof window.FLEX_VIEWPORT_WIDTH === 'number') ? window.FLEX_VIEWPORT_WIDTH : null;
            if (injectedWidth && injectedWidth > 0) {
                state.viewportWidth = injectedWidth;
            }

            const injectedFontSize = (typeof window.FLEX_FONT_SIZE_PX === 'number') ? window.FLEX_FONT_SIZE_PX : null;
            const injectedLineHeight = (typeof window.FLEX_LINE_HEIGHT === 'number') ? window.FLEX_LINE_HEIGHT : null;

            if (injectedFontSize && injectedFontSize > 0 && injectedLineHeight && injectedLineHeight > 0) {
                LINE_HEIGHT_PX = Math.max(1, Math.round(injectedFontSize * injectedLineHeight));
                BR_HEIGHT_PX = LINE_HEIGHT_PX;
                PARAGRAPH_HEIGHT_PX = LINE_HEIGHT_PX * 2;
                HEADING1_HEIGHT_PX = Math.round(LINE_HEIGHT_PX * 2.5);
                HEADING2_HEIGHT_PX = Math.round(LINE_HEIGHT_PX * 2.0);
                HEADING3_HEIGHT_PX = Math.round(LINE_HEIGHT_PX * 1.5);
            }
            
            // Get viewport dimensions (prefer injected width; fallback to window.innerWidth)
            if (!state.viewportWidth || state.viewportWidth < MIN_VIEWPORT_WIDTH) {
                state.viewportWidth = Math.max(window.innerWidth, MIN_VIEWPORT_WIDTH);
            }
            if (state.viewportWidth < MIN_VIEWPORT_WIDTH) {
                state.viewportWidth = FALLBACK_VIEWPORT_WIDTH;
                log('WARN', `Viewport width too small, using fallback: ${FALLBACK_VIEWPORT_WIDTH}px`);
            }
            
            log('INIT', `Viewport: ${state.viewportWidth}x${state.viewportHeight}px`);
            
            // Find window root
            const windowRoot = document.getElementById('window-root');
            if (!windowRoot) {
                const error = 'No window-root element found';
                log('ERROR', error);
                callAndroidBridge('onSlicingError', error);
                return;
            }
            
            // Get window index from data attribute (overrides global variable)
            const dataWindowIndex = windowRoot.getAttribute('data-window-index');
            if (dataWindowIndex) {
                state.windowIndex = parseInt(dataWindowIndex, 10);
            }
            
            log('INIT', `Window index: ${state.windowIndex}`);
            
            // Find all sections (chapters)
            const sections = windowRoot.querySelectorAll('section[data-chapter]');
            if (sections.length === 0) {
                const error = 'No sections with data-chapter found';
                log('ERROR', error);
                callAndroidBridge('onSlicingError', error);
                return;
            }
            
            log('INIT', `Found ${sections.length} sections to slice`);
            
            // Slice each section into pages
            sliceSections(sections);
            
            // Build metadata and send to Android
            const metadata = buildMetadata();
            sendMetadataToAndroid(metadata);
            
            state.isInitialized = true;
            log('SUCCESS', `Initialization complete: ${state.slices.length} pages`);
            
        } catch (e) {
            const error = `Initialization error: ${e.message}`;
            log('ERROR', error);
            callAndroidBridge('onSlicingError', error);
        }
    }
    
    // ========================================================================
    // NODE-WALKING SLICING ALGORITHM
    // ========================================================================
    
    /**
     * Slice all sections into viewport-sized pages.
     * 
     * This is the core algorithm that walks the DOM tree and creates pages.
     * 
     * **Algorithm**:
     * 1. For each <section>, create a container
     * 2. Walk DOM nodes in order
     * 3. Accumulate content into current .page div
     * 4. When accumulated height >= viewport height, start new page
     * 5. Force hard break at each <section> boundary
     * 6. Track character offsets for each page
     * 
     * @param {NodeList} sections Array of <section> elements
     */
    function sliceSections(sections) {
        let currentPage = null;
        let currentPageIndex = 0;
        let accumulatedHeight = 0;
        let currentChapter = null;
        let sliceStartChar = 0;
        let currentCharOffset = 0;
        
        // Clear document body
        document.body.innerHTML = '';
        
        // Create flex container for pages
        const flexContainer = document.createElement('div');
        flexContainer.id = 'flex-container';
        flexContainer.style.cssText = `
            display: flex;
            flex-direction: row;
            overflow-x: auto;
            overflow-y: hidden;
            scroll-snap-type: x mandatory;
            width: 100vw;
            height: 100vh;
        `;
        document.body.appendChild(flexContainer);
        
        // Helper to start a new page
        function startNewPage(chapterIndex) {
            if (currentPage) {
                // Finalize previous page
                const slice = {
                    page: currentPageIndex - 1,
                    chapter: currentChapter,
                    startChar: sliceStartChar,
                    endChar: currentCharOffset,
                    heightPx: accumulatedHeight
                };
                state.slices.push(slice);
                log('SLICE', `Page ${slice.page}: chapter=${slice.chapter}, chars=${slice.startChar}-${slice.endChar}`);
            }
            
            // Create new page
            currentPage = document.createElement('div');
            currentPage.className = 'page';
            currentPage.setAttribute('data-page', currentPageIndex);
            currentPage.setAttribute('data-chapter', chapterIndex);
            currentPage.style.cssText = `
                flex: 0 0 ${state.viewportWidth}px;
                width: ${state.viewportWidth}px;
                height: ${state.viewportHeight}px;
                overflow: hidden;
                scroll-snap-align: start;
                padding: ${(typeof window.FLEX_PAGE_PADDING_PX === 'number' && window.FLEX_PAGE_PADDING_PX >= 0) ? window.FLEX_PAGE_PADDING_PX : 16}px;
                box-sizing: border-box;
            `;
            flexContainer.appendChild(currentPage);
            
            currentChapter = chapterIndex;
            sliceStartChar = currentCharOffset;
            accumulatedHeight = 0;
            currentPageIndex++;
        }
        
        // Process each section
        for (let sectionIdx = 0; sectionIdx < sections.length; sectionIdx++) {
            const section = sections[sectionIdx];
            const chapterIndex = parseInt(section.getAttribute('data-chapter'), 10);
            
            log('SECTION', `Processing chapter ${chapterIndex} (section ${sectionIdx + 1}/${sections.length})`);
            
            // Force hard break: always start a new page at section boundary
            // (unless this is the very first section and we haven't created a page yet)
            // Start new page for this chapter
            startNewPage(chapterIndex);
            // PageSlice start/end offsets are chapter-local; reset at chapter boundaries.
            sliceStartChar = 0;
            currentCharOffset = 0; // Reset char offset for new chapter
            
            // Walk nodes in this section
            walkNodes(section, currentPage, (height, textLength) => {
                accumulatedHeight += height;
                currentCharOffset += textLength;
                
                // Check if we need to start a new page
                if (accumulatedHeight >= state.viewportHeight) {
                    startNewPage(chapterIndex);
                }
            });
        }
        
        // Finalize last page
        if (currentPage) {
            const slice = {
                page: currentPageIndex - 1,
                chapter: currentChapter,
                startChar: sliceStartChar,
                endChar: currentCharOffset,
                heightPx: accumulatedHeight
            };
            state.slices.push(slice);
            log('SLICE', `Page ${slice.page}: chapter=${slice.chapter}, chars=${slice.startChar}-${slice.endChar} (final page)`);
        }
        
        log('SLICING', `Complete: ${state.slices.length} pages from ${sections.length} chapters`);
        setupNavigationHandlers(flexContainer);
    }

    // ========================================================================
    // NAVIGATION
    // ========================================================================

    /**
     * Navigate to a specific pre-sliced page.
     *
     * @param {number} pageIndex Page index within this window
     * @returns {boolean} true if navigation was handled
     */
    function navigateToPage(pageIndex) {
        if (!state.isInitialized || state.slices.length === 0) {
            log('WARN', 'navigateToPage: paginator not ready');
            return false;
        }

        const validIndex = Math.max(0, Math.min(pageIndex, state.slices.length - 1));
        const flexContainer = document.getElementById('flex-container');
        if (!flexContainer) {
            log('ERROR', 'navigateToPage: flex-container not found');
            return false;
        }

        const scrollLeft = validIndex * state.viewportWidth;
        if (typeof flexContainer.scrollTo === 'function') {
            flexContainer.scrollTo({ left: scrollLeft, top: 0, behavior: 'auto' });
        } else {
            flexContainer.scrollLeft = scrollLeft;
        }

        state.currentPage = validIndex;
        notifyPageChanged(validIndex);
        notifyBoundaryIfNeeded(validIndex);
        log('NAV', `navigateToPage(${pageIndex}) -> ${validIndex}`);
        return true;
    }

    function jumpToAnchor(anchorId) {
        const target = document.getElementById(anchorId);
        if (!target) {
            return false;
        }

        if (document.body.classList.contains('mode-scroll')) {
            const prefersReducedMotion = window.matchMedia &&
                window.matchMedia('(prefers-reduced-motion: reduce)').matches;
            target.scrollIntoView({ behavior: prefersReducedMotion ? 'auto' : 'smooth', block: 'start' });
            return true;
        }

        if (!state.isInitialized || state.slices.length === 0) {
            log('WARN', 'jumpToAnchor: paginator not ready');
            return false;
        }

        const targetPage = target.closest('.page');
        if (!targetPage) {
            log('WARN', `jumpToAnchor: anchor ${anchorId} is not inside a page`);
            return false;
        }

        const targetChapter = parseInt(targetPage.getAttribute('data-chapter'), 10);
        const targetPageIndex = parseInt(targetPage.getAttribute('data-page'), 10);
        if (!Number.isInteger(targetChapter) || !Number.isInteger(targetPageIndex)) {
            log('WARN', `jumpToAnchor: invalid page metadata for anchor ${anchorId}`);
            return false;
        }

        const containingSlice = targetPageIndex >= 0 && targetPageIndex < state.slices.length
            ? state.slices[targetPageIndex]
            : null;
        if (!containingSlice) {
            log('WARN', `jumpToAnchor: no slice found for anchor ${anchorId}`);
            return false;
        }
        if (containingSlice.chapter !== targetChapter || containingSlice.page !== targetPageIndex) {
            log('WARN', `jumpToAnchor: slice/page mismatch for anchor ${anchorId}`);
            return false;
        }

        // Slicing and anchor lookup both count DOM text nodes, so the page slice
        // start character can be combined with the anchor's in-page text offset.
        const startChar = getSliceStartChar(containingSlice);
        const relativeCharOffset = getTextLengthBeforeTarget(targetPage, target);
        const targetCharOffset = startChar + relativeCharOffset;

        let nearestPageIndex = 0;
        // Sentinel below any valid slice offset so page 0 can be selected.
        let nearestStartChar = -1;
        for (let i = 0; i < state.slices.length; i++) {
            const slice = state.slices[i];
            if (!slice) continue;
            if (Number.isInteger(targetChapter) && slice.chapter !== targetChapter) continue;

            const sliceStartChar = getSliceStartChar(slice);
            // Find the closest slice that starts before or exactly at the target character offset.
            if (sliceStartChar <= targetCharOffset && sliceStartChar >= nearestStartChar) {
                nearestPageIndex = i;
                nearestStartChar = sliceStartChar;
            }
        }

        return navigateToPage(nearestPageIndex);
    }

    function nextPage() {
        return navigateToPage(state.currentPage + 1);
    }

    function prevPage() {
        return navigateToPage(state.currentPage - 1);
    }

    function getCurrentPage() {
        return state.currentPage;
    }

    function getPageCount() {
        return state.slices.length;
    }

    function getCharacterOffsetForPage(pageIndex) {
        const slice = state.slices[pageIndex];
        return slice ? slice.startChar : 0;
    }

    function getSliceStartChar(slice) {
        if (!slice) return 0;
        if (typeof slice.charOffset === 'number') return slice.charOffset;
        if (typeof slice.startChar === 'number') return slice.startChar;
        return 0;
    }

    /**
     * Count text characters from root up to, but not including, target for anchor jumps.
     *
     * @param {Node} root The page container that owns the target heading.
     * @param {Node} target The heading element being navigated to.
     * @returns {number} Cumulative character count from the start of root up to
     * the target element, or 0 if target is not found. This selects the closest
     * precomputed slice whose charOffset/startChar is before the anchor.
     */
    function getTextLengthBeforeTarget(root, target) {
        function walk(node) {
            if (node === target) {
                return { found: true, length: 0 };
            }

            if (node.nodeType === Node.TEXT_NODE) {
                return { found: false, length: (node.textContent || '').length };
            }

            let length = 0;
            for (let i = 0; i < node.childNodes.length; i++) {
                const child = node.childNodes[i];
                const result = walk(child);
                if (result.found) {
                    return { found: true, length: length + result.length };
                }
                length += result.length;
            }

            return { found: false, length };
        }

        const result = walk(root);
        return result.found ? result.length : 0;
    }

    function isReady() {
        return state.isInitialized && state.slices.length > 0;
    }

    function notifyPageChanged(pageIndex) {
        const slice = state.slices[pageIndex];
        if (!slice) return;

        callAndroidBridge('onPageChanged', slice.page, slice.chapter, slice.startChar);
    }

    function notifyBoundaryIfNeeded(pageIndex) {
        if (pageIndex === 0) {
            callAndroidBridge('onBoundaryReached', 'backward');
        } else if (pageIndex === state.slices.length - 1) {
            callAndroidBridge('onBoundaryReached', 'forward');
        }
    }

    function setupNavigationHandlers(flexContainer) {
        let scrollTimer = null;

        flexContainer.addEventListener('scroll', function() {
            if (scrollTimer !== null) {
                clearTimeout(scrollTimer);
            }

            scrollTimer = setTimeout(function() {
                if (!state.isInitialized || state.viewportWidth <= 0 || state.slices.length === 0) return;

                const pageIndex = Math.round(flexContainer.scrollLeft / state.viewportWidth);
                const validIndex = Math.max(0, Math.min(pageIndex, state.slices.length - 1));
                if (validIndex !== state.currentPage) {
                    state.currentPage = validIndex;
                    notifyPageChanged(validIndex);
                    notifyBoundaryIfNeeded(validIndex);
                }
            }, 100);
        });
    }
    
    /**
     * Walk DOM nodes and append them to the current page.
     * 
     * This function recursively walks the DOM tree and clones nodes into
     * the target page container, calling the callback for height measurements.
     * 
     * @param {Node} node The node to walk
     * @param {HTMLElement} targetPage The page to append content to
     * @param {Function} onContentAdded Callback(height, textLength) when content is added
     */
    function walkNodes(node, targetPage, onContentAdded) {
        // Text node
        if (node.nodeType === Node.TEXT_NODE) {
            const text = node.textContent || '';
            if (text.trim().length > 0) {
                const textNode = document.createTextNode(text);
                targetPage.appendChild(textNode);
                
                // Estimate height (rough approximation)
                const lines = Math.max(1, Math.ceil(text.length / CHARS_PER_LINE));
                const height = lines * LINE_HEIGHT_PX;
                onContentAdded(height, text.length);
            }
            return;
        }
        
        // Element node
        if (node.nodeType === Node.ELEMENT_NODE) {
            const element = node;
            
            // Clone element (without children)
            const clonedElement = document.createElement(element.tagName);
            
            // Copy attributes
            for (let i = 0; i < element.attributes.length; i++) {
                const attr = element.attributes[i];
                clonedElement.setAttribute(attr.name, attr.value);
            }
            
            // Copy inline styles
            if (element.style.cssText) {
                clonedElement.style.cssText = element.style.cssText;
            }
            
            targetPage.appendChild(clonedElement);
            
            // Recursively walk children
            for (let i = 0; i < element.childNodes.length; i++) {
                walkNodes(element.childNodes[i], clonedElement, onContentAdded);
            }
            
            // Estimate element height based on tag
            let estimatedHeight = 0;
            const tagName = element.tagName.toLowerCase();
            if (tagName === 'p') {
                estimatedHeight = PARAGRAPH_HEIGHT_PX;
            } else if (tagName === 'h1') {
                estimatedHeight = HEADING1_HEIGHT_PX;
            } else if (tagName === 'h2') {
                estimatedHeight = HEADING2_HEIGHT_PX;
            } else if (tagName === 'h3') {
                estimatedHeight = HEADING3_HEIGHT_PX;
            } else if (tagName === 'img') {
                estimatedHeight = IMAGE_HEIGHT_PX;
            } else if (tagName === 'br') {
                estimatedHeight = BR_HEIGHT_PX;
            }
            
            if (estimatedHeight > 0) {
                onContentAdded(estimatedHeight, 0);
            }
            
            return;
        }
        
        // Other node types (comments, etc.) - ignore
    }
    
    // ========================================================================
    // METADATA GENERATION
    // ========================================================================
    
    /**
     * Build slice metadata object for Android.
     * 
     * @returns {Object} Metadata object
     */
    function buildMetadata() {
        return {
            windowIndex: state.windowIndex,
            totalPages: state.slices.length,
            slices: state.slices
        };
    }
    
    /**
     * Send metadata to Android via AndroidBridge.
     * 
     * @param {Object} metadata Metadata object
     */
    function sendMetadataToAndroid(metadata) {
        try {
            const json = JSON.stringify(metadata);
            log('METADATA', `Sending to Android: ${json.length} chars, ${metadata.totalPages} pages`);
            
            if (typeof AndroidBridge !== 'undefined' && AndroidBridge !== null && typeof AndroidBridge.onSlicingComplete === 'function') {
                AndroidBridge.onSlicingComplete(json);
            } else {
                log('WARN', 'AndroidBridge.onSlicingComplete not available');
            }
        } catch (e) {
            log('ERROR', `Failed to send metadata: ${e.message}`);
            callAndroidBridge('onSlicingError', `Failed to send metadata: ${e.message}`);
        }
    }
    
    // ========================================================================
    // UTILITIES
    // ========================================================================
    
    /**
     * Call Android bridge method.
     * 
     * @param {string} method Method name
     * @param {...*} args Arguments for the bridge method
     */
    function callAndroidBridge(method, ...args) {
        try {
            if (typeof AndroidBridge !== 'undefined' && AndroidBridge !== null && typeof AndroidBridge[method] === 'function') {
                AndroidBridge[method](...args);
            }
        } catch (e) {
            console.error(`[FLEX] Bridge call failed: ${e.message}`);
        }
    }

    function getInvalidRequiredGlobals() {
        const validators = {
            FLEX_WINDOW_INDEX: value => Number.isInteger(value) && value >= 0,
            FLEX_VIEWPORT_WIDTH: value => typeof value === 'number' && value >= MIN_VIEWPORT_WIDTH,
            FLEX_VIEWPORT_HEIGHT: value => typeof value === 'number' && value > 0,
            FLEX_FONT_SIZE_PX: value => typeof value === 'number' && value > 0,
            FLEX_LINE_HEIGHT: value => typeof value === 'number' && value > 0,
            FLEX_PAGE_PADDING_PX: value => typeof value === 'number' && value >= 0
        };

        return Object.keys(validators).filter(name => !validators[name](window[name]));
    }
    
    /**
     * Logging utility.
     * 
     * @param {string} tag Log tag
     * @param {string} message Log message
     */
    function log(tag, message) {
        console.log(`[FLEX:${tag}] ${message}`);
    }

    // ========================================================================
    // EXPORT PUBLIC API
    // ========================================================================

    window.flexPaginator = {
        navigateToPage,
        goToPage: navigateToPage,
        jumpToAnchor,
        nextPage,
        prevPage,
        getCurrentPage,
        getPageCount,
        getCharacterOffsetForPage,
        isReady
    };
    
    // ========================================================================
    // AUTO-INITIALIZE
    // ========================================================================
    
    // Initialize on DOMContentLoaded
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initialize);
    } else {
        // DOM already loaded, initialize immediately
        initialize();
    }
    
})();
