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
- **`Disk2Controller`** — the first real `SlotCard` in this project. The
  boot ROM and all 16 soft switches (phase stepper, motor, drive select,
  Q6/Q7 mode latches) are fully implemented; actual bit-level disk data
  through Q6/Q7 is a named, thrown gap pending `SystemClock` actually
  being wired to drive the LSS sequencer and a WOZ bitstream parser,
  neither of which exist yet. `RemovableMediaDrive` tracks which image
  is in which drive today, without yet parsing what's inside it.
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
  floating-bus emulation needs, not a full pixel renderer. Timing
  (65 cycles/scanline, 262 scanlines/frame) and both the text/lores and
  hi-res row-to-address formulas are independently confirmed against a
  real, detailed row-address reference, checked against specific known
  points (row 0, 1, 8, 64) rather than trusted on formula alone. Ticked
  via `SystemClock.addCycleListener`, the same mechanism `PaddleTimers`
  uses. Three honest, named gaps: horizontal blanking, vertical
  blanking, and mixed mode (which shows text on the bottom scanlines
  regardless of the overall lores/hires selection) are all real,
  well-documented behaviors this class doesn't yet model with the same
  verification rigor as the two address formulas -- it throws for
  these rather than guessing. Not yet wired into the actual "empty
  slot" floating-bus gaps -- see below.
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

### Deliberately not implemented

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
- **Floating-bus emulation** (what an empty slot or an ownerless
  expansion window actually returns) is not wired up yet, but the hard
  part -- `VideoScanner`, see above -- is done and verified. What
  remains is threading that computed address through the actual
  "empty slot" gap-throwing code (`SlotIoHandler`, `SlotZeroIoHandler`,
  and others) so a genuine floating-bus read replaces a thrown
  exception, plus reading the real byte at that address from RAM once
  it's threaded through. `VideoScanner` itself still has three of its
  own named gaps too -- horizontal blanking, vertical blanking, and
  mixed mode -- so even once wired in, floating-bus reads during those
  periods would still throw, honestly, rather than guess.
- **`Apple2Plus`** currently exists only as a placeholder Swing window
  with no emulator content -- built specifically to validate the
  `jlink`/`jpackage` packaging pipeline (see [Packaging](#packaging))
  on real hardware before a complete application rides on top of an
  unvalidated assumption. The target for the real version: boot to a
  BASIC/Monitor prompt via `SystemRom`, accept keyboard input via
  `KeyboardRegister`, and render text-mode output via `CharacterRom` --
  no disk support yet, since `Disk2Controller`'s LSS ticking and the
  WOZ parser are separate, still-open gaps regardless of `Apple2Plus`
  itself. Text rendering specifically doesn't need `VideoScanner`'s
  cycle-accurate timing at all -- reading the fixed 40x24 text page
  addresses directly and painting the result is enough; `VideoScanner`
  matters for floating-bus accuracy and any future scanline-timed
  rendering, not for this first target.

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

Run all tests with:

```
./gradlew test
```

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
