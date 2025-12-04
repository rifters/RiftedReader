# Copilot Session Protocol (RiftedReader)

Personal notes for how I want Copilot to work with this repo so I don’t have to re-explain everything every time.

---

## 1. Capability check before code work

Before we touch any code, I want Copilot to prove it can see the repo.

**Prompt to use:**

> Before we touch any code: can you access `rifters/RiftedReader` and show me the latest contents of:
>  1. `app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderPageFragment.kt`
>  2. `app/src/main/java/com/rifters/riftedreader/ui/reader/ReaderActivity.kt`
> If not, we’ll stay in theory/design mode only.

**Rules:**

- If Copilot **fails** to show real up-to-date contents:
  - No concrete edits or patches.
  - Only theory, design, architecture, planning docs.
- If Copilot **succeeds**:
  - It’s allowed to talk about specific code paths, but I still don’t blindly paste large blocks.

---

## 2. No “paste this big patch” suggestions

I’ve had bad experiences with big copy-paste patches breaking things.

**What I want instead:**

- Small, focused suggestions / explanations:
  - “In function X, consider changing Y to Z, because…”
  - “Add a guard around this call to handle …”
- Or: high-level checklists and planning docs (like this one) that I implement at my own pace.

**Prompt to remind Copilot:**

> Don’t give me large code patches to paste. Explain changes in small, focused steps or as checklists. I’ll do the edits myself.

---

## 3. Modes: “theory” vs “implementation”

I want to be able to say explicitly which mode we’re in.

- **Theory / planning mode:**
  - Architecture, design docs, backlog items, refactor plans.
  - No direct code edits, even if tools work.
  - Good for when I’m tired or just want to think about the design.

- **Implementation advice mode:**
  - Only allowed **after** the repo capability check passes.
  - Still no giant patches, just targeted guidance.

**Prompt I can use at the start of a chat:**

> For this session, we are in THEORY mode only. Don’t suggest direct code edits; focus on architecture, design, and planning docs.

or:

> For this session, we can do IMPLEMENTATION advice, but keep changes small and explain them clearly. Remember I’ll edit the code myself.

---

## 4. TTS & pagination boundaries

Quick mental note of roles (for future discussions):

- TTS:
  - I’m happy with current implementation; future work is refinement, not a rewrite.
- Pagination:
  - JS paginator should be treated as a layout engine + page math helper.
  - Kotlin/VMs own windows, navigation, and global state.

**Prompt anchor:**

> Remember: TTS is working and is a “win” for me. When discussing refactors, avoid suggesting large rewrites of TTS unless I explicitly ask. Focus refactor ideas primarily on pagination responsibilities (Kotlin vs JS).

---

## 5. How to start a “real work” session

When I want to do real work with Copilot on this repo, I’ll start with:

> We’re working on `rifters/RiftedReader` today.  
> 1) Run the repo capability check described in `docs/copilot-session-protocol.md`.  
> 2) Tell me whether we’re in THEORY mode or IMPLEMENTATION advice mode based on that check.

This doc is here so I can point Copilot to it instead of repeating all of this every time.