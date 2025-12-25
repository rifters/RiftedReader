# Documentation Cleanup Checklist

This document tracks documentation maintenance tasks to keep the repo organized and current.

## High Priority

- [ ] **Review & Archive Deprecated Docs**
  - [ ] Identify docs with outdated "don'ts" or invalid guidance
  - [ ] Move obsolete files to `docs/deprecated/`
  - [ ] Keep only currently relevant planning docs in `docs/planning/`
  - [ ] Archive superseded roadmaps to `docs/historical/`

- [ ] **Update Planning & Roadmaps**
  - [ ] Review `docs/planning/IMPLEMENTATION_ROADMAP.md`
    - [ ] Mark completed stages (1-3 complete, TTS in progress?)
    - [ ] Update stage timelines with actual vs. planned
    - [ ] Add current blockers/challenges
  - [ ] Update `docs/planning/STAGE_6_8_TODO.md`
    - [ ] Mark completed tasks
    - [ ] Update progress percentages
  - [ ] Check all other planning docs for outdated information

- [ ] **Rewrite Root README.md**
  - [ ] Add current project state summary
    - [ ] What's working now (Stages 1-3, Conveyor Belt, etc.)
    - [ ] What's in-progress (TTS?)
    - [ ] What's planned next
  - [ ] Add quick navigation
    - [ ] Link to `docs/INDEX.md`
    - [ ] Link to getting started guide
    - [ ] Link to latest session report
    - [ ] Link to current architecture
  - [ ] Add build & test instructions
  - [ ] Add contribution guidelines
  - [ ] Include known issues/limitations
  - [ ] Add recent fixes/improvements summary

## Medium Priority

- [ ] **Audit Existing Docs for Currency**
  - [ ] Review all files in `docs/complete/` - are they still accurate?
  - [ ] Review all files in `docs/architecture/` - reflect current implementation?
  - [ ] Check `docs/guides/` for outdated quick references
  - [ ] Verify all code examples still match current codebase

- [ ] **Create/Update Status Dashboard**
  - [ ] Feature completion matrix (done/in-progress/planned)
  - [ ] Build status & test coverage
  - [ ] Known bugs & limitations
  - [ ] Performance metrics (if applicable)
  - [ ] Next 3-month priorities

- [ ] **Consolidate Duplicate Documentation**
  - [ ] Check for duplicate/overlapping docs
  - [ ] Consolidate into single authoritative version
  - [ ] Remove duplicates and link to canonical version

## Low Priority

- [ ] **Code Example Verification**
  - [ ] Verify all code snippets in docs still compile/work
  - [ ] Update outdated API examples
  - [ ] Add cross-references to actual code files

- [ ] **Links & Cross-References**
  - [ ] Verify all internal doc links still work
  - [ ] Update broken links
  - [ ] Add cross-reference links between related docs

- [ ] **Session Report Archival**
  - [ ] Review `docs/sessions/` for older reports
  - [ ] Archive very old sessions to `docs/historical/`
  - [ ] Create summary of major milestones from session reports

## Notes

### What Should Be Deprecated
- Docs describing features that are no longer implemented
- Guides for removed/replaced systems (e.g., old pagination attempts)
- "Don'ts" that are no longer valid
- Outdated workarounds that have been fixed

### What Should Stay
- Core architecture & design docs (kept current!)
- Implementation guides for current systems
- Testing & debugging guides
- All session reports (historical record)
- Planning docs for future work

### How to Archive
1. Move to `docs/deprecated/` or `docs/historical/` with old naming
2. Add note at top: "⚠️ DEPRECATED - Kept for historical reference only"
3. Link from relevant current docs if useful for context

---

**Last Updated**: December 27, 2025  
**Status**: 🎯 Ready to tackle  
**Estimated Time**: 2-4 hours for full cleanup
