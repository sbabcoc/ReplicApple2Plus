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
peripherals wired into a live memory map. What's still missing is the
top-level object that assembles all of this into something that actually
boots — there is no `SystemClock` and no `Apple2Plus` yet.

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
  through Q6/Q7 is a named, thrown gap pending a `SystemClock` to drive
  the LSS sequencer and a WOZ bitstream parser, neither of which exist
  yet. `RemovableMediaDrive` tracks which image is in which drive today,
  without yet parsing what's inside it.
- **`VideoSoftSwitches`** — the `$C050`-`$C05F` video mode switches
  (text/graphics, full/mixed, page1/page2, lo/hi-res, and the four
  annunciators) are a complete implementation; there is no equivalent
  deferred half, since none of these addresses expose data real software
  reads back.
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
- **General/keyboard soft switches** (`$C000`-`$C04F`, `$C060`-`$C07F`) and
  **floating-bus emulation** (what an empty slot or an ownerless expansion
  window actually returns) are both named, thrown gaps — the latter
  depends on a video scanner that doesn't exist, the former hasn't been
  designed yet at all.
- **`SystemClock` and `Apple2Plus`** — no per-cycle scheduler and no
  top-level object assembling the CPU, bus, and slots into something that
  boots. This is the actual remaining gap between "a complete CPU and a
  working peripheral architecture" and "a running emulator."

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

## Project layout

- `src/main/java/com/nordstrom/emulator/` — `MemoryBus` and
  `InterruptLines`, the two contracts shared between `cpu` and `system`
  (deliberately in neither package, so neither depends sideways on the
  other for a cross-cutting hardware concept)
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
