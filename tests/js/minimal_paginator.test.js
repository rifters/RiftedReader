/**
 * Unit tests for minimal_paginator.js - scrollend fix
 * 
 * These tests verify the scrollend event handling fix for the double-page jump bug.
 */

const fs = require('fs');
const path = require('path');

// Path to the actual minimal_paginator.js file
const paginatorPath = path.join(__dirname, '../../app/src/main/assets/minimal_paginator.js');

/**
 * Helper function to extract a function body by counting braces
 * @param {string} scriptContent - The full script content
 * @param {string} functionName - The function name to extract
 * @returns {string|null} - The function body or null if not found
 */
function extractFunctionBody(scriptContent, functionName) {
  const funcStart = scriptContent.indexOf(`function ${functionName}(`);
  if (funcStart === -1) {
    return null;
  }
  
  let braceCount = 0;
  let inFunction = false;
  let funcEnd = funcStart;
  
  for (let i = funcStart; i < scriptContent.length; i++) {
    if (scriptContent[i] === '{') {
      braceCount++;
      inFunction = true;
    } else if (scriptContent[i] === '}') {
      braceCount--;
      if (inFunction && braceCount === 0) {
        funcEnd = i + 1;
        break;
      }
    }
  }
  
  return scriptContent.substring(funcStart, funcEnd);
}

describe('minimal_paginator.js - scrollend fix', () => {
  
  test('goToPage function should use scrollend event listener', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Verify the implementation has scrollend event handling
    expect(scriptContent).toContain('scrollend');
    expect(scriptContent).toContain('addEventListener');
    expect(scriptContent).toContain('removeEventListener');
  });
  
  test('goToPage should have 300ms fallback timeout', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Verify fallback timeout is 300ms (not 100ms)
    expect(scriptContent).toContain('300');
    expect(scriptContent).toContain('fallback timeout');
  });
  
  test('goToPage should not call checkBoundary immediately', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Find the goToPage function
    const goToPageMatch = scriptContent.match(/function goToPage\([\s\S]*?\n    \}/);
    expect(goToPageMatch).toBeTruthy();
    
    const goToPageFunction = goToPageMatch[0];
    
    // Verify there's a comment mentioning REMOVED checkBoundary
    expect(goToPageFunction).toContain('REMOVED: checkBoundary()');
    
    // Verify checkBoundary is not actually called (check for non-comment occurrence)
    // Remove all comments first
    const withoutComments = goToPageFunction.replace(/\/\/.*$/gm, '');
    expect(withoutComments).not.toContain('checkBoundary()');
  });
  
  test('goToPage should have scrollEndFired flag to prevent double-execution', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Verify the scrollEndFired flag exists
    expect(scriptContent).toContain('scrollEndFired');
    expect(scriptContent).toContain('Prevent double-execution');
  });
  
  test('should have scrollEndTimeout variable for cleanup', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Verify scrollEndTimeout exists
    expect(scriptContent).toContain('scrollEndTimeout');
    expect(scriptContent).toContain('clearTimeout');
  });
  
  test('scroll listener should call checkBoundary after state update', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Find the setupScrollListener function
    const scrollListenerMatch = scriptContent.match(/function setupScrollListener\(\)[\s\S]*?log\('SCROLL_LISTENER'/);
    expect(scrollListenerMatch).toBeTruthy();
    
    const scrollListenerFunction = scrollListenerMatch[0];
    
    // Verify checkBoundary IS called in the scroll listener
    expect(scrollListenerFunction).toContain('checkBoundary()');
  });
  
  test('should sync pagination state before checking boundaries', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Find the scroll listener - updated to use columnContainer instead of window
    const scrollListenerMatch = scriptContent.match(/state\.columnContainer\.addEventListener\('scroll'[\s\S]*?}, false\);/);
    expect(scrollListenerMatch).toBeTruthy();
    
    const scrollListener = scrollListenerMatch[0];
    
    // Verify that syncPaginationState is called when page changes
    expect(scrollListener).toContain('syncPaginationState');
    // Verify page change detection
    expect(scrollListener).toContain('prevPage');
  });
  
  test('isNavigating flag should prevent scroll listener interference', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Find the scroll listener - updated to use columnContainer instead of window
    const scrollListenerMatch = scriptContent.match(/state\.columnContainer\.addEventListener\('scroll'[\s\S]*?}, false\);/);
    expect(scrollListenerMatch).toBeTruthy();
    
    const scrollListener = scrollListenerMatch[0];
    
    // Verify isNavigating check exists
    expect(scrollListener).toContain('isNavigating');
    expect(scrollListener).toContain('return');
  });
  
  test('snapToNearestPage should use Math.floor to prevent backward snapping', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Verify the exact calculation line exists with Math.floor
    expect(scriptContent).toContain('const targetPage = Math.floor(currentScrollLeft / state.appliedColumnWidth);');
    
    // Verify Math.round is NOT used in the targetPage calculation
    // This is a more direct check that won't break if function structure changes
    expect(scriptContent).not.toContain('const targetPage = Math.round(currentScrollLeft / state.appliedColumnWidth);');
  });
  
  test('applyColumnLayout should set wrapper width to exact multiple of viewport width', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Find the applyColumnLayout function - it now delegates to applyColumnStylesWithWidth
    const applyColumnLayoutMatch = scriptContent.match(/function applyColumnLayout\(\)[^}]*\}/);
    expect(applyColumnLayoutMatch).toBeTruthy();
    
    const applyColumnLayoutFunction = applyColumnLayoutMatch[0];
    
    // Verify it calls applyColumnStylesWithWidth
    expect(applyColumnLayoutFunction).toContain('applyColumnStylesWithWidth');
    
    // Now find the applyColumnStylesWithWidth helper function
    // Match from function declaration to its closing brace, accounting for nested braces
    const helperStart = scriptContent.indexOf('function applyColumnStylesWithWidth(');
    expect(helperStart).toBeGreaterThan(-1);
    
    // Find matching closing brace by counting braces
    let braceCount = 0;
    let inFunction = false;
    let helperEnd = helperStart;
    
    for (let i = helperStart; i < scriptContent.length; i++) {
      if (scriptContent[i] === '{') {
        braceCount++;
        inFunction = true;
      } else if (scriptContent[i] === '}') {
        braceCount--;
        if (inFunction && braceCount === 0) {
          helperEnd = i + 1;
          break;
        }
      }
    }
    
    const helperFunction = scriptContent.substring(helperStart, helperEnd);
    
    // Verify the critical fix is present in the helper with the exact calculations
    expect(helperFunction).toContain('var useWidth = columnWidth > 0 ? columnWidth : FALLBACK_WIDTH;');
    expect(helperFunction).toContain('var scrollWidth = wrapper.scrollWidth;');
    expect(helperFunction).toContain('var pageCount = Math.max(1, Math.ceil(scrollWidth / useWidth));');
    expect(helperFunction).toContain('var exactWidth = pageCount * useWidth;');
    expect(helperFunction).toContain("wrapper.style.width = exactWidth + 'px';");
    
    // Verify the comment explaining the fix (checking multi-line comment separately)
    expect(helperFunction).toContain('CRITICAL FIX');
    expect(helperFunction).toContain('horizontal grid alignment');
    // Check that both parts of the phrase are present (may be on different lines)
    expect(helperFunction).toContain('prevents');
    expect(helperFunction).toContain('vertical stacking');
  });
  
  test('applyColumnStylesWithWidth should preserve font size', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Find the applyColumnStylesWithWidth function by counting braces
    const helperStart = scriptContent.indexOf('function applyColumnStylesWithWidth(');
    expect(helperStart).toBeGreaterThan(-1);
    
    let braceCount = 0;
    let inFunction = false;
    let helperEnd = helperStart;
    
    for (let i = helperStart; i < scriptContent.length; i++) {
      if (scriptContent[i] === '{') {
        braceCount++;
        inFunction = true;
      } else if (scriptContent[i] === '}') {
        braceCount--;
        if (inFunction && braceCount === 0) {
          helperEnd = i + 1;
          break;
        }
      }
    }
    
    const helperFunction = scriptContent.substring(helperStart, helperEnd);
    
    // Verify font size preservation logic
    expect(helperFunction).toContain('var preservedFontSize = wrapper.style.fontSize;');
    expect(helperFunction).toContain('if (preservedFontSize)');
    expect(helperFunction).toContain('wrapper.style.fontSize = preservedFontSize;');
  });
  
  test('applyColumnStylesWithWidth should use cssText for comprehensive style application', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Find the applyColumnStylesWithWidth function by counting braces
    const helperStart = scriptContent.indexOf('function applyColumnStylesWithWidth(');
    expect(helperStart).toBeGreaterThan(-1);
    
    let braceCount = 0;
    let inFunction = false;
    let helperEnd = helperStart;
    
    for (let i = helperStart; i < scriptContent.length; i++) {
      if (scriptContent[i] === '{') {
        braceCount++;
        inFunction = true;
      } else if (scriptContent[i] === '}') {
        braceCount--;
        if (inFunction && braceCount === 0) {
          helperEnd = i + 1;
          break;
        }
      }
    }
    
    const helperFunction = scriptContent.substring(helperStart, helperEnd);
    
    // Verify it uses cssText for setting styles
    expect(helperFunction).toContain('wrapper.style.cssText');
    
    // Verify complete CSS properties are present
    expect(helperFunction).toContain('display: block');
    expect(helperFunction).toContain('column-width:');
    expect(helperFunction).toContain('-webkit-column-width:');
    expect(helperFunction).toContain('column-gap:');
    expect(helperFunction).toContain('-webkit-column-gap:');
    expect(helperFunction).toContain('column-fill: auto');
    expect(helperFunction).toContain('-webkit-column-fill: auto');
    expect(helperFunction).toContain('height: 100%');
    expect(helperFunction).toContain('scroll-snap-align: start');
  });
  
});

describe('minimal_paginator.js - wrapExistingContentAsSegment and reflow functions', () => {
  
  test('wrapExistingContentAsSegment function should exist', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Verify the function exists
    expect(scriptContent).toContain('function wrapExistingContentAsSegment()');
  });
  
  test('wrapExistingContentAsSegment should check for pre-wrapped sections', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Find the function
    const funcStart = scriptContent.indexOf('function wrapExistingContentAsSegment()');
    expect(funcStart).toBeGreaterThan(-1);
    
    // Extract function body
    let braceCount = 0;
    let inFunction = false;
    let funcEnd = funcStart;
    
    for (let i = funcStart; i < scriptContent.length; i++) {
      if (scriptContent[i] === '{') {
        braceCount++;
        inFunction = true;
      } else if (scriptContent[i] === '}') {
        braceCount--;
        if (inFunction && braceCount === 0) {
          funcEnd = i + 1;
          break;
        }
      }
    }
    
    const functionBody = scriptContent.substring(funcStart, funcEnd);
    
    // Verify it checks for existing sections
    expect(functionBody).toContain('querySelectorAll');
    expect(functionBody).toContain('section[data-chapter-index]');
    expect(functionBody).toContain('Found');
    expect(functionBody).toContain('pre-wrapped');
  });
  
  test('wrapExistingContentAsSegment should be called in initialize()', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Find the initialize function
    const initStart = scriptContent.indexOf('function initialize(htmlContent)');
    expect(initStart).toBeGreaterThan(-1);
    
    // Extract initialize function
    let braceCount = 0;
    let inFunction = false;
    let initEnd = initStart;
    
    for (let i = initStart; i < scriptContent.length; i++) {
      if (scriptContent[i] === '{') {
        braceCount++;
        inFunction = true;
      } else if (scriptContent[i] === '}') {
        braceCount--;
        if (inFunction && braceCount === 0) {
          initEnd = i + 1;
          break;
        }
      }
    }
    
    const initFunction = scriptContent.substring(initStart, initEnd);
    
    // Verify wrapExistingContentAsSegment is called
    expect(initFunction).toContain('wrapExistingContentAsSegment()');
    
    // Verify it's called before applyColumnLayout
    const wrapPos = initFunction.indexOf('wrapExistingContentAsSegment()');
    const applyPos = initFunction.indexOf('applyColumnLayout()');
    expect(wrapPos).toBeGreaterThan(-1);
    expect(applyPos).toBeGreaterThan(-1);
    expect(wrapPos).toBeLessThan(applyPos);
  });
  
  test('reflow function should exist', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Verify the function exists
    expect(scriptContent).toContain('function reflow(preservePosition)');
  });
  
  test('reflow should handle position preservation', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Find the function
    const funcStart = scriptContent.indexOf('function reflow(preservePosition)');
    expect(funcStart).toBeGreaterThan(-1);
    
    // Extract function body
    let braceCount = 0;
    let inFunction = false;
    let funcEnd = funcStart;
    
    for (let i = funcStart; i < scriptContent.length; i++) {
      if (scriptContent[i] === '{') {
        braceCount++;
        inFunction = true;
      } else if (scriptContent[i] === '}') {
        braceCount--;
        if (inFunction && braceCount === 0) {
          funcEnd = i + 1;
          break;
        }
      }
    }
    
    const functionBody = scriptContent.substring(funcStart, funcEnd);
    
    // Verify it handles preservePosition
    expect(functionBody).toContain('preservePosition');
    expect(functionBody).toContain('currentPageBeforeReflow');
    expect(functionBody).toContain('currentCharOffset');
    expect(functionBody).toContain('findPageByCharOffset');
  });
  
  test('reflow should call applyColumnLayout and calculatePageCountAndOffsets', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Find the function
    const funcStart = scriptContent.indexOf('function reflow(preservePosition)');
    expect(funcStart).toBeGreaterThan(-1);
    
    // Extract function body
    let braceCount = 0;
    let inFunction = false;
    let funcEnd = funcStart;
    
    for (let i = funcStart; i < scriptContent.length; i++) {
      if (scriptContent[i] === '{') {
        braceCount++;
        inFunction = true;
      } else if (scriptContent[i] === '}') {
        braceCount--;
        if (inFunction && braceCount === 0) {
          funcEnd = i + 1;
          break;
        }
      }
    }
    
    const functionBody = scriptContent.substring(funcStart, funcEnd);
    
    // Verify it calls the necessary functions
    expect(functionBody).toContain('applyColumnLayout()');
    expect(functionBody).toContain('calculatePageCountAndOffsets()');
  });
  
  test('reflow should be in the public API', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Find the public API export
    const apiStart = scriptContent.indexOf('window.minimalPaginator = {');
    expect(apiStart).toBeGreaterThan(-1);
    
    // Extract the API object
    const apiEnd = scriptContent.indexOf('};', apiStart);
    const apiObject = scriptContent.substring(apiStart, apiEnd + 2);
    
    // Verify reflow is exported
    expect(apiObject).toContain('reflow');
  });
  
  test('setFontSize should call reflow()', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Find the setFontSize function
    const funcStart = scriptContent.indexOf('function setFontSize(px)');
    expect(funcStart).toBeGreaterThan(-1);
    
    // Extract function body
    let braceCount = 0;
    let inFunction = false;
    let funcEnd = funcStart;
    
    for (let i = funcStart; i < scriptContent.length; i++) {
      if (scriptContent[i] === '{') {
        braceCount++;
        inFunction = true;
      } else if (scriptContent[i] === '}') {
        braceCount--;
        if (inFunction && braceCount === 0) {
          funcEnd = i + 1;
          break;
        }
      }
    }
    
    const functionBody = scriptContent.substring(funcStart, funcEnd);
    
    // Verify it calls reflow
    expect(functionBody).toContain('reflow(');
  });
  
});

describe('minimal_paginator.js - multi-chapter window logic', () => {
  
  test('should have chapterSegments array at module level', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Verify chapterSegments array is declared
    expect(scriptContent).toContain('let chapterSegments = []');
    expect(scriptContent).toContain('Chapter segment tracking');
  });
  
  test('wrapExistingContentAsSegment should store valid sections in chapterSegments', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    const functionBody = extractFunctionBody(scriptContent, 'wrapExistingContentAsSegment');
    expect(functionBody).toBeTruthy();
    
    // Verify it stores valid sections in chapterSegments array
    expect(functionBody).toContain('chapterSegments = validSections');
    expect(functionBody).toContain('validSections.push(section)');
    
    // Verify it also stores single wrapped segment
    expect(functionBody).toContain('chapterSegments = [segment]');
  });
  
  test('should have getSegmentDiagnostics function', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Verify function exists
    expect(scriptContent).toContain('function getSegmentDiagnostics()');
    
    const functionBody = extractFunctionBody(scriptContent, 'getSegmentDiagnostics');
    expect(functionBody).toBeTruthy();
    
    // Verify it checks chapterSegments length
    expect(functionBody).toContain('chapterSegments.length');
    
    // Verify it uses state containers
    expect(functionBody).toContain('state.contentWrapper');
    expect(functionBody).toContain('state.columnContainer');
    
    // Verify it calculates page boundaries
    expect(functionBody).toContain('startPage');
    expect(functionBody).toContain('endPage');
    expect(functionBody).toContain('pageCount');
    expect(functionBody).toContain('chapterIndex');
    
    // Verify it maps over chapterSegments
    expect(functionBody).toContain('chapterSegments.map');
    
    // Verify it uses correct calculations
    expect(functionBody).toContain('Math.floor(Math.max(0, offsetLeft) / pageWidth)');
    expect(functionBody).toContain('Math.ceil((offsetLeft + segmentRect.width) / pageWidth)');
  });
  
  test('should have getChapterBoundaries function', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Verify function exists
    expect(scriptContent).toContain('function getChapterBoundaries()');
    
    const functionBody = extractFunctionBody(scriptContent, 'getChapterBoundaries');
    expect(functionBody).toBeTruthy();
    
    // Verify it checks chapterSegments length
    expect(functionBody).toContain('chapterSegments.length');
    
    // Verify it uses state containers
    expect(functionBody).toContain('state.contentWrapper');
    expect(functionBody).toContain('state.columnContainer');
    
    // Verify it gets total page count
    expect(functionBody).toContain('getPageCount()');
    
    // Verify it calculates page boundaries
    expect(functionBody).toContain('startPage');
    expect(functionBody).toContain('endPage');
    expect(functionBody).toContain('pageCount');
    expect(functionBody).toContain('chapterIndex');
    
    // Verify it maps over chapterSegments
    expect(functionBody).toContain('chapterSegments.map');
    
    // Verify it clamps endPage to totalPages
    expect(functionBody).toContain('Math.min(totalPages');
    expect(functionBody).toContain('Clamp endPage to totalPages');
  });
  
  test('getSegmentDiagnostics should be exported in public API', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Find the window.minimalPaginator export
    const exportMatch = scriptContent.match(/window\.minimalPaginator\s*=\s*\{[\s\S]*?\}/);
    expect(exportMatch).toBeTruthy();
    
    const exportBlock = exportMatch[0];
    
    // Verify getSegmentDiagnostics is exported
    expect(exportBlock).toContain('getSegmentDiagnostics');
  });
  
  test('getChapterBoundaries should be exported in public API', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Find the window.minimalPaginator export
    const exportMatch = scriptContent.match(/window\.minimalPaginator\s*=\s*\{[\s\S]*?\}/);
    expect(exportMatch).toBeTruthy();
    
    const exportBlock = exportMatch[0];
    
    // Verify getChapterBoundaries is exported
    expect(exportBlock).toContain('getChapterBoundaries');
  });
  
  test('getSegmentDiagnostics should return empty array when no segments', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Find the function
    const funcStart = scriptContent.indexOf('function getSegmentDiagnostics()');
    const funcMatch = scriptContent.substring(funcStart).match(/function getSegmentDiagnostics\(\)[\s\S]*?^    \}/m);
    expect(funcMatch).toBeTruthy();
    
    const functionBody = funcMatch[0];
    
    // Verify early return when no chapterSegments
    expect(functionBody).toMatch(/if\s*\(!chapterSegments\.length\)\s*\{\s*return\s*\[\]\s*;\s*\}/);
  });
  
  test('getChapterBoundaries should return empty array when no segments', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Find the function
    const funcStart = scriptContent.indexOf('function getChapterBoundaries()');
    const funcMatch = scriptContent.substring(funcStart).match(/function getChapterBoundaries\(\)[\s\S]*?^    \}/m);
    expect(funcMatch).toBeTruthy();
    
    const functionBody = funcMatch[0];
    
    // Verify early return when no chapterSegments
    expect(functionBody).toMatch(/if\s*\(!chapterSegments\.length\)\s*\{\s*return\s*\[\]\s*;\s*\}/);
  });
  
  test('both functions should have error handling', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Check getSegmentDiagnostics has try-catch
    const segDiagMatch = scriptContent.match(/function getSegmentDiagnostics\(\)[\s\S]*?^    \}/m);
    expect(segDiagMatch).toBeTruthy();
    expect(segDiagMatch[0]).toContain('try {');
    expect(segDiagMatch[0]).toContain('catch');
    
    // Check getChapterBoundaries has try-catch
    const chapterBoundMatch = scriptContent.match(/function getChapterBoundaries\(\)[\s\S]*?^    \}/m);
    expect(chapterBoundMatch).toBeTruthy();
    expect(chapterBoundMatch[0]).toContain('try {');
    expect(chapterBoundMatch[0]).toContain('catch');
  });
  
});
