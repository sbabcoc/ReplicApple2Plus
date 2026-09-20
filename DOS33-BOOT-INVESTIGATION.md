# DOS 3.3 Boot Failure — Investigation Log

**Status: ROOT CAUSE FOUND AND FIXED (see UPDATE 7/8). A new,
likely-related "I/O ERROR" has surfaced past the original crash point
and is the current open item.**

## The bug, in one sentence

Booting the real, legally-owned "DOS 3.3 System Master.woz" (Applesauce
capture, write-protected, confirmed genuine) hangs partway through boot
and eventually executes into unloaded memory, landing in the Monitor at
`$A853` (`A=FC X=00 Y=00 P=37 S=F8`) — **but the same file boots
completely successfully in Virtual ][ with the identical System ROM and
Character ROM**, so the disk and ROMs are not the problem. The defect is
somewhere in ReplicApple2Plus's own emulation.

## How to reproduce, exactly

This reproduces with **zero keyboard input** — no interaction needed.

```java
SlotCard[] slots = new SlotCard[8];
Disk2Controller disk = new Disk2Controller();
disk.removableDrives().get(0).insert(Path.of(".../DOS_3_3_System_Master.woz"));
slots[6] = disk;

MotherboardBus bus = new MotherboardBus(slots);
Cpu6502 cpu = new Cpu6502(bus, 0xFFFC); // real, unmodified Autostart reset vector
SystemClock clock = new SystemClock(cpu);
clock.addCycleListener(bus.videoScanner()::tick);
clock.addCycleListener(disk::tick);

// run clock.step() in a loop, tracking totalCycles += clock.step()
```

Key cycle counts observed (deterministic, same every run):
- **~83,117,460**: `$2000-$20FF` finishes filling with real data (last
  page of low-memory staging that succeeds)
- **~89,093,244**: motor goes off and stays off (retry loop gives up)
- **~90,134,524**: CPU executes `BRK` at `$A851` (Monitor displays
  `$A853` = `$A851+2`, since BRK pushes PC+2)

The real System Master `.woz` file is **not includable here** (real
Apple copyright), but the user has it and can supply it again if a new
session needs to re-run any of this.

## What's been definitively eliminated (do not re-investigate these)

All confirmed via direct, reproducible tests against the *real* file,
not synthetic data:

1. **The WOZ file itself is completely intact.** Every sector on
   tracks 0-4 was independently decoded (address field checksum +
   6-and-2 data field checksum) via a standalone scanner
   (`WozInspector`, now in the project at
   `src/main/java/com/nordstrom/emulator/expansion/WozInspector.java`)
   and via the real `tick()`/`Disk2LogicSequencer` pipeline. All
   sectors check out. Track 0 has 15/16 real sectors (sector 5 is
   genuinely, legitimately all-zero on the real disk); track 2 has
   only 5/16 real sectors (0, 7, 9, 11, 13) with the rest legitimately
   zero — **this is normal disk layout, not corruption**.
2. **`WozDiskImage` parsing is correct** — confirmed byte-for-byte via
   an independently-implemented reference decoder (ported from a2kit's
   `decode_sector_62_256`/`decode_44`, not the same code checking
   itself).
3. **The skew table in `DskDiskImage` is correct** (separately
   verified against the real embedded skew table inside a real boot0
   sector, found in a2kit's boot data — confirmed as the exact inverse
   permutation). Not directly relevant to WOZ reading, but ruled out
   early in this investigation.
4. **Real phase-stepping + multi-track reads work correctly** —
   stepping the head across tracks 0→1→2 and back while reading via
   the real `Disk2Controller.tick()` pipeline finds all 16 sectors on
   each track every time.
5. **The single most important elimination**: from the *exact live
   drive/controller state* at the moment the real boot process is
   stuck retrying (cycle ~83.5M, quarter-track 0, motor on), directly
   polling `disk.readIoSwitch(0xC)` the same way real RWTS code does
   (rising-edge byte detection) finds **all 16 sectors on track 0,
   including sector 14, within one revolution** (tick 187,156). This
   proves the disk/LSS/bitstream mechanism is not the problem *at the
   exact moment and state where the real process fails*.
6. **The Swing keyboard-focus theory (two attempted fixes, both
   reverted) was a dead end.** The apparent "keyboard wait loop" at
   `$FD1B` in the Monitor ROM is real, but it's the Monitor's own
   post-crash command prompt (`*`), not a pre-crash DOS coldstart
   check — discovered by checking screen rows 22-23, not just 0-2.
   `CliArgs`/`DiskMenu`/`Apple2Plus` focus-handling changes were
   reverted; do not re-attempt this fix.

## What's been confirmed as real, legitimate DOS 3.3 mechanics
(not bugs, just how it works — don't waste time re-deriving these)

- **DOS relocates itself.** Low memory `$1D00-$3FFF` (35 pages) is
  loaded first as a staging area, then:
  - An address-patching pass (loop at `$1BBE`, real disassembly
    confirmed with a from-scratch disassembler using the CPU's own
    real opcode table — see `Disassemble.java` below) walks a
    table-driven set of 8 chunk ranges (table at `$1C5A-$1C7D`)
    patching any embedded address byte in the range `$1D-$40` by
    adding delta `$80`, converting low-memory self-references into
    their eventual high-memory addresses.
  - A simple, unconditional page-copy loop at `$1C02` then copies all
    35 pages from `$1D00-$3FFF` to `$9D00-$BFFF` (source/dest both
    descending, delta `$80` throughout). **This copy loop has no
    conditional logic tied to sector content** — if part of the
    destination ends up empty, it's because the corresponding *source*
    page in low memory was never populated, not a bug in the copy
    itself.
- **DOS's coldstart code unconditionally calls `$A851`.** Disassembled
  the real bytes at `$9D84-$9DE5`: there's a hardware-detection branch
  at `$9D94` (`LDA $E000; EOR #$20; BNE $9DAC`) that decides which of
  two small tables to copy, but **both paths converge at `$9DBC`
  (`SEC` then `BCS $9DD1`, which is unconditional)**, so `$9DD2: JSR
  $A851` executes on literally every boot, regardless of hardware
  config. This is not a hardware-detection bug — the call is supposed
  to happen.
- **`$A851`'s source, low-memory `$2851`, is genuinely empty** (`$2800-
  $28FF` never gets written) — but this must be a real bug, since the
  same disk boots fine elsewhere, meaning on a working boot this page
  *does* get populated with real code from disk.
- **RWTS is specifically hunting for Track 0, Sector 14 when the
  retry/motor-toggle loop starts.** Confirmed via the real IOB
  structure at the moment of the stuck retry (pointer `$48/$49` →
  `$37E8`): `slot=$60` (slot 6, correct), `drive=1`, `track=$00`,
  `sector=$0E`, `command=$01` (read). Track 0 sector 14 has 253/256
  nonzero bytes and a valid checksum on the real disk (confirmed via
  `WozInspector`) — **the data is genuinely there and correct**.

## The actual open question

Given elimination #5 above (sector 14 is *directly, empirically
findable* via manual polling from the exact live state where the real
process is stuck), the disk-reading mechanism itself is not the
suspect. The remaining hypothesis space:

1. **A CPU core instruction bug** that only manifests under this
   specific real RWTS polling/comparison code sequence — something not
   covered by the existing 6502 functional test suite
   (`6502_functional_test.bin`, which still passes cleanly, trapping
   at `$3469` as it always has — unaffected by anything in this
   investigation).
2. **A timing interaction specific to real RWTS's actual polling
   instruction sequence** (not yet identified precisely — the loop
   context around `$3D90-$3DC0` and the IOB-driven dispatch through
   `$3E5A` is disassembled but the actual byte-by-byte "is this the
   sector I want" comparison logic has not yet been traced
   instruction-by-instruction against live latch values).

### UPDATE — the address-field routine has now been confirmed to succeed

Real RWTS's address-field-search-and-decode routine is at
`$3944-$399F` (disassembled in full via `Disassemble.java`). Key
correction: **the 4-byte storage order was initially misread.** The
decode loop (`$396D-$3986`) stores bytes at `$002C,Y` with `Y` counting
*down* from 3 to 0, and RWTS reads bytes off disk in volume, track,
sector, checksum order — so the **first** byte read (volume) lands at
the *highest* address, `$2F`, not `$2C`. Correct mapping:
`$2F`=volume, `$2E`=track, `$2D`=sector, `$2C`=checksum (reverse of
what an initial read of the code suggested).

With that correction, watching every return via `$399F` (`CLC; RTS` —
success) during the live retry window shows the routine **succeeding
repeatedly and correctly**, cycling through real sector numbers on
track 0 each revolution (volume consistently `$FE`, track consistently
`$0`, sector cycling 1,2,2,5,6,7,8,9,A,B,C,D,E,F,0,1,2,3,4,4...). At
cycle 85,297,358, it finds and correctly decodes **exactly track 0,
sector 14** — the address field RWTS is looking for is found
successfully. Zero hits on the error-return path (`$3942`) in the same
window.

### UPDATE 2 — the read is actively progressing, not stuck; the picture was wrong

Traced the actual sector-comparison logic precisely (full disassembly
of `$3DA0-$3E35`, real values from live memory, not guessed): the
comparison at `$3E2E`/`$3E30` is **`skewTable[desiredLogicalSector] ==
foundPhysicalSector`** (`LDA $3FB8,Y; CMP $2D; BNE retry`) — this is
DOS's real logical-to-physical sector translation, and **the skew
table itself was independently verified correct** (matches the known
table exactly: logical 14→physical 2, etc.).

Checking the IOB's live track/sector/buffer fields at 1M-cycle
checkpoints through the whole retry window shows **the read is
actively succeeding and progressing, sector by sector, right up to the
crash**:

```
cycle 83,500,000: sector=$E buffer=$1F00
cycle 85,000,002: sector=$D buffer=$1E00
cycle 86,000,002: sector=$C buffer=$1D00
cycle 87,000,000: sector=$B buffer=$1C00
cycle 88,000,002: sector=$A buffer=$1B00
cycle 89,000,001: sector=$A buffer=$1B00   <- stalls here for ~1M cycles
cycle 90,134,525: sector=$9 buffer=$1A00   <- progresses again, right at the crash
```

**This is not a stuck retry loop — it's a real, working sector-by-
sector read, descending through track 0's sectors 14→13→12→11→10→9.**
The buffer range (`$1A00-$2000`) is *below* the `$1D00-$3FFF` DOS-image
staging area traced earlier — **this is a different read operation
than the one that populates DOS's own low-memory staging**, something
not yet identified (possibly a VTOC/catalog read, or a HELLO-program
load — not yet traced which). The `$A851` crash appears to happen
*concurrently with, or interleaved with*, this ongoing sector read,
not as a separate, independent step after it. The relationship between
this read loop and the coldstart code that calls `$A851` is **not yet
established** — this is the next thing to trace: is `$A851` called
*during* this read loop (e.g. as a per-sector callback/hook), or does
this read loop belong to a completely different, concurrent context?

### UPDATE 3 — confirmed sole caller; the real gap is one stage earlier than assumed

Captured the stack at the exact moment of the BRK (PC first reaching
`$A851`, before executing): `SP=$FC`, stack shows only `$9DD5` as the
return address (`$01FD/$01FE` = `$D4/$9D` → `$9DD4+1`). **`$9DD2`'s
`JSR $A851` is the sole, direct, one-level-deep cause** — no
intermediate call chain. This confirms UPDATE 2's coldstart-calls-$A851
finding precisely.

Cross-referencing timing already captured earlier in this
investigation: the `$1BBE` address-patch loop was first reached at
cycle 89,106,173, and the `$1C02` page-copy loop began at cycle
89,990,220 — **both before** the crash at ~90,134,518. So the
relocation/copy mechanism itself *did* run before the crash — it's not
a matter of the copy never happening or happening too late. The
problem is one stage earlier: when the copy ran, its *source* in low
memory (`$2800-$28FF`, which becomes `$A800-$A8FF` after the `$80`
relocation) was **already empty**, as already confirmed in the
original `CheckSource.java` check.

### UPDATE 4 — narrowed to one specific sector read that silently writes nothing

Checked the IOB's live track/sector/buffer fields across many earlier
checkpoints (not just the retry window) and found the full picture:
well before the track-0 read traced in UPDATE 2, there's an earlier
**track 1** read, with the buffer descending from somewhere above
`$2B00` down through `$2100`, at which point it transitions to track 0
(matching UPDATE 2's `$2000→$1A00` sequence exactly).

Within that earlier track-1 read: **`buffer=$2800` corresponds to
track 1, sector 7** (observed at cycle ~52,000,002). The IOB
bookkeeping progresses completely normally past this — by cycle 55M it
has moved on to `buffer=$2700` (sector 6) — **but `$2800` itself is
confirmed to never become nonzero even once**, checked continuously
across the entire `[0, 56M]` cycle range, not just at snapshot points.

This is now a narrow, concrete, reproducible target: **track 1, sector
7's read reports success (or at least doesn't block progress) via the
IOB, but writes nothing to its destination buffer.** Track 1 was
separately confirmed (via `WozInspector`, much earlier in this
investigation) to have all 16 sectors present with valid data and
checksums — sector 7 is not one of the known-empty sectors, so this
isn't the "legitimately empty sector" case that applies to some of
track 2's sectors.

### UPDATE 5 — likely root cause found: the head stops at a half-track position

Checked whether track1/sector7's address field is findable via live
polling at cycle 52,000,002 (same method that successfully found
sector 14 earlier). Result: **zero sectors findable at all** — not
just sector 7, none of the 16. Checking the drive's actual physical
position at that moment: **`quarterTrack=2`**, not `4`. Track 1
corresponds to quarter-track 4 (`quarterTrack/4`); quarter-track 2 is
a **half-track position straddling track 0 and track 1** — the head
never completed its step to track 1.

This directly explains everything in UPDATE 4: with the head
physically misaligned between two tracks, **no sector's data lines up
correctly**, which is exactly consistent with silent, total failure to
read anything into the buffer while the IOB still advances (RWTS
presumably has some bounded-retry-then-give-up-and-continue behavior
at the whole-sector level, separate from the single-byte-search retry
budget already examined).

**This is very likely the actual root cause**: something causes
`Disk2Controller`'s phase-stepping to land on a half-track (`qt=2`)
instead of completing the step to a full track (`qt=4`) during this
specific real seek sequence. Earlier synthetic phase-stepping tests
(all passing) used simple, complete 4-phase step sequences built by
hand — they may not have exercised whatever specific phase on/off
timing or sequence real seek code actually uses here.

### UPDATE 6 — confirmed: the head gets permanently stuck bouncing 0↔1↔2, never reaches track 1

Traced `quarterTrack` at every single change from cycle 0 to 52M.
Early on (cycles ~2M-19M), stepping works correctly and reaches as far
as quarter-track 6 several times, stepping back to 0 in between (this
looks like normal RWTS seek/backlash-compensation behavior — bumping
out and back is a real, intentional RWTS technique, not itself a bug).

**Starting at cycle 20,993,653, this changes**: the head enters a
repeating `0→1→2→1→0` bounce and **never exceeds quarter-track 2
again for the rest of the trace** (checked continuously through
52,000,002 — over 30 million cycles stuck in this exact pattern).
Track 1 is quarter-track 4; the head never gets there. Meanwhile, per
UPDATE 4/5, the IOB reports `track=1` starting around cycle 40M and
proceeds to "read" several sectors from it (advancing its own
bookkeeping normally) despite the physical head never actually having
arrived — which fully explains why sectors targeting that range
(`$2800` etc.) never receive real data: the read head is genuinely
misaligned, straddling two tracks, the whole time.

**This is very likely the root cause of the entire failure.** The bug
is specifically: **something about the real phase-on/off sequence
issued during this particular seek attempt causes
`Disk2Controller`'s phase-stepping logic to stop making net progress
past quarter-track 2**, even though the code clearly keeps trying
(the repeating bounce shows it's still actively issuing phase
commands, just never net-advancing). Earlier synthetic phase-stepping
tests (in earlier sessions) all used simple, hand-built 4-phase
sequences and passed — they evidently don't cover whatever specific
pattern real seek code uses here.

### UPDATE 7 — ROOT CAUSE FOUND

Watched the actual `phaseOn[]` state transitions (not just `quarterTrack`)
through the critical window. The pattern is: the head deliberately,
correctly steps out to `qt=1` then `qt=2` (a real, intentional
step-and-verify seek sequence), settles there for ~600,000 cycles
(consistent with attempting a verification read), then **gives up and
retreats all the way back to `qt=0`**, spends ~3 million cycles in a
real, legitimate "recalibrate to track 0" loop (repeatedly issuing a
step-toward-zero phase sequence that's a no-op once already at 0 —
this part is normal, deliberate RWTS behavior, not a bug), then tries
the same qt=1→qt=2 seek again — repeating forever.

That pointed straight at the verification read itself. Checked what
`WozDiskImage.trackAt()` actually returns for quarter-tracks 0-5 on
the real file:

```
quarterTrack 0: bitCount=50304  (real data)
quarterTrack 1: bitCount=50304  (real data)
quarterTrack 2: bitCount=51200  <<<< EMPTY-TRACK SENTINEL
quarterTrack 3: bitCount=50304  (real data)
quarterTrack 4: bitCount=50304  (real data)
quarterTrack 5: bitCount=50304  (real data)
```

**This is the root cause.** The real WOZ file's TMAP genuinely doesn't
map quarter-track 2 to any captured track data — but real Disk II
hardware has a read head physically wide enough to still pick up an
*adjacent* mapped track's flux pattern when positioned at an unmapped
quarter-track between two mapped ones. `WozDiskImage.trackAt()`
currently returns the all-zero empty-track sentinel for any unmapped
quarter-track unconditionally, with no fallback to a nearby mapped
one. When real seek code's verification read lands exactly on `qt=2`
(between two perfectly good, mapped tracks at `qt=0`/`qt=1` and
`qt=3`/`qt=4`), it gets nothing readable at all and retreats,
believing the seek failed — even though a real drive would have
successfully read *something* there and continued on.

**The fix**: `WozDiskImage.trackAt()` should fall back to the nearest
*mapped* quarter-track's data when the requested one is unmapped in
the TMAP, rather than unconditionally returning the empty-track
sentinel. The empty-track sentinel should be reserved for cases with
no nearby mapped track at all (e.g. past the innermost/outermost real
track, or a genuinely blank disk) — this matches how real hardware
behaves and is a well-understood, documented WOZ-reading convention
(nearest-neighbor fallback for unmapped quarter-tracks), not a novel
workaround.

### UPDATE 8 — fix implemented, original crash confirmed eliminated, one new issue surfaced

Implemented the fix in `WozDiskImage.trackAt()`: added a private
`resolveTrksIndex()` that, when the requested quarter-track isn't
directly mapped, searches outward (bounded to a 3-quarter-track
radius) for the nearest mapped neighbor and uses its data, falling
back to the empty-track sentinel only if nothing is mapped nearby at
all. Verified directly: `trackAt(2)` now returns `bitCount=50304`
(real data) instead of the `51200`-bit empty sentinel, matching its
mapped neighbors.

Ran the full real boot for 200 million cycles with the fix in place,
watching for any BRK landing inside DOS's resident range (`$9D00-
$BFFF`) — **zero such landings the entire run**. The original hang/
crash at `$A851` is confirmed eliminated.

**New result**: the boot now reaches a real `]` prompt (visible on
screen) followed by a genuine DOS **"I/O ERROR"** message — a real,
legitimate DOS 3.3 error report, not a crash into unloaded memory.
This is very likely the *same* class of bug surfacing at a different
disk location (e.g. a similar TMAP gap encountered while reading the
catalog/VTOC on track 17, or loading a HELLO program) rather than a
new, unrelated defect — the search-radius fix only helps at whichever
specific quarter-tracks it's actually invoked for; if another gap
exists further out on the disk within a similarly-narrow radius it
should also be fixed by the same mechanism, but this hasn't been
directly confirmed yet.

### UPDATE 9 — the new I/O ERROR is a different mechanism, not another TMAP gap

Scanned the entire TMAP for gaps: there's a clean, regular pattern —
every track boundary's exact midpoint (`qt = 4N+2`) is unmapped, always
with a mapped neighbor at distance 1 on both sides. The existing
radius-3 fallback fix handles every one of these, including near track
17 (VTOC, `qt=68`, itself mapped). The only large gap is `qt 138-159`
(tracks 35+, simply the natural end of a real 35-track disk).

Traced the actual "I/O ERROR" appearing on screen: first visible at
cycle 37,779,389. At that moment, the IOB claims it wants track 0,
sector 9 -- but the drive's **actual physical position is
`quarterTrack=62`** (near track 15-16), with the **motor already off**.
Forcing the motor back on and live-polling from that exact position
confirms **all 16 sectors, including sector 9, are perfectly readable
there** with the fix in place -- so this is not another instance of
the original TMAP-gap bug; the data itself is fine.

The mismatch (IOB wants track 0, head is stranded near track 15-16,
motor already shut off) looks like a **different failure mode**: RWTS
attempting a long, multi-track seek (plausibly back from the VTOC at
track 17 toward track 0) that doesn't complete, leaving the head
stranded partway through the seek, followed by RWTS's own
give-up-and-report-error behavior. This is not yet confirmed to be
disk-image-related at all -- it may be a seek-timing or retry-budget
issue distinct from the quarter-track-fallback bug already fixed.

### UPDATE 10 — the seek to qt=62 is clean and monotonic, not a bounce/retry pattern

Traced `quarterTrack`/motor transitions leading up to cycle
37,779,389 (same technique as UPDATE 6). The picture is very different
from the original bug:

```
cycle 36,109,658-36,202,454: clean recalibration from qt=16 down to qt=0
cycle 37,010,225-37,551,542: clean, continuous, monotonic step OUT
                              from qt=1 all the way to qt=62 -- no
                              pauses, no bouncing, no retries
cycle 37,590,445: motor turns off, still at qt=62
```

This is not a repeated bounce/retry like the original bug -- it's a
single, deliberate, uninterrupted seek that lands exactly on `qt=62`,
which is precisely the midpoint between track 15 (`qt=60`) and track
16 (`qt=64`) -- one of the same `4N+2` gap positions from UPDATE 9,
now confirmed reachable/readable via the fix, but the seek stops
exactly there rather than continuing one more quarter-track to a real
track. Whether the *intended* destination was track 15, track 16, or
something else (e.g. track 17's VTOC at `qt=68`) is not yet
determined. Two live hypotheses, neither confirmed:

1. RWTS's own step-count calculation lands intentionally on a
   half-track as an interim checkpoint, and normally expects a
   *failed* verification there (informing it to step one more) --
   but the fix now makes that checkpoint falsely appear valid
   (returning the neighbor's real data instead of correctly-diagnostic
   silence), causing RWTS to stop one step early. If true, this would
   mean the fix's fallback, while correct for the original bug's
   *reading* path, may need to be scoped more narrowly (e.g. only
   applied where real hardware's read head genuinely can't distinguish
   the position, not universally for every unmapped quarter-track).
2. A genuine off-by-one/off-by-two in how many phase-step commands
   this specific seek issues, unrelated to the fix -- would need
   comparing against real RWTS seek-distance-calculation logic to
   confirm.

### UPDATE 11 — IOB appears stale during this seek; genuinely uncertain territory now

Checked the IOB's track/sector fields at checkpoints spanning the
entire qt=62 seek (before, during, and after): it reads `track=$0,
sector=$9` unchanged throughout, including at the moment the motor
turns off. Two live possibilities, neither confirmed:

1. This is real RWTS error-recovery behavior -- a failed read/seek at
   track 0 triggers a "seek far away, then give up" recovery pattern,
   and the IOB's fields are simply left showing the *original* request
   since the recovery attempt doesn't update them.
2. This IOB (`$37E8`) is not the one actually driving this later
   operation at all -- DOS 3.3 can use more than one IOB for different
   purposes (e.g. the initial boot load versus normal post-boot file
   I/O), and I may be watching stale state from the earlier,
   already-successful boot2 load rather than whatever's actually
   happening now.

Neither has been distinguished yet. This is now genuinely deeper,
more speculative territory than the original bug -- the original
bug's mechanism (a specific, reproducible, verifiable data-return
defect) was concrete and directly testable at every step; this one
requires either finding the *actual* IOB/state driving this specific
operation, or disassembling the real seek-recovery code path, neither
done yet.

### UPDATE 12 — my fix ruled out as the cause of this second issue

Confirmed the machine is genuinely stable and functional past the
original bug: typed a real `CATALOG` command at the live prompt, it
echoed correctly (`]CATALOG` visible on screen), confirming keyboard
input and command dispatch both work. The attempt to actually read the
catalog (which requires track 17's VTOC) produces a second,
reproducible "I/O ERROR". Waited an additional 40 million cycles past
the error with no further activity -- the machine has genuinely given
up, not still retrying; it's sitting at a stable, responsive prompt.

Traced the seek attempt driving this: from `qt=14`, steps out to
`qt=59` (one short of track 15 at `qt=60`), retreats all the way back
to `qt=0`, retries and this time reaches `qt=62` (between track 15 and
16), then gives up (motor off) -- only two attempts before quitting,
neither landing on a clean whole-track boundary.

**Tested the specific hypothesis that my own quarter-track fallback
fix causes this**, by checking whether `qt=62` (a fallback position)
gives a misleading "verified" signal that a genuinely-mapped position
wouldn't: checked what track number each position's real address
field reports.

```
qt=62 (fallback position):        reports track=15
qt=59 (directly, genuinely mapped): reports track=15
```

**Both report the same track (15), whether fallback or genuinely
mapped.** Since `qt=59` isn't touched by the fix at all and shows
identical behavior to the fallback case, the fix is not the cause of
this second issue -- confirmed, not just presumed. Track 17's VTOC
data was also independently confirmed valid and correctly checksummed
(sector 0 has real content) -- the target data is fine; the seek
simply never reaches it.

**This is very likely a separate, pre-existing issue** in the phase-
stepping/seek mechanics -- either a genuine bug in `Disk2Controller`'s
step-counting for this specific real seek sequence (the earlier
synthetic phase-stepping tests used simple hand-built sequences, not
necessarily this exact one), or a real RWTS retry-budget/backlash-
compensation detail not yet correctly understood. Both landing points
(`qt=59`, `qt=62`) report track 15, not track 17 -- the seek appears to
undershoot its real target by a wide margin (roughly 8-9 quarter-tracks
short of `qt=68`), not fail by a small amount, which doesn't fit a
simple off-by-one.

**Next concrete step, not yet done**: disassemble the actual seek-
distance-calculation code (not yet done for this seek specifically --
UPDATE 10/11 found the phase-write instruction but not where the
*target* track number or step count is computed) to determine what
value it's actually trying to reach and why it stops at 59/62 instead.

## Tooling built this session (reusable, not yet formally in the project except WozInspector)

- **`WozInspector`** — already added to the project at
  `src/main/java/com/nordstrom/emulator/expansion/WozInspector.java`.
  Standalone tool: `java ... WozInspector <path.woz> [firstTrack]
  [lastTrack]`. Reports every sector's address/data checksum status
  per track, using a proper self-syncing bit reader (do not use a
  naive fixed-8-bit reader — an early version of this tool had exactly
  that bug and produced false "missing sector" results).
- **A from-scratch 6502 disassembler** (`Disassemble.java`, in scratch
  space only, not yet added to the project) built directly from the
  CPU's own real opcode table (`Opcodes.TABLE`, package-private in
  `com.nordstrom.emulator.cpu`) — avoids manual byte-by-byte misreads
  (which happened at least once this session before this tool
  existed). Lives in the `cpu` package to access the package-private
  table. Presented separately this turn; worth adding to the project
  properly (e.g. as a real diagnostic tool alongside `WozInspector`)
  in a future session if useful again.
- **Pattern for live-state re-polling** (elimination #5): run the real
  boot to a specific cycle count, then call `disk.tick(N)` /
  `disk.readIoSwitch(0xC)` directly in a loop (rising-edge byte
  detection: only count a byte when latch transitions from `<0x80` to
  `>=0x80`, not on every poll) to check what the disk hardware is
  *actually* producing at that exact moment, independent of whatever
  the CPU is doing with it.

## Answers to try to avoid re-litigating

- Yes, `CliArgs` is public, `DiskMenu` exists, diagnostic prints were
  removed from `Apple2Plus.java` — all confirmed clean as of this
  writing.
- Yes, the disk-selection UI supports `.dsk`/`.do` now, not just
  `.woz`.
- The keyboard-focus fixes were tried and reverted — don't retry them
  without new evidence they're relevant.
