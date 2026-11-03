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
    let originalSectionsData = [];
    
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
            
            // Find sections
            const sections = windowRoot.querySelectorAll('section[data-chapter]');
            if (sections.length === 0) {
                const error = 'No sections found in window-root';
                log('ERROR', error);
                callAndroidBridge('onSlicingError', error);
                return;
            }

            // Cache sections data so dynamic re-slicing (resize, font-size changes) works
            originalSectionsData = [];
            for (let i = 0; i < sections.length; i++) {
                originalSectionsData.push({
                    chapter: parseInt(sections[i].getAttribute('data-chapter'), 10),
                    html: sections[i].innerHTML
                });
            }

            log('INIT', `Found ${sections.length} sections to slice`);
            
            // Slice each section into pages
            sliceSections(originalSectionsData);
            
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
    function sliceSections(sectionsData) {
        state.slices = [];
        let currentPage = null;
        let currentPageIndex = 0;
        let accumulatedHeight = 0;
        let currentChapter = null;
        let sliceStartChar = 0;
        let currentChapterCharOffset = 0;
        
        // Clear document body
        document.body.innerHTML = '';

        // Inject responsive image safety style into head if missing
        const styleId = 'flex-paginator-img-styles';
        if (!document.getElementById(styleId)) {
            const style = document.createElement('style');
            style.id = styleId;
            style.textContent = `
                .page img {
                    max-width: 100% !important;
                    max-height: calc(100% - 16px) !important;
                    object-fit: contain !important;
                    height: auto !important;
                }
            `;
            document.head.appendChild(style);
        }
        
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
        
        function finalizeCurrentPage() {
            if (!currentPage) return;
            const hasChildren = currentPage.childNodes.length > 0;
            const hasChars = currentChapterCharOffset > sliceStartChar;
            const hasHeight = accumulatedHeight > 0;
            // Only keep page if it contains content or is the only page in the window
            if (hasChildren || hasChars || hasHeight || state.slices.length === 0) {
                const slice = {
                    page: currentPageIndex - 1,
                    chapter: currentChapter,
                    startChar: sliceStartChar,
                    endChar: currentChapterCharOffset,
                    heightPx: accumulatedHeight
                };
                state.slices.push(slice);
                log('SLICE', `Page ${slice.page}: chapter=${slice.chapter}, chars=${slice.startChar}-${slice.endChar}`);
            } else {
                if (currentPage.parentNode) {
                    currentPage.parentNode.removeChild(currentPage);
                }
                currentPageIndex--;
            }
        }

        function startNewPage(chapterIndex) {
            finalizeCurrentPage();
            
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
            sliceStartChar = currentChapterCharOffset;
            accumulatedHeight = 0;
            currentPageIndex++;
        }

        function getActiveTarget(targetParent) {
            if (!targetParent || targetParent === currentPage) {
                return currentPage;
            }
            if (targetParent.closest && targetParent.closest('.page') === currentPage) {
                return targetParent;
            }
            const clonedParent = document.createElement(targetParent.tagName);
            for (let i = 0; i < targetParent.attributes.length; i++) {
                const attr = targetParent.attributes[i];
                clonedParent.setAttribute(attr.name, attr.value);
            }
            if (targetParent.style.cssText) {
                clonedParent.style.cssText = targetParent.style.cssText;
            }
            currentPage.appendChild(clonedParent);
            return clonedParent;
        }

        function walkAndSlice(node, targetParent, chapterIndex) {
            if (node.nodeType === Node.TEXT_NODE) {
                const text = node.textContent || '';
                if (text.length > 0) {
                    let height = 0;
                    if (text.trim().length > 0) {
                        const lines = Math.max(1, Math.ceil(text.length / CHARS_PER_LINE));
                        height = lines * LINE_HEIGHT_PX;
                    }

                    // Decide page break BEFORE appending content
                    if (height > 0 && accumulatedHeight > 0 && (accumulatedHeight + height > state.viewportHeight)) {
                        startNewPage(chapterIndex);
                    }

                    const textNode = document.createTextNode(text);
                    const dest = getActiveTarget(targetParent);
                    dest.appendChild(textNode);
                    accumulatedHeight += height;
                    currentChapterCharOffset += text.length;

                    if (accumulatedHeight >= state.viewportHeight) {
                        startNewPage(chapterIndex);
                    }
                }
                return;
            }

            if (node.nodeType === Node.ELEMENT_NODE) {
                const element = node;
                const tagName = element.tagName.toLowerCase();

                let estimatedHeight = 0;
                let isImage = false;
                if (tagName === 'p') {
                    estimatedHeight = PARAGRAPH_HEIGHT_PX;
                } else if (tagName === 'h1') {
                    estimatedHeight = HEADING1_HEIGHT_PX;
                } else if (tagName === 'h2') {
                    estimatedHeight = HEADING2_HEIGHT_PX;
                } else if (tagName === 'h3') {
                    estimatedHeight = HEADING3_HEIGHT_PX;
                } else if (tagName === 'img') {
                    isImage = true;
                    estimatedHeight = IMAGE_HEIGHT_PX;
                    const attrHeight = parseInt(element.getAttribute('height'), 10);
                    const attrWidth = parseInt(element.getAttribute('width'), 10);
                    if (!isNaN(attrHeight) && attrHeight > 0) {
                        if (!isNaN(attrWidth) && attrWidth > 0 && state.viewportWidth > 0) {
                            const availWidth = Math.max(1, state.viewportWidth - 32);
                            const scale = Math.min(1.0, availWidth / attrWidth);
                            estimatedHeight = Math.round(attrHeight * scale);
                        } else {
                            estimatedHeight = attrHeight;
                        }
                    } else if (element.style.height && element.style.height.endsWith('px')) {
                        const pxHeight = parseInt(element.style.height, 10);
                        if (!isNaN(pxHeight) && pxHeight > 0) {
                            estimatedHeight = pxHeight;
                        }
                    }
                    const maxAllowedImgHeight = Math.max(1, state.viewportHeight - 32);
                    estimatedHeight = Math.min(estimatedHeight, maxAllowedImgHeight);
                } else if (tagName === 'br') {
                    estimatedHeight = BR_HEIGHT_PX;
                }

                // Decide page break BEFORE appending content
                if (estimatedHeight > 0 && accumulatedHeight > 0 && (accumulatedHeight + estimatedHeight > state.viewportHeight)) {
                    startNewPage(chapterIndex);
                }

                const clonedElement = document.createElement(element.tagName);
                for (let i = 0; i < element.attributes.length; i++) {
                    const attr = element.attributes[i];
                    clonedElement.setAttribute(attr.name, attr.value);
                }
                if (element.style.cssText) {
                    clonedElement.style.cssText = element.style.cssText;
                }

                const dest = getActiveTarget(targetParent);
                dest.appendChild(clonedElement);
                accumulatedHeight += estimatedHeight;

                for (let i = 0; i < element.childNodes.length; i++) {
                    walkAndSlice(element.childNodes[i], clonedElement, chapterIndex);
                }

                if (isImage && estimatedHeight >= state.viewportHeight * 0.75) {
                    startNewPage(chapterIndex);
                }
                return;
            }
        }
        
        // Process each section
        for (let sectionIdx = 0; sectionIdx < sectionsData.length; sectionIdx++) {
            const section = sectionsData[sectionIdx];
            const chapterIndex = section.chapter;
            
            log('SECTION', `Processing chapter ${chapterIndex} (section ${sectionIdx + 1}/${sectionsData.length})`);
            
            // Force hard break at chapter boundary
            startNewPage(chapterIndex);
            sliceStartChar = 0;
            currentChapterCharOffset = 0;
            
            // Parse section HTML into a temporary container
            const tempDiv = document.createElement('div');
            tempDiv.innerHTML = section.html || '';
            
            for (let c = 0; c < tempDiv.childNodes.length; c++) {
                walkAndSlice(tempDiv.childNodes[c], currentPage, chapterIndex);
            }
        }
        
        // Finalize last page
        finalizeCurrentPage();
        
        log('SLICING', `Complete: ${state.slices.length} pages from ${sectionsData.length} chapters`);
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
        if (!state.isInitialized || state.slices.length === 0) return false;
        if (state.currentPage >= state.slices.length - 1) {
            log('BOUNDARY', 'nextPage: at last page, emitting forward boundary');
            callAndroidBridge('onBoundaryReached', 'forward');
            return false;
        }
        return navigateToPage(state.currentPage + 1);
    }

    function prevPage() {
        if (!state.isInitialized || state.slices.length === 0) return false;
        if (state.currentPage <= 0) {
            log('BOUNDARY', 'prevPage: at first page, emitting backward boundary');
            callAndroidBridge('onBoundaryReached', 'backward');
            return false;
        }
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

                const maxScroll = (state.slices.length - 1) * state.viewportWidth;
                if (flexContainer.scrollLeft > maxScroll + 30) {
                    log('BOUNDARY', 'Scroll overscroll forward detected');
                    callAndroidBridge('onBoundaryReached', 'forward');
                    return;
                }
                if (flexContainer.scrollLeft < -30) {
                    log('BOUNDARY', 'Scroll overscroll backward detected');
                    callAndroidBridge('onBoundaryReached', 'backward');
                    return;
                }

                const pageIndex = Math.round(flexContainer.scrollLeft / state.viewportWidth);
                const validIndex = Math.max(0, Math.min(pageIndex, state.slices.length - 1));
                if (validIndex !== state.currentPage) {
                    state.currentPage = validIndex;
                    notifyPageChanged(validIndex);
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
            if (text.length > 0) {
                const textNode = document.createTextNode(text);
                targetPage.appendChild(textNode);
                
                // Estimate height (rough approximation)
                if (text.trim().length > 0) {
                    const lines = Math.max(1, Math.ceil(text.length / CHARS_PER_LINE));
                    const height = lines * LINE_HEIGHT_PX;
                    onContentAdded(height, text.length, false);
                } else {
                    onContentAdded(0, text.length, false);
                }
            }
            return;
        }
        
        // Element node
        if (node.nodeType === Node.ELEMENT_NODE) {
            const element = node;
            const tagName = element.tagName.toLowerCase();
            
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
            let isImage = false;
            if (tagName === 'p') {
                estimatedHeight = PARAGRAPH_HEIGHT_PX;
            } else if (tagName === 'h1') {
                estimatedHeight = HEADING1_HEIGHT_PX;
            } else if (tagName === 'h2') {
                estimatedHeight = HEADING2_HEIGHT_PX;
            } else if (tagName === 'h3') {
                estimatedHeight = HEADING3_HEIGHT_PX;
            } else if (tagName === 'img') {
                isImage = true;
                estimatedHeight = IMAGE_HEIGHT_PX;
                const attrHeight = parseInt(element.getAttribute('height'), 10);
                const attrWidth = parseInt(element.getAttribute('width'), 10);
                if (!isNaN(attrHeight) && attrHeight > 0) {
                    if (!isNaN(attrWidth) && attrWidth > 0 && state.viewportWidth > 0) {
                        const availWidth = Math.max(1, state.viewportWidth - 32);
                        const scale = Math.min(1.0, availWidth / attrWidth);
                        estimatedHeight = Math.round(attrHeight * scale);
                    } else {
                        estimatedHeight = attrHeight;
                    }
                } else if (element.style.height && element.style.height.endsWith('px')) {
                    const pxHeight = parseInt(element.style.height, 10);
                    if (!isNaN(pxHeight) && pxHeight > 0) {
                        estimatedHeight = pxHeight;
                    }
                }
                const maxAllowedImgHeight = Math.max(1, state.viewportHeight - 32);
                estimatedHeight = Math.min(estimatedHeight, maxAllowedImgHeight);
            } else if (tagName === 'br') {
                estimatedHeight = BR_HEIGHT_PX;
            }
            
            if (estimatedHeight > 0) {
                onContentAdded(estimatedHeight, 0, isImage);
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

    function runChunkPreparation() {
        try {
            const styleId = 'tts-chunk-style';
            if (!document.getElementById(styleId)) {
                const style = document.createElement('style');
                style.id = styleId;
                style.innerHTML = '[data-tts-chunk]{cursor:pointer;transition:background-color 0.2s ease-in-out;} .tts-highlight{background-color: rgba(255, 213, 79, 0.4) !important;}';
                document.head.appendChild(style);
            }

            const existing = document.querySelectorAll('[data-tts-chunk]');
            existing.forEach(node => {
                node.classList.remove('tts-highlight');
                node.removeAttribute('data-tts-chunk');
            });

            const selectors = 'p, li, blockquote, h1, h2, h3, h4, h5, h6, pre, article, section';
            const nodes = document.querySelectorAll(selectors);
            const chunks = [];
            let index = 0;
            let currentPos = 0;

            nodes.forEach(node => {
                if (!node) return;
                let text = node.innerText || '';
                text = text.replace(/\s+/g, ' ').trim();
                if (!text) return;

                node.setAttribute('data-tts-chunk', index);
                chunks.push({
                    index: index,
                    text: text,
                    startPosition: currentPos
                });

                currentPos += text.length + 1;
                index++;
            });

            if (!window.__ttsTapHandlerAttached) {
                document.addEventListener('click', function(event) {
                    const target = event.target.closest('[data-tts-chunk]');
                    if (!target) return;
                    const idx = parseInt(target.getAttribute('data-tts-chunk'), 10);
                    if (isNaN(idx)) return;
                    if (window.AndroidTtsBridge && AndroidTtsBridge.onChunkTapped) {
                        AndroidTtsBridge.onChunkTapped(idx);
                    }
                }, false);
                window.__ttsTapHandlerAttached = true;
            }

            if (window.AndroidTtsBridge && AndroidTtsBridge.onChunksPrepared) {
                AndroidTtsBridge.onChunksPrepared(JSON.stringify(chunks));
            }
        } catch (e) {
            console.error('[FLEX] prepareTtsChunks error:', e);
        }
    }

    function rebuildSlicesFromSavedSections() {
        if (!originalSectionsData || originalSectionsData.length === 0) return;
        sliceSections(originalSectionsData);
        const metadata = buildMetadata();
        sendMetadataToAndroid(metadata);
        if (typeof window.prepareTtsChunks === 'function') {
            window.prepareTtsChunks();
        } else {
            runChunkPreparation();
        }
    }

    function setFontSize(fontSizePx) {
        if (typeof fontSizePx !== 'number' || fontSizePx <= 0) return;

        // Update actual CSS font size and line height on root and body
        const lineHeight = (typeof window.FLEX_LINE_HEIGHT === 'number' && window.FLEX_LINE_HEIGHT > 0)
            ? window.FLEX_LINE_HEIGHT
            : 1.6;
        document.documentElement.style.fontSize = fontSizePx + 'px';
        document.documentElement.style.setProperty('--flex-font-size', fontSizePx + 'px');
        document.documentElement.style.lineHeight = String(lineHeight);
        document.documentElement.style.setProperty('--flex-line-height', String(lineHeight));
        if (document.body) {
            document.body.style.fontSize = fontSizePx + 'px';
            document.body.style.lineHeight = String(lineHeight);
        }
        window.FLEX_FONT_SIZE_PX = fontSizePx;

        LINE_HEIGHT_PX = Math.max(1, Math.round(fontSizePx * lineHeight));
        BR_HEIGHT_PX = LINE_HEIGHT_PX;
        PARAGRAPH_HEIGHT_PX = LINE_HEIGHT_PX * 2;
        HEADING1_HEIGHT_PX = Math.round(LINE_HEIGHT_PX * 2.5);
        HEADING2_HEIGHT_PX = Math.round(LINE_HEIGHT_PX * 2.0);
        HEADING3_HEIGHT_PX = Math.round(LINE_HEIGHT_PX * 1.5);

        if (state.isInitialized && originalSectionsData.length > 0) {
            log('FONT', `Font size updated to ${fontSizePx}px; re-slicing`);
            const prevSlice = state.slices[state.currentPage];
            const prevChapter = prevSlice ? prevSlice.chapter : 0;
            const prevChar = prevSlice ? prevSlice.startChar : 0;

            rebuildSlicesFromSavedSections();

            let targetPage = 0;
            for (let i = 0; i < state.slices.length; i++) {
                const s = state.slices[i];
                if (s.chapter === prevChapter && s.startChar <= prevChar) {
                    targetPage = i;
                }
            }
            navigateToPage(targetPage);
        }
    }

    // Window resize / orientation change listener
    let resizeTimer = null;
    window.addEventListener('resize', function() {
        if (resizeTimer !== null) clearTimeout(resizeTimer);
        resizeTimer = setTimeout(function() {
            if (!state.isInitialized || originalSectionsData.length === 0) return;
            const newWidth = Math.max(window.innerWidth, MIN_VIEWPORT_WIDTH);
            const newHeight = (typeof window.innerHeight === 'number' && window.innerHeight > 0)
                ? window.innerHeight
                : DEFAULT_VIEWPORT_HEIGHT_PX;
            if (newWidth === state.viewportWidth && newHeight === state.viewportHeight) return;

            log('RESIZE', `Window resized to ${newWidth}x${newHeight}px; re-slicing`);
            const prevSlice = state.slices[state.currentPage];
            const prevChapter = prevSlice ? prevSlice.chapter : 0;
            const prevChar = prevSlice ? prevSlice.startChar : 0;

            state.viewportWidth = newWidth;
            state.viewportHeight = newHeight;

            rebuildSlicesFromSavedSections();

            let targetPage = 0;
            for (let i = 0; i < state.slices.length; i++) {
                const s = state.slices[i];
                if (s.chapter === prevChapter && s.startChar <= prevChar) {
                    targetPage = i;
                }
            }
            navigateToPage(targetPage);
        }, 150);
    });

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
        setFontSize,
        isReady
    };
    // Alias for backwards compatibility with readers expecting inpagePaginator
    window.inpagePaginator = window.flexPaginator;
    
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
