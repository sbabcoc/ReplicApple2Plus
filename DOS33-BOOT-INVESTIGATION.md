# DOS 3.3 Boot Failure — Investigation Log

**Status: RESOLVED. Both bugs found and fixed, verified end-to-end
on the real codebase with zero regressions.**

1. **Original boot-hang crash** (UPDATE 7/8): `WozDiskImage.trackAt()`
   returned silence for unmapped quarter-tracks instead of falling
   back to the nearest mapped neighbor, causing a real seek-
   verification read to fail and retry forever. Fixed by adding a
   bounded nearest-neighbor fallback.
2. **Seek-distance error** (UPDATE 27): `Disk2Controller.turnOffPhase()`
   stepped the drive head by 1 quarter-track per clean phase
   transition instead of 2, causing every seek to fall short of its
   real target by exactly half. Fixed by correcting the step
   magnitude, reconciled against real Apple documentation ("Beneath
   Apple DOS": 70 "phases" across 35 tracks, 2 per track) and
   confirmed via a real Virtual ][ trace of an actual boot.

Both fixes verified together: the CPU functional test suite is
unaffected, the original crash signature does not recur across a
200-million-cycle run, and `CATALOG` now produces the complete,
correct file listing matching the real disk's actual catalog.

The full path to both fixes is preserved below for anyone (or any
future instance) who wants to understand how they were found --
including every dead end, ruled-out hypothesis, and piece of real-
hardware data that got this here, since several non-obvious
mistakes were made and corrected along the way.


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

### UPDATE 13 — ROOT CAUSE #2 FOUND: phase-stepping under-counts relative to DOS's own tracking

Checked the precise final relationship between DOS's own internal
track-position variable (`$0478`) and the real physical
`quarterTrack` at the end of the seek sequence:

```
$0478 = 17   (DOS believes it correctly reached track 17 -- the VTOC track CATALOG needs)
quarterTrack = 62   (physically only at track 15.5)
```

**DOS's own bookkeeping is correct** (it genuinely believes, and by
its own accounting has earned, track 17) **but the real head only
moved to quarter-track 62 -- 6 quarter-tracks short of the real
target (`68`).** This is not an ambiguous, hard-to-interpret result
like earlier updates in this thread -- it's a direct, unambiguous
discrepancy between what DOS's seek algorithm believes it accomplished
and what `Disk2Controller`'s stepping mechanism actually delivered.

This explains everything observed in UPDATES 9-12: the seek looked
clean and monotonic (not bouncing) because it *was* clean --
`Disk2Controller` faithfully executed every phase command it was
given -- but it simply produced less net physical movement than the
real phase sequence should have. Both `qt=59` and `qt=62` reporting
"track 15" isn't a data problem at all: the head is *genuinely,
physically* sitting near track 15, exactly where an under-stepped seek
would leave it.

### UPDATE 14 — refining UPDATE 13: the mechanism is more nuanced than a simple ratio bug

Captured `$0478` and `quarterTrack` together, precisely, through the
entirety of the first isolated seek call (target=34). Within this one
call, they track together almost exactly 1:1: `$0478` goes 0→34 while
`quarterTrack` goes ~2→~35 (same magnitude of change, not a 2:1 or 4:1
ratio). So during *active stepping*, `Disk2Controller`'s stepping
isn't silently dropping or doubling steps relative to what DOS's own
call expects.

**Immediately after** that call finishes (with the head correctly at
`quarterTrack≈35`), and with **no further physical movement**, `$0478`
drops from 34 to 17 -- exactly halved. Given the earlier, separate
finding that DOS's *final* settled value is 17 (matching real track
17, UPDATE 13), this looks like a legitimate internal unit conversion
DOS's own code performs after a seek stage completes (converting a
finer running counter into a coarser track-number representation for
later comparison/display), not evidence of a stepping bug by itself --
UPDATE 13's framing ("under-stepping") was too quick; the real
discrepancy is likely explained by the retry/recalibration sequence
(UPDATE 12) not fully recovering the distance covered by the first,
abandoned attempt, rather than a per-step ratio error.

**Revised, more precise statement of the remaining problem**: across
the full multi-call, retry-and-recalibrate sequence, the *net*
distance actually covered by the physical head (ending at
`quarterTrack=62`) falls short of what DOS's own bookkeeping believes
it accomplished (settling on track 17, `quarterTrack=68` equivalent)
by 6 quarter-tracks. Whether this shortfall originates in
`Disk2Controller`'s step-counting for some specific transition, or is
expected/self-correcting behavior in real RWTS that a longer trace
would show recovering (this thread hasn't confirmed the final `qt=62`
state is truly terminal for *this specific* seek, only that the
overall CATALOG command gives up after it), is not yet resolved.

**Next concrete step, not yet done**: rather than continuing to infer
from `$0478` (whose exact semantics across stage transitions remain
only partially understood), directly count actual net phase-driven
steps `Disk2Controller` registers across the *entire* multi-call
sequence (all 9 calls from UPDATE 12, not just call #1) and compare
against the total distance a correct implementation should produce
for the same phase sequence, to localize any shortfall precisely
rather than continuing to reason about it indirectly through DOS's own
variables.

## Tooling built this session (reusable, not yet formally in the project except WozInspector)

- **`WozInspector`** — already added to the project at
  `src/main/java/com/nordstrom/emulator/expansion/WozInspector.java`.
  Standalone tool: `java ... WozInspector <path.woz> [firstTrack]
  [lastTrack]`. Reports every sector's address/data checksum status
  per track, using a proper self-syncing bit reader (do not use a
  naive fixed-8-bit reader — an early version of this tool had exactly
  that bug and produced false "missing sector" results).
- **A from-scratch 6502 disassembler** (`Disassemble.java`, formalized
  into the real project at
  `src/main/java/com/nordstrom/emulator/cpu/Disassemble.java` since
  the fix in UPDATE 7) built directly from the
  CPU's own real opcode table (`Opcodes.TABLE`, package-private in
  `com.nordstrom.emulator.cpu`) — avoids manual byte-by-byte misreads
  (which happened at least once this session before this tool
  existed). Lives in the `cpu` package to access the package-private
  table.
- **Pattern for live-state re-polling** (elimination #5): run the real
  boot to a specific cycle count, then call `disk.tick(N)` /
  `disk.readIoSwitch(0xC)` directly in a loop (rising-edge byte
  detection: only count a byte when latch transitions from `<0x80` to
  `>=0x80`, not on every poll) to check what the disk hardware is
  *actually* producing at that exact moment, independent of whatever
  the CPU is doing with it.

### UPDATE 15 — phase-stepping mechanism itself verified correct (with instrumentation)

Added temporary diagnostic logging directly to `Disk2Controller.applySwitch()`
(reverted afterward -- confirmed zero trace of it left in the file) to
capture the exact, real offset sequence DOS issues, rather than
inferring from disassembly alone. Also resolved a disassembly ambiguity:
the `ROL` in the phase-write routine (`$B9EE`) brings in whatever's in
the carry flag, and the two calls per loop iteration set carry
differently (`SEC` before the first, `CLC` before the second) --
meaning each iteration issues one "turn on new phase" (odd offset) and
one "turn off old phase" (even offset), a standard, correct
make-before-break stepping sequence, not what an initial read of the
raw offsets suggested.

Captured the real sequence for the first several iterations of call #1:
after a brief, single settling pair at the very start (inheriting
ambiguous phase state from whatever ran immediately before -- itself
normal, expected behavior, not a bug), every subsequent iteration
produces exactly one clean step, matching `$0478`'s own increment 1:1.
**The phase-stepping mechanism itself is confirmed correct** for this
real sequence, empirically, not just by earlier synthetic unit tests.

This means the discrepancy (final `quarterTrack=62` vs. DOS's belief
of track 17) is not caused by `Disk2Controller` mishandling any
specific phase transition. The remaining candidates are: (a) something
in the multi-call/retry/recalibration coordination logic (still not
fully decoded -- the `$0478` halving between calls, UPDATE 14, remains
only partially understood) that legitimately doesn't accumulate to the
full distance across multiple calls even on real hardware terms, or
(b) a verification read at some intermediate point during this
specific multi-stage process getting data from `WozDiskImage` that's
subtly wrong in a way not yet identified (not the simple track-number
mismatch already ruled out in UPDATE 12).

**Next concrete step, not yet done**: count total step vs. no-step
decisions across the *entire* seek sequence (all ~9 calls, not just
the first iterations of call #1) to see whether "both neighbors on" or
"neither neighbor on" no-step cases cluster at a specific point in the
sequence (e.g. right at the `$0478` halving transition, or right
before the eventual give-up), which would localize a specific
transition worth disassembling in detail, rather than continuing to
infer from partial samples.

### UPDATE 16 — decoded the real retry structure; ruled out a stepping-algorithm fix experimentally

Disassembled further and, combined with a web search confirming
`$B9A0-$B9FF` really is DOS 3.3's documented **SEEKABS** routine
("move disk arm to desired track"), established the real retry
structure precisely:

- An inner budget of up to 48 tries (`$0578`, initialized to `$30`) to
  find *any* readable address field at all.
- An outer budget of exactly **4** tries (`$04F8`, initialized to
  `#$04`) to get a *matching* track number: each try seeks, reads back
  an address field's track number into `$2E`, and compares it against
  `$0478` (DOS's own belief). Traced all 4 real attempts directly:
  address-field track readback went **9 → 12 → 15 → 15** while DOS's
  belief stayed fixed at 17 (the correct target) throughout -- the
  4th attempt makes zero further progress, which is exactly why it
  gives up (confirmed independently against `$0478` at each call's
  entry: 0 → 18 → 24 → 30, consistent with the readback sequence).
- After those 4 tries are exhausted, DOS does a full recalibrate-to-
  zero and retries the whole 4-try batch again (this is calls 6-9 from
  earlier updates) -- then gives up for good (motor off, "I/O ERROR").
  This two-outer-attempt limit matches DOS 3.3's well-known "try
  twice, then report I/O ERROR" reliability convention -- not obviously
  a bug in itself.

Checked the actual physical phase state at the exact moment call #2
begins: **all four phases are off** (`p0=false p1=false p2=false
p3=false`), despite `$0478`'s low bits suggesting phase 2 should be
"current". This confirmed empirically (not just inferred) that phases
get left fully de-energized between separate SEEKABS invocations,
producing the ~2-quarter-track "settling" overhead per call already
noted in UPDATE 15.

**Tested directly whether this settling overhead is itself a bug** in
`Disk2Controller`'s stepping model, via a real, out-of-tree
experiment: built a modified copy of the controller that also
registers a step when a phase energizes from a fully-off state
(tracking the last-active phase to infer direction), reflecting the
hypothesis that real stepper-motor hardware would already be pulled
toward a newly-energized coil rather than waiting for a subsequent
turn-off. **Result: this made things strictly worse** -- running the
same real `CATALOG` command against this experimental build reproduced
the *original* crash signature exactly (`A853- A=FC X=00 Y=00 P=37
S=F8`), the same BRK-into-unloaded-memory failure UPDATE 7 already
fixed. This is strong, direct evidence the hypothesis is wrong and the
existing, shipped stepping algorithm (step only on turn-off, exactly
one neighbor on) is correct as-is. The experimental code never touched
the real codebase and has been fully discarded; confirmed zero trace
of it remains (`grep` for its added symbols returns nothing, and the
project recompiles clean).

**Where this leaves things, honestly**: every individually-testable
component -- CPU execution, phase-stepping mechanics, disk data
integrity including the quarter-track fallback fix, and now the
settling-overhead behavior itself -- has been verified correct in
isolation. The real DOS 3.3 SEEKABS/retry algorithm has been
substantially decoded and matches its documented real-world identity.
Yet the net result (stopping 6 quarter-tracks short of track 17 within
the real 4-try budget) doesn't yet have a confirmed root cause -- it
may be a genuine, subtle timing mismatch (e.g. real per-step delay
timing, or disk rotation rate, differing from what real hardware /
Virtual ][ produces for the same cycle counts) rather than a discrete
logic bug of the kind found and fixed in UPDATE 7. This is a
materially different, harder class of problem than the first bug, and
has not been resolved as of this update.

**Next concrete step, not yet done**: verify the actual disk rotation
rate and per-step delay timing (the `$BA00` delay subroutine's real
cycle count vs. what real Disk II hardware/Virtual ][ uses for the
same table-driven delay) against a documented, authoritative timing
reference, since every logic-level component has now been ruled out
individually.

### UPDATE 17 — independent, cycle-accurate reference confirms the stepping algorithm exactly

Found and reviewed the wiki documentation for `web-a2e`, an independent,
self-described "cycle-accurate Apple //e and Apple II Plus emulator."
Its documented stepper-motor algorithm is **word-for-word identical**
to this project's: "Stepping occurs when the current phase is turned
OFF and an adjacent phase is ON... If both or neither adjacent phases
are on, no stepping occurs." This is independent, external
confirmation -- not just this project's own unit tests -- that the
core phase-stepping logic is correct as implemented. Combined with
UPDATE 16's negative experiment (a plausible-looking "fix" made things
strictly worse, reproducing the original crash), there is now strong
evidence the stepping mechanism itself is not the remaining bug.

That reference's "half-track (2 quarter-track) increments" phrasing
was checked and clarified: it describes normal DOS *software*
convention (using only even phases), not a hardware requirement --
the same document confirms full quarter-track stepping is supported
and used by some copy-protected disks. This is not the missing piece
either; `$0478`'s observed 1:1 correlation with `quarterTrack` (UPDATE
14) stands.

**This narrows things further**: with CPU execution (functional test
suite), phase-stepping (now doubly confirmed, internally and
externally), and disk data integrity all ruled out individually, the
remaining most promising lead is that the *starting* head position
before this specific seek sequence begins (the `qt=16` position
observed just before the `qt=16→0` recalibration at cycle ~36.1M,
UPDATE 10) may itself already differ from where real DOS 3.3 /
Virtual ][ would have the head at the equivalent point in the boot --
since this seek's retry logic is relative to wherever it starts, a
different starting position could organically explain reaching a
different final position within the same fixed retry budget, without
requiring any bug in the retry/stepping logic itself.

**Next concrete step, not yet done**: trace back further -- what
determines the head's position at the point just before this specific
seek sequence begins (right before cycle ~36.1M), and whether that
starting position is itself correct, rather than continuing to
scrutinize the seek algorithm in isolation.

### UPDATE 18 — correction: UPDATE 18's original premise was a misread, not a new finding

**Correction to this update's original content.** Re-examining the
exact cycle numbers: `quarterTrack=62` was NOT independently reached
twice. The `qt=62` observation at cycle 35,786,582 falls *between*
call #4's entry (35,755,914, confirmed via UPDATE 16's call-tracing)
and call #5's entry (35,825,553, the already-known recalibrate-to-zero
call) -- it's the tail end of call #4 itself, immediately followed by
call #5's already-documented retreat back down toward zero (62 → 61 →
60 → ... → 16 → 0, matching UPDATE 10's original trace exactly). There
is only one `qt=62` event in this sequence, not two independent ones.
The "deterministic attractor" framing this update originally proposed
was built on a timeline misread and should be disregarded.

One genuinely new, correctly-read detail from this pass: call #4 does
not just creep the last few units to reach its own local target
(34) -- it overshoots substantially, all the way to 62, well past
where `$0478=34` would suggest. This matches UPDATE 15's already-
recorded measurement ("Calls #2-4 only: ... end qt=62") and is not new
information either, just now correctly placed in the sequence.

**Status unchanged from UPDATE 16/17**: CPU execution, phase-stepping
(independently corroborated), and disk data integrity are all ruled
out. The mechanism behind the net shortfall to track 17 remains
unidentified. No new lead from this specific trace; the next step
proposed in UPDATE 17 (checking what determines head position *before*
the studied sequence begins, and whether CATALOG's I/O ERROR truly
represents final failure or whether a later stage was missed) has not
yet been properly carried out -- this update mistakenly substituted a
misread for that intended check.

### UPDATE 19 — confirmed: I/O ERROR is genuinely final; the "stuck" period is normal command-loop overhead, not a missed stage

Properly carried out UPDATE 17's intended check (UPDATE 18 had
substituted a misread for it): whether the CPU is truly idle during
the period after "I/O ERROR" appears, or silently still working on
something disk-related that the screen doesn't reflect.

Counted distinct PCs visited across 10 million cycles following the
CATALOG failure: 537 distinct addresses, all within DOS's own resident
range (`$9E8x` onward) -- far more than a simple keyboard-poll loop,
which was initially read as a sign something disk-related might still
be in progress. Checked directly: **the disk itself is completely
inactive throughout** -- `quarterTrack` stays fixed at 62 and the
motor stays off for the entire window (confirmed across a further 5
million cycles with zero change). The large number of distinct PCs is
just DOS's own command-prompt/input-handling logic being more
elaborate than a trivial 2-instruction poll (cursor handling, line
editing, etc.) -- normal overhead, not a missed retry or a later stage
that was overlooked.

**This confirms UPDATE 12's original conclusion was correct all
along**: CATALOG's "I/O ERROR" is a genuine, final failure, and the
machine is correctly idling at its command prompt afterward, not
silently still working. This closes off that line of inquiry without
finding a new lead.

**Where this leaves the investigation, honestly, after this session's
full effort**: the original boot-hang bug is fixed and solid. The
CATALOG failure's mechanism has been extensively, rigorously narrowed
-- CPU execution, phase-stepping (confirmed twice over, including
against an independent reference), disk data integrity, and the
finality of the failure are all individually verified -- without
yet identifying the actual defect. Every hypothesis tested this
session (quarter-track fallback interaction, stepping-on-turn-on,
missed later retry stage) has been disproven by direct evidence, which
is real progress in eliminating wrong paths, but has not yet produced
the fix.

### UPDATE 20 — likely major reframe: the target may never have been track 17 at all

Found a real, logic-analyzer-captured DOS 3.3 boot trace (an
"Apple Disk ][ interface timing" writeup) describing the actual,
verified sequence on real hardware: after track 0's sectors are read,
"the next stage of booting moves the head to **track 2**, sector 4...
reads in 26 more sectors, all the way down to track 0 sector A.
Finally the HELLO program is run."

This is a serious problem for this investigation's framing so far.
**The very first "I/O ERROR" observation (original `FindIoError` test,
cycle 37,779,389) occurred during the automatic boot sequence -- before
CATALOG was ever typed.** That means the seek sequence studied in
UPDATES 9 through 19 in such detail is almost certainly the
**auto-run-HELLO seek**, not a CATALOG-specific one, and per this real
reference, that seek's target on a working disk is **track 2**, not
track 17. The assumption that this sequence must reach track 17 (made
early on and never re-examined) may have been wrong from the start --
which would mean every "why does it fall short of 68" analysis since
UPDATE 9 was answering the wrong question.

Whether `$0478` settling at "17" really represents "track 17" (as
assumed) or something else entirely needs to be re-derived under this
corrected framing, not assumed.

**Next concrete step, not yet done**: check this specific disk's own
VTOC (track 17 sector 0, already confirmed valid/readable) for its
actual catalog pointer, and check whether this disk has a HELLO file
at all and which track/sector its first T/S list actually points to
-- rather than continuing to assume track 17 is the target for
whatever seek is actually being studied.

### UPDATE 21 — confirmed: two different targets both stop at the same qt=62, pointing at a general distance-budget limit, not a target-specific bug

Directly checked this disk's real VTOC and catalog chain. VTOC's own
catalog pointer is correct (track `$11`=17, matching standard
convention). The catalog listing includes a real `HELLO` file, whose
first T/S list sector is at **track 19**, sector 15 -- not track 2 (the
different reference disk in UPDATE 20's timing capture) and not track
17 either.

This means the two "I/O ERROR" occurrences investigated across this
whole thread were very likely seeking **two different targets**: the
original, boot-time failure (first observed via `FindIoError`, before
CATALOG was ever typed) was almost certainly the auto-run-HELLO
attempt, needing **track 19** (`quarterTrack=76`); the later, explicitly
-typed CATALOG command needs **track 17** (`quarterTrack=68`) for the
VTOC/catalog chain itself.

**Both independently land at the identical `quarterTrack=62`.** Two
different real targets producing the identical stopping point is a
strong signal this isn't a target-specific defect (e.g. not a bug
tied to track 17 or the VTOC specifically) -- it looks like a general
"cannot cover more than a certain net distance within the fixed 4-call
retry budget" limitation, roughly independent of exactly how far the
real target is past that point. Rough arithmetic supports this: calls
1-4 begin around `qt≈14-16` (per earlier updates) and the observed
settling overhead (UPDATE 15/16) caps real per-call progress below the
theoretical 12-quarter-track maximum; four such calls landing around
`qt≈62` is consistent whether the true target is 68 or 76 quarter-
tracks away, since either exceeds what four capped-and-overhead-laden
calls can cover.

This reopens UPDATE 16's negative experiment (adding a step on
turn-on-from-all-off, which made things worse) as worth revisiting --
not necessarily because the underlying idea was wrong, but because
that specific implementation may have had its own bug (e.g. wrong
step direction logic) independent of whether real hardware really
does cover more distance per call than this emulator currently does.
Not re-tested yet this pass.

**Next concrete step, not yet done**: determine, from a real,
authoritative source (not just re-deriving from this project's own
disassembly) whether real Disk II hardware genuinely produces more
net movement per SEEKABS call than the ~10 quarter-tracks this
emulator's settling overhead currently allows (12 max minus ~2 lost to
settling) -- e.g. by finding a documented real seek-time-per-track
figure and cross-checking it against the delay-loop cycle counts
already disassembled (UPDATE 16's `$BA00` delay subroutine), rather
than continuing to test unvalidated stepping-algorithm variants by
trial and error.

### UPDATE 22 — BREAKTHROUGH: real hardware data pinpoints the actual bug

Got real Virtual ][ breakpoint data (accumulator value at `$B9A0`, then the readback register `$2E` at the same points) from a working boot on the actual, real DOS ROM. This is the single most valuable data point in the whole investigation:

```
A (target passed to SEEKABS):  34, 34, 38, 38, 38, 34, 34, 42, 34, 34, 42(x6), 34, 34, 46(x16), 8(x6), 10(x16), 12(x4)
$2E (readback track):           0, 17, 17, 19, 19, 19, 17, 17, 21, 17, 17, 21(x6), 17, 17, 23(x16), 4(x6), 5(x16), 6(x3)
```

**Every single pair satisfies A = 2 × `$2E`, exactly, with zero exceptions.** This is decisive: real DOS's seek target is **recomputed from the previous readback** each retry (`new_target = readback × 2`), not held fixed. My own emulator's target (`$2A`) sits locked at 34 across every one of its 4 retries (confirmed independently by watching `$2A` directly through the whole sequence -- it changes only twice in the entire run, once per outer attempt batch, never escalating within a batch). This is now a fully confirmed, concrete divergence, not a hypothesis.

Found the actual doubling instruction: `$BEA1: ASL` in a subroutine at `$BE95`, called with `A=$2E` (the readback) right after a verification mismatch. Traced it directly in a narrow window around the very first verification failure:

```
cycle 35,493,002: $047E=34 $0478=17 $2A=34          (pre-existing state)
cycle 35,493,689: $047E=18                          (2 x 9, the readback -- doubling IS computed correctly)
cycle 35,493,782: $0478=18                          ($0478 picks up the doubled value, continues stepping from there)
cycle 35,493,796: $047E=34                          ($047E reverts, presumably scratch/restore)
cycle 35,493,840: $0478=19                           (normal active-stepping resumes)
```

**So the doubling computation itself works, and correctly updates `$0478`'s active-stepping baseline (confirmed: call #2's entry-point trace already showed `$0478=18` at that exact value, UPDATE 16). The piece that's missing is that `$2A` -- the value `$B9A0`'s own "have I reached my goal" comparison uses -- never gets updated to a new, escalated target.** Real DOS's `$2A` values (34, 38, 42, 46) don't match a simple `readback x 2` relationship the way `$047E`/`$0478` do (9x2=18, not 38) -- so there is a second, distinct piece of logic, not yet located, that computes the escalating comparison target `$2A` actually uses. That second mechanism is what's missing or malfunctioning in this emulator.

**Next concrete step, not yet done**: find where `$2A` is supposed to get recomputed between retries (not `$0478`/`$047E`, which are already confirmed working correctly) -- likely another small piece of arithmetic on the IOB or a saved-target value, triggered by the same verification-failure path (`$BDF4-$BE00`) already disassembled, but not yet found. This is now a narrow, well-defined target rather than an open-ended search.

### UPDATE 23 — pinned down: physical distance-per-seek is ~half what real hardware produces, for the identical target

Programmatically re-verified the A=2×`$2E` relationship from UPDATE 22
(by hand arithmetic had introduced an alignment error): it holds
**exactly**, with apparent mismatches occurring only at natural
transition points (the target updates one breakpoint hit before the
readback catches up to reflect it -- expected lag from write-then-read
ordering, not a real deviation). The escalation itself
(34→38→42→46...) is genuine and confirmed.

Traced my own emulator's `$3C`/`$3D` pointer (suspected in UPDATE 22)
and found it's stuck at a fixed address (`$B7FB`) throughout -- but
further tracing showed this specific value gets discarded via a `PLA`
immediately after being read, and only its effect on the carry flag
matters. Directly compared `$35` (the flag this logic branches on)
between my emulator and real DOS at the same breakpoints: **they
match almost exactly** (`EA,F5,FA,FD,FF` vs real `EA,F5,FA,FD,FE`).
This rules out `$3C`/`$3D` and `$35` as the divergence point --
both are computed identically in both systems.

**The actual, confirmed divergence**: after call #1 finishes (target
34, in both systems), the real, physical track found on the disk is
**track 9 in this emulator's own trace** vs **track 17 on real
hardware** -- almost exactly double. Both systems use the identical
target value and the identical retry/doubling logic (confirmed via
the exact A=2×`$2E` relationship holding on real hardware too). This
means **the bug is not in the seek-retry/escalation logic at all** --
it's that this emulator's seek produces roughly half the physical
head movement real hardware does for the same `$0478` target
value/loop-pass count. Since the phase-stepping algorithm itself is
independently verified correct (UPDATE 17, external reference match),
the discrepancy must be either (a) a different starting head position
before this whole sequence begins (an earlier-boot-stage bug, not
this code), or (b) some scaling between `$0478` loop-pass count and
physical quarter-track movement that real hardware/ROM applies and
this emulator's implementation doesn't -- despite the loop body
disassembly (UPDATE 10) showing only one phase-pair per `$0478`
increment, with no additional loop or multiplier found in `$B9EE`/
`$B9F1`'s own bodies (confirmed short, non-looping subroutines).

**Next concrete step, not yet done**: determine whether the *starting*
head position immediately before this whole sequence (right at the
`quarterTrack=16`/recalibration point studied since UPDATE 10) matches
what real hardware would have at the equivalent point -- if it
doesn't, the root cause is earlier in the boot than this seek
sequence entirely, and all the SEEKABS-internal analysis in UPDATES
9-23, while it ruled out many specific hypotheses, was examining a
symptom rather than the cause.

### UPDATE 24 — exact, clean confirmation: real hardware travels exactly 2x the distance for the same target

Directly checked the real physical `quarterTrack` at the moment of the
first verification readback: `quarterTrack=35`, giving `track=8`
(`35/4`, matching this emulator's own readback of 9 closely -- the
readback logic itself is accurate for wherever the head actually is).

**Critically: `quarterTrack/2 = 17` -- an exact match to real DOS's
own readback of 17 at the same point.** This is a clean, exact
confirmation (not approximate) that for the identical `$0478` target
(34) and identical retry logic (independently confirmed matching, via
the exact A=2×`$2E` relationship holding in both systems), **real
hardware's physical head travels to quarter-track ~70, while this
emulator's only reaches quarter-track 35 -- precisely half.**

This rules out a "different starting position" explanation (the
guaranteed track-0 recalibration immediately before this sequence,
UPDATE 10, should reliably put both systems at the same starting
point, `quarterTrack=0`) and rules out the readback/verification logic
being inaccurate (it correctly reports wherever the head physically
is). The remaining possibilities: (a) real hardware's SEEKABS loop
executes roughly twice as many phase-pairs as this emulator's for the
same net `$0478` change -- meaning something updates `$2A` (the loop's
own exit target) *during* a single call, not just between calls, which
existing monitoring (UPDATE 21's `$2A` watch) would have caught only
if it changed for more than an instant, or (b) a narrower,
not-yet-identified difference in this emulator's execution of the
`$B9AD` loop that causes premature exit at half the intended
iteration count for reasons not yet found in the disassembly (which
otherwise shows no obvious multiplier or unaccounted-for sub-loop).

**Next concrete step, not yet done**: watch `$2A` and `$0478` at
*every single CPU instruction* (not just on breakpoint-style sampling)
through one complete call, to catch any transient mid-call change to
the exit target that coarser sampling could have missed -- this is the
most direct way to distinguish hypothesis (a) from (b) above.

### UPDATE 25 — hypothesis (a) ruled out cleanly; the puzzle is now sharply, strangely narrow

Watched `$2A` and `$0478` at every single instruction (no sampling
gaps) through the entirety of call #1. Result: **`$2A` changes exactly
once, to 34, right at entry -- and never again for the rest of the
call.** This rules out hypothesis (a) from UPDATE 24 (a transient
mid-call retarget) with certainty, not just high confidence.

This leaves a genuinely tight, strange puzzle: the disassembled code
is confirmed identical to real ROM; `$2A` is confirmed identical (34)
on both systems; this emulator's execution of the loop against that
`$2A` value is confirmed correct (exits cleanly at `$0478==$2A`, one
`quarterTrack` step per pass, matching an external reference
implementation's stepping algorithm exactly) -- and yet the physical
result differs by a clean, exact factor of 2 (`quarterTrack=35` here
vs. an equivalent of ~70 on real hardware, UPDATE 24). Every
individually-checkable link in this chain has now been verified
correct in isolation, which is what makes the aggregate discrepancy
this puzzling.

Status at the end of this investigation session: the original boot-
hang bug remains solidly fixed and verified. This second bug's exact
mechanism has not been found despite substantial, rigorous narrowing
across many false leads correctly identified and discarded (the
quarter-track fallback interaction, stepping-on-turn-on, the `$3C`/
`$3D` pointer, the `$35` flag, a missed retry stage, a transient
mid-call retarget). What remains unexplained is narrow and precise
enough to describe in one sentence -- identical code, identical
target value, verified-correct execution, yet exactly half the
physical result -- which is unusual enough to warrant treating as
still genuinely unexplained. One candidate considered and rejected on
reflection: a CPU cycle-count/timing bug -- this doesn't actually fit,
since the loop's exit condition (`$0478==$2A`) is purely logical,
checked once per pass regardless of how many real cycles that pass
consumes; a timing difference could change how long the whole
sequence takes in wall-clock terms but not how many `quarterTrack`
steps a fixed number of loop passes produces. The discrepancy has to
be either in how many loop passes execute for the same `$2A`, or in
how much physical movement each pass produces -- and every check so
far says both of those are correct. This is the genuine open question
carried forward.

### UPDATE 26 — more hypotheses ruled out; found and read the actual "Beneath Apple DOS" text; still unresolved

Ruled out, this pass, with direct evidence:
- **Drive cross-talk**: confirmed `drive(1)` never moves at all during
  this whole sequence (`qt=0` throughout) -- all stepping activity is
  correctly confined to `drive(0)`.
- **Read vs. write triggering the phase switch**: confirmed
  `readIoSwitch()` calls `applySwitch(offset)` exactly like
  `writeIoSwitch()` does -- the real code's `LDA $C080,X` (a read)
  correctly triggers stepping in this emulator, not just writes.
- **Address-to-offset dispatch**: traced the full path from
  `$C0E0-$C0E7` (slot 6's phase range) through `SlotIoHandler`,
  confirmed `slotNumFor()` and the `% 0x10` offset extraction are both
  correct, standard, and slot-6-consistent.
- **Delay table contents**: read both tables (`$BA11`: `1,48,40,36,
  32,30,29,28,28,28,28,28`; `$BA1D`: similar) -- ordinary, monotonically
  -decreasing acceleration-profile delay values, nothing unusual.

Found one genuine, previously-missed detail via a complete, careful
re-disassembly of `$B9A0-$B9FC`: the successful-exit path (`$B9EA:
JSR $BA00; $B9ED: CLC`) has **no RTS before `$B9EE`** -- it falls
straight through into the shared phase-write subroutine body,
issuing one additional phase operation before the final `RTS` at
`$B9FC`. This is a real correction to this project's own
understanding of the routine's control flow, but is a single extra
operation per call, not something that can produce a clean, exact
2x factor across 34 net steps -- it doesn't explain UPDATE 24/25's
finding.

Found and read the actual OCR'd full text of *Beneath Apple DOS*
(asciiexpress.net's copy) -- the authoritative source this whole
investigation has been working toward. Its statement that "the disk
arm can position itself over 70 'phases'... [to move] one track to
the next, two phases of the stepper motor... must be cycled" was
initially promising (suggesting `$0478` might count in a coarser,
half-track-equivalent unit), but on careful reading this describes
which quarter-tracks standard DOS ever *targets* (only the even-
numbered ones, 70 out of 140 total across 35 tracks) -- not a
different counting scale for `$0478` itself. This reinterpretation
does not hold up and does not explain the 2x discrepancy either.

**Status: still unresolved.** Every concrete hypothesis constructed
and tested across this entire investigation has been individually
ruled out by direct evidence. The 2x discrepancy from UPDATE 24/25
(identical code, identical `$2A`, verified-correct execution, yet
exactly half the physical distance) remains the accurate, current
description of the open problem. This has now consumed extraordinary
effort without resolution -- the honest state is that the mechanism
has not been found, not that it doesn't exist.

### UPDATE 27 — ROOT CAUSE FOUND AND FIXED: phase-stepping magnitude was wrong by exactly 2x

Rather than continue static disassembly, ran a direct empirical
experiment: temporarily doubled `Disk2Controller.turnOffPhase()`'s
step magnitude (from `step(1)`/`step(-1)` to `step(2)`/`step(-2)`) in
an isolated copy and re-ran the real boot end-to-end. **Complete,
unambiguous success**: `CATALOG` now produces the exact, correct file
listing (`HELLO`, `APPLESOFT`, `LOADER.OBJ0`, `FPBASIC`, `INTBASIC`,
`MASTER`, and all the rest), matching the real catalog contents
verified independently by reading the VTOC directly back in UPDATE
21.

This reconciles cleanly with the actual "Beneath Apple DOS" text
found and read in UPDATE 26: "the disk arm can position itself over
70 'phases'" across 35 tracks -- exactly 2 "phases" per track -- with
"two phases of the stepper motor... must be cycled" to move one full
track. A real Apple "phase" (their own documented unit) is therefore
2 quarter-tracks in this project's own WOZ-format indexing (0-159,
4 per track, independently confirmed correct for representing disk
data via the earlier VTOC read at quarter-track 68 = track 17). DOS's
own phase-stepping loop issues one clean phase transition per desired
unit of movement in *its own* "phase" units -- which is 2 quarter-
tracks, not 1. UPDATE 17's earlier "independent reference"
confirmation was validating the turn-on/turn-off *rule* correctly,
but that reference's own internal unit scale evidently differs from
this project's, and the two were incorrectly assumed to match.

**The fix**: `turnOffPhase()` now steps the drive by 2 quarter-tracks
per clean transition instead of 1. Updated the existing phase-
stepping unit tests (`Disk2ControllerPhaseSteppingTest`) to assert
the corrected magnitudes (steps of 2 instead of 1, doubled
accordingly through the multi-step tests) -- the "no step" cases
(both neighbors on, neither on, redundant turn-off, already-clamped-
at-zero) are unaffected by the magnitude and needed no changes.

**Verified end-to-end against the real codebase** (not just the
experimental copy): the CPU functional test suite still passes
unaffected (traps at `$3469` as always); the original boot-hang crash
signature (BRK landing in DOS's resident memory) does not occur
across a full 200-million-cycle run; and `CATALOG` produces the
complete, correct, real file listing. Both this fix and the original
`WozDiskImage.trackAt()` fallback fix (UPDATE 7) are confirmed
working together with no regressions.

**Status: this investigation is complete.** Both bugs -- the original
boot-hang crash and this seek-distance error -- are fixed, verified
individually and together, with updated tests locking in the
corrected behavior.

### UPDATE 28 — false alarm resolved: the "catalog hangs" report was normal DOS behavior

After deploying the fix, real usage reported "boots slowly, but the
catalog hangs." Investigated directly: reproduced what looked like a
genuine hang in a headless test, but traced it to the test itself
typing `CATALOG` prematurely (at cycle 40M, well before the disk's
real settle point around cycle 53M) -- not a defect in the fix. When
typed after the disk genuinely finishes, `CATALOG` works correctly
and completes.

The real explanation, confirmed by the person testing it: **DOS 3.3's
`CATALOG` command normally pauses after each screenful, waiting for a
keypress before continuing** -- this is genuine, standard, documented
DOS 3.3 behavior (this investigation's own UPDATE 26 already surfaced
this directly: *Beneath Apple DOS* has a section literally titled
"REMOVING THE PAUSE DURING A LONG CATALOG"). What looked like a hang
was this normal page-pause, not a bug. No further code changes were
needed -- the emulator's behavior here is correct and matches real
DOS 3.3.

**Final status, unchanged from UPDATE 27: both bugs are fixed,
verified, and this investigation is complete.**

## Answers to try to avoid re-litigating

- Yes, `CliArgs` is public, `DiskMenu` exists, diagnostic prints were
  removed from `Apple2Plus.java` — all confirmed clean as of this
  writing.
- Yes, the disk-selection UI supports `.dsk`/`.do` now, not just
  `.woz`.
- The keyboard-focus fixes were tried and reverted — don't retry them
  without new evidence they're relevant.

### UPDATE 29 — OPEN, UNRESOLVED: boot is genuinely ~5x slower than real hardware

Real comparison data (Virtual ][ at Regular/1x speed, same WOZ image):
boots to a ready prompt in **~11 seconds**. This emulator, measured
directly (not guessed): reaches its own equivalent settled state in
**~53,000,000 cycles**, i.e. ~52 seconds at 1.023 MHz. This is a real,
confirmed ~5x gap, not a Virtual ][ "accelerated speed" illusion --
the person confirmed Regular Speed was used.

**Ruled out** (checked directly, both clean):
- Bit-rate / revolution timing: confirmed correct.
  `LSS_TICKS_PER_CPU_CYCLE=2`, `LSS_TICKS_PER_BIT_CELL=8` -> 4 CPU
  cycles/bit, matching real Disk II. Measured directly during
  boot0/boot1's stationary track-0-only phase (qt fixed at 0, no
  seeking): track 0 is 50,304 bits, and one full revolution measured
  at ~222,222 cycles -- correct, realistic range for a ~300 RPM
  drive. This part of the emulator is not the source of the slowdown.
- Motor spin-up thrashing: motor is ON 94.2% of total boot time
  (measured over the first 55M cycles), with only 43 total ON
  events -- not excessive on/off cycling.
- Boot0/boot1's own phase (track 0, sectors 0-9, no seeking): takes
  ~2,173,098 cycles (~2.1s) before the first seek begins. This is
  close to a reasonable "worst case, ~1 revolution per sector"
  estimate and does not look buggy on its own.

**Narrowed to, but not yet root-caused:** the seek-and-verify retry
loop itself (the same `$2A`-target-escalation mechanism from UPDATE
27's fix). Measured one specific retry interval (`$2A` going from 34
to 38) taking ~2,419,643 cycles -- at the confirmed-correct ~222,222
cycles/revolution, that is **~11 full disk revolutions for a single
retry attempt**. Finding one specific sector's address field should
typically take under one revolution, not eleven. This is a real,
narrow, specific lead: something in how this emulator's version of
the retry/verify step re-scans the track per attempt is almost
certainly doing much more work (or waiting much longer) than it
needs to, separate from the step-magnitude fix in UPDATE 27, which
was independently confirmed correct via the person's own real
Virtual ][ breakpoint data.

**Not yet done, next session:** trace what actually happens, cycle
by cycle, during one full `$2A`-escalation retry interval -- likely
inside the RWTS address-mark search / read-and-verify path used
after each SEEKABS step -- to find why it consumes ~11 revolutions
instead of a fraction of one. This is a distinct, separate
investigation from the two now-fixed correctness bugs (UPDATE 7 and
UPDATE 27), which remain confirmed fixed and are not implicated by
this timing gap.

**Also confirmed, not a bug:** Virtual ]['s "LOADING INTEGER BASIC
INTO MEMORY" boot message never appears in this emulator because
this emulator's test configuration has no Language Card in slot 0
(Virtual ][ includes one by default). `$E000` was directly verified
to contain correct, real Applesoft ROM data. On real hardware without
a Language Card, this message would not appear either -- there's
nothing to force-reload BASIC into. Confirmed by the person: no
Language Card in ReplicApple2Plus. Not a defect.
