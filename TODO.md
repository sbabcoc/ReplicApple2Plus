# TODO

Tracked ideas not yet scheduled for implementation.

## Type-from-file keystroke injection

Feed a plain text file's contents into the emulator as simulated
keystrokes, one character at a time, via the same
`bus.keyboardRegister().keyPressed(...)` path already used internally
for testing. Solves a real, concrete gap: there is currently no way to
get more than a line or two of BASIC into the emulator without typing
it by hand -- no clipboard support, and nothing like Virtual ][''s own
AppleScript `type line "..."` automation.

Surfaced while trying to manually verify the Lo-Res renderer against
Bob Bishop's "floating bus" demo program (Softalk, October 1982) --
typing a 20+ line BASIC listing by hand at the emulator's own window is
real, avoidable friction.

**Scope note**: a general clipboard integration (paste-to-keystrokes,
copy-from-screen) was considered and set aside as solving a broader
problem than what's actually needed here -- see conversation history
for the fuller design discussion (case-folding ambiguity on live
clipboard paste, live-paste timing races, screen-copy selection
semantics). A file-based loader sidesteps all of that: a `.bas` file is
already in the correct case, and there's no "was this meant to be
pasted" ambiguity to resolve.

**Design questions still open, not yet decided:**
- Trigger: `--type-file <path>` CLI option, a "Type File..." menu item,
  or both? Leaning toward explicit trigger (a keypress or menu action)
  rather than "start typing once booted," since "booted" isn't a clean,
  reliably detectable signal for a general-purpose emulator.
- Line-ending translation: each `\n` (or `\r\n`) in the file becomes a
  single `\r` keystroke (Apple II's own Return), not a raw pass-through.
- Pacing between characters: fixed delay, or configurable (matching the
  spirit of Virtual ][''s own "keyboard delay" setting)?

## Reset and Reboot menu items

Add "Reset" and "Reboot" items to the emulator's menu bar.

**Reset**: the real Apple II's Ctrl-Reset. `Cpu6502` already has a
complete, hardware-faithful RESET line implementation --
`raiseReset()`/`lowerReset()` -- correctly modeling the real sequence
(SP decremented by 3, PC reloaded from the reset vector, interrupt-disable
forced, D left indeterminate as on real hardware). It's fully built and
tested at the CPU level; nothing anywhere currently calls it. Wiring a
menu item to it should be straightforward.

**Reboot**: no existing mechanism. Scope still undecided -- options
raised but not chosen between:
- A full cold restart: recreate `MotherboardBus`/`Cpu6502` from scratch,
  as if the emulator had just launched.
- Something closer to a real Apple II power-cycle specifically, which
  does more than a soft reset (disk drive re-homing, video/soft-switch
  state clearing) but isn't a full restart.
- Something else not yet discussed.

Both menu items are deferred behind Android game controller support
(current top priority).
