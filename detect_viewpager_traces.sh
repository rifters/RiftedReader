#!/bin/bash

# detect_viewpager_traces.sh
# 
# Script to find references to ViewPager2/ViewPager, in-page paginator JS,
# and pagination-related flags in the repository.
#
# Usage:
#   ./detect_viewpager_traces.sh [OPTIONS]
#
# Options:
#   -v, --verbose    Show full context for each match
#   -h, --help       Show this help message
#
# This script helps identify leftover ViewPager2/continuous pagination traces
# that might cause race conditions or mixed pagination behavior.

set -e

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERBOSE=false

usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  -v, --verbose    Show full context for each match (5 lines before/after)"
    echo "  -h, --help       Show this help message"
    echo ""
    echo "This script searches for ViewPager2, pagination mode, and related traces."
}

while [[ $# -gt 0 ]]; do
    case $1 in
        -v|--verbose)
            VERBOSE=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            usage
            exit 1
            ;;
    esac
done

CONTEXT_FLAG=""
if [ "$VERBOSE" = true ]; then
    CONTEXT_FLAG="-B5 -A5"
fi

echo "==========================================="
echo "ViewPager2/Pagination Trace Detection"
echo "==========================================="
echo ""

echo "--- 1. ViewPager2/ViewPager References ---"
echo "(Excluding imports and standard adapter patterns)"
grep -rn --include="*.kt" --include="*.java" $CONTEXT_FLAG \
    -e "ViewPager2" \
    -e "ViewPager\b" \
    "$REPO_ROOT/app/src/main" 2>/dev/null || echo "No ViewPager references found."
echo ""

echo "--- 2. Pagination Mode Flags ---"
grep -rn --include="*.kt" --include="*.java" $CONTEXT_FLAG \
    -e "PaginationMode\." \
    -e "paginationMode" \
    -e "CONTINUOUS" \
    -e "CHAPTER_BASED" \
    "$REPO_ROOT/app/src/main" 2>/dev/null || echo "No pagination mode flags found."
echo ""

echo "--- 3. In-Page Paginator JS References ---"
grep -rn --include="*.kt" --include="*.java" --include="*.js" $CONTEXT_FLAG \
    -e "inpage_paginator" \
    -e "InPagePaginator" \
    -e "configurePaginator" \
    -e "initializePaginator" \
    "$REPO_ROOT/app/src/main" 2>/dev/null || echo "No in-page paginator references found."
echo ""

echo "--- 4. Window-Related References ---"
grep -rn --include="*.kt" --include="*.java" $CONTEXT_FLAG \
    -e "windowCount" \
    -e "windowIndex" \
    -e "SlidingWindow" \
    -e "WindowManager" \
    "$REPO_ROOT/app/src/main" 2>/dev/null || echo "No window-related references found."
echo ""

echo "--- 5. ContinuousPaginator References ---"
grep -rn --include="*.kt" --include="*.java" $CONTEXT_FLAG \
    -e "ContinuousPaginator" \
    -e "continuousPaginator" \
    -e "isContinuousMode" \
    -e "isContinuousInitialized" \
    "$REPO_ROOT/app/src/main" 2>/dev/null || echo "No ContinuousPaginator references found."
echo ""

echo "--- 6. Potential Race Condition Patterns ---"
echo "(Looking for mixed mode transitions and async window updates)"
grep -rn --include="*.kt" --include="*.java" $CONTEXT_FLAG \
    -e "\.value\s*=" \
    -e "notifyDataSetChanged" \
    "$REPO_ROOT/app/src/main/java/com/rifters/riftedreader/ui/reader" 2>/dev/null | \
    grep -v "^Binary" || echo "No race condition patterns found in reader package."
echo ""

echo "==========================================="
echo "Detection complete."
echo "Review the above output for traces that may need attention."
echo "==========================================="
