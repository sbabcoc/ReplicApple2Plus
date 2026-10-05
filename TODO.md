# TODO

Open work only. Completed tasks, with the reasoning and verification
history behind them, are in [TASK_RETRO.md](TASK_RETRO.md).

## VideoTerm 80-column card and Saturn 128K RAM card

Two Apple II+ expansion cards, both researched down to primary-source
technical specs and ready to implement. Neither exists in code yet.

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

### Videx VideoTerm -- fully spec'd, ready to implement

Everything needed is now confirmed from primary sources and recorded in
`HARDWARE-REFERENCE.md` section 3: firmware 2.4 (ROM binary, CRC32
`4DDBE669`), the character generator ROM (CRC32 `87F89F08`), the CRTC
values the firmware programs, the `$C800`-`$CFFF` memory map and
C8-space ownership, and the 40/80-column switching rule from Videx's
own Soft Video Switch schematic. The notes below summarize; the
reference doc is authoritative.

Two primary/near-primary sources, both fetched directly:
- The actual Videx manual (archive.org): https://archive.org/details/Videx_Videoterm_Installation_and_Operation_Manual
- A modern, carefully-researched FPGA reimplementation writeup (not
  primary, but corroborates the manual precisely on every point
  checked) -- a two-part article covering the MC6845 CRTC and why slot
  3 is architecturally special.

**Must be in slot 3.** Not a recommendation -- firmware 2.4 (the
version this manual documents) is hard-coded for it; slot independence
was a feature of earlier firmware and was explicitly removed.

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

**CRTC setup** (read from the firmware's own table at `$C8A1`): 80 x 24
characters, **9 scan lines per row** (R9 = 8), 124 character times per
line, 260 lines per frame, full-cell block cursor blinking at 1/32 field
rate.

**Character generator ROM**: 128 glyphs x 16 bytes (one byte per scan
line, 8 bits wide); printable characters use lines 0-8 (7-line body
plus true descenders) with full lowercase; `$01`-`$1F` are graphics
characters.

**40/80-column switching (single-window mode)**: per the Soft Video
Switch schematic, 80-column output is shown **if and only if AN0 is high
(`$C059`) and graphics is off**; otherwise the Apple's own video. The
firmware touches `$C059` at initialization and with every character it
outputs, and `$C058` only for its "1" command. This emulator's
`VideoSoftSwitches` already uses the matching convention ($C058 clears
AN0, $C059 sets it).

**Two-window mode** bypasses the Soft Video Switch entirely: the primary
window always shows the Apple's own video, and the secondary window
always shows the 80-column output, regardless of AN0 or graphics mode.

Apple Language interactions are also documented (HOME and `CALL -936`
don't work with VideoTerm active -- substitute `PRINT CHR$(12)`;
`FLASH`/`INVERSE`/`NORMAL` and the hi-res graphics calls are silently
ignored since they only affect the standard Apple display) -- relevant
for deciding how much software-compatibility behavior to model versus
just the hardware registers.

**Edit menu copying, once VideoTerm output exists**: Copy Screen Image
should carry over as-is, since it snapshots whatever panel is showing --
in the default single-window mode, whichever display the Soft Video
Switch has selected (AN0 high and graphics off → 80 columns -- see
`HARDWARE-REFERENCE.md`); in two-window mode, each window should copy
its own content. Copy Screen Text will need a VideoTerm counterpart to
`system/ScreenText`, decoding the card's own 2KB on-board VRAM -- read
from the CRTC's R12/R13 start address, which scrolling depends on --
rather than the motherboard text pages.

## Noticed, not yet verified

Observations made while working on other things. Each is a lead to
check, not a confirmed bug -- confirm with a real failing case before
changing code.

- **Lo-res mixed mode may render its bottom four rows as graphics.**
  `ScanlineModes.currentLineMode` forces the mixed-mode text window only
  when hi-res is on (`if (hires && videoSoftSwitches.isMixed() ...)`).
  On real hardware mixed mode applies to lo-res too (Applesoft's `GR`).
  If `GR` shows colored blocks instead of text along the bottom, this is
  why. Copy Screen Text follows the displayed modes, so it inherits the
  same behavior.
- **Blank WOZ images leave INFO "optimal bit timing" at 0.**
  `WozDiskImage.buildBlankFileBytes` doesn't set INFO byte 39; the WOZ
  spec gives 32 (4 µs) for 5.25" disks. Not known to cause a problem.
