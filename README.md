# ReplicApple2Plus

A from-scratch Apple II+ emulator, written in Java, prioritizing hardware
fidelity over performance. The design goal is to reproduce the real machine's
behavior down to documented (and, where necessary, undocumented) hardware
quirks — not just "an" Apple II+ emulator, but one whose CPU, memory map, and
peripheral behavior can be checked against known-good references rather than
taken on faith.

This project supersedes an earlier, abandoned attempt
([6502-Emulator](https://github.com/sbabcoc/6502-Emulator)) written in x86
assembly targeting real x86 hardware directly. That attempt planned to use
page-level memory protection (mapping most of the address space as ordinary
memory, trapping access to specific protected pages via the OS's own
page-fault mechanism) to keep memory-mapped I/O completely decoupled from the
CPU's own implementation. This project can't use real page-level protection —
there's no portable JVM equivalent — but achieves the same decoupling anyway,
through the address-space design described below.

## Current status

**The NMOS 6502 CPU core is complete and independently verified.** A real,
working peripheral architecture exists on top of it: a generic memory bus,
genuine plugin discovery and dynamic loading, and several real, verified
peripherals wired into a live memory map. `SystemClock` now drives the CPU
with exact cycle accounting. What's still missing is the top-level object
that actually assembles a bootable machine — `Apple2Plus` is currently
just a placeholder window proving out the packaging pipeline (see below).

### What's implemented

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

### Deliberately not implemented

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

## Verification

This project treats "I traced it from a reference" as a claim to be checked,
not a conclusion.

- **`FunctionalTestHarnessTest`** runs
  [Klaus2m5's 6502 functional test suite](https://github.com/Klaus2m5/6502_65C02_functional_tests)
  — an industry-standard, cycle-exact validation ROM covering every
  documented opcode and addressing mode — and asserts it reaches the
  documented success trap.
- **`ArrDecimalCrossCheckTest`** exhaustively checks the illegal `ARR`
  opcode's decimal-mode behavior against an independently-transcribed copy
  of the reference algorithm, across all 131,072 possible combinations.
- **`ServiceLoaderSpiTest`** confirms, using the real `java.util.ServiceLoader`
  API directly with no custom code involved, that the peripheral service
  file is genuinely valid SPI — not merely shaped like one.
- **The disk ROMs** (`DiskBootRom`, `DiskLogicSequencerRom`) are verified
  byte-for-byte against two independent sources each time: a hardware PROM
  dumping project and MAME's own source, not just one or the other.
- **`SystemRom`** is verified the same way, chip-by-chip, against MAME's
  own source for the `apple2p` driver.
- **`PaddleTimers`' cycle calibration** is verified by assembling and
  running the actual historical Monitor ROM `PREAD` routine (real
  disassembled bytes, not a re-implementation) against the real CPU
  core, `SystemClock`, and `MotherboardBus` together -- an exact match
  across the full 0-255 position range, not just a plausible one.
- **`SystemClockCycleListenerTest`** locks in `SystemClock.addCycleListener`
  itself, not just the classes that use it -- confirming it genuinely
  drives a real listener forward with the actual per-instruction cycle
  counts as the CPU executes, and that multiple independently-registered
  listeners are each notified (the actual reason it's a list, not a
  single callback slot).
- **The real Autostart boot sequence** -- the actual, unmodified
  `$FFFC` reset vector, no shortcuts -- has been driven end-to-end
  through `Cpu6502`, `MotherboardBus`, `SystemClock`, and `VideoScanner`
  together, and produces the real "APPLE ][" banner and `]` prompt on
  screen. This exercised a genuine, previously-undiscovered gap: the
  real Autostart ROM scans slots 1-7 for a bootable device during cold
  boot, hitting `VideoScanner`'s own vertical-blanking address range in
  the process -- something no smaller, isolated test had reason to
  reach. Confirmed with no regression to the CPU functional test suite
  or any other peripheral.

Run all tests with:

```
./gradlew test
```

## Required ROM files

Three real Apple II+ ROM dumps are deliberately excluded from this
repository (`.gitignore`'s `*.rom` rule) rather than committed as
binary source-controlled assets, since they're copyrighted Apple
firmware, not this project's own work. This means **a fresh clone
cannot build or run until these are provisioned manually** -- the
failure mode is exactly the `IllegalStateException: ... is missing from
the classpath` each ROM class's own loader throws by design, not a bug
to chase further if you see it.

Each file must be placed at the exact path below and match the listed
CRC32 exactly (verified automatically at class-load time -- a wrong or
corrupted file fails loudly with a specific error naming the mismatch,
rather than silently serving bad data):

| File | Path | CRC32 |
| --- | --- | --- |
| System ROM (Applesoft BASIC + Autostart Monitor) | `src/main/resources/com/nordstrom/emulator/system/system-rom.rom` | chip-by-chip, see `SystemRom`'s own Javadoc |
| Character generator ROM (341-0036) | `src/main/resources/com/nordstrom/emulator/system/character-rom.rom` | `64F415C6` |
| Integer BASIC Firmware Card ROM | `src/main/resources/com/nordstrom/emulator/expansion/integer-basic-firmware-card.rom` | chip-by-chip, see `IntegerBasicFirmwareCardRom`'s own Javadoc |

The System ROM and Integer BASIC Firmware Card ROM are each assembled
from five separate 2KB/4KB chip dumps concatenated in address order --
see each class's own Javadoc for the exact per-chip CRC32/SHA1 pairs
and MAME source cross-reference used to verify them originally. The
simplest way to provision a fresh checkout is copying these three files
directly from a machine where the project already builds successfully,
at the exact paths above.

## Building

```
./gradlew build
```

Requires a JDK (17+) and the bundled Gradle wrapper — no local Gradle
install needed beyond generating the wrapper once, if it's ever missing:
`gradle wrapper`.

Artifact publishing (Sonatype Central Portal, GPG signing) is configured in
`build.gradle`, following the same pattern as this project's siblings
(`selenium-bom`, `selenium-grid-manager`).

## Running

```
java -cp build/classes/java/main:build/resources/main com.nordstrom.emulator.Apple2Plus [--config slots.ini] [--plugins DIR]
```

Both flags are optional and independent, matching this project's other
command-line tools (`SlotConfigTemplate`, `CardCatalog`). With no
`--config`, every slot stays empty and the machine boots straight to
Applesoft/the Monitor -- this is required, not just the default, since
a `Disk2Controller` present with no disk inserted causes the real
Autostart boot sequence to hang waiting for disk data that will never
arrive (see `Apple2Plus`'s own Javadoc). To boot from a real disk,
configure a `Disk2Controller` with at least one drive in the INI file:

```
[6]
type=disk2
drive1=path/to/disk1.woz
drive2=path/to/disk2.woz
```

`drive1`/`drive2` are both optional; `type`/`drive1`/`drive2` are the
same keys `SlotCardLoader` and `Disk2Controller` already document.
Once running, either drive's disk can be swapped at any time via the
window's Disk menu -- the same underlying operation as the config
file's own initial load, not a separate mechanism.

### Useful Gradle tasks

- `./gradlew runCardCatalog [-PpluginsDir=DIR]` — list every available
  peripheral and what it needs.
- `./gradlew runSlotConfigTemplate [-PpluginsDir=DIR] [-PoutputFile=FILE]` —
  generate a starter slot configuration file.

## Packaging

```
./gradlew jpackage
```

Produces a native, self-contained installer -- no Java installation
required on the target machine -- via the `org.beryx.runtime` plugin,
which wraps `jlink` (custom minimal JRE) and `jpackage` (platform
installer). **Not** `org.beryx.jlink`: this project has no
`module-info.java` and shouldn't get one, since the dynamic plugin-loading
architecture (`PluginLoader`, `CardTypes`) depends on open class loading
that JPMS's module boundaries actively restrict. `org.beryx.runtime` is
built specifically for non-modular applications like this one.

`jpackage` cannot cross-build: it only produces an installer for the
platform it runs on. Building all three requires running the same command
on each:

| Platform | Output |
|---|---|
| Linux | `.deb` (needs `fakeroot` installed) or `.rpm` |
| Windows | `.msi` or `.exe` |
| macOS | `.dmg` or `.pkg` |

The full pipeline -- compiled classes through a `jlink` runtime image,
`jpackage`, and an actual installed, launched application -- has been
validated end-to-end for Linux `.deb` output.

Related tasks: `./gradlew runtime` (just the custom JRE image, no
installer) and `./gradlew jpackageImage` (an installable application
directory, skipping the installer format).

## Project layout

- `src/main/java/com/nordstrom/emulator/` — `MemoryBus` and
  `InterruptLines`, the two contracts shared between `cpu` and `system`
  (deliberately in neither package, so neither depends sideways on the
  other for a cross-cutting hardware concept), and `Apple2Plus`, the
  application entry point (currently a packaging-pipeline placeholder,
  see [Packaging](#packaging))
- `src/main/java/com/nordstrom/emulator/cpu/` — the CPU core
- `src/main/java/com/nordstrom/emulator/expansion/` — actual expansion
  card implementations and their verified ROM data: `Disk2Controller`
  (plus `DiskBootRom`, `DiskLogicSequencerRom`), `LanguageCard` (plus
  `IntegerBasicFirmwareCardRom`, sourced and verified but not yet wired
  to anything -- there is no `IntegerBasicFirmwareCard` class yet)
- `src/main/java/com/nordstrom/emulator/system/` — the memory bus
  (`AddressSpace`, `MotherboardBus`, and the individual region and
  dispatch handlers), the peripheral architecture (`SlotCard`,
  `CardTypes`, `PluginLoader`, `Install`, `CardCatalog`,
  `SlotCardLoader`, `SlotConfigTemplate`, `RemovableMediaDrive`), the
  one true motherboard-built-in peripheral that isn't a slot card at all
  (`VideoSoftSwitches`), and the Apple II+'s own system ROM
  (`SystemRom`, `SystemRomHandler`) -- package-private, like its sibling
  ROM classes in `expansion`: `LanguageCard` never references it at all,
  reporting only that it isn't intercepting a given address and leaving
  the actual fallback to `SlotZeroBankingHandler`
- `src/test/java/` — verification tests, see above
- `src/test/resources/` — the Klaus2m5 test ROM (binary and source — see
  NOTICE for its license)

## Design conventions

A few decisions run consistently through this codebase and are worth
knowing before reading the source:

- **Table-driven dispatch over conditionals**, wherever a table faithfully
  reflects how the actual hardware decodes something (the LSS sequencer,
  the opcode table, per-mode cycle costs, address-range dispatch). Genuine
  algorithm-mode selections (binary vs. decimal arithmetic, ROM vs. RAM
  read source) remain real conditionals rather than being forced into a
  branchless shape that would obscure them.
- **Branchless bit arithmetic where it mirrors real hardware mechanisms**,
  not merely as a performance trick — e.g. page-boundary-crossing detection
  is computed as a literal carry-out bit, because that is how the real
  6502 detects it internally.
- **Unverified is treated the same as unimplemented.** Where a documented
  behavior couldn't be confirmed against a trustworthy source, the code
  throws rather than guesses — the genuinely unstable illegal opcodes and
  the multi-latch expansion-ROM conflict are the current examples.
- **A second source of truth is a bug waiting to happen, so it gets
  removed, not monitored.** A card's short name and parameters live as
  real methods on the card itself, not as comments in a separate file;
  `AddressSpace` pairs a handler with its range in one shared record, not
  two arrays that have to be kept in sync by hand.
- **Storage cells use `byte[]`; actively computed values stay `int`.**
  Java's `byte` is signed, which makes it genuinely painful for anything
  compared, added to, or used as a table index — but for an array that's
  purely read and written at rest (RAM, ROM), it's a real, free memory
  reduction with the sign-conversion localized to exactly the point where
  storage meets the rest of the system.
- **A card knows only its own bus interface, never a sibling's
  internals.** Real hardware components don't reach across the bus to
  consult each other's contents; a card that isn't driving a given
  address just says so and steps aside. `LanguageCard` reports "not
  intercepting" (`OptionalInt.empty()` from `readSlotZeroBank`) rather
  than reaching for `SystemRom` itself — resolving that fallback is
  motherboard-level dispatch's job, not any individual card's.
- **Comments carry rationale, not narration.** A comment should explain
  why the current code is shaped the way it is — a hardware quirk, a
  real constraint, a genuine trade-off — not recount how it got there
  through revision. That history belongs in version control, not in the
  file itself.

## Acknowledgments

This project's accuracy rests on the work of people who originally
reverse-engineered this hardware, often decades ago, usually without any
official documentation to work from. Full attributions, including the
license terms for bundled third-party material, are in [NOTICE](NOTICE).
