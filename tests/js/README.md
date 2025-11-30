# JavaScript Tests for RiftedReader

This directory contains unit tests for the JavaScript components of RiftedReader, specifically the `inpage_paginator.js` file used for in-WebView horizontal pagination.

## Prerequisites

- Node.js v20 or later
- npm

## Installation

From the `tests/js/` directory:

```bash
npm ci
```

Or to install fresh:

```bash
npm install
```

## Running Tests

From the `tests/js/` directory:

```bash
# Run all tests
npm test

# Run tests in watch mode (for development)
npm run test:watch

# Run tests with coverage report
npm run test:coverage
```

## Test Structure

- `inpage_paginator.test.js` - Unit tests for the in-page paginator JavaScript module

### Test Coverage

The tests cover:

1. **Initialization**
   - Paginator creates required DOM elements
   - `getPageCount()` returns > 0 after initialization
   - `AndroidBridge.onPaginationReady` is called with correct page count

2. **Wrapper Width Calculation** (`updateColumnStyles`)
   - Content wrapper width is an exact multiple of viewport width
   - Ensures horizontal paging alignment

3. **loadWindow with descriptor.chapters**
   - Supports string array format for chapters
   - Supports object array format (`{chapterIndex, html}`)
   - Does not throw when appending chapters during initial load
   - Calls `AndroidBridge.onWindowLoaded` with pageCount

4. **CONSTRUCTION vs ACTIVE Mode Enforcement**
   - `appendChapter` throws in ACTIVE mode
   - `prependChapter` throws in ACTIVE mode
   - Both work correctly in CONSTRUCTION mode (before `finalizeWindow`)

5. **Navigation**
   - `goToPage` updates current page state
   - `getCurrentPage` returns correct initial value

6. **Error Handling**
   - Proper error callbacks for invalid descriptors

## Mocking

The tests use:

- **jsdom** - Browser-like DOM environment
- **Jest fake timers** - For controlling `requestAnimationFrame` and async behavior
- **Mock AndroidBridge** - Captures all callbacks for verification

## Running Locally

These tests are designed to run locally during development. To run them:

```bash
cd tests/js
npm ci
npm test
```
