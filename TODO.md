# TODO

Tracked ideas not yet scheduled for implementation.

## Type-from-file keystroke injection -- done, as the Edit menu

Built as an "Edit" menu rather than a CLI option (a command-line option
is only processed at launch -- far too restrictive), and widened to the
clipboard integration that was originally set aside: see `EditMenu.java`,
`TypingFeeder.java` and `system/ScreenText.java`.

- **Paste** and **Type File...** feed the same `TypingFeeder`; only the
  text's source differs. **Stop Typing** discards whatever is left.
- **Pacing** is gated on the keyboard strobe, not a fixed delay: the next
  key loads only once software has cleared $C010 for the previous one.
  That retired the "live-paste timing races" concern from the original
  scope note -- injection is lossless at whatever pace the running
  software reads the keyboard.
- **Line endings**: `\r\n`, `\n` and lone `\r` each become one Return.
  Everything else goes through `KeyboardMapper.mapTypedCharacter`, so
  injected text folds to uppercase exactly like typed text -- which also
  retired the "case-folding ambiguity" concern.
- **Copy Screen Text** copies the whole displayed text (trailing spaces
  and trailing blank rows trimmed), using the same per-scan-line modes
  `ScreenPanel` paints from: all 24 rows in text mode, only the text rows
  in mixed mode, nothing in full-screen graphics. Mouse-selection copy
  remains a possible later addition.
- **Copy Screen Image** copies exactly what the window shows, at its
  on-screen size, by having `ScreenPanel` paint itself into an offscreen
  image -- the same `paint` call Swing makes, so it can't drift from the
  display.
- Deliberately no keyboard shortcuts -- menu items only.

## Reset and Reboot toolbar buttons -- both done

Both built, wired, and verified -- see `ToolbarControls.java` and
`Apple2Plus.java`. Kept here as a reference for the real-hardware
context and the design reasoning behind each, in case that matters
again later (e.g. if VideoTerm or the Saturn card ever need similar
toolbar controls).

**What was actually built**: a toolbar (not a menu -- deliberate,
see below), with a "Reset" `JButton` whose `MouseListener` calls
`cpu.raiseReset()` on press and `cpu.lowerReset()` on release, each
posted onto the emulation thread via the same `Executor` pattern
`KeyboardInputListener`/`DiskMenu` already use. Verified directly with
a real `Cpu6502` and a real built button (not just compiled): pressing
freezes the CPU (`step()` returns 0 while held), and releasing fires
the actual reset sequence -- PC lands exactly at the reset vector, SP
decrements by exactly 3, confirmed by reading the real post-reset
values. One genuine mistake caught and fixed along the way: a first
test attempt checked only `getMouseListeners()[0]`, which turned out
to be Swing's own internal `BasicButtonListener` installed before mine
-- not a bug in the button itself, but a reminder that
`addMouseListener` appends, it doesn't replace, and real event
dispatch calls every registered listener, not just the first.

**Why press/release, not a single click action**: `Cpu6502` already
had a complete, hardware-faithful RESET line --
`raiseReset()`/`lowerReset()`, with `step()` a no-op while the line is
held. This isn't incidental complexity: RESET is a real,
level-triggered line, and the actual reset sequence fires only on the
*release* edge, so the API maps directly onto a physical press and
release. This is also why toolbar beat menu as more than a style
preference: a `JMenuItem`'s `actionPerformed` fires once per completed
click, with no natural way to get separate press/release events; a
toolbar `JButton`'s `MouseListener` gives both halves directly.

**Real hardware wiring, confirmed, and still relevant if this area
gets touched again**: on a genuine Apple II/II+, RESET bypasses the
keyboard encoder entirely -- wired directly to the 6502's hardware
RESET pin, never touching the ASCII-producing path every other key
uses (confirmed across several sources, including a repair thread
where a fully dead encoder chip still left RESET working, precisely
because its circuit has no dependency on the encoder). This project
models an Apple II **Plus**, where Ctrl-Reset (not bare RESET) is the
historically correct behavior -- the original Apple II shipped with no
Control interlock, and a widely-adopted mod added one after enough
people bumped it by accident. None of this ended up mattering for the
toolbar button itself (keyboard-triggered RESET was explicitly ruled
out of scope), but it's why the button goes straight to
`raiseReset()`/`lowerReset()` with zero dependency on `KeyboardMapper`,
rather than being modeled as a keyboard special case that happens to
have no key bound to it.

**Reboot**: done. No press/release split, unlike Reset -- a plain
`ActionListener` click, since there's no real hardware line this
corresponds to (a power switch is a single discrete event, not
something with a meaningful "hold" duration).

Settled on, after checking MAME's own documented soft-reset/hard-reset
distinction as a reference point (confirmed directly against
docs.mamedev.org, not taken on secondhand faith): a hard reset in MAME
"tears down the emulation session and starts another session with the
same system and options" -- confirmed via a MAMETesters bug thread that
this does NOT wipe NVRAM or swap out the loaded ROM. Adapted to this
project: Reboot recreates `MotherboardBus`/`Cpu6502`/the slot cards
from scratch (everything volatile resets -- RAM, CPU state, soft
switches), while preserving whatever disk image is currently inserted,
read via `RemovableMediaDrive.currentImagePath()` from the outgoing
`Disk2Controller` and re-inserted into the fresh one via `insert(Path)`
-- matching how a real Apple II power-cycle doesn't eject a floppy
either.

The implementation (`Apple2Plus.buildMachine` + a `Machine` record
holding everything reboot-sensitive: disk, bus, screen, loop, pad
poller, toolbar) also rebuilds the screen, keyboard listener, disk
menu, and toolbar in place in the same, reused `JFrame` -- none of
those are safe to leave pointing at the old, torn-down machine. Not
preserved across a reboot, by design: the pad poller's underlying
controller *connection* (SDL/Jamepad or the network socket) briefly
restarts, since `PadPoller`'s `InputMapper` is fixed at construction
with no way to swap it -- an acceptable, brief reconnection cost for a
deliberate, infrequent action, not something worth redesigning
`PadPoller`'s API around.

Verified two ways, not just compiled: a focused test built a real
`Disk2Controller` with a real synthetic WOZ image (via the existing
`WozTestFixtures`), called `buildMachine` a second time as a reboot
would, and confirmed the new `Disk2Controller` is a genuinely different
instance with the same media still present. Separately, a full
end-to-end test launched the actual application under Xvfb and clicked
the real Reboot button -- which caught a real bug before delivery: the
reboot lambda initially passed `null` instead of itself when rebuilding
(a lambda can't refer to the local variable it's being assigned to;
fixed with a one-element array holder), which silently left the rebuilt
toolbar with no REBOOT button at all. After the fix, the same test
confirmed a genuinely new Reset button exists post-reboot and still
works correctly (no stale references to the torn-down CPU).

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

**Edit menu copying, once VideoTerm output exists**: Copy Screen Image
should carry over as-is, since it snapshots whatever panel is showing --
in the default single-window mode, whichever display the Soft Video
Switch has selected (annunciator 0 plus text mode -- see
`HARDWARE-REFERENCE.md`); in dual-window mode, each window should copy
its own content. Copy Screen Text will need a VideoTerm counterpart to
`system/ScreenText`, decoding the card's own 2KB on-board VRAM -- read
from the CRTC's R12/R13 start address, which scrolling depends on --
rather than the motherboard text pages.

## Disk write support -- done for WOZ and DSK

**WOZ**: done. `TrackBitStream.writeBit()` overwrites the bit at the
current head position, mutating the same backing array
`WozDiskImage` holds persistently (so a write survives a track
change, not just the one call that made it). What gets written is
the sequencer's write line -- state bit 3, exposed as
`Disk2LogicSequencer.writeSignal()` -- one flux transition (a 1 bit)
per change in its level, in both Q7=1 modes, with the disk advancing
one bit cell per 8 LSS ticks in every mode; this matches MAME's
`wozfdc`. (The first version emitted a bit only on LSS shift actions,
which skipped the cell where the LSS loads the next byte: every byte
reached the disk as 7 bits, found via an `INIT` I/O error.)
`Disk2Controller` wires this together and enforces write-protect at
the drive level, not inside the LSS -- matching real hardware, where
the notch sensor lives in the drive, not the controller card.

The open design question this item used to flag -- persist to the
host file, or stay in-memory only -- is resolved: **persisted to the
host file**, confirmed explicitly ("the data must be persisted in
the host machine disk image file. Anything less is inaccurate
emulation"). Mechanism: `WozDiskImage.persist()` rewrites every
captured track's current bytes back into the file at their original
offsets and recomputes the CRC32, triggered on track change, eject,
and application exit/Reboot -- a dirty flag skips the rewrite
entirely when nothing changed, so ordinary reading (which seeks
across tracks constantly) costs nothing extra. META and anything
else this project doesn't itself track survive untouched, since
persistence works by patching a fresh read of the real file rather
than reconstructing one from parsed fields.

A real bug was caught by the integration test for this, not left for
later: `Disk2Controller.tick()` was unconditionally reading a pulse
bit every 8th tick regardless of mode, double-advancing the stream's
position whenever a write also landed that tick and silently writing
every bit to the wrong (every-other) position. Mutation-tested
directly: reverting the fix reproduces exactly that corrupted
pattern.

**DSK**: done. Two changes to `DskDiskImage`, each tied to a failure
found while building it:

- **Tracks are kept, not re-synthesized.** `trackAt()` used to build a
  fresh `TrackBitStream` on every call, so a write landed in a throwaway
  array and was lost on the next head movement (even between
  quarter-tracks of the same track). Each track is now encoded once, on
  first access, and that stream is kept.
- **Sync gaps are real 10-bit self-sync bytes** (`FF` plus two zero
  bits), not plain 8-bit `FF`s. Found by the end-to-end write test: a
  rewritten data field ends at an arbitrary bit offset relative to the
  old bits after it, and without self-sync bits a reader never regains
  byte framing, so the *next* sector's address field became unreadable
  -- to the emulator's own reads as well as to persisting. Tracks are
  now 49,764 bits rather than 49,104.

`persist()` decodes only tracks whose bits changed since they were
encoded: bits become disk bytes the way the controller's latch forms
them, the track is read around twice so a field wrapping past its end
is read whole, and each sector is accepted only if its address-field
checksum, data checksum and `DE AA` epilog all check out -- the same
checks DOS makes on every read. A written track's sector that fails
those keeps its previous contents (never guessed at); every good sector
is still saved, and `persist()` then throws naming the bad ones.

Inherent to the format, not a gap in this work: a .dsk file holds only
sector contents, so an `INIT`'s volume number and gap lengths aren't
kept -- a reloaded track is re-synthesized with volume 254, as with any
DSK image.

## Blank, formattable disk image creation -- done for WOZ and DSK

Done. `WozDiskImage.createBlank(path)` builds a complete, valid
WOZ2 file with 35 pre-allocated tracks. Every still-unformatted track
reads as fresh, genuine randomness on every single read (confirmed:
two reads of the same position differ), matching the WOZ spec's own
documented requirement for blank media -- fixing a previously
*documented* simplification (this project used to return constant
zeros here instead) now that a blank disk's tracks are the normal
case, not a rare copy-protection edge case.

Virgin-track status -- "this track has a real slot but was never
actually formatted" -- is detected from the file's own content, not
remembered only in memory: a track whose stored bytes are *all*
zero is treated as virgin, confirmed to have zero false positives on
real data, not a risky heuristic -- real Apple II disk encoding has
a hard physical constraint of no more than two consecutive zero bits
anywhere in valid, formatted data (confirmed across several
independent, primary sources), making a whole track of zero bytes
(51,200 consecutive zero bits) physically impossible to produce by
accident. This means a disk created but never formatted, then
reloaded in a completely separate session, still correctly resumes
as random noise rather than silently becoming stable, meaningless
data -- an earlier version of this work had that as a disclosed,
accepted limitation; it's fixed now, not just documented.

UI: a "New..." item per drive in the `Disk` menu, alongside
Insert/Eject, prompting for name and location via a save dialog and
inserting the result directly (not via a second, separate load that
would have discarded the in-memory virgin-tracking this all depends
on). The save dialog offers both WOZ and DSK; a typed extension decides
the format, otherwise the selected filter does.

**DSK blank media**: a .dsk file can't represent unformatted media, so a
blank DSK is an *empty (0-byte) file* -- the same convention Virtual ][
uses (confirmed from a blank DSK it created). `DskDiskImage.load`
accepts a 0-byte file as an entirely unformatted disk whose tracks read
as per-read random noise (51,200-bit tracks, matching WOZ's blank
tracks), and `createBlank` makes one. The first persist after a write
turns it into a normal 143,360-byte image; a track still never written
at that point is stored as zeroed sectors, since the format can't record
"unformatted" for one track among formatted ones. `INIT` writes every
track, so normal use loses nothing to that.
