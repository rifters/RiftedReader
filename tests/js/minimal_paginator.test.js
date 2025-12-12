/**
 * Unit tests for minimal_paginator.js - scrollend fix
 * 
 * These tests verify the scrollend event handling fix for the double-page jump bug.
 */

const fs = require('fs');
const path = require('path');

// Path to the actual minimal_paginator.js file
const paginatorPath = path.join(__dirname, '../../app/src/main/assets/minimal_paginator.js');

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
    
    // Find the scroll listener
    const scrollListenerMatch = scriptContent.match(/window\.addEventListener\('scroll'[\s\S]*?}, false\);/);
    expect(scrollListenerMatch).toBeTruthy();
    
    const scrollListener = scrollListenerMatch[0];
    
    // Verify that syncPaginationState is called when page changes
    expect(scrollListener).toContain('syncPaginationState');
    // Verify page change detection
    expect(scrollListener).toContain('prevPage');
  });
  
  test('isNavigating flag should prevent scroll listener interference', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Find the scroll listener
    const scrollListenerMatch = scriptContent.match(/window\.addEventListener\('scroll'[\s\S]*?}, false\);/);
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
    const applyColumnLayoutMatch = scriptContent.match(/function applyColumnLayout\(\)[\s\S]*?\n    \}/);
    expect(applyColumnLayoutMatch).toBeTruthy();
    
    const applyColumnLayoutFunction = applyColumnLayoutMatch[0];
    
    // Verify it calls applyColumnStylesWithWidth
    expect(applyColumnLayoutFunction).toContain('applyColumnStylesWithWidth');
    
    // Now find the applyColumnStylesWithWidth helper function
    const helperMatch = scriptContent.match(/function applyColumnStylesWithWidth\([\s\S]*?\n    \}/);
    expect(helperMatch).toBeTruthy();
    
    const helperFunction = helperMatch[0];
    
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
    
    // Find the applyColumnStylesWithWidth function
    const helperMatch = scriptContent.match(/function applyColumnStylesWithWidth\([\s\S]*?\n    \}/);
    expect(helperMatch).toBeTruthy();
    
    const helperFunction = helperMatch[0];
    
    // Verify font size preservation logic
    expect(helperFunction).toContain('var preservedFontSize = wrapper.style.fontSize;');
    expect(helperFunction).toContain('if (preservedFontSize)');
    expect(helperFunction).toContain('wrapper.style.fontSize = preservedFontSize;');
  });
  
  test('applyColumnStylesWithWidth should use cssText for comprehensive style application', () => {
    const scriptContent = fs.readFileSync(paginatorPath, 'utf-8');
    
    // Find the applyColumnStylesWithWidth function
    const helperMatch = scriptContent.match(/function applyColumnStylesWithWidth\([\s\S]*?\n    \}/);
    expect(helperMatch).toBeTruthy();
    
    const helperFunction = helperMatch[0];
    
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
