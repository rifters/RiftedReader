#!/bin/bash
#
# detect_viewpager_traces.sh
# 
# Scans the repository for ViewPager/ViewPager2 traces, in-page paginator references,
# and pagination flags that may indicate leftover or conflicting pagination code.
#
# Run this script before merging to ensure no unwanted pagination traces remain.
#
# Usage:
#   ./detect_viewpager_traces.sh
#

set -e

# Colors for output
RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

echo "=============================================="
echo "  ViewPager/Pagination Trace Detector"
echo "=============================================="
echo ""

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

FOUND_ISSUES=0

# Function to search and report
search_pattern() {
    local pattern="$1"
    local description="$2"
    local exclude_pattern="${3:-}"
    
    echo -e "${YELLOW}Searching for: ${description}${NC}"
    echo "Pattern: $pattern"
    echo "---"
    
    if [ -n "$exclude_pattern" ]; then
        RESULTS=$(grep -rn --include="*.kt" --include="*.java" --include="*.xml" "$pattern" app/src 2>/dev/null | grep -v "$exclude_pattern" || true)
    else
        RESULTS=$(grep -rn --include="*.kt" --include="*.java" --include="*.xml" "$pattern" app/src 2>/dev/null || true)
    fi
    
    if [ -n "$RESULTS" ]; then
        echo -e "${RED}Found traces:${NC}"
        echo "$RESULTS"
        echo ""
        FOUND_ISSUES=$((FOUND_ISSUES + 1))
    else
        echo -e "${GREEN}No traces found.${NC}"
        echo ""
    fi
}

# Search for ViewPager references
search_pattern "ViewPager[^2]" "ViewPager (legacy) references"

# Search for ViewPager2 references
search_pattern "ViewPager2" "ViewPager2 references"

# Search for inpage paginator references
search_pattern "inpage.paginator" "In-page paginator references"
search_pattern "inpage_paginator" "In-page paginator file references"
search_pattern "InPagePaginator" "InPagePaginator class references"

# Search for pagination mode flags
search_pattern "PAGINATION_MODE" "Pagination mode flags"
search_pattern "paginationMode" "paginationMode property references"

# Search for window count references that might be problematic
# Note: 97 is a known problematic value that appears when chapter-based window count
# is incorrectly used instead of sliding-window count. In a book with 97 chapters,
# this value appears when the code falls back to treating each chapter as a window.
search_pattern "windowCount\s*=\s*97" "Hardcoded windowCount=97 (known race condition indicator)"

# Search for potential race condition patterns
search_pattern "getItemCount" "ViewPager adapter getItemCount implementations"

# Search for sliding window manager references
search_pattern "SlidingWindowManager" "SlidingWindowManager references"

# Search for continuous paginator references
search_pattern "ContinuousPaginator" "ContinuousPaginator references"

echo "=============================================="
echo "  Summary"
echo "=============================================="
echo ""

if [ $FOUND_ISSUES -gt 0 ]; then
    echo -e "${YELLOW}Found $FOUND_ISSUES categories with potential traces.${NC}"
    echo "Review the above results and determine if any need to be addressed."
    echo ""
    echo "Note: Some traces may be intentional (e.g., the ReaderPagerAdapter)."
    echo "Focus on unexpected or duplicate pagination logic."
else
    echo -e "${GREEN}No concerning traces found.${NC}"
fi

echo ""
echo "=============================================="
echo "  Script completed"
echo "=============================================="
