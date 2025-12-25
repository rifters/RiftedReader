# RiftedReader Documentation Index

This document provides a roadmap through the organized documentation structure.

## Quick Start

- **New to the project?** → See `/docs/guides/QUICK_START_60_SECONDS.md`
- **Setting up development?** → See `/docs/complete/DEVELOPMENT_SETUP.md`
- **Understanding architecture?** → See `/docs/complete/ARCHITECTURE.md`

---

## Documentation Structure

### 📚 `/docs/complete/` - Core Documentation
Comprehensive guides on the app's architecture and features. These are the canonical reference documents.
- `ARCHITECTURE.md` - System architecture and component relationships
- `DEVELOPMENT_SETUP.md` - Getting started guide
- `LIBRERA_ANALYSIS.md` - Analysis of LibreraReader for feature inspiration
- `TTS_IMPLEMENTATION_GUIDE.md` - Text-to-Speech system detailed guide
- `UI_UX_DESIGN_GUIDE.md` - UI/UX specifications and design patterns
- `STABLE_WINDOW_MODEL.md` - Stable window reading model documentation
- `PAGINATOR_API.md` - JavaScript paginator API reference

### 🏗️ `/docs/architecture/` - Implementation Architecture
Documents about pagination systems, conveyor belt, and core implementation patterns.
- `CONVEYOR_BELT_ENGINE_NOW_RUNNING.md` - Conveyor belt system overview
- `PAGINATOR_WHAT_IS_IT.md` - What the paginator system does
- `WEBVIEW_PAGINATOR_BRIDGE_INTEGRATION_COMPLETE.md` - JavaScript bridge details
- `FLEX_PAGINATOR_IMPLEMENTATION.md` - Flexible paginator architecture
- `sliding-window-inpage-pagination-notes.md` - Window pagination notes

### 🔧 `/docs/implemented/` - Completed Features
Documentation of features that have been fully implemented and verified.
- `CONVEYOR_BELT_VERIFIED.md` - Conveyor belt system verification
- `SLIDING_WINDOW_PAGINATION_STATUS.md` - Current status of window pagination
- `STABLE_WINDOW_IMPLEMENTATION.md` - Stable window reading implementation
- `VIEWPAGER2_REMOVAL.md` - ViewPager2 removal completion report
- `STAGE_1-3_SUMMARY.md` - Stages 1-3 implementation summary

### 🛠️ `/docs/fixes/` - Bug Fixes & Patches
Documentation of bugs that were identified and fixed.
- `CIRCULAR_BUFFER_FIX_SUMMARY.md` - Window index out-of-bounds fix
- `PAGINATION_FREEZE_FIX_SUMMARY.md` - Pagination freeze fix
- `IMAGE_RENDERING_FIX_SUMMARY.md` - Image rendering fixes
- `HARDWARE_NAV_FIX_SUMMARY.md` - Hardware button navigation fix
- `JSON_PARSING_FIX_SUMMARY.md` - JSON parsing error fix
- And more...

### 🚀 `/docs/guides/` - Quick References & How-Tos
Quick reference guides and practical how-to documents.
- `QUICK_START_60_SECONDS.md` - Get running in 60 seconds
- `CONVEYOR_BELT_DEBUG_GUIDE.md` - Debugging the conveyor belt system
- `PHASE3_BRIDGE_QUICK_REF.md` - Phase 3 bridge quick reference
- `TESTING_GUIDE_PAGINATION_FIX.md` - Testing pagination fixes
- `TASKS_8_9_QUICK_START.md` - Quick start for specific tasks

### 🐛 `/docs/debug/` - Debugging & Troubleshooting
Detailed debugging guides and trace documents for troubleshooting issues.
- `DEBUG_WINDOW_SHIFTING.md` - Window shifting debug guide
- `EDGE_DETECTION_TRIGGER_TRACE.md` - Edge detection trace
- `JAVASCRIPT_TO_WINDOW_SHIFT_TRIGGER_CHAIN.md` - JS trigger chain
- `WINDOW_JUMPING_LOG.md` - Window jumping analysis

### 📋 `/docs/planning/` - Roadmaps & Plans
Planning documents, roadmaps, and future implementation plans.
- `IMPLEMENTATION_ROADMAP.md` - 20-week development roadmap
- `STAGE_6_8_TODO.md` - TODO items for stages 6-8
- `FLEX_PAGINATOR_ARCHITECTURE.md` - Flex paginator architecture plan
- `FLEX_PAGINATOR_INTEGRATION_ROADMAP.md` - Master execution roadmap for Flex paginator integration
- `WEBVIEW_PAGINATOR_ENHANCEMENTS.md` - WebView paginator enhancements
- `EPUB_IMPROVEMENTS.md` - EPUB format improvements

### 📌 `/docs/reference/` - Reference Materials
Reference documents, summaries, and completion reports.
- `EXECUTIVE_SUMMARY.md` - High-level project summary
- `SOLUTION_SUMMARY.md` - Technical solutions summary
- `STATUS_DASHBOARD.md` - Project status dashboard
- `SYSTEM_STATUS.md` - System status overview
- `CODE_LOCATIONS_REFERENCE.md` - Quick reference for finding code

### 📹 `/docs/sessions/` - Session Reports
Detailed reports from development sessions documenting work completed.
- `SESSION_CIRCULAR_BUFFER_FIX_COMPLETE.md` - Latest circular buffer fix session
- `SESSION_6_FINAL_REPORT.md` - Session 6 final report
- `SESSION_5_COMPLETE.md` - Session 5 completion report
- And previous session reports...

### 🧪 `/docs/testing/` - Testing Documentation
Testing guides, verification procedures, and test reports.
- `CONTINUOUS_PAGINATOR_VERIFICATION.md` - Paginator verification
- `SCROLL_GESTURE_TESTING_GUIDE.md` - Testing scroll gestures
- `WINDOW_RENDERING_VALIDATION.md` - Window rendering validation
- `EPUB_COVER_DEBUG_GUIDE.md` - Debugging EPUB covers

### 🏛️ `/docs/meta/` - Project Metadata
Project metadata, protocols, and session information.
- `docs_copilot-session-protocol.md` - Session protocol documentation
- `docs_pagination-architecture-notes.md` - Architecture notes
- `PR_SUMMARY.md` - Pull request summaries

### 📚 `/docs/historical/` - Historical Documents
Older or superseded documents kept for reference.
- `SLIDING_WINDOW_PAGINATION.md` - Earlier pagination implementation notes

### ⚠️ `/docs/deprecated/` - Deprecated Code
Reference implementations that have been superseded.
- Contains old implementations kept for reference only

---

## By Topic

### Pagination & Window Management
1. Start: `/docs/complete/ARCHITECTURE.md`
2. Deep dive: `/docs/architecture/PAGINATOR_WHAT_IS_IT.md`
3. Current status: `/docs/implemented/SLIDING_WINDOW_PAGINATION_STATUS.md`
4. Debugging: `/docs/debug/DEBUG_WINDOW_SHIFTING.md`
5. Recent fix: `/docs/fixes/CIRCULAR_BUFFER_FIX_SUMMARY.md`

### TTS (Text-to-Speech)
1. Guide: `/docs/complete/TTS_IMPLEMENTATION_GUIDE.md`
2. Architecture: `/docs/complete/ARCHITECTURE.md` (Section 5)

### UI/UX
1. Guide: `/docs/complete/UI_UX_DESIGN_GUIDE.md`
2. Setup: `/docs/complete/DEVELOPMENT_SETUP.md`

### Conveyor Belt System
1. Overview: `/docs/architecture/CONVEYOR_BELT_ENGINE_NOW_RUNNING.md`
2. Debug: `/docs/guides/CONVEYOR_BELT_DEBUG_GUIDE.md`
3. Status: `/docs/implemented/CONVEYOR_BELT_VERIFIED.md`

### Bug Fixes
All bug fixes are documented in `/docs/fixes/` with summaries and quick references.

---

## File Naming Convention

- `*_SUMMARY.md` - Brief explanation of a feature or fix
- `*_QUICK_REF.md` - Quick reference guide for debugging
- `*_GUIDE.md` - Detailed how-to guide
- `*_IMPLEMENTATION*.md` - Implementation details and architecture
- `*_COMPLETE.md` - Completion report or final status
- `SESSION_*.md` - Session work reports
- `*_VERIFICATION.md` - Testing and verification reports
- `*_PLAN.md` - Future plans and roadmaps

---

## Recent Updates

Latest documentation organized from root level:
- Circular buffer fix documentation (3 files)
- Session reports (7 files)
- Bug fix documentation (18 files)
- Architecture documentation (17 files)
- Quick reference guides (7 files)
- Debug guides (5 files)

**Total organized**: 115+ markdown files in 14 categories

---

## Navigation Tips

1. **Lost?** Start with `/docs/guides/QUICK_START_60_SECONDS.md`
2. **Bug fixing?** Look in `/docs/debug/` and `/docs/fixes/`
3. **Understanding code?** Check `/docs/complete/ARCHITECTURE.md` first
4. **Recent work?** See `/docs/sessions/` for latest session reports
5. **Planning features?** Check `/docs/planning/`

---

## Contributing Documentation

When adding new documentation:
1. Choose the appropriate folder based on the categorization above
2. Follow the naming convention for clarity
3. Update this INDEX.md file if adding a new section
4. Keep related documents together in the same folder

---

**Last Updated**: December 27, 2025  
**Organization Tool**: Automated librarian script  
**Status**: 🎯 Complete & Organized
