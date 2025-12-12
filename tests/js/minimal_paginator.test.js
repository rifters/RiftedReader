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
    
    // Find the snapToNearestPage function
    const snapFunctionMatch = scriptContent.match(/function snapToNearestPage\(\)[\s\S]*?const targetPage = Math\.(floor|round|ceil)\(/);
    expect(snapFunctionMatch).toBeTruthy();
    
    // Verify it uses Math.floor, not Math.round
    expect(snapFunctionMatch[0]).toContain('Math.floor');
    expect(snapFunctionMatch[0]).not.toContain('Math.round');
    
    // Verify the full line exists correctly
    expect(scriptContent).toContain('const targetPage = Math.floor(currentScrollLeft / state.appliedColumnWidth);');
  });
  
});
