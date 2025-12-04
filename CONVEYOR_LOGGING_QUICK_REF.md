# Conveyor Belt Logging - Quick Grep Reference

**Use these commands in your terminal while testing the conveyor belt system.**

---

## 1. Verify Entry Points Are Being Called

### ReaderViewModel.onWindowBecameVisible()
```bash
# See every call to onWindowBecameVisible
adb logcat | grep "\[WINDOW_VISIBILITY\] \*\*\* onWindowBecameVisible ENTRY"

# Alternative: see both entry and exit
adb logcat | grep "onWindowBecameVisible"
```

### WindowBufferManager.onEnteredWindow()
```bash
# See every entry to onEnteredWindow
adb logcat | grep "\[WINDOW_ENTRY\] ENTRY: onEnteredWindow"

# With state info
adb logcat | grep "\[WINDOW_ENTRY\]"
```

---

## 2. Monitor Phase Transitions

### STARTUP → STEADY Transition
```bash
# The big moment - watch for this in logs
adb logcat | grep "PHASE TRANSITION: STARTUP -> STEADY"

# See all phase transition diagnostics
adb logcat | grep "PHASE_TRANS"

# Alternative: see phase values
adb logcat | grep "\[WINDOW_ENTRY\] phase="
```

---

## 3. Verify Buffer Shifting

### Forward Shifts
```bash
# Watch for successful forward shifts
adb logcat | grep "SHIFT SUCCEEDED" | grep "FORWARD"

# See all forward shift activity
adb logcat | grep "\[SHIFT_FORWARD\]"

# Just the triggered shifts
adb logcat | grep "TRIGGERING SHIFT" | grep "FORWARD"
```

### Backward Shifts
```bash
# Watch for successful backward shifts
adb logcat | grep "SHIFT SUCCEEDED" | grep "BACKWARD"

# See all backward shift activity
adb logcat | grep "\[SHIFT_BACKWARD\]"

# Just the triggered shifts
adb logcat | grep "TRIGGERING SHIFT" | grep "BACKWARD"
```

### Any Shift (Forward or Backward)
```bash
adb logcat | grep "\[SHIFT_"
```

---

## 4. Detailed State Inspection

### Buffer Contents Over Time
```bash
adb logcat | grep "buffer="
```

### Phase Changes
```bash
adb logcat | grep "phase="
```

### Active Window Changes
```bash
adb logcat | grep "activeWindow="
```

### Center Window Index
```bash
adb logcat | grep "getCenterWindowIndex"
```

---

## 5. Combined Diagnostics

### See the complete flow for one window entry
```bash
adb logcat | grep -E "onWindowBecameVisible|onEnteredWindow|PHASE|buffer=" | tail -20
```

### Monitor all conveyor events
```bash
adb logcat | grep -E "\[WINDOW_VISIBILITY\]|\[WINDOW_ENTRY\]|\[SHIFT_|PHASE_TRANS"
```

### Track a specific window (example: window 2)
```bash
adb logcat | grep "2" | grep -E "\[WINDOW_ENTRY\]|\[SHIFT"
```

---

## 6. Common Debugging Sessions

### Session 1: Verify Entry Points
```bash
# Open one terminal
adb logcat | grep -E "onWindowBecameVisible ENTRY|onEnteredWindow"

# In another terminal, interact:
# - Swipe to different windows
# - Jump via Table of Contents
# Expected: See entries for each navigation
```

### Session 2: Find Phase Transition
```bash
adb logcat > /tmp/session.log &
# ... use app for 1-2 minutes, navigate through windows ...
kill %1

grep "PHASE TRANSITION" /tmp/session.log
grep "Still in STARTUP" /tmp/session.log
```

### Session 3: Monitor Buffer Shifting
```bash
adb logcat | grep -E "buffer_before|buffer_after|oldBuffer|newBuffer"

# Expected to see pattern like:
# buffer_before=[0, 1, 2, 3, 4]
# buffer_after=[1, 2, 3, 4, 5]
# buffer_before=[1, 2, 3, 4, 5]
# buffer_after=[2, 3, 4, 5, 6]
```

---

## 7. Error Cases

### No Transition Happening
```bash
# Check if we ever reach center window
adb logcat | grep "Checking transition condition" | head -5

# Check if hasEnteredSteadyState ever gets set
adb logcat | grep "hasEnteredSteadyState = true"
```

### No Shifting (After Transition)
```bash
# Check if shift methods are called
adb logcat | grep "\[SHIFT_" | grep "ENTRY"

# Check if phase is actually STEADY
adb logcat | grep "phase=STEADY" | wc -l

# Check if we hit boundary conditions
adb logcat | grep "Boundary check"
```

### Window Index Mismatches
```bash
# See reported vs expected centers
adb logcat | grep -E "globalWindow=|centerWindow="
```

---

## 8. Real-Time Monitoring Commands

### Follow continuous logs (live tail)
```bash
adb logcat -c  # Clear buffer first
adb logcat | grep -E "\[WINDOW_|SHIFT_|PHASE_"
```

### Live filtering with multiple tags
```bash
adb logcat "*:V" | grep -E "\[WINDOW_ENTRY\]|\[SHIFT_FORWARD\]|\[SHIFT_BACKWARD\]"
```

### Timestamp mode (helpful for tracking duration)
```bash
adb logcat -v threadtime | grep -E "\[WINDOW_|SHIFT_|PHASE_"
```

---

## 9. Capture Full Session for Analysis

```bash
# Start logging with timestamp (runs in background)
adb logcat -v threadtime > ~/reader_session_$(date +%s).log &
SESSION_PID=$!

# ... use the app for 30-60 seconds ...
# Perform: swipes, ToC jumps, scrolling through windows

# Stop logging
kill $SESSION_PID
wait $SESSION_PID

# Analyze the file
grep "PHASE TRANSITION" ~/reader_session_*.log
grep "SHIFT SUCCEEDED" ~/reader_session_*.log
```

---

## 10. Expected Log Output Examples

### Successful STARTUP → STEADY Transition
```
[WINDOW_VISIBILITY] *** onWindowBecameVisible ENTRY *** windowIndex=0
[WINDOW_ENTRY] ENTRY: onEnteredWindow(globalWindowIndex=0)
[WINDOW_ENTRY] phase=STARTUP
[WINDOW_ENTRY] buffer.toList()=[0, 1, 2, 3, 4]
[WINDOW_ENTRY] getCenterWindowIndex()=2
[WINDOW_ENTRY] Still in STARTUP phase: window=0 is not center (center=2)

[WINDOW_VISIBILITY] *** onWindowBecameVisible ENTRY *** windowIndex=2
[WINDOW_ENTRY] ENTRY: onEnteredWindow(globalWindowIndex=2)
[WINDOW_ENTRY] phase=STARTUP
[WINDOW_ENTRY] buffer.toList()=[0, 1, 2, 3, 4]
[WINDOW_ENTRY] getCenterWindowIndex()=2
[PHASE_TRANS_DEBUG] Checking transition condition: centerWindow=2, globalWindow=2
[WINDOW_ENTRY] *** PHASE TRANSITION: STARTUP -> STEADY ***
```

### Successful Buffer Shift
```
[SHIFT_FORWARD] ENTRY: currentPage=98/100
[SHIFT_FORWARD] State: phase=STEADY, activeWindow=2, buffer=[0, 1, 2, 3, 4]
[SHIFT_FORWARD] shouldShift=true (threshold=2), phase=STEADY
[SHIFT_FORWARD] *** TRIGGERING SHIFT ***
[SHIFT_FORWARD] Calling bufferManager.shiftForward()...
[CONVEYOR] *** SHIFT FORWARD ***
  oldBuffer=[0, 1, 2, 3, 4]
  newBuffer=[1, 2, 3, 4, 5]
[SHIFT_FORWARD] *** SHIFT SUCCEEDED ***
```

---

## Quick Command Reference Card

| What to Check | Command |
|---|---|
| Entry points called? | `adb logcat \| grep "onWindowBecameVisible ENTRY"` |
| Phase transitioned? | `adb logcat \| grep "PHASE TRANSITION"` |
| Buffer shifting? | `adb logcat \| grep "SHIFT SUCCEEDED"` |
| Current phase? | `adb logcat \| grep "phase="` |
| Buffer state? | `adb logcat \| grep "buffer="` |
| Errors? | `adb logcat \| grep -i "error\|failed\|exception"` |
| Complete flow? | `adb logcat \| grep -E "\[WINDOW_\|\[SHIFT_"` |

---

**Pro Tip**: Combine with `tee` to save while viewing:
```bash
adb logcat | tee ~/reader_debug.log | grep "\[WINDOW_\|\[SHIFT_"
```

This saves to `~/reader_debug.log` while still showing filtered output.

---

**Updated**: December 4, 2025
