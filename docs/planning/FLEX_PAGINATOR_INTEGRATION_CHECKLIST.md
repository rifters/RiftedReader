# FlexPaginator Integration Checklist

## Overview

This checklist breaks down the FlexPaginator integration into 5 implementation phases. Each phase can be completed and tested independently before moving to the next.

## How to Use This Checklist

Use `docs/planning/FLEX_PAGINATOR_INTEGRATION_ROADMAP.md` as the **phase guide** (what to do next + entry/exit criteria), and use this file as the **checkbox tracker** for each phase.

## Status Legend

- [ ] Not Started
- [🟡] In Progress
- [✅] Complete
- [❌] Blocked

## Phase 1: Core FlexPaginator (Already Complete ✅)

### Phase 1 Reality Check (Important)

Phase 1 is “complete” in the sense that the end-to-end pipeline exists (assemble HTML → offscreen slice → parse `SliceMetadata`).
However, there are known intentional limitations in the current implementation that must be addressed before FlexPaginator can become the default reader paginator:

- [ ] Offscreen viewport parity: offscreen slicing must use the same width/height as the on-screen reader WebView (current JS uses fixed height and the offscreen WebView is 1x1).
- [ ] Real measurement: current JS uses height *estimation* constants, not DOM measurement. This will drift vs real layout.
- [ ] CSS parity contract: offscreen slicing CSS must be derived from reader settings (font size, line height, margins, hyphenation, theme) and match the on-screen CSS.
- [ ] Runtime callbacks: `flex_paginator.js` currently only calls `onSlicingComplete/onSlicingError`. Boundary and page-change callbacks in the quick-ref are not implemented yet.

### Data Structures
- [✅] Create `PageSlice` data class
- [✅] Create `SliceMetadata` data class
- [✅] Add validation methods (`isValid()`, `findPageByCharOffset()`)
- [✅] Extend `WindowData` with `sliceMetadata` field
- [✅] Add `isPreSliced` property to `WindowData`

### FlexPaginator.kt
- [✅] Implement `assembleWindow()` method
- [✅] Wrap chapters in `<section data-chapter="N">` tags
- [✅] Return `WindowData` with wrapped HTML
- [✅] Handle HTML and text-only content
- [✅] Skip empty chapters automatically
- [✅] HTML-encode special characters

### OffscreenSlicingWebView.kt
- [✅] Create hidden WebView (1x1 pixel)
- [✅] Implement `sliceWindow()` suspending function
- [✅] Load wrapped HTML from FlexPaginator
- [✅] Inject flex_paginator.js script
- [✅] Wait for JavaScript completion (10s timeout)
- [✅] Parse SliceMetadata from JSON
- [✅] Implement AndroidBridge callbacks

### flex_paginator.js
- [✅] Implement node-walking slicing algorithm
- [✅] Slice content into viewport-sized pages
- [✅] Measure real heights in WebView
- [✅] Build character offset array
- [✅] Enforce hard breaks at `<section>` boundaries
- [✅] Call AndroidBridge.onSlicingComplete(metadata)
- [✅] Call AndroidBridge.onSlicingError(error)

### Testing
- [✅] Write SliceMetadataTest (13 tests)
- [✅] Write FlexPaginatorTest (9 tests)
- [✅] All tests passing (22/22)
- [✅] Code review feedback addressed

**Phase 1 Status**: ✅ Complete (as of 2025-12-08)

---

## Phase 2: Font Size Re-Slicing

### Settings Lock
- [ ] Implement `isScrollLocked` in ReaderActivity
- [ ] Disable WebView touch events when locked
- [ ] Disable gesture detection when locked
- [ ] Implement `lockBufferShifts()` in ConveyorBeltSystemViewModel
- [ ] Check lock before buffer shift operations

### Operation Queuing
- [ ] Create `ResliceOperation` data class
- [ ] Create `ResliceReason` enum
- [ ] Implement `queuedResliceOperation` field
- [ ] Queue font size changes
- [ ] Queue line height changes
- [ ] Queue font family changes
- [ ] Update queued operation (don't replace)
- [ ] Execute queued operation on settings close
- [ ] Clear queued operation after execution

### Re-Slicing Flow
- [ ] Save current position (windowIndex, page, charOffset)
- [ ] Show loading overlay (book cover + spinner)
- [ ] Re-slice buffer windows (5 windows)
- [ ] Update SliceMetadata for each window
- [ ] Cache updated WindowData
- [ ] Find page containing saved charOffset
- [ ] Navigate to restored page
- [ ] Dismiss loading overlay

### Position Restoration
- [ ] Implement `findPageByCharOffset()` in SliceMetadata
- [ ] Save position before re-slice
- [ ] Restore position after re-slice
- [ ] Handle case where page boundaries changed
- [ ] Test with font size increase
- [ ] Test with font size decrease
- [ ] Test with line height change

### Loading Overlay
- [ ] Create XML layout (reslice_loading_overlay.xml)
- [ ] Implement ResliceLoadingOverlay class
- [ ] Show/dismiss with fade animation
- [ ] Load book cover as background
- [ ] Display progress spinner
- [ ] Update progress text (X/Y, percent%)
- [ ] Integrate with ReaderViewModel

### Testing
- [ ] Unit test: Position restoration after font size change
- [ ] Unit test: Re-slice updates all windows
- [ ] Integration test: End-to-end re-slicing flow
- [ ] Manual test: Change font size and verify position
- [ ] Manual test: Multiple rapid changes (queuing)
- [ ] Performance test: Re-slicing time <3 seconds

**Phase 2 Estimated Time**: 6-8 hours

---

## Phase 3: Settings Lock & Concurrent Operations

### Settings Lifecycle
- [ ] Hook `onSettingsOpened()` to settings dialog
- [ ] Lock all operations on settings opened
- [ ] Save current reading position
- [ ] Hook `onSettingsClosed()` to settings dialog
- [ ] Execute queued operation on settings closed
- [ ] Unlock all operations after re-slice completes
- [ ] Handle settings dismissal (back button, outside tap)

### Re-Slice Mutex
- [ ] Implement `resliceMutex` in ReaderViewModel
- [ ] Check mutex before all re-slice operations
- [ ] Mark `isReSlicingInProgress` flag
- [ ] Release mutex after re-slice completes
- [ ] Handle concurrent operation attempts

### Conveyor Locking
- [ ] Implement `isBufferShiftLocked` flag
- [ ] Lock Conveyor during re-slice
- [ ] Check lock in `onBoundaryReached()`
- [ ] Unlock Conveyor after re-slice completes
- [ ] Prevent window creation during re-slice
- [ ] Prevent window eviction during re-slice

### Edge Cases
- [ ] Handle settings closed without changes
- [ ] Handle navigation away during settings
- [ ] Handle app killed during settings (save/restore state)
- [ ] Handle re-slice in progress when settings close
- [ ] Handle theme changes (no re-slice needed)
- [ ] Handle multiple concurrent operation attempts

### Testing
- [ ] Test scroll lock prevents page flipping
- [ ] Test Conveyor lock prevents buffer shifts
- [ ] Test re-slice lock prevents concurrent operations
- [ ] Test operation queuing (multiple changes)
- [ ] Test queued operation execution
- [ ] Test unlock after re-slice
- [ ] Test edge cases (no changes, app killed, etc.)

**Phase 3 Estimated Time**: 4-6 hours

---

## Phase 4: Error Recovery

### Rollback Strategy
- [ ] Implement `resliceWindowWithRollback()`
- [ ] Keep old SliceMetadata during re-slice
- [ ] Only update on successful re-slice
- [ ] Rollback to old metadata on failure
- [ ] Continue with remaining windows after failure

### Partial Success Handling
- [ ] Implement `resliceBufferWithPartialSuccess()`
- [ ] Track successful windows
- [ ] Track failed windows
- [ ] Map errors to window indices
- [ ] Allow user to continue with mixed layout
- [ ] Offer retry for failed windows

### Error Overlay
- [ ] Show error icon (hide spinner)
- [ ] Display user-friendly error message
- [ ] Show "Try Again" button
- [ ] Show "Continue Reading" button
- [ ] Show "Revert Font Size" button (optional)
- [ ] Implement retry functionality
- [ ] Implement revert functionality

### Error Scenarios
- [ ] Handle timeout errors (>10s)
- [ ] Handle WebView initialization failure
- [ ] Handle JavaScript execution errors
- [ ] Handle invalid metadata
- [ ] Handle OutOfMemoryError
- [ ] Handle complete re-slice failure

### Error Logging
- [ ] Log re-slice errors with context
- [ ] Include device info in logs
- [ ] Include stack traces
- [ ] Send to crash reporting service
- [ ] Provide user-friendly error messages

### Testing
- [ ] Test partial failure (some windows fail)
- [ ] Test complete failure (all windows fail)
- [ ] Test retry after failure
- [ ] Test revert after failure
- [ ] Test continue reading with mixed layout
- [ ] Simulate timeout, WebView crash, OOM

**Phase 4 Estimated Time**: 6-8 hours

---

## Phase 5: Polish & Optimization

### Progress Overlay Enhancements
- [ ] Add blur effect to book cover (optional)
- [ ] Implement cancel button (optional)
- [ ] Add more detailed progress messages
- [ ] Improve animation smoothness
- [ ] Test in all themes (Light, Dark, Sepia, Black)

### Performance Optimization
- [ ] Benchmark re-slicing time
- [ ] Profile memory usage
- [ ] Consider parallel re-slicing (careful!)
- [ ] Implement WebView pooling (optional)
- [ ] Optimize JavaScript slicing algorithm
- [ ] Lazy load book cover background

### Keyboard & Navigation
- [ ] Handle back button during re-slice
- [ ] Handle home button during re-slice
- [ ] Handle screen rotation during re-slice
- [ ] Disable hardware back button during re-slice
- [ ] Resume re-slice on app return

### Accessibility
- [ ] Add content descriptions to overlay elements
- [ ] Announce progress to screen reader
- [ ] Support TalkBack navigation
- [ ] Test with screen reader enabled
- [ ] Ensure sufficient contrast ratios

### Documentation
- [ ] Update user-facing documentation
- [ ] Add developer comments in code
- [ ] Update README with FlexPaginator info
- [ ] Create troubleshooting guide
- [ ] Document known limitations

### Testing & QA
- [ ] Test with small books (<1MB)
- [ ] Test with medium books (1-5MB)
- [ ] Test with large books (5-10MB)
- [ ] Test with very large books (>10MB)
- [ ] Test with various EPUB formats
- [ ] Test with TXT files
- [ ] Test on different Android versions (7.0+)
- [ ] Test on different screen sizes
- [ ] Test on low-end devices
- [ ] Test on high-end devices

**Phase 5 Estimated Time**: 6-10 hours

---

## Integration Timeline

### Week 1: Phase 2 (Font Size Re-Slicing)
- Days 1-2: Settings lock & operation queuing
- Days 3-4: Re-slicing flow implementation
- Day 5: Loading overlay design & implementation
- Days 6-7: Testing & bug fixes

### Week 2: Phase 3 & 4 (Settings Lock & Error Recovery)
- Days 1-2: Settings lifecycle & concurrent operations
- Days 3-4: Error recovery & rollback
- Day 5: Error overlay & user messaging
- Days 6-7: Testing error scenarios

### Week 3: Phase 5 (Polish & Optimization)
- Days 1-2: Performance optimization
- Days 3-4: Accessibility & keyboard handling
- Day 5: Documentation
- Days 6-7: Comprehensive testing & QA

**Total Estimated Time**: 3 weeks (20-30 hours)

---

## Success Metrics

### Performance Targets
- [✅] Phase 1: Slicing time <500ms/window
- [ ] Phase 2: Re-slicing time <3 seconds
- [ ] Phase 3: Lock acquisition <10ms
- [ ] Phase 4: Error recovery <1 second
- [ ] Phase 5: Memory usage <50MB

### Quality Targets
- [✅] Phase 1: 100% test coverage for core components
- [ ] Phase 2: Position restoration accuracy 100%
- [ ] Phase 3: Zero race conditions
- [ ] Phase 4: Graceful degradation in all error cases
- [ ] Phase 5: Zero crashes in production

### User Experience Targets
- [✅] Phase 1: Zero display latency
- [ ] Phase 2: Seamless position restoration
- [ ] Phase 3: No accidental page flips during settings
- [ ] Phase 4: Clear error messages & recovery options
- [ ] Phase 5: Professional, polished UI

---

## Risk Assessment

### High Risk (Mitigation Required)
- **Concurrent Operations**: Use mutex, thorough testing
- **Memory Usage**: Monitor closely, implement eviction
- **WebView Stability**: Fallback to minimal_paginator

### Medium Risk (Monitor Closely)
- **Re-Slicing Performance**: Optimize if needed, parallel processing
- **Position Restoration Accuracy**: Extensive testing, edge cases
- **Error Recovery Complexity**: Comprehensive error handling

### Low Risk (Acceptable)
- **UI Polish**: Can be improved iteratively
- **Accessibility**: Can be enhanced post-launch
- **Documentation**: Can be updated as needed

---

## Dependencies

### Internal Dependencies
- [✅] Phase 1 → Phase 2 (Core must be complete before re-slicing)
- [✅] Phase 1 → Phase 3 (Core must be complete before locking)
- [ ] Phase 2 → Phase 4 (Re-slicing must work before error recovery)
- [ ] Phases 2-4 → Phase 5 (All features must work before polish)

### External Dependencies
- ConveyorBeltSystemViewModel (already implemented)
- WindowBufferManager (already implemented)
- ReaderViewModel (already implemented)
- ReaderActivity (needs integration hooks)
- ReaderSettings (needs observable properties)

---

## Rollout Strategy

### Stage 1: Internal Testing (Phase 1-2)
- Enable for developers only
- Test with sample books
- Fix critical bugs
- Verify performance targets

### Stage 2: Alpha Testing (Phase 3-4)
- Enable for alpha testers
- Collect feedback
- Monitor crash reports
- Iterate on error handling

### Stage 3: Beta Testing (Phase 5)
- Enable for beta testers
- Monitor performance metrics
- Polish based on feedback
- Prepare for production

### Stage 4: Production Release
- Gradual rollout (10% → 50% → 100%)
- Feature flag: `enableFlexPaginator`
- Monitor crash rates
- Ready to rollback if needed

---

## Post-Launch

### Monitoring
- [ ] Track slicing time metrics
- [ ] Track memory usage
- [ ] Track crash rates
- [ ] Track user feedback
- [ ] Monitor error recovery usage

### Iteration
- [ ] Address user feedback
- [ ] Optimize performance
- [ ] Fix edge case bugs
- [ ] Enhance accessibility
- [ ] Improve documentation

### Future Enhancements
- [ ] WebView pooling for faster slicing
- [ ] Parallel re-slicing (careful testing!)
- [ ] Advanced blur effects
- [ ] Custom fonts support
- [ ] Per-book settings (font size, etc.)

---

## Completion Checklist

### Phase 1: Core FlexPaginator
- [✅] All components implemented
- [✅] All tests passing
- [✅] Code review complete
- [✅] Documentation complete

### Phase 2: Font Size Re-Slicing
- [ ] Re-slicing flow implemented
- [ ] Loading overlay functional
- [ ] Position restoration accurate
- [ ] Tests passing
- [ ] Manual testing complete

### Phase 3: Settings Lock
- [ ] All locks implemented
- [ ] Operation queuing working
- [ ] Edge cases handled
- [ ] Tests passing
- [ ] Manual testing complete

### Phase 4: Error Recovery
- [ ] Rollback strategy working
- [ ] Error overlay functional
- [ ] All error scenarios handled
- [ ] Tests passing
- [ ] Manual testing complete

### Phase 5: Polish
- [ ] Performance optimized
- [ ] Accessibility complete
- [ ] Documentation updated
- [ ] QA complete
- [ ] Ready for production

---

**Document Version**: 1.0  
**Date**: 2025-12-08  
**Status**: Phase 1 Complete ✅, Phases 2-5 Planned  
**Next**: Begin Phase 2 Implementation
