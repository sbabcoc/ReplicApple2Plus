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

## Disk write support (prerequisite for blank/formatted disk creation)

Confirmed directly, not assumed: **ReplicApple2Plus does not currently
support writing to disk at all.** The write-mode *protocol* is modeled
correctly -- `Disk2Controller.writeIoSwitch` latches the write-data
register exactly when real software would (a write to $C08D while Q6=1,
Q7=1), and WOZ write-protect sensing is correctly read and honored. But
`TrackBitStream`'s entire public API is `nextBit()`/`bitCount()`/
`position()`/`seekTo()` -- read-only. The latched write-data byte is
only ever read back into the LSS's own internal latch
(`Disk2LogicSequencer`'s `case 0xB`); nothing takes it and encodes it
into a track's bit stream. Practically: a write-protected disk
correctly refuses a write, but writing to a non-protected disk silently
succeeds from the CPU's perspective while persisting nothing -- DOS
3.3's `SAVE` would appear to work and lose the file.

Needed before writes do anything real:
- A write path on `TrackBitStream` itself -- something like
  `writeBit(int bit)` that overwrites the bit at the current head
  position and advances, mirroring how `nextBit()` already does the
  read side.
- `Disk2LogicSequencer` actually calling it, timed to the real hardware
  rate (one bit shifted out roughly every 4 CPU cycles, matching the
  existing read-side LSS timing already modeled in
  `Disk2Controller`'s tick ratio comments) -- not just latching the
  byte and discarding it.
- A decision, not yet made: does a write persist back to the host
  `.woz`/`.dsk` file on disk, matching how a write to a real floppy
  immediately, physically persists to the magnetic media (more
  hardware-faithful, but means deciding when to flush -- every write,
  or on eject/exit) -- or stay in-memory for the session only, discarded
  unless something explicit saves it? This project's own stated
  hardware-fidelity goal points toward the former, but it's a real
  design question, not a given.
- WOZ and DSK likely need different answers for "blank" image creation
  specifically. A blank `.dsk` is straightforward (143,360 zero bytes;
  `DskDiskImage` would encode that as blank, unformatted sector data,
  which is exactly the right starting state for `INIT` to then format).
  A blank `.woz` is harder to get right: a real, newly-manufactured
  unformatted floppy's magnetic surface is random noise, not silence,
  until formatted -- WOZ's own format (chunk-based, with TMAP/TRKS
  structures) would need either synthesized noise tracks or an empty
  but structurally valid file, and which of those is actually correct
  isn't yet researched the way VideoTerm/Saturn were.

## Blank, formattable disk image creation

Depends on the write-support work above to be meaningful -- filed
separately since the two are genuinely different pieces of work (file
creation vs. emulated drive mechanics), but creating a blank disk a
real `INIT` command couldn't actually format would be a half-finished,
misleading feature. A host-side menu action ("File > New Disk" or
similar) that writes a fresh, blank image of the chosen format to a
path the user picks, then (optionally) inserts it into a drive the same
way `DiskMenu`'s existing insert action does.
