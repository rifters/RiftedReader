#!/bin/bash
#
# detect_viewpager_traces.sh
#
# Greps for ViewPager2/pagination-related patterns in the codebase.
# Run this script to find leftover references that may cause race conditions.
#
# Usage: ./detect_viewpager_traces.sh
#

set -e

echo "=== Detecting ViewPager/Pagination Traces ==="
echo ""

# Colors for output (if terminal supports it)
if [ -t 1 ]; then
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    NC='\033[0m' # No Color
else
    RED=''
    GREEN=''
    YELLOW=''
    NC=''
fi

# Patterns to search for
PATTERNS=(
    "ViewPager2"
    "ViewPager"
    "paginationMode"
    "CONTINUOUS"
    "CHAPTER_BASED"
    "continuousStreaming"
    "inpagePaginator"
    "inpage_paginator.js"
    "setUserInputEnabled"
    "isUserInputEnabled"
    "pagerRecyclerView"
    "windowCount"
    "chaptersPerWindow"
)

# Directories to search
SEARCH_DIRS=(
    "app/src/main/java"
    "app/src/main/assets"
    "app/src/main/res"
)

# File extensions to search
EXTENSIONS="-name '*.kt' -o -name '*.java' -o -name '*.xml' -o -name '*.js'"

FOUND_ANY=0

for pattern in "${PATTERNS[@]}"; do
    echo -e "${YELLOW}Searching for: ${pattern}${NC}"
    
    for dir in "${SEARCH_DIRS[@]}"; do
        if [ -d "$dir" ]; then
            # Use grep with context
            results=$(grep -rn --include="*.kt" --include="*.java" --include="*.xml" --include="*.js" "$pattern" "$dir" 2>/dev/null || true)
            
            if [ -n "$results" ]; then
                FOUND_ANY=1
                echo -e "${RED}Found in $dir:${NC}"
                echo "$results" | while read -r line; do
                    echo "  $line"
                done
                echo ""
            fi
        fi
    done
done

echo ""
echo "=== Summary ==="
if [ $FOUND_ANY -eq 1 ]; then
    echo -e "${YELLOW}Found ViewPager/pagination-related patterns.${NC}"
    echo "Review the above matches and remove/update leftover references before merging."
else
    echo -e "${GREEN}No ViewPager/pagination traces found.${NC}"
fi

echo ""
echo "Done."
