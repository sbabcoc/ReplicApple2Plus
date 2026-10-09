# Task Retrospective

Completed work, kept for its reasoning and verification history -- the
real-hardware context behind each decision, the sources checked, and
the bugs caught along the way. Open work lives in [TODO.md](TODO.md);
current behavior is summarized in [README.md](README.md) and specified
in [HARDWARE-REFERENCE.md](HARDWARE-REFERENCE.md).

These entries are a historical record: each was written when its work
landed, and later work sometimes changed what it describes. Where an
entry and the current code disagree, the code (and the README) are
current.

---

## Completed tasks (moved from TODO.md)

### Type-from-file keystroke injection -- done, as the Edit menu

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

### Reset and Reboot toolbar buttons -- both done

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

### Disk write support -- done for WOZ and DSK

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

### Blank, formattable disk image creation -- done for WOZ and DSK

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

### Disk swapping saves pending writes -- done

`Drive.insert` and `Drive.insertNewBlankDisk` replaced the loaded image
without persisting it, unlike `eject()`. A write still held in memory --
persistence otherwise happens on track change, eject and exit -- was
lost by inserting another disk directly, and re-inserting the same disk
reloaded its file over the only copy. Confirmed with two tests that
failed before the fix (a swap, and a re-insert). Both paths now call
`persistBeforeReplacing()` first, with `eject()`'s rule: if saving
fails, the call throws and the current disk stays loaded.

Application exit was already covered: a shutdown hook (normal window
close or SIGINT/SIGTERM) stops the emulation loop and then persists the
disks of whichever machine is current, and Reboot persists the outgoing
machine's disks the same way.

### Disk write path: the write line, not shift actions -- done

**Symptom**: `INIT HELLO,D2` failed with an I/O error on a blank WOZ
disk. A diagnostic build logged every byte the controller loaded for
writing and every byte read back afterward: the loaded bytes were
correct, but a data field written as `96 96 96...` read back as `97 B9
E5 CB` repeating. That pattern is exactly `96` (`10010110`) with its
last bit dropped -- `1001011` repeating -- so every byte reached the
track as 7 bits.

**Cause**: `Disk2LogicSequencer` produced a written bit only on an LSS
shift action with Q6 low, and the controller advanced the disk only
when a bit was written. In the bit cell where DOS reloads the latch
(`STA $C08D,X` ... `ORA $C08C,X`), the LSS performs LD instead of a
shift, so that cell was neither written nor passed over. Replaying the
project's own ROM table with DOS's loop timing reproduced it: 7.0 bits
per byte for every 2-5 cycle LOAD window.

**Fix**, modeled on MAME's `wozfdc`: the write line is the sequencer's
state bit 3 (`writeSignal()`), and each change in its level is one flux
transition, in both Q7=1 modes; the disk advances one bit cell per 8
LSS ticks in every mode. The same replay then gave exactly 8 bits per
byte for every LOAD window from 3 to 10 LSS ticks. Confirmed in use:
`INIT`, `SAVE`, and booting from the written disk.

### Random power-on RAM -- done

**Symptom**: Joust (a DSK) froze just as play started, after the usual
sequence -- `C` at the disk's menu, Space past the crack screen, Space
at the title.

**Cause**: at the start of play the game picks a random number from 1
to 4 that differs from the previous one (loop at `$A32D`), retrying
until it gets one. Its generator (`$AB22`) seeds from `$4D`-`$4F`,
which the game never sets: it relies on power-up garbage, or on the
Monitor's KEYIN having churned `$4E`/`$4F` while waiting at a prompt.
The menu and title read the keyboard directly, so KEYIN never ran, and
the emulator powered up with all-zero RAM -- the seed stayed `00 00
00`, the generator returned the same value forever, and the loop never
ended.

**Evidence**: every commit from hi-res support onward froze identically
from a cold start. Booting the System Master first and typing at the
`]` prompt, then `PR#6` into Joust, worked (KEYIN had seeded the RNG);
Reboot straight into Joust froze. Some seeds collapse to zero in this
generator (`00 5A 00` did); `FF 00 00` (what AppleWin's default
`FF FF 00 00` power-up pattern leaves there) and fully random seeds
both played.

**Fix**: RAM -- the motherboard's 48K and the Language Card's 16K --
powers up random, as real DRAM does (`PowerOnRam`). Six random
power-ons through the exact key sequence all played.

**Side effect caught**: `BishopScreenSplitIntegrationTest` began failing
17 of 20 runs. It enters Bob Bishop's routine by setting `pc` directly,
so the routine's `RTS` popped whatever the stack page held -- harmless
`$0000` with zeroed RAM, a jump into random memory with random RAM. The
test now pushes a return address to a one-instruction parking loop, as
`JSR` would; 40 of 40 runs pass.

### Write-protect sensing off the per-tick path -- done

**Symptom**: on the Debian/Termux/PRoot setup, DSK images appeared not
to boot -- stuck at the "APPLE ][" banner -- while a WOZ System Master
booted. Left long enough, a DSK did boot: it was running far below real
time.

**Cause**: `Disk2Controller.tick()` asked the disk image for its
write-protect state after every CPU instruction while the motor ran.
Since `d7170b5`, `DskDiskImage.isWriteProtected()` checks the host
file's permission (`Files.isWritable`) -- a system call each time, and
PRoot intercepts every system call. A protected WOZ answers from its
INFO flag without touching the filesystem, which is why the System
Master was unaffected.

**Evidence**, timing a 15-emulated-second DSK boot under PRoot: `7f9f8d4`
(before the change) 1.3 s; `d7170b5` 20.8 s; with the fix 0.9 s. (2.2 s
natively, without PRoot.)

**Fix**: the controller caches the sensor and reads it only when
software can observe it -- entering sense mode (Q6 on, Q7 off), entering
write mode (Q7 on), and selecting a drive. A test confirms a protection
change while the disk sits in the drive is still seen at the next
sense, in both directions.

### Tests build buses without audio -- done

**Symptom**: `PaddleTimersPreadIntegrationTest` took a very long time on
Termux.

**Cause**: every `MotherboardBus` opened a real audio line, and that
test builds about 70 buses. Through Termux's PulseAudio bridge, each
open and close was slow. Confirmed by stopping PulseAudio, which made
the test fast.

**Fix**: `MotherboardBus.withoutAudio(slots)` gives tests a bus whose
speaker takes its existing no-device path; the four test classes that
build buses use it, and a guard test checks it never opens a device.

### Network controller tests use OS-assigned ports -- done

**Symptom**: `NetworkPadProviderTest.neverConnectingAtAllReadsAsAbsentFromTheStart`
failed on Termux/PRoot, reproducibly, while the other eleven tests in
the class passed.

**Cause**: binding UDP `127.0.0.1:41009` was refused with "Operation not
permitted" (EPERM, not "address in use") -- by the OS itself: Python's
bind of the same port failed the same way. The tests used fixed ports
41001-41012.

**Fix**: each test asks the OS for a free port, releases it, and uses
that number. (Running the emulator there with `--network-input 41009`
would fail for the same reason.)

### Write-protect control: a named choice, not a checkbox -- done

A disk image was left write-protected (set while checking how Virtual
][ handles protection) and later read as writable: the single
"Write-Protected" checkbox named only one state, and writable was shown
only by a missing checkmark. Virtual ][ showing the disk as protected
was correct -- the image's INFO flag was `01` and the host file was
read-only, both set by that checkbox. Replaced with a Writable/Protected
radio pair, and every drive title now names its state (`[Writable]` or
`[Protected]`), so neither state is shown only by an absence.

### Videx VideoTerm 80-column card -- done

Built from the primary-source spec in HARDWARE-REFERENCE.md section 3:
`VideoTerm` (the card), `VideoTermRoms` (both ROMs, CRC-checked),
`VideoTermRenderer` (drawing and text), `EightyColumnView` (display),
plus `VideoSoftSwitches.softVideoSwitchSelects80Columns()`.

**Two firmware facts settled from the ROM binary while building it**
(now in HARDWARE-REFERENCE.md): the card's `$C300` page is the image's
last 256 bytes (`BIT $FFCB` entry, Pascal 1.1 ID bytes), and the
firmware stores each character with bit 7 taken from the character-set
flag, so standard text always has bit 7 clear.

**Decisions:**
- The card models hardware only -- device select (CRTC index/data, VRAM
  page on any access), the `$C300` page, firmware at `$C800`, the paged
  2K VRAM window at `$CC00` -- and the real firmware does everything
  else. VRAM powers up random, like the motherboard RAM.
- The renderer follows the CRTC as programmed (R1/R6/R9 geometry, R12/R13
  start address for scrolling with VRAM wraparound, R10/R11/R14/R15
  cursor with steady/hidden/blink modes) rather than hard-coding 80 x 24.
- ROMs load in `configure()`, never in the constructor:
  `ServiceLoader` constructs every registered card just to list it, so a
  missing VideoTerm ROM affects only setups that configure the card.
- `display=switched` (default) applies the Soft Video Switch rule in the
  main window; `display=separate` gives the 80 columns their own window
  (opened beside the main one, kept across Reboot) with its own Edit menu
  for copying, and leaves the main window always on the Apple's video.
- The 640 x 216 dots are stretched to fill the window with
  nearest-neighbor scaling, as a monitor fills its screen.
- Copy Screen Text copies whichever screen is showing.

**Verified:**
- The real firmware and system ROM together, headless: cold boot,
  `PR#3`, `PRINT`, `GR`, `TEXT` -- the CRTC holds the firmware's table, AN0
  turns on, the 80-column text is right, and the Soft Video Switch
  selection follows text/graphics (`VideoTermFirmwareIntegrationTest`).
- The real application under a virtual display, both modes: switched
  mode changes the main window to 80 columns after `PR#3` and back to
  the Apple's video for `GR`; separate mode keeps the main window on the
  Apple's video while the second window shows the session.
- A rendered screen showing lowercase, true descenders, the `{ } | ~`
  glyphs the stock II+ lacks, and the block cursor.
- Card and renderer unit tests: paging, registers, ROM mapping,
  configuration, glyphs, scrolling and wraparound, cursor modes and blink
  timing, text decoding.

### VideoTerm display mode on the toolbar -- done

The `display=` setting alone was too static, so the mode became a
runtime choice: **Soft Switch** and **Dual Monitor** toggle buttons
(`DisplayModeButtons`), shown when a VideoTerm is configured, with
`display=` in `slots.ini` now setting only the initial mode.

- The 80-column view and its window exist whenever a card is present;
  the mode just shows or hides the window, and `ScreenPanel` applies the
  Soft Video Switch only in Soft Switch mode.
- Closing the 80-column window selects Soft Switch, so the toolbar never
  claims a window that isn't there.
- The chosen mode carries across Reboot, as the inserted disks do.
- The buttons aren't focusable, so clicking one leaves the keyboard with
  the Apple.

Verified with unit tests (initial selection, clicks, programmatic
selection, focus) and in the running application: start in Soft Switch,
`PR#3`, switch to Dual Monitor (second window opens, main window returns
to the Apple's video, typing still reaches the Apple), close the second
window (back to Soft Switch, 80 columns in the main window), then Dual
Monitor plus Reboot (the second window and selection come back).

### Lo-res mixed mode shows its text rows -- done

**Symptom**: after `GR`, the four text rows at the bottom of the screen
showed as gray lo-res bars -- the text page's `$A0` spaces drawn as
color blocks -- instead of text. First spotted in a capture taken while
testing the VideoTerm.

**Cause**: `ScanlineModes.currentLineMode` switched mixed mode's bottom
rows to text only when hi-res was on. The condition was carried over
from `VideoScanner`'s address logic, where testing hi-res alone is right
-- only hi-res fetches from different addresses on those lines, since
lo-res already uses text-page addressing -- but what's *displayed* there
is text over lo-res and hi-res alike.

**Fix**: the display decision no longer tests hi-res; the address logic
is unchanged. A new test reproduced the bug before the fix (scan line
160 recorded as `LORES`) and guards hi-res mixed and full-screen modes;
in the running application, `GR` with a plot, a line and a `PRINT` now
shows the commands as text beneath the graphics.

### VideoTerm display modes: a four-way drop-down -- done

**Trigger**: after Merlin's `VID 0`, Soft Switch mode kept showing the
80-column screen. Reproduced with the user's Merlin disk: `VID 3` turns
AN0 on through the card's own firmware (`$C82A: STA $C059`), and `VID 0`
redirects output to the 40-column screen without ever touching AN0 --
a search of the whole disk found `$C058` only inside copies of the
Monitor's RESET routine. So the emulator was right: a real Soft Video
Switch would also keep showing 80 columns. On real hardware, RESET is
the way back -- the ROM's RESET routine (`$FA62`) clears all four
annunciators before Merlin regains control -- and the toolbar's Reset
does the same here, returning to Merlin's main menu in 40 columns with
the source still in memory.

**Change**: the two Soft Switch / Dual Monitor toggles became a
non-focusable drop-down with four modes, adding **Apple Video** and
**Slot 3 Video** -- a single window held on one source, like the manual
monitor switch such software was written for. `display=` accepts
`switched`, `apple`, `slot3` and `separate`.

**Verified** with unit tests (the four modes, their order and labels,
selection, focus, and what each mode shows for AN0 on and off) and in
the running application, cycling all four modes after `PR#3` and closing
the Dual Monitor window.

### VideoTerm integration test: the firmware's init marker -- done

`VideoTermFirmwareIntegrationTest` failed intermittently (on the user's
Mac, then 2 of 40 runs here): after `PR#3`, CRTC R0 was still 0. Cause:
`SETUP` skips programming the CRTC when `$077B AND $F8 = $30` -- its
"already initialized" marker, in a screen hole that powers up random --
so about 1 power-on in 32 skipped it. Confirmed by setting the byte
deliberately (`$30`/`$37` skip; `$00`/`$FF`/`$38`/`$28` don't). Real
firmware behavior, not an emulator fault. The test now clears the marker
before `PR#3` (40 of 40 runs pass), and a second test documents the skip
itself.

Same delivery fixed a Javadoc warning from the `supportedSlots()` change:
the new method had been inserted between `wantsExpansionRom()`'s Javadoc
and its declaration. Verified clean with Java 17's `javadoc`, which
reproduces the warning against the broken file.

### Saturn Systems 128K RAM card -- done

Built from the spec recorded from the 1982 Saturn manual (now in
HARDWARE-REFERENCE.md section 2): `SaturnCard`, eight 16K banks laid out
like the Language Card (4K sub-banks A/B at `$D000`, a per-bank 8K at
`$E000`), state select by the Language Card's truth table, bank select
on the A2 = 1 addresses, and the documented difference that writes as
well as reads count toward the double-access write enable.

**Verified with Saturn's own software.** The user supplied the Saturn
software disk; its `RAMTEST128K` is the manufacturer's RAM test. It
asks which slots to test and accepts only 1-7, which overturned the
plan (and an older `SlotCard` Javadoc claim) that upper-memory banking
was slot 0's alone. So the base system changed to match real hardware:
- `wantsSlotZeroBanking`/`readSlotZeroBank`/`writeSlotZeroBank` and
  `SlotZeroBankingHandler` became `wantsUpperMemory`/`readUpperMemory`/
  `writeUpperMemory` and `UpperMemoryHandler`: a card in any slot may take
  over `$D000`-`$FFFF` (the INHIBIT line), and configuring two such cards
  is refused at startup.
- `SlotCard.hasRom()` (default true): a ROM-less card's `$Cn00` page
  reads the floating bus, like an empty slot. The Autostart ROM reads
  every slot's page while looking for a boot disk.

With the card in slot 4, the full test passed: every page of all eight
banks and both sub-banks, about 13 emulated minutes. A copy with bank
select deliberately broken failed at once (`?LOOKS LIKE 16K RAM`), so the
pass is meaningful. Unit tests cover the truth table, bank select,
independent banks and shared 8K, the write-counting difference, slot 4
operation, the floating ROM page, and the one-upper-memory-card rule.

The 64K model isn't modeled: how it treats the bank 5-8 select
addresses isn't documented.

<details><summary>The spec as it stood in TODO.md before implementation</summary>

#### Saturn 128K RAM card (as recorded in TODO.md)

Researched down to a primary-source technical spec and ready to
implement. (Its companion, the VideoTerm 80-column card, is done -- see
TASK_RETRO.md.)

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
(`$C080`-`$C08F` for slot 0). **Emulator limit to resolve when building it**: the
card's control range follows its slot, but `MotherboardBus` routes
`$D000`-`$FFFF` bank switching only through slot 0's card
(`slots[0].wantsSlotZeroBanking()`). Either support the Saturn in slot 0
only -- declaring `supportedSlots()` as `{0}` -- or extend that routing. Address line A2 selects between two
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


</details>

### ProDOS-order (.po) disk images -- done

A 140K `.po` image holds the same 560 sectors as a `.dsk`, in ProDOS
order (each 512-byte block as two consecutive sectors). `DskDiskImage`
now picks its physical-to-file sector table by extension: the existing
DOS 3.3 table, or the ProDOS one. Both tables were checked against
MAME's `ap2_dsk.cpp` (`dos_skewing` matched the existing table exactly;
`prodos_skewing` was adopted as-is). Reading, writing and blank media
are shared code. Insert accepts `.po`, New can create one, and the
extension swap in New knows it.

**Verified** with sample images from AppleCommander's repository
(GPL-licensed, so used for verification only, not added to this
project): `DOS 3.3.po` boots DOS 3.3 as a `.po`, while the same bytes
named `.dsk` run off into garbage after the boot sector -- so the order
really matters. Unit tests check that the same disk stored in either
order produces identical tracks; both fail with the ProDOS table broken.

**Also learned**: AppleCommander's `Prodos.dsk` is Apple's ProDOS 8
2.0.3, which starts and then stops with "REQUIRES ENHANCED APPLE IIE OR
LATER" -- confirming that Apple's last ProDOS dropped the II+, and that
the community 2.4.x series is the one to use here.

### ProDOS 2.4.3 boots: head stepping follows the head -- done

**Symptom**: ProDOS 2.4.3 (`ProDOS_2_4_3.po`, the official release
image) showed its splash screen on a II+ with a Language Card, then
hung. A profile showed the boot loader reading tracks fine, then the
kernel's disk driver (in Language Card RAM, `$D385`-`$D3A8`) seeking
and searching for address fields forever. Logging head movement: the
kernel's first step from track 5 went **outward**, its seek to track 0
ended on track 1, and its retries rocked the head between quarter-tracks
4 and 6.

**Cause**: the stepper was modeled on transitions, with a separately
remembered "current phase". When a phase opposite the head was energized
with nothing else on, that model adopted it as its reference although
an opposite magnet doesn't move the head -- leaving the reference two
phases away from the head (logged: `currentPhase=0` with the head over
phase 2). The kernel's first inward step was then read as outward.
DOS 3.3's stepping happened never to trigger this.

**Fix**: `settleHead()` decides from the head's real position, as the
magnets do: if the magnet under the head is off and exactly one
neighbor is on, the head moves half a track toward it; otherwise it
stays. No remembered phase to drift. Two existing stepper tests relied
on the old model's convention that the first phase touched "adopts" the
head without moving it; on a real drive, energizing the neighbor of the
head's magnet pulls the head over, so their baselines now start after
that move (what each test checks is unchanged).

**Verified**: ProDOS 2.4.3 boots to Bitsy Bye, launches BASIC.SYSTEM,
and `CATALOG` lists the disk (280 blocks). Every earlier disk still
boots to the same screen (Joust, Merlin, the Saturn software disk, a
ProDOS-ordered DOS 3.3 master), and Joust plays through its second load.
`ProDosSeekTest` reproduces the trap and the recorded seek; run against
the old model it fails and ends on quarter-track 4 -- exactly where the
real boot got stuck.

### Host file transfer card: ProDOS adapter firmware (phase 3) -- done

The card's 6502 firmware (`firmware/hostfiles/hostfiles.s`, ca65), which
implements the abstract file API for ProDOS 8 by calling the MLI. Details
of what was built are in TRANSFER-CARD.md section 6. What the work turned
up:

- **Checked against the real system first:** the directory header and
  file entry offsets (read from the ProDOS 2.4.3 disk's block 2), the
  memory bit map's layout and BASIC.SYSTEM's vectors (read from a running
  system) -- not taken from memory.
- **A line-by-line review before the first run caught six bugs:** `fail`
  never saved the error code it looked up; the agent copy rounded up to
  whole pages and could have read `$CFFF` (releasing the ROM it was
  running from); the type table's end marker could match file type `$00`;
  the GET_FILE_INFO list was a byte short; `JMP (finptr)` could have hit
  the 6502's page-wrap bug; and the chunk-size and hex-digit code was
  wrong. The code then outgrew one 2K bank, so the agent moved to bank 1,
  copied by a routine in the `$Cn00` page.
- **The first real run worked except the ending**, which hung the Apple.
  Reading the vectors during the session showed BASIC.SYSTEM keeps the
  output device in `CSW` as well as `VECTOUT`; restoring only `VECTOUT`
  re-entered the firmware. Both are restored now.
- **The DOS 3.3 exit printed the ProDOS message:** a `BNE` used as a jump
  after loading message offset 0. Found by running real DOS 3.3.
- **`.gitignore` excluded `*.rom`,** which would have kept the committed
  firmware out of the repository; it now has an exception for that file.

Verified under real ProDOS 2.4.3 and BASIC.SYSTEM 1.7: every request,
multi-chunk files both ways, subdirectories, replacing, deleting,
attributes, the early exits under ProDOS (no host) and DOS 3.3, memory
restored (a BASIC program in the borrowed region still runs), and the
real application opening the transfer window on `PR#2`. Permanent tests:
`HostTransferCardFirmwareTest` (the image) and
`ProDosTransferIntegrationTest` (the whole session, when the ProDOS image
is supplied).

---

## Component development history (moved from README.md)

The README's original per-component write-ups, recorded as each piece
was built and verified. Several statements here were later superseded --
for example, disks are now writable (WOZ and DSK), a DSK track keeps
its bit position across head movement (WOZ tracks still restart at bit
0), DSK write-protect follows the host file's permission, unmapped WOZ
quarter-tracks read as random weak bits, the speaker drives real audio,
and lo-res/hi-res graphics are rendered.

#### What's implemented

**CPU core** — every documented NMOS 6502 opcode and addressing mode,
cycle-accurate timing (including the conditional page-boundary-crossing
penalty), every stable illegal/undocumented opcode, decimal (BCD) mode with
its NMOS-specific flag quirks, `JAM`/`KIL` as a genuine halt state, and
`IRQ`/`NMI`/`RESET` modeled as physically distinct signals with correct
polarity, wire-OR semantics, and the one-instruction interrupt-polling lag
that makes real `SEI`/`CLI` timing work. See [Verification](#verification)
for how this is checked, not just asserted.

**Memory bus** — `AddressSpace` is a generic, hardware-agnostic
address-range dispatcher: registered `(start, end, handler)` ranges, routed
by direct block-table indexing (not a linear scan), with alignment and
overlap validation and a hard 8-bit-wide guarantee on every value that
crosses it (matching the real, physical data bus, which cannot represent
more than 8 bits in either direction — reads are masked exactly like writes
already are, and a handler violating that contract is caught by an
assertion, not modeled as an emulated condition). It has no knowledge of
what a "slot" or "video switch" is. `MotherboardBus` is the Apple II+-specific
wiring on top of it — a list of registrations, not branching logic mixed
into the dispatch mechanics. Swapping a placeholder for a real
implementation means changing one registration line; nothing else needs to
change.

**Peripheral architecture** — `SlotCard` is the contract every peripheral
implements: a public no-arg constructor plus a `configure(Properties)`
lifecycle method (deliberately not a constructor argument, so
`META-INF/services/...SlotCard` is a genuine, ordinary Java SPI file that
real `ServiceLoader` can use directly — proven by `ServiceLoaderSpiTest`,
not just claimed). A card declares its own short config-file name and
recognized parameters as real methods on the class itself, not as
comments in a separate file that could drift out of sync with the code —
and if a card's own declared parameters and what it actually reads ever
disagree, that's caught automatically rather than silently tolerated.
Around this: `PluginLoader` builds a classloader from a directory of
external `.jar` files (the actual mechanism that lets a new peripheral be
added without recompiling this project), `Install` is a minimal
installer any peripheral jar can use as its own `Main-Class`,
`CardCatalog` lists every available card and what it needs, and
`SlotConfigTemplate` generates a one-time, safe-to-paste starting point
for a slot configuration file (explicitly not a live or auto-synced
catalog — regenerate it, don't trust it to stay current).

**Real, wired-in peripherals:**
- **`Disk2Controller`** — genuinely complete now, not a partial slice.
  The boot ROM and all 16 soft switches (phase stepper, motor, drive
  select, Q6/Q7 mode latches) are fully implemented. Phase-stepper
  head positioning is real: the actual electromagnet-sequencing
  algorithm (a step occurs only when the currently-on phase turns off
  while exactly one neighbor is on, confirmed against two independent
  sources for both the algorithm and the direction convention),
  clamped at the real 0-159 quarter-track range, per-drive
  independent. `RemovableMediaDrive.insert` genuinely parses a real
  WOZ2 disk image (`WozDiskImage`, see below). `Disk2LogicSequencer`
  (see below) is fully wired in: Q6/Q7 changes reach it, `tick`
  advances it at the real hardware ratio (the LSS runs at 2x CPU clock
  rate -- confirmed independently -- sampling one real bitstream bit
  every 8 LSS ticks, matching the real 4-CPU-cycle-per-bit data rate),
  and reading the Q6/Q7 data latch (offsets $C-$F) returns the actual
  sequencer output instead of throwing. A real bug caught while wiring
  this: `WozDiskImage.trackAt` returns a fresh `TrackBitStream` on
  every call, which would have silently reset the read position to 0
  on every single tick -- fixed by caching the stream in `Drive`,
  refreshed only on an actual head move or disk swap. Verified
  end-to-end, not just per-piece: 66 checkpoints of an inserted,
  real synthetic WOZ file's known bit pattern flowing through
  `tick()` match a Python reference implementing the identical timing
  exactly, under variable, realistic cycle-delivery chunk sizes (not a
  fixed, convenient tick size) -- plus separate confirmation that
  motor-off genuinely halts all ticking, that switching drives reads
  the correct drive's own track, and that a write-protected disk's
  sense mode correctly yields `0xFF`. Two named, deliberate
  simplifications remain: track changes reset to bit 0 rather than
  preserving relative rotational position (the WOZ spec's own
  recommendation, relevant to a small number of copy-protection
  schemes, not ordinary reading), and a documented DOS 3.2 `INIT`
  sub-instruction timing quirk (the LSS completing a cycle *within* a
  single CPU instruction) that this project's instruction-boundary
  cycle granularity can't represent. Now wired into `Apple2Plus` --
  see below for why slot 6 is populated conditionally, not always.
- **`Disk2LogicSequencer`** — the actual state machine that converts a
  disk bitstream pulse into shift-register (nibble) data, driven by
  `DiskLogicSequencerRom`'s 256-byte table. A genuinely important
  correction surfaced while building this: that ROM class's own,
  previously-documented address-bit wiring
  (`{state:4}{Q7,Q6:2}{latchMSB:1}{sense:1}`) had never actually been
  verified against a working reference, and turned out to be wrong.
  The real mapping was found by exhaustively searching all 8-bit
  permutations and single-bit inversions (roughly 10.3 million
  candidates) for the one that exactly reproduces a2kit's independent,
  real-world Rust LSS implementation -- a unique match, not a guess:
  MSB to LSB, `state[3], state[2], state[0], ~pulse, Q7, Q6, latch[7],
  state[1]`. Confirmed two ways before trusting it: the underlying ROM
  byte *values* are an exact multiset match against a2kit's decoded
  table (genuinely the same ROM, differently addressed), and a
  2000-pulse randomized trajectory comparison plus separate checks of
  all four Q6/Q7 modes (including write-protect sense correctly
  yielding `0xFF`) match a parallel Python reference implementing the
  same corrected formula exactly, tick for tick. That was this class's
  own standalone verification; `Disk2Controller`'s entry above
  describes the separate, later end-to-end pass once it was actually
  wired in.
- **`WozDiskImage`** — parses a real WOZ2 disk image file: header, INFO,
  TMAP, and TRKS chunks, exposing genuine bit-level per-quarter-track
  access via `TrackBitStream`. Deliberately scoped to 5.25-inch disks
  only -- the only kind a real Disk II drive reads -- so 3.5-inch
  disks' different TMAP layout and the optional FLUX/WRIT chunks aren't
  implemented at all. Handles two easy-to-miss real details precisely:
  the bitstream is genuinely bit-level (a track's bit count is almost
  never a multiple of 8, confirmed by testing against hand-constructed
  24-bit and 11-bit patterns, not just byte-aligned ones), and bit order
  within each stored byte is high to low, per the spec's own wording.
  CRC32 verification reuses `java.util.zip.CRC32` (the same standard
  algorithm `RomChecksum` already uses elsewhere in this project, not a
  hand-ported version of the spec's own C table) -- confirmed
  independently against the official CRC-32 test vector
  (`CRC32("123456789") = 0xCBF43926`), not just checked for
  self-consistency against this project's own test file. Unmapped
  (0xFF) quarter-tracks return the spec's own recommended 51,200-bit
  length but filled with zeros rather than the spec's randomized
  "weak bits" -- a real, stated simplification, not a hidden one.
  Verified against a hand-constructed, spec-compliant synthetic WOZ
  file (a real WOZ test corpus exists but is hosted outside this
  environment's network access) covering exact bit round-tripping,
  wraparound at a track's true bit count, `seekTo`, corruption
  detection, and bad-header detection.
- **`TrackBitStream`** — extracted out of `WozDiskImage` into its own
  top-level class specifically so `DskDiskImage` (below) can produce
  the identical type -- `Disk2LogicSequencer` reads either through the
  exact same interface, genuinely unaware of which format the bits
  came from.
- **`DiskImage`** — the small interface (`isWriteProtected`,
  `trackAt`) both `WozDiskImage` and `DskDiskImage` implement, letting
  `Disk2Controller.Drive` hold either without knowing which.
- **`DskDiskImage`** — reads a DOS-order sector image (.dsk/.do, 35
  tracks x 16 sectors x 256 bytes) and synthesizes the real,
  historical 6-and-2 GCR bitstream each track would actually carry on
  physical media -- address fields, data fields, checksums, and the
  64-entry 6-and-2 translate table, ported directly from a2kit's own
  verified Rust implementation (itself a port of CiderPress's C++),
  not reimplemented from a written description. Applies the real,
  documented DOS 3.3 sector skew (`0,7,14,6,13,5,12,4,11,3,10,2,9,1,8,15`
  -- physical position to logical sector) when placing each sector's
  data, and writes each physical position's own address field with
  its own physical sector number, since the skew only ever affects
  which data lands where, never how the address fields are numbered.
  `Drive.insert` dispatches to this class instead of `WozDiskImage` by
  file extension (`.dsk`/`.do` versus everything else). Deliberately
  scoped to standard 16-sector DOS 3.3 media only -- no 13-sector DOS
  3.2/3.1 support, no ProDOS-order (`.po`, a different +2 skew), and no
  copy-protection-specific variations. A .dsk file has no
  write-protect flag at all, so `isWriteProtected` always returns
  `false`. Verified two ways: encoding known sector data (random,
  all-zero, all-ones) and decoding it back with a reference decoder
  independently ported from a2kit's own `decode_sector_62_256` and
  `decode_44` confirms an exact round trip -- the strongest check
  available without a real, legally-sourced DOS 3.3 image, since
  encoder and decoder are independently-implemented halves of the same
  real algorithm rather than the same code checking itself; and
  driving a real `.dsk`-backed `Drive` through `Disk2Controller`'s own
  `tick()` end to end shows the LSS genuinely finding synced,
  full-byte latch values from the synthesized stream, not just that
  the encoder's output looks plausible in isolation. A dedicated test
  also confirms the full 16-sector skew lands correctly, not just that
  one sector round-trips: every logical sector's data is independently
  verified to appear at its real, documented physical position.
- **`VideoSoftSwitches`** — the `$C050`-`$C05F` video mode switches
  (text/graphics, full/mixed, page1/page2, lo/hi-res, and the four
  annunciators) are a complete implementation; there is no equivalent
  deferred half, since none of these addresses expose data real software
  reads back.
- **`SpeakerToggle`** — the `$C030`-`$C03F` speaker toggle is a complete
  implementation: a single flip-flop, any access anywhere in the
  16-byte window flips it (unlike `VideoSoftSwitches`' eight distinct
  per-offset flags, there's only one flag here, since there's only one
  speaker). Real audio synthesis -- turning access timing into an
  actual waveform -- has nothing to consume it yet.
- **`KeyboardRegister`** — `$C000` (last key pressed) and `$C010`-`$C01F`
  (clear the strobe) are a complete, verified implementation, including
  the easy-to-miss detail that the key's ASCII value persists after the
  strobe clears (only bit 7 changes). Deliberately doesn't model live
  "any key down" status on `$C010`'s own return value -- that's real
  IIe-and-later behavior, but multiple sources (including a filed
  hardware-behavior bug report against a well-known emulator) treat it
  as inconsistent to nonexistent on the original II/II+ this project
  targets. Deliberately doesn't model autorepeat either -- a
  timing-dependent behavior with no honest way to model it without a
  driving clock behind it. Has no dependency on any UI toolkit at all;
  `keyPressed(int)` is how a real input source, whatever it ends up
  being, feeds this class -- reachable via `MotherboardBus.keyboardRegister()`.
- **`VideoScanner`** computes which memory address the video circuitry
  is fetching at any point in the frame -- the specific piece
  floating-bus emulation needs, not a full pixel renderer. A faithful
  port of AppleWin's own `VideoGetScannerAddress` bit-level counter
  model (citing Jim Sather's *Understanding the Apple IIe*), not a
  row/column approximation -- reproducing a mature emulator's verified
  logic directly proved more trustworthy here than re-deriving it
  independently, especially the real hardware's genuine vertical
  counter stutter (8 bits reaching 262 lines by repeating its own last
  6 states, not counting linearly). This single formula covers
  horizontal blanking, vertical blanking, and mixed mode correctly with
  no special-casing at all -- real hardware never stops addressing
  memory just because nothing is currently visible, and neither does
  this class; it never throws. Verified against the same text/lores and
  hi-res reference points as before (row 0, 1, 8, 64), plus confirmed
  to run exception-free across a complete 17,030-cycle frame in both
  text and mixed modes. Ticked via `SystemClock.addCycleListener`, the
  same mechanism `PaddleTimers` uses.
- **`FloatingBus`** combines `VideoScanner`'s address with a real RAM
  read at that address -- `VideoScanner` only answers "which address,"
  this answers "what byte is actually there." Wired into every "empty
  slot" or "nothing latched" fallback (`SlotIoHandler`,
  `SlotZeroIoHandler`, `SlotRomHandler`, `ExpansionRomArbiter`), all of
  which used to throw a named gap and now return a genuine,
  scanner-driven value instead. A write to any of these same addresses
  is a silent no-op, matching real hardware -- nothing is listening, so
  nothing happens.
- **`PaddleTimers`** — `$C064`-`$C067` (read) and `$C070`-`$C07F`
  (trigger) model the real hardware faithfully: four independent RC
  one-shot countdowns sharing one strobe line, each non-retriggerable
  while still running. That non-retriggerable detail isn't a minor
  nuance -- it's *why* real software reading a second paddle right
  after a first gets a skewed value (both timers started from the same
  strobe, so time spent polling the first eats into the second's
  countdown), and this project's implementation reproduces that quirk
  because the underlying structure is right, not because it's
  special-cased. Ticked via `SystemClock.addCycleListener`, which
  exists specifically because this needed it. The 0-255-to-cycles
  conversion is calibrated to 2816 cycles full-scale -- not a generic
  RC-formula guess (which would give a noticeably different ~3700 and
  cause a full-scale read to wrap early), but the real, precisely
  documented figure tied to the standard ROM `PREAD` routine's own
  256-iteration, 11-cycle-per-iteration counting loop. Confirmed, not
  just cited: assembling and running that actual historical ROM
  routine (real disassembled bytes) against this implementation
  produces an *exact* match between the position set and the value the
  real routine computes, across the full 0-255 range.
- **`LanguageCard`** — a genuine expansion card occupying slot 0 (real,
  physical, and electrically special: no `$Cn00`-`$CnFF` ROM window,
  but the only slot that can bank-switch `$D000`-`$FFFF`, via
  `SlotCard`'s `wantsSlotZeroBanking` hook). The `$C080`-`$C08F` control
  switches and the full `$D000`-`$FFFF` banked RAM path (two independent
  4K banks plus a single 8K bank, the real two-consecutive-qualifying-
  reads write-enable state machine) are fully implemented and real RAM
  read/write works end-to-end. An empty slot 0 fails the same way an
  empty slot 1-7 does, and `MotherboardBus` dispatches to whatever
  actually occupies `slots[0]` rather than assuming any specific card
  is there.
- **`SystemRom`** — the Apple II+'s own motherboard ROM at
  `$D000`-`$FFFF` (Applesoft BASIC and the Autostart Monitor), verified
  chip-by-chip against MAME's own source. This is what shows through
  when nothing overrides it -- an empty slot 0, or any slot-0 card
  reporting it isn't intercepting a given address. `LanguageCard` itself
  never references this class: it has no business knowing the Apple
  II+'s own ROM contents just to say "not me" (see `SlotCard`'s
  `readSlotZeroBank`) -- that fallback is `SlotZeroBankingHandler`'s
  job, motherboard-level dispatch, not the card's.
- **`CharacterRom`** — the Apple II+'s character generator ROM (Apple
  part 341-0036, a repurposed general-purpose Signetics 2513 chargen
  chip). Addressing (`code * 8 + row`) and the crucial masking detail
  (the fetched byte's 8th bit is genuine noise from this chip's use in
  other, unrelated systems, not part of the actual glyph) both
  confirmed directly against MAME's own working source for the
  original II/II+ code path specifically -- a different branch from how
  IIe/IIgs handle the same chip. Deliberately does not apply
  inverse/flash inversion itself -- that's a real display-mode decision
  a future renderer makes, not a fact about ROM contents, the same
  separation `VideoSoftSwitches` already keeps between reporting mode
  state and deciding how to render it. Verified by literally printing
  the resulting glyph as ASCII art and confirming it's a recognizable
  letter, not just passing numeric assertions.
- **`SystemClock`** drives the CPU with exact cycle accounting.
  `addCycleListener` exists now because it has a real, concrete
  consumer (`PaddleTimers`' RC countdowns) -- still deliberately no
  real-time throttling (a genuinely separate concern from
  cycle-accurate execution, left to whatever drives this class). It
  does not attempt to interleave execution with
  video at the sub-instruction, alternating-half-cycle level real
  hardware uses to share RAM -- that would mean rewriting the CPU
  core's opcode executors into per-cycle micro-steps for no
  software-visible benefit, since that scheme exists purely so video
  and CPU never electrically contend for the same RAM cell, which is
  invisible to software either way. Verified to change nothing about
  CPU behavior: driving the full Klaus2m5 functional test through
  `SystemClock` traps at the identical address after the identical
  step and cycle counts as driving the CPU directly.
- **`Apple2Plus`, `ScreenPanel`, `KeyboardInputListener`, and `DiskMenu`**
  together are the real application. `Apple2Plus` populates slots
  through this project's own existing `SlotCardLoader`/INI mechanism
  (`--config slots.ini`, plus an optional `--plugins DIR`) rather than
  a parallel, application-specific one -- an earlier version of this
  class accepted disk paths as direct command-line arguments, bypassing
  a config system that already existed and already handled this
  correctly; that version was replaced with this one rather than kept
  alongside it. With no `--config` given, slots stay entirely empty and
  boot behaves exactly as it did before disk support existed. A
  `Disk2Controller`, wherever the config file places it (not assumed to
  be slot 6, though that's the real, conventional choice), is found by
  scanning the populated slots after loading, so its `tick` can be
  registered with `SystemClock.addCycleListener`. Never populating a
  disk slot with no disk actually configured is necessary, not a
  simplification: confirmed directly by driving the real boot sequence,
  a `Disk2Controller` present with no disk inserted causes the real,
  unmodified `$FFFC` Autostart ROM to recognize the real Disk II boot
  ROM signature and hang forever waiting for sync bytes an all-zero
  pulse stream will never produce -- the "APPLE ][" banner prints, but
  the `]` prompt never appears, confirmed by comparing against the
  working no-card case side by side. `SlotCardLoader`'s own existing
  behavior (an unconfigured slot stays `null`) already provides this
  safety property without `Apple2Plus` needing anything special of its
  own. A bad config file, unknown card type, or disk-load failure is
  caught and reported clearly (exit code 1, no stack trace) rather than
  crashing during Swing construction. When a `Disk2Controller` is
  found, `DiskMenu` adds a "Disk" menu for swapping either drive's
  media while the emulator runs -- calling the exact same
  `RemovableMediaDrive.insert`/`eject` methods `configure` itself uses
  at startup, matching `SlotCardLoader`'s own documented point that
  boot-time loading and live swapping are the same operation, not two.
  With no disk card present, the menu is simply not added, since
  there's nothing it could operate on. When a `Disk2Controller` is
  present with drive 1 (the boot drive) empty -- exactly the
  configuration that would otherwise hang silently -- a dialog explains
  why and prompts for a disk before emulation starts, reusing
  `DiskMenu`'s own insert logic rather than a second copy of it.
  Dismissing the prompt without choosing a file still starts emulation;
  this is a courtesy, not an enforced requirement, and the same hang
  remains possible -- but the user was actually given the chance to
  avoid it. Confirmed directly, not just assumed: during the hang, the
  CPU is genuinely still executing (a real software polling loop
  waiting for sync bytes, not an actual freeze), and cycles continue
  advancing normally after a disk is inserted mid-hang -- so the
  prompt's own claim that a disk can still be inserted later from the
  Disk menu, even after emulation has already started without one, is
  verified, not just asserted. A full successful boot from that
  mid-hang insert remains unconfirmed, though, since no real bootable
  disk image was available to test with -- only a hand-constructed
  synthetic one. `ScreenPanel` is pure Swing glue
  around `TextScreenRenderer` -- it owns no rendering logic of its own,
  just painting the boolean grid that class produces, scaled up 3x from
  the real 280x192 display. Flash state belongs to `Apple2Plus`'s own
  timer loop, not the panel, for the same real-time-vs-cycle-accurate
  reason `SystemClock` excludes wall-clock pacing from itself.
  `KeyboardInputListener` is equally thin around `KeyboardMapper` -- no
  mapping logic lives in the listener itself. `Apple2Plus` decides
  pacing (`SystemClock` deliberately has no opinion on that) and
  catches a runtime exception from emulation cleanly, stopping rather
  than crashing the Swing event thread. Not independently visually
  verifiable in this environment (a genuinely headless sandbox) --
  verified instead by confirming construction reaches real window
  creation with no exception across six startup scenarios (no config,
  a working disk config with the card in its conventional slot, the
  same config with the card in a different slot entirely, a bad card
  type, a missing config file, and an unrecognized flag), that the full
  real `--config` pipeline (not a hand-built substitute) runs stably
  for 10 million cycles (~10 seconds of real Apple II time) with a disk
  actually ticking alongside the CPU, and that `DiskMenu`'s own
  structure and its eject action are correct when exercised directly
  (a real file-chooser or message dialog can't be automated in this
  headless environment, so the insert path's own dialog interaction,
  and the boot-disk prompt's execution specifically, remain genuinely
  untested here -- both `JFrame` construction and any dialog shown
  after it fail identically in this sandbox, since neither can reach a
  real display).

#### Deliberately not implemented

- **Swappable ROM images.** `SystemRom` and `CharacterRom` both hardcode
  one specific classpath resource and a fixed checksum against the
  stock Apple II+ ROM contents -- appropriate for catching corruption
  of the real thing, wrong for anyone wanting to load a real,
  historically common hobbyist modification (an alternate F8 ROM, a
  custom character set). Needs an external override path (config-file
  driven, matching `SlotCardLoader`'s existing pattern) that skips the
  stock checksum in favor of a basic size sanity check when a
  substitution is actually requested.
- **Lowercase keyboard input plus a matching display.** `KeyboardRegister`
  already passes lowercase ASCII through today with no changes needed;
  the real gap is display -- the stock `CharacterRom` has no lowercase
  glyphs at all, and real hardware needed either an 80-column card or a
  software "soft-70" hi-res-based rendering trick to show them. This
  depends on real video rendering existing at all, not on any small
  addition to what's already built.
- **The genuinely chip-unstable illegal opcodes** (`ANE`/`XAA`, `LXA`,
  `SHA`, `SHX`, `SHY`, `TAS`) throw `UnsupportedOperationException` rather
  than encode a guess. Different real NMOS chips disagree with each other on
  these — some behaviors are documented as temperature-dependent on a
  *single* chip — so there is no canonical behavior to be faithful to.
- **A multi-latch expansion-ROM bus conflict** (`$C800`-`$CFFF`, when more
  than one card's expansion-ROM latch is set at once) throws rather than
  fabricating a result. Real hardware has no motherboard-level arbitration
  here at all — only a per-card latch — and more than one latch being set
  is a genuine, historically-documented electrical failure mode, not
  something with a single correct answer.
- **`IntegerBasicFirmwareCard`** doesn't exist as a class at all yet,
  though its ROM data does (`IntegerBasicFirmwareCardRom`, verified
  against Apple's own 1981 Level II Service Manual and MAME's source) --
  a genuinely different, simpler card from `LanguageCard`: a fixed,
  non-bank-switched ROM set selected by a plain two-address toggle, not
  bank-switched RAM. Also occupies slot 0, mutually exclusive with
  `LanguageCard`.
- **General/keyboard soft switches** (`$C020`-`$C02F`, `$C040`-`$C04F` --
  the speaker toggle at `$C030`-`$C03F`, the keyboard register at
  `$C000`-`$C01F`, and the paddle timers at `$C060`-`$C07F` are all
  done, see above) hasn't been designed yet at all. `$C061`-`$C063`
  (joystick/paddle pushbuttons) is its own separate, still-undesigned
  gap even though it shares a 16-byte block with the now-working
  paddle reads.
- **Writing to disk, at any level.** `Disk2LogicSequencer` correctly
  simulates write-mode nibble shifting into its in-memory latch (the
  real `SL0`/`SL1`/`LD` actions), but nothing persists that data
  anywhere -- `TrackBitStream` has no write method, and neither
  `WozDiskImage` nor `DskDiskImage` can write a file. A WOZ file also
  has no way to be write-protected *by this project*: the flag is only
  ever read from a file some other tool already set, since there's no
  writer here to set it. Building this for real means a `TrackBitStream`
  write path, a WOZ file writer (chunks, recomputed CRC32), and either
  a DSK sector-level writer (simpler, but loses any bit-level
  fidelity the disk originally had) or the much harder inverse of
  `DskDiskImage`'s own encoder -- decoding a real GCR bitstream back
  into sectors, checksums and all.
- **Blank, freshly-formatted disk creation.** Depends on the same
  writer infrastructure as the item above, plus a real DOS 3.3/ProDOS
  volume table of contents -- an empty WOZ or DSK shell with no
  filesystem structure at all isn't a blank disk DOS or ProDOS could
  actually use.
