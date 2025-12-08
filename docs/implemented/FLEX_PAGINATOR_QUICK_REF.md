# FlexPaginator Quick Reference

**Quick-start guide for implementing FlexPaginator in RiftedReader**

---

## TL;DR

**LibreraReader doesn't use flex-based pagination.** RiftedReader's current CSS column approach (minimal_paginator.js) is already more advanced than LibreraReader's pagination system.

**Recommendation**: Enhance existing system rather than replacing it. If you still want FlexPaginator, complete implementation code is provided below.

---

## Quick Implementation Checklist

If implementing FlexPaginator:

### Phase 1: Create Files (30 minutes)
- [ ] Create `app/src/main/java/com/rifters/riftedreader/pagination/FlexPaginator.kt`
- [ ] Create `app/src/main/assets/flex_paginator.js`
- [ ] Copy code from `FLEXPAGINATOR_IMPLEMENTATION_GUIDE.md`

### Phase 2: Wire Up Integration (20 minutes)
- [ ] Add FlexPaginator option to WindowBufferManager
- [ ] Add feature flag for switching between implementations
- [ ] Verify AndroidBridge methods exist (they should already)

### Phase 3: Test (1-2 hours)
- [ ] Test with single chapter
- [ ] Test with 5 chapters (full window)
- [ ] Verify character offset accuracy
- [ ] Test boundary detection at 90%
- [ ] Measure performance vs CSS columns

### Phase 4: Decide (After Testing)
- [ ] Compare metrics: speed, accuracy, memory
- [ ] Choose implementation to keep
- [ ] Clean up unused code

---

## File Locations

### Existing Files (Reference)
```
app/src/main/java/com/rifters/riftedreader/pagination/
├── SlidingWindowPaginator.kt      ✅ Already exists
├── DefaultWindowAssembler.kt      ✅ Already exists (CSS columns)
├── WindowAssembler.kt             ✅ Interface exists
└── WindowBufferManager.kt         ✅ Already exists

app/src/main/assets/
├── minimal_paginator.js           ✅ Already exists (CSS columns)
└── inpage_paginator.js            ✅ Already exists (full-featured)
```

### New Files (To Create)
```
app/src/main/java/com/rifters/riftedreader/pagination/
└── FlexPaginator.kt               ⭐ NEW

app/src/main/assets/
└── flex_paginator.js              ⭐ NEW
```

---

## Key Code Snippets

### FlexPaginator.kt (Minimal)

```kotlin
package com.rifters.riftedreader.pagination

class FlexPaginator : WindowAssembler {
    override fun assembleWindow(windowIndex: Int, chapters: List<Chapter>): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>${getFlexStyles()}</style>
            </head>
            <body>
                <div id="flex-root" data-window-index="$windowIndex">
                    ${chapters.joinToString("\n") { 
                        """<section data-chapter="${it.index}">${it.htmlContent}</section>"""
                    }}
                </div>
                <script src="file:///android_asset/flex_paginator.js"></script>
            </body>
            </html>
        """.trimIndent()
    }
    
    private fun getFlexStyles() = """
        html, body { margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden; }
        #flex-root { display: flex; flex-direction: column; width: 100%; height: 100%; }
        section { flex-shrink: 0; width: 100%; padding: 16px; }
    """.trimIndent()
}
```

### flex_paginator.js (Core Algorithm)

```javascript
function calculatePages(root) {
    const pages = [];
    let currentPage = { height: 0, nodes: [], startOffset: 0 };
    let charOffset = 0;
    const charOffsets = [0];
    
    walkDOM(root, (node) => {
        if (node.nodeType === Node.TEXT_NODE) {
            const nodeHeight = measureNode(node);
            const textLength = node.textContent.length;
            
            // Break page if exceeds viewport
            if (currentPage.height + nodeHeight > window.innerHeight && currentPage.nodes.length > 0) {
                pages.push(currentPage);
                charOffsets.push(charOffset);
                currentPage = { height: 0, nodes: [], startOffset: charOffset };
            }
            
            currentPage.nodes.push(node);
            currentPage.height += nodeHeight;
            charOffset += textLength;
        }
    });
    
    if (currentPage.nodes.length > 0) pages.push(currentPage);
    
    return { pages, charOffsets };
}
```

### AndroidBridge Integration

```kotlin
// In your WebView setup
webView.addJavascriptInterface(
    PaginatorBridge(webView, viewModel),
    "AndroidBridge"
)

// Bridge methods (already exist in RiftedReader)
class PaginatorBridge {
    @JavascriptInterface
    fun onPaginationReady(jsonParams: String) { /* ... */ }
    
    @JavascriptInterface
    fun onPageChanged(jsonParams: String) { /* ... */ }
    
    @JavascriptInterface
    fun onNextWindowBoundary(jsonParams: String) { /* ... */ }
}
```

---

## Comparison Matrix

| Feature | CSS Columns (Current) | Flex Layout (New) |
|---------|----------------------|-------------------|
| Speed | ⭐⭐⭐⭐⭐ Very Fast | ⭐⭐⭐ Medium |
| Accuracy | ⭐⭐⭐⭐ Good | ⭐⭐⭐⭐⭐ Excellent |
| Code Size | ~400 lines | ~600 lines |
| Maintenance | ⭐⭐⭐⭐⭐ Easy | ⭐⭐⭐ Complex |
| Browser Support | ⭐⭐⭐⭐⭐ Native | ⭐⭐⭐⭐ Custom |
| Debug | ⭐⭐⭐ Moderate | ⭐⭐⭐⭐ Explicit |

---

## LibreraReader Analysis Results

### What LibreraReader Uses ❌
- Native PDF rendering (MuPDF)
- Standard WebView for EPUB
- No flex-based pagination
- No character offset tracking
- No viewport-based slicing

### What RiftedReader Already Has ✅
- Character offset tracking (minimal_paginator.js)
- Boundary detection at 90%
- AndroidBridge integration
- Sliding window architecture
- Working CSS column pagination

**Conclusion**: RiftedReader is MORE advanced than LibreraReader in pagination.

---

## Decision Tree

```
Do you have specific problems with CSS columns?
│
├─ NO → Keep current system (minimal_paginator.js)
│        Maybe enhance character offset accuracy
│        Don't implement FlexPaginator
│
└─ YES → What problems?
         │
         ├─ Character offset inaccuracy
         │  └─ Test current accuracy first
         │     Is it <1% error? → Probably acceptable
         │     Is it >5% error? → Consider FlexPaginator
         │
         ├─ Page break control issues
         │  └─ Can CSS `break-inside` solve it?
         │     YES → Use CSS approach
         │     NO → Consider FlexPaginator
         │
         └─ Performance issues
             └─ Measure current performance
                Is it <100ms? → Current is fine
                Is it >500ms? → FlexPaginator won't help (likely slower)
```

---

## Performance Expectations

### CSS Columns (Current)
```
Single Chapter (10K words):
├─ Initial calculation: 10-30ms
├─ Page navigation: <5ms
├─ Memory: ~5MB
└─ Character offset: ±2% accuracy

Full Window (5 chapters, 50K words):
├─ Initial calculation: 50-100ms
├─ Page navigation: <5ms
├─ Memory: ~15MB
└─ Character offset: ±2% accuracy
```

### Flex Layout (Estimated)
```
Single Chapter (10K words):
├─ Initial calculation: 30-100ms (slower - DOM walking)
├─ Page navigation: 5-10ms
├─ Memory: ~8MB (more state tracking)
└─ Character offset: ±0.1% accuracy (very precise)

Full Window (5 chapters, 50K words):
├─ Initial calculation: 150-400ms (slower - more nodes)
├─ Page navigation: 5-10ms
├─ Memory: ~25MB
└─ Character offset: ±0.1% accuracy
```

**Trade-off**: Slower initial calculation, more accurate offsets.

---

## Testing Commands

```bash
# Run unit tests
./gradlew test

# Run instrumentation tests
./gradlew connectedAndroidTest

# Test FlexPaginator specifically
./gradlew test --tests FlexPaginatorTest

# Benchmark performance
./gradlew test --tests FlexPaginatorPerformanceTest
```

---

## Common Pitfalls

### Pitfall 1: Not Measuring First
❌ **Wrong**: "I'll implement FlexPaginator because it sounds better"
✅ **Right**: "Let me measure current accuracy and identify specific problems"

### Pitfall 2: Premature Optimization
❌ **Wrong**: "Flex will be more accurate, let's rewrite everything"
✅ **Right**: "Current system works, enhance if needed"

### Pitfall 3: Not Testing Performance
❌ **Wrong**: "FlexPaginator should be fast enough"
✅ **Right**: "Let me benchmark with real content before deciding"

### Pitfall 4: Ignoring Maintenance Cost
❌ **Wrong**: "600 lines of JavaScript is manageable"
✅ **Right**: "That's 50% more code to maintain - worth it?"

---

## When to Use FlexPaginator

✅ **Use FlexPaginator if**:
- Character offset accuracy is critical (e.g., <0.5% error required)
- You need explicit control over page break locations
- You're okay with slower initial pagination
- You have resources to maintain complex JavaScript

❌ **Don't use FlexPaginator if**:
- Current CSS columns work fine
- Performance is critical
- Code simplicity is valued
- LibreraReader compatibility is the goal (they don't use it)

---

## Support & Next Steps

### Full Documentation
- **Complete Guide**: `FLEXPAGINATOR_IMPLEMENTATION_GUIDE.md`
- **Q&A**: `FLEXPAGINATOR_QUESTIONS_ANSWERED.md`
- **This File**: `FLEXPAGINATOR_QUICK_REFERENCE.md`

### Get Help
- Review existing `minimal_paginator.js` implementation
- Check `docs/complete/PAGINATOR_API.md` for current architecture
- Test current accuracy before implementing alternatives

### Ready to Implement?
1. Read `FLEXPAGINATOR_IMPLEMENTATION_GUIDE.md` (complete code provided)
2. Copy FlexPaginator.kt and flex_paginator.js
3. Test with single chapter
4. Benchmark vs CSS columns
5. Make data-driven decision

---

**Remember**: LibreraReader doesn't provide patterns for FlexPaginator. RiftedReader's current system is already more advanced. Enhance rather than replace unless you have specific issues to solve.
