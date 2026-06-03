/**
 * Unit tests for flex_paginator.js
 *
 * These tests verify the public JS API for flex-based slicing:
 * - slicing success and error callbacks
 * - hard chapter breaks at section boundaries
 * - navigation and anchor jumps
 * - exported public API helpers
 */

const fs = require('fs');
const path = require('path');

// Path to the actual flex_paginator.js file
const paginatorPath = path.join(__dirname, '../../app/src/main/assets/flex_paginator.js');

const FLEX_GLOBALS = [
  'FLEX_WINDOW_INDEX',
  'FLEX_VIEWPORT_WIDTH',
  'FLEX_VIEWPORT_HEIGHT',
  'FLEX_FONT_SIZE_PX',
  'FLEX_LINE_HEIGHT',
  'FLEX_PAGE_PADDING_PX'
];

/**
 * Helper to set up a clean DOM environment for each test
 */
function setupDOM(htmlContent = '', options = {}) {
  // Reset the document
  document.documentElement.innerHTML = '<head></head><body>' + htmlContent + '</body>';

  // Mock window.innerWidth for consistent testing
  Object.defineProperty(window, 'innerWidth', {
    writable: true,
    configurable: true,
    value: options.width || 320
  });

  // Mock window.innerHeight
  Object.defineProperty(window, 'innerHeight', {
    writable: true,
    configurable: true,
    value: options.height || 600
  });
}

/**
 * Helper to set up required FLEX_PAGINATOR globals
 */
function setupFlexGlobals(options = {}) {
  window.FLEX_WINDOW_INDEX = options.windowIndex || 0;
  window.FLEX_VIEWPORT_WIDTH = options.width || 320;
  window.FLEX_VIEWPORT_HEIGHT = options.height || 600;
  window.FLEX_FONT_SIZE_PX = options.fontSize || 16;
  window.FLEX_LINE_HEIGHT = options.lineHeight || 1.5;
  window.FLEX_PAGE_PADDING_PX = options.padding || 0;
}

/**
 * Helper to load the paginator script into the test environment
 */
function loadPaginator() {
  const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');

  // Clear any previous paginator instance
  delete window.flexPaginator;
  delete window.__riftedReaderFlexPaginator_v1__;

  // Execute the script in the current context
  const script = new Function(scriptContent);
  script();

  if (document.readyState === 'loading') {
    document.dispatchEvent(new Event('DOMContentLoaded'));
  }
  jest.runAllTimers();

  return window.flexPaginator;
}

/**
 * Helper to create mock AndroidBridge
 */
function createMockAndroidBridge() {
  const mock = {
    calls: {
      onSlicingComplete: [],
      onSlicingError: [],
      onPageChanged: [],
      onBoundaryReached: []
    },
    onSlicingComplete: jest.fn(function(json) {
      mock.calls.onSlicingComplete.push(JSON.parse(json));
    }),
    onSlicingError: jest.fn(function(error) {
      mock.calls.onSlicingError.push({ error });
    }),
    onPageChanged: jest.fn(function(page, chapter, startChar) {
      mock.calls.onPageChanged.push({ page, chapter, startChar });
    }),
    onBoundaryReached: jest.fn(function(direction) {
      mock.calls.onBoundaryReached.push({ direction });
    })
  };

  window.AndroidBridge = mock;
  return mock;
}

/**
 * Helper to create wrapped chapter HTML
 */
function createWindowRoot(sections, windowIndex = 3) {
  return `<div id="window-root" data-window-index="${windowIndex}">${sections}</div>`;
}

describe('flex_paginator.js', () => {

  beforeEach(() => {
    // Use fake timers for scroll debounce callbacks
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.useRealTimers();
    delete window.flexPaginator;
    delete window.AndroidBridge;
    delete window.__riftedReaderFlexPaginator_v1__;
    FLEX_GLOBALS.forEach(name => {
      delete window[name];
    });
  });

  describe('slicing', () => {

    test('should call AndroidBridge.onSlicingComplete with correct page count', () => {
      const content = 'a'.repeat(160);
      setupDOM(createWindowRoot(`
        <section data-chapter="0">
          <div style="height:48px">${content}</div>
        </section>
      `));
      setupFlexGlobals({ windowIndex: 5, width: 320, height: 48 });
      const mockBridge = createMockAndroidBridge();

      const paginator = loadPaginator();

      expect(mockBridge.onSlicingComplete).toHaveBeenCalled();
      expect(mockBridge.calls.onSlicingComplete.length).toBe(1);
      expect(mockBridge.calls.onSlicingComplete[0].windowIndex).toBe(3);
      expect(mockBridge.calls.onSlicingComplete[0].totalPages).toBe(2);
      expect(mockBridge.calls.onSlicingComplete[0].slices.length).toBe(2);
      expect(paginator.getPageCount()).toBe(2);
    });

    test('should call AndroidBridge.onSlicingError when window-root is missing', () => {
      setupDOM('<section data-chapter="0"><p>Chapter content</p></section>');
      setupFlexGlobals();
      const mockBridge = createMockAndroidBridge();

      const paginator = loadPaginator();

      expect(mockBridge.onSlicingError).toHaveBeenCalled();
      expect(mockBridge.calls.onSlicingError[0].error).toBe('No window-root element found');
      expect(paginator.isReady()).toBe(false);
    });

    test('should call AndroidBridge.onSlicingError when FLEX_PAGINATOR globals are not set', () => {
      setupDOM(createWindowRoot('<section data-chapter="0"><p>Chapter content</p></section>'));
      const mockBridge = createMockAndroidBridge();

      const paginator = loadPaginator();

      expect(mockBridge.onSlicingError).toHaveBeenCalled();
      expect(mockBridge.calls.onSlicingError[0].error).toContain('Missing FLEX_PAGINATOR globals');
      expect(paginator.isReady()).toBe(false);
    });

    test('should hard break at chapter section boundaries', () => {
      setupDOM(createWindowRoot(`
        <section data-chapter="0"><p>Chapter zero</p></section>
        <section data-chapter="1"><p>Chapter one</p></section>
      `));
      setupFlexGlobals({ height: 600 });
      const mockBridge = createMockAndroidBridge();

      loadPaginator();

      const metadata = mockBridge.calls.onSlicingComplete[0];
      const pages = document.querySelectorAll('.page');

      expect(metadata.totalPages).toBe(2);
      expect(metadata.slices.map(slice => slice.chapter)).toEqual([0, 1]);
      expect(Array.from(pages).map(page => page.getAttribute('data-chapter'))).toEqual(['0', '1']);
    });

  });

  describe('public API', () => {

    test('should expose the full flex paginator API surface', () => {
      setupDOM(createWindowRoot('<section data-chapter="0"><p>Chapter content</p></section>'));
      setupFlexGlobals();
      createMockAndroidBridge();

      const paginator = loadPaginator();

      expect(paginator).toBeDefined();
      expect(typeof paginator.navigateToPage).toBe('function');
      expect(typeof paginator.goToPage).toBe('function');
      expect(typeof paginator.jumpToAnchor).toBe('function');
      expect(typeof paginator.nextPage).toBe('function');
      expect(typeof paginator.prevPage).toBe('function');
      expect(typeof paginator.getCurrentPage).toBe('function');
      expect(typeof paginator.getPageCount).toBe('function');
      expect(typeof paginator.getCharacterOffsetForPage).toBe('function');
      expect(typeof paginator.isReady).toBe('function');
    });

    test('isReady should return true after successful slicing', () => {
      setupDOM(createWindowRoot('<section data-chapter="0"><p>Chapter content</p></section>'));
      setupFlexGlobals();
      createMockAndroidBridge();

      const paginator = loadPaginator();

      expect(paginator.isReady()).toBe(true);
    });

    test('getPageCount should match slicing metadata totalPages', () => {
      setupDOM(createWindowRoot(`
        <section data-chapter="0"><p>Chapter zero</p></section>
        <section data-chapter="1"><p>Chapter one</p></section>
      `));
      setupFlexGlobals();
      const mockBridge = createMockAndroidBridge();

      const paginator = loadPaginator();

      expect(paginator.getPageCount()).toBe(mockBridge.calls.onSlicingComplete[0].totalPages);
    });

    test('getCurrentPage should return 0 initially', () => {
      setupDOM(createWindowRoot('<section data-chapter="0"><p>Chapter content</p></section>'));
      setupFlexGlobals();
      createMockAndroidBridge();

      const paginator = loadPaginator();

      expect(paginator.getCurrentPage()).toBe(0);
    });

    test('getCharacterOffsetForPage should return slice startChar and 0 for missing pages', () => {
      setupDOM(createWindowRoot('<section data-chapter="0"><p>Chapter content</p></section>'));
      setupFlexGlobals();
      const mockBridge = createMockAndroidBridge();

      const paginator = loadPaginator();
      const firstSlice = mockBridge.calls.onSlicingComplete[0].slices[0];

      expect(paginator.getCharacterOffsetForPage(0)).toBe(firstSlice.startChar);
      expect(paginator.getCharacterOffsetForPage(99)).toBe(0);
    });

    test('navigateToPage should clamp page index and notify AndroidBridge', () => {
      setupDOM(createWindowRoot(`
        <section data-chapter="0"><p>Chapter zero</p></section>
        <section data-chapter="1"><p>Chapter one</p></section>
      `));
      setupFlexGlobals();
      const mockBridge = createMockAndroidBridge();

      const paginator = loadPaginator();
      const result = paginator.navigateToPage(99);

      expect(result).toBe(true);
      expect(paginator.getCurrentPage()).toBe(1);
      expect(mockBridge.calls.onPageChanged[0]).toEqual({ page: 1, chapter: 1, startChar: 0 });
      expect(mockBridge.calls.onBoundaryReached[0]).toEqual({ direction: 'forward' });
    });

    test('goToPage should navigate to the requested page', () => {
      setupDOM(createWindowRoot(`
        <section data-chapter="0"><p>Chapter zero</p></section>
        <section data-chapter="1"><p>Chapter one</p></section>
      `));
      setupFlexGlobals();
      createMockAndroidBridge();

      const paginator = loadPaginator();

      expect(paginator.goToPage(1)).toBe(true);
      expect(paginator.getCurrentPage()).toBe(1);
    });

    test('nextPage and prevPage should update currentPage', () => {
      setupDOM(createWindowRoot(`
        <section data-chapter="0"><p>Chapter zero</p></section>
        <section data-chapter="1"><p>Chapter one</p></section>
      `));
      setupFlexGlobals();
      createMockAndroidBridge();

      const paginator = loadPaginator();

      expect(paginator.nextPage()).toBe(true);
      expect(paginator.getCurrentPage()).toBe(1);

      expect(paginator.prevPage()).toBe(true);
      expect(paginator.getCurrentPage()).toBe(0);
    });

    test('jumpToAnchor should navigate to the page containing the anchor', () => {
      setupDOM(createWindowRoot(`
        <section data-chapter="0"><h2 id="chapter-zero">Chapter zero</h2></section>
        <section data-chapter="1"><h2 id="chapter-one">Chapter one</h2></section>
      `));
      setupFlexGlobals();
      createMockAndroidBridge();

      const paginator = loadPaginator();

      expect(paginator.jumpToAnchor('chapter-one')).toBe(true);
      expect(paginator.getCurrentPage()).toBe(1);
    });

    test('jumpToAnchor should return false when the anchor is missing', () => {
      setupDOM(createWindowRoot('<section data-chapter="0"><p>Chapter content</p></section>'));
      setupFlexGlobals();
      createMockAndroidBridge();

      const paginator = loadPaginator();

      expect(paginator.jumpToAnchor('missing-anchor')).toBe(false);
      expect(paginator.getCurrentPage()).toBe(0);
    });

  });

});
