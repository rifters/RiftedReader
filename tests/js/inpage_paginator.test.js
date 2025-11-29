/**
 * Unit tests for inpage_paginator.js
 * 
 * These tests verify the core JS logic for pagination:
 * - updateColumnStyles wrapper width calculation
 * - loadWindow behavior with descriptor.chapters
 * - append/prepend rules in CONSTRUCTION vs ACTIVE mode
 * - AndroidBridge callbacks
 */

const fs = require('fs');
const path = require('path');

// Path to the actual inpage_paginator.js file
const paginatorPath = path.join(__dirname, '../../app/src/main/assets/inpage_paginator.js');

/**
 * Helper to set up a clean DOM environment for each test
 */
function setupDOM(htmlContent = '') {
  // Reset the document
  document.documentElement.innerHTML = '<head></head><body>' + htmlContent + '</body>';
  
  // Mock window.innerWidth for consistent testing
  Object.defineProperty(window, 'innerWidth', {
    writable: true,
    configurable: true,
    value: 400
  });
  
  // Mock window.innerHeight
  Object.defineProperty(window, 'innerHeight', {
    writable: true,
    configurable: true,
    value: 600
  });
}

/**
 * Helper to load the paginator script into the test environment
 */
function loadPaginator() {
  const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
  
  // Clear any previous paginator instance
  delete window.inpagePaginator;
  
  // Execute the script in the current context
  const script = new Function(scriptContent);
  script();
  
  return window.inpagePaginator;
}

/**
 * Helper to create mock AndroidBridge
 */
function createMockAndroidBridge() {
  const mock = {
    calls: {
      onPaginationReady: [],
      onWindowLoaded: [],
      onWindowFinalized: [],
      onWindowLoadError: [],
      onPageChanged: [],
      onBoundaryReached: [],
      onStreamingRequest: [],
      onSegmentEvicted: [],
      onChapterNotLoaded: [],
      onChapterJumped: []
    },
    onPaginationReady: jest.fn(function(pageCount) {
      mock.calls.onPaginationReady.push({ pageCount });
    }),
    onWindowLoaded: jest.fn(function(json) {
      mock.calls.onWindowLoaded.push(JSON.parse(json));
    }),
    onWindowFinalized: jest.fn(function(pageCount) {
      mock.calls.onWindowFinalized.push({ pageCount });
    }),
    onWindowLoadError: jest.fn(function(json) {
      mock.calls.onWindowLoadError.push(JSON.parse(json));
    }),
    onPageChanged: jest.fn(function(page) {
      mock.calls.onPageChanged.push({ page });
    }),
    onBoundaryReached: jest.fn(function(direction, currentPage, pageCount) {
      mock.calls.onBoundaryReached.push({ direction, currentPage, pageCount });
    }),
    onStreamingRequest: jest.fn(function(direction, boundaryPage, totalPages) {
      mock.calls.onStreamingRequest.push({ direction, boundaryPage, totalPages });
    }),
    onSegmentEvicted: jest.fn(function(chapterIndex) {
      mock.calls.onSegmentEvicted.push({ chapterIndex });
    }),
    onChapterNotLoaded: jest.fn(function(chapterIndex) {
      mock.calls.onChapterNotLoaded.push({ chapterIndex });
    }),
    onChapterJumped: jest.fn(function(chapterIndex, pageIndex) {
      mock.calls.onChapterJumped.push({ chapterIndex, pageIndex });
    })
  };
  
  window.AndroidBridge = mock;
  return mock;
}

/**
 * Helper to wait for requestAnimationFrame callbacks
 */
function waitForAnimationFrame() {
  return new Promise(resolve => {
    // jsdom doesn't have real requestAnimationFrame, use setTimeout
    setTimeout(resolve, 0);
  });
}

/**
 * Helper to wait for multiple animation frames
 */
async function waitForLayout(frames = 2) {
  for (let i = 0; i < frames; i++) {
    await waitForAnimationFrame();
  }
}

describe('inpage_paginator.js', () => {
  
  beforeEach(() => {
    // Use fake timers for requestAnimationFrame
    jest.useFakeTimers();
  });
  
  afterEach(() => {
    jest.useRealTimers();
    delete window.inpagePaginator;
    delete window.AndroidBridge;
  });
  
  describe('initialization', () => {
    
    test('should initialize with basic HTML content', () => {
      setupDOM('<p>Hello World</p>');
      const mockBridge = createMockAndroidBridge();
      
      const paginator = loadPaginator();
      
      // Run pending timers to trigger requestAnimationFrame callback
      jest.runAllTimers();
      
      expect(paginator).toBeDefined();
      expect(typeof paginator.getPageCount).toBe('function');
      expect(typeof paginator.getCurrentPage).toBe('function');
      expect(typeof paginator.goToPage).toBe('function');
    });
    
    test('should create paginator-container and paginator-content elements', () => {
      setupDOM('<p>Test content</p>');
      createMockAndroidBridge();
      
      loadPaginator();
      jest.runAllTimers();
      
      const container = document.getElementById('paginator-container');
      const content = document.getElementById('paginator-content');
      
      expect(container).not.toBeNull();
      expect(content).not.toBeNull();
    });
    
    test('getPageCount should return > 0 after initialization', () => {
      setupDOM('<p>Some content that should create at least one page</p>');
      createMockAndroidBridge();
      
      const paginator = loadPaginator();
      jest.runAllTimers();
      
      const pageCount = paginator.getPageCount();
      expect(pageCount).toBeGreaterThan(0);
    });
    
    test('should call AndroidBridge.onPaginationReady with pageCount', () => {
      setupDOM('<p>Content</p>');
      const mockBridge = createMockAndroidBridge();
      
      loadPaginator();
      jest.runAllTimers();
      
      expect(mockBridge.onPaginationReady).toHaveBeenCalled();
      expect(mockBridge.calls.onPaginationReady.length).toBeGreaterThan(0);
      expect(mockBridge.calls.onPaginationReady[0].pageCount).toBeGreaterThan(0);
    });
    
  });
  
  describe('updateColumnStyles wrapper width calculation', () => {
    
    test('contentWrapper width should be a multiple of viewportWidth', () => {
      setupDOM('<p>Test content for width calculation</p>');
      createMockAndroidBridge();
      
      const paginator = loadPaginator();
      jest.runAllTimers();
      
      const content = document.getElementById('paginator-content');
      const viewportWidth = window.innerWidth;
      
      // Parse the width from the style
      const widthStr = content.style.width;
      if (widthStr && widthStr.endsWith('px')) {
        const width = parseInt(widthStr, 10);
        // Width should be an exact multiple of viewportWidth
        expect(width % viewportWidth).toBe(0);
      }
    });
    
    test('wrapper width should equal pageCount * viewportWidth', () => {
      setupDOM('<p>Content for page count test</p>');
      createMockAndroidBridge();
      
      const paginator = loadPaginator();
      jest.runAllTimers();
      
      const content = document.getElementById('paginator-content');
      const viewportWidth = window.innerWidth;
      const pageCount = paginator.getPageCount();
      
      const widthStr = content.style.width;
      if (widthStr && widthStr.endsWith('px')) {
        const width = parseInt(widthStr, 10);
        const expectedWidth = pageCount * viewportWidth;
        expect(width).toBe(expectedWidth);
      }
    });
    
  });
  
  describe('loadWindow with descriptor.chapters', () => {
    
    test('should append chapters from descriptor.chapters array (string format)', () => {
      // Start with minimal content
      setupDOM('');
      const mockBridge = createMockAndroidBridge();
      
      const paginator = loadPaginator();
      jest.runAllTimers();
      
      // loadWindow with chapters as string array
      paginator.loadWindow({
        windowIndex: 0,
        firstChapterIndex: 0,
        lastChapterIndex: 1,
        chapters: [
          '<p>Chapter 0 content</p>',
          '<p>Chapter 1 content</p>'
        ]
      });
      
      jest.runAllTimers();
      
      // Check that onWindowLoaded was called
      expect(mockBridge.onWindowLoaded).toHaveBeenCalled();
      expect(mockBridge.calls.onWindowLoaded.length).toBe(1);
      expect(mockBridge.calls.onWindowLoaded[0].windowIndex).toBe(0);
      expect(mockBridge.calls.onWindowLoaded[0].pageCount).toBeGreaterThan(0);
    });
    
    test('should append chapters from descriptor.chapters array (object format)', () => {
      setupDOM('');
      const mockBridge = createMockAndroidBridge();
      
      const paginator = loadPaginator();
      jest.runAllTimers();
      
      // loadWindow with chapters as object array
      paginator.loadWindow({
        windowIndex: 1,
        firstChapterIndex: 5,
        lastChapterIndex: 6,
        chapters: [
          { chapterIndex: 5, html: '<p>Chapter 5 content</p>' },
          { chapterIndex: 6, html: '<p>Chapter 6 content</p>' }
        ]
      });
      
      jest.runAllTimers();
      
      // Check that onWindowLoaded was called with correct window index
      expect(mockBridge.onWindowLoaded).toHaveBeenCalled();
      expect(mockBridge.calls.onWindowLoaded[0].windowIndex).toBe(1);
    });
    
    test('should not throw when appending chapters during loadWindow', () => {
      setupDOM('');
      const mockBridge = createMockAndroidBridge();
      
      const paginator = loadPaginator();
      jest.runAllTimers();
      
      // This should NOT throw because we're still in CONSTRUCTION mode
      expect(() => {
        paginator.loadWindow({
          windowIndex: 0,
          firstChapterIndex: 0,
          lastChapterIndex: 2,
          chapters: [
            '<p>Chapter 0</p>',
            '<p>Chapter 1</p>',
            '<p>Chapter 2</p>'
          ]
        });
      }).not.toThrow();
      
      jest.runAllTimers();
    });
    
    test('loadWindow should call onWindowLoaded with pageCount property', () => {
      setupDOM('');
      const mockBridge = createMockAndroidBridge();
      
      const paginator = loadPaginator();
      jest.runAllTimers();
      
      paginator.loadWindow({
        windowIndex: 0,
        firstChapterIndex: 0,
        lastChapterIndex: 0,
        chapters: ['<p>Single chapter content</p>']
      });
      
      jest.runAllTimers();
      
      expect(mockBridge.calls.onWindowLoaded.length).toBe(1);
      const loadedData = mockBridge.calls.onWindowLoaded[0];
      expect(loadedData).toHaveProperty('pageCount');
      expect(typeof loadedData.pageCount).toBe('number');
    });
    
  });
  
  describe('CONSTRUCTION vs ACTIVE mode enforcement', () => {
    
    test('appendChapter should throw when called in ACTIVE mode', () => {
      setupDOM('<p>Initial content</p>');
      createMockAndroidBridge();
      
      const paginator = loadPaginator();
      jest.runAllTimers();
      
      // Call loadWindow to finalize and enter ACTIVE mode
      paginator.loadWindow({
        windowIndex: 0,
        firstChapterIndex: 0,
        lastChapterIndex: 0
      });
      
      jest.runAllTimers();
      
      // Now appendChapter should throw
      expect(() => {
        paginator.appendChapter(1, '<p>New chapter</p>');
      }).toThrow('Cannot append chapter: window is in ACTIVE mode');
    });
    
    test('prependChapter should throw when called in ACTIVE mode', () => {
      setupDOM('<p>Initial content</p>');
      createMockAndroidBridge();
      
      const paginator = loadPaginator();
      jest.runAllTimers();
      
      // Call loadWindow to finalize and enter ACTIVE mode
      paginator.loadWindow({
        windowIndex: 0,
        firstChapterIndex: 0,
        lastChapterIndex: 0
      });
      
      jest.runAllTimers();
      
      // Now prependChapter should throw
      expect(() => {
        paginator.prependChapter(-1, '<p>Previous chapter</p>');
      }).toThrow('Cannot prepend chapter: window is in ACTIVE mode');
    });
    
    test('appendChapter should work before finalizeWindow', () => {
      setupDOM('');
      createMockAndroidBridge();
      
      const paginator = loadPaginator();
      jest.runAllTimers();
      
      // Configure but don't load window (stays in CONSTRUCTION mode)
      paginator.configure({
        mode: 'window',
        windowIndex: 0
      });
      
      // This should NOT throw - we're in CONSTRUCTION mode
      expect(() => {
        paginator.appendChapter(0, '<p>Chapter 0</p>');
      }).not.toThrow();
    });
    
    test('prependChapter should work before finalizeWindow', () => {
      setupDOM('');
      createMockAndroidBridge();
      
      const paginator = loadPaginator();
      jest.runAllTimers();
      
      // Configure but don't load window (stays in CONSTRUCTION mode)
      paginator.configure({
        mode: 'window',
        windowIndex: 0
      });
      
      // First append, then prepend
      paginator.appendChapter(1, '<p>Chapter 1</p>');
      
      // This should NOT throw - we're in CONSTRUCTION mode
      expect(() => {
        paginator.prependChapter(0, '<p>Chapter 0</p>');
      }).not.toThrow();
    });
    
  });
  
  describe('navigation functions', () => {
    
    test('goToPage should update currentPage', () => {
      setupDOM('<p>Content that spans multiple pages with lots of text to ensure pagination</p>'.repeat(10));
      createMockAndroidBridge();
      
      const paginator = loadPaginator();
      jest.runAllTimers();
      
      const pageCount = paginator.getPageCount();
      
      // Only test navigation if we have multiple pages
      if (pageCount > 1) {
        paginator.goToPage(1, false);
        expect(paginator.getCurrentPage()).toBe(1);
        
        paginator.goToPage(0, false);
        expect(paginator.getCurrentPage()).toBe(0);
      }
    });
    
    test('getCurrentPage should return 0 initially', () => {
      setupDOM('<p>Initial content</p>');
      createMockAndroidBridge();
      
      const paginator = loadPaginator();
      jest.runAllTimers();
      
      expect(paginator.getCurrentPage()).toBe(0);
    });
    
  });
  
  describe('isReady function', () => {
    
    test('isReady should return true after initialization complete', () => {
      setupDOM('<p>Content</p>');
      createMockAndroidBridge();
      
      const paginator = loadPaginator();
      jest.runAllTimers();
      
      expect(paginator.isReady()).toBe(true);
    });
    
  });
  
  describe('pre-wrapped chapter sections', () => {
    
    test('should recognize pre-wrapped section elements', () => {
      setupDOM(`
        <section data-chapter-index="0"><p>Chapter 0</p></section>
        <section data-chapter-index="1"><p>Chapter 1</p></section>
      `);
      createMockAndroidBridge();
      
      const paginator = loadPaginator();
      jest.runAllTimers();
      
      const loadedChapters = paginator.getLoadedChapters();
      expect(loadedChapters.length).toBe(2);
      expect(loadedChapters[0].chapterIndex).toBe(0);
      expect(loadedChapters[1].chapterIndex).toBe(1);
    });
    
  });
  
  describe('error handling', () => {
    
    test('loadWindow with null descriptor should call onWindowLoadError', () => {
      setupDOM('');
      const mockBridge = createMockAndroidBridge();
      
      const paginator = loadPaginator();
      jest.runAllTimers();
      
      paginator.loadWindow(null);
      
      jest.runAllTimers();
      
      expect(mockBridge.onWindowLoadError).toHaveBeenCalled();
    });
    
  });
  
});
