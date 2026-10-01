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

## VideoTerm 80-column card and Saturn 128K RAM card

Two Apple II+ expansion cards. Both were already researched in depth in
an earlier session, down to a primary-source technical spec for each --
captured here in full so it doesn't need to be dug out of conversation
history again. Neither has been implemented yet.

### Saturn Systems 128K RAM card -- fully spec'd, ready to implement

Primary source: the actual 1982 Saturn Systems Operations Manual
(Chapter 9, "Technical Information"), fetched directly --
https://garrettsworkshop.com/files/RAM128/SATURN128MAN.pdf (also
mirrored at applelogic.org and apple2.org.za). Not a secondhand forum
recollection.

A bank-switched RAM expansion: 8 banks of 16K each (128K total; the 64K
variant is 4 banks), extending the same mechanism as Apple's own
Language Card -- the first 16K bank behaves identically to a Language
Card, so existing Language-Card-aware software works unmodified.

**Addressing**: control range `$C0N0`-`$C0NF`, where N = 8 + slot#
(`$C080`-`$C08F` for slot 0). Address line A2 selects between two
modes: state-select (A2=0: read source, write-enable, which 4K
sub-bank) vs. bank-select (A2=1: which of the 8 16K banks). Full
verbatim truth table:

```
$C0N0: 4K Bank A; RAM read;  write protect
$C0N1: 4K Bank A; ROM read;  write enabled
$C0N2: 4K Bank A; ROM read;  write protect  (power-up default)
$C0N3: 4K Bank A; RAM read;  write enabled
$C0N8: 4K Bank B; RAM read;  write protect
$C0N9: 4K Bank B; ROM read;  write enabled
$C0NA: 4K Bank B; ROM read;  write protect
$C0NB: 4K Bank B; RAM read;  write enabled
$C0N4-$C0N7: select 16K banks 1-4
$C0NC-$C0NF: select 16K banks 5-8
```

**Memory layout**: each 16K bank occupies $D000-$FFFF like the
Language Card, but is internally split: two 4K sub-banks (A and B)
cover $D000-$DFFF (only one visible at a time), and a shared 8K region
covers $E000-$FFFF (common to both sub-banks of that 16K bank).

**Confirmed behavioral divergence from this project's existing
`LanguageCard`**: on real Saturn hardware, *either a read or a write*
to a write-enable-eligible address counts toward the double-access
arming sequence -- not reads-only, the way the standard Language Card
(and this project's existing `LanguageCard.java`) works. Manual's own
wording: "accomplished by either reading or writing." This is a real
difference to implement deliberately, not something to silently reuse
from the existing class.

**LED indicator behavior** (A3/A1/A0 + RAM-READ, useful for any future
front-panel/status display) and the full "16 possible states" table are
in the manual; not reproduced here since they're not needed to pass
`PDL()`-style software compatibility, only documented for completeness
if ever wanted.

Given `LanguageCard` already exists and is correct, the Saturn card is
a generalization of it to 8 banks plus the double-access divergence --
not a from-scratch peripheral.

### Videx VideoTerm -- nearly fully spec'd

Two primary/near-primary sources, both fetched directly:
- The actual Videx manual (archive.org): https://archive.org/details/Videx_Videoterm_Installation_and_Operation_Manual
- A modern, carefully-researched FPGA reimplementation writeup (not
  primary, but corroborates the manual precisely on every point
  checked) -- a two-part article covering the MC6845 CRTC and why slot
  3 is architecturally special.

**Must be in slot 3.** Not a recommendation -- firmware 2.4 (the
version this manual documents) is hard-coded for it, and the
SLOTC3ROM/INTCXROM mechanism exists specifically for the 80-column
card slot.

**Device select at `$C0B0`-`$C0BF`** (slot 3; `$C0(8+n)x` generally).
Bit 0: 0 = register-select (which of 18 CRTC registers), 1 = register
data -- the documented two-write sequence (write index, then write
value). Bit 1: 8-vs-9-cell matrix width; VideoTerm always uses 0. Bits
2-3: which of 4 pages (0-3) of the 2KB on-board VRAM is mapped into the
fixed `$CC00`-`$CDFF` window -- confirmed both by the manual's own
device-select section and independently by its BASIC example programs,
which page through VRAM exactly this way.

**CRTC**: Hitachi HD46505SP (6845-compatible), 18 registers fully
documented in the manual:
- R0-R3: horizontal timing (total, displayed count, sync position, sync width)
- R4-R9: vertical timing (total, total-adjust fraction, displayed rows, sync position, interlace mode, scan lines per row)
- R10-R11: cursor start/end row, blink mode (bits 5-6 of R10)
- R12-R13: start address -- manual explicitly warns: do not touch, breaks scrolling
- R14-R15: cursor position, read/write
- R16-R17: light pen capture, read-only

**Video set-up flags at `$7F8+n`**: bit 0 (alternate character set /
inverse video if hardware-modified), bit 4 (18 vs 24 lines), bit 6
(case-conversion, toggled by CTRL-A), bit 7 (GETLN vs. GET as input
source).

**`$C300`-`$C3FF`**: 256-byte slot firmware ROM.

**Still missing / needs verification once coding begins**: the
manual's own "VIDEOTERM Memory Mapping" section (which would document
the `$C800`-`$CFFF` expansion-ROM ownership/C8-space-release protocol
in Videx's own words) cut off mid-fetch and was never retrieved. The
FPGA article's account of this mechanism is credible and detailed, but
secondhand -- worth confirming against the manual directly (or real
firmware disassembly) before relying on it, rather than implementing
from the secondary source alone.

Apple Language interactions are also documented (HOME and `CALL -936`
don't work with VideoTerm active -- substitute `PRINT CHR$(12)`;
`FLASH`/`INVERSE`/`NORMAL` and the hi-res graphics calls are silently
ignored since they only affect the standard Apple display) -- relevant
for deciding how much software-compatibility behavior to model versus
just the hardware registers.
