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

**A working Apple II+.** It boots DOS 3.3 from WOZ and DSK disk images,
reads and writes them, runs Applesoft BASIC and machine-language software, and
displays text, lo-res and hi-res graphics with sound and game
controllers, and supports the Videx VideoTerm 80-column card. The Saturn
128K RAM card is researched and specified but not yet built -- see
[TODO.md](TODO.md).

Companion documents:
- [HARDWARE-REFERENCE.md](HARDWARE-REFERENCE.md) -- primary-source
  hardware specs for implemented and planned peripherals.
- [TODO.md](TODO.md) -- open work.
- [TASK_RETRO.md](TASK_RETRO.md) -- completed work, with the reasoning,
  sources and verification history behind it.
- [DOS33-BOOT-INVESTIGATION.md](DOS33-BOOT-INVESTIGATION.md) -- the
  full history of getting DOS 3.3 to boot.

## What it does

**The machine**
- NMOS 6502 CPU, cycle-accurate, including the stable undocumented
  opcodes and decimal-mode quirks.
- 48K of RAM that powers up random, as real DRAM does -- some software
  depends on it.
- The Apple II+ system ROM (Applesoft BASIC and the Autostart Monitor),
  checksum-verified at startup.
- Floating-bus reads from empty slots and unlatched addresses, driven
  by a model of the video scanner's real memory fetches.

**Display and sound**
- Text (normal, inverse and flashing), lo-res, and hi-res graphics with
  NTSC artifact color; page 1/page 2 and mixed mode, tracked per scan
  line so mid-frame mode changes render correctly.
- Speaker output to the host's audio device, which also paces emulation
  to real time (with no audio device, a timer paces it instead).

**Input**
- Keyboard, with the II+'s single-character latch and strobe.
- Game controllers: four paddle timers (`$C064`-`$C067`, calibrated
  against the ROM's own `PREAD` routine) and three pushbuttons
  (`$C061`-`$C063`), fed by a locally connected gamepad (via Jamepad, an
  SDL binding)
  or, on Android, by the companion PadBridge app over UDP -- see
  [android-bridge/README.md](android-bridge/README.md). Controller
  mapping is configurable.

**Disks** (Disk II controller, two drives)
- WOZ 2 images (5.25") and DOS-order DSK images (`.dsk`/`.do`), read
  and written. Writes persist to the image file on track change, eject
  and exit.
- **Disk ▸ New...** creates blank, unformatted WOZ or DSK media ready
  for `INIT`. A blank DSK is an empty file, the same convention Virtual
  ][ uses.
- Per-disk write protection, shown in each drive's menu title and
  toggled with a Writable/Protected choice.
- The same image can't be inserted in both drives at once.

**Expansion**
- Language Card in slot 0 (16K of bank-switched RAM, also random at
  power-on).
- Videx VideoTerm 80-column card in slot 3, running its real firmware
  2.4: 80 x 24 text with lowercase and true descenders, a blinking
  block cursor, and the card's own character set. Four display modes,
  chosen at any time from the toolbar:
  - **Soft Switch** models Videx's Soft Video Switch: the main window
    shows 80 columns when annunciator 0 is on and the machine is in text
    mode, and the Apple's video otherwise.
  - **Apple Video** and **Slot 3 Video** hold the main window on one
    source, like a manual monitor switch -- useful with software that
    leaves 80-column mode without turning annunciator 0 off. (Merlin's
    `VID 0` is one: on real hardware with a Soft Video Switch, its user
    would press RESET, whose ROM routine clears the annunciators; the
    toolbar's Reset does the same here.)
  - **Dual Monitor** gives the 80 columns a window of their own beside
    an always-Apple main window. Closing that window returns to Soft
    Switch.

  The chosen mode carries across Reboot.
- Peripherals are plug-ins: cards are discovered through Java's standard
  `ServiceLoader`, and new ones can be added from external jars without
  rebuilding the emulator.

**Application**
- **Edit** menu: Copy Screen Text, Copy Screen Image, Paste, and Type
  File... -- pasted or typed text is fed to the keyboard at exactly the
  pace the running software reads it, so nothing is dropped. Stop Typing
  cancels.
- Toolbar: Reset (a real press-and-release RESET line), Reboot (a power
  cycle that keeps the inserted disks), and -- with a VideoTerm
  configured -- the display mode drop-down.

## Architecture

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

### Deliberately not implemented

- **Swappable ROM images.** `SystemRom` and `CharacterRom` each load one
  specific resource and verify it against the stock Apple II+ checksum --
  right for catching corruption, wrong for anyone wanting a historically
  common modification (an alternate F8 ROM, a custom character set).
  Needs a config-driven override that skips the stock checksum when a
  substitution is actually requested.
- **Lowercase on the 40-column screen.** The stock character ROM has no
  lowercase glyphs, and the II+ keyboard folds typing to uppercase. The
  VideoTerm displays lowercase on its 80-column screen.
- **The genuinely chip-unstable illegal opcodes** (`ANE`/`XAA`, `LXA`,
  `SHA`, `SHX`, `SHY`, `TAS`) throw `UnsupportedOperationException` rather
  than encode a guess -- real NMOS chips disagree with each other on
  these, some even varying with temperature, so there is no canonical
  behavior to be faithful to.
- **A multi-latch expansion-ROM bus conflict** (`$C800`-`$CFFF` with more
  than one card's latch set) throws rather than fabricating a result --
  real hardware has no arbitration there, and the condition is a
  documented electrical failure mode, not something with one right
  answer.
- **`IntegerBasicFirmwareCard`** doesn't exist as a class yet, though its
  verified ROM data does (`IntegerBasicFirmwareCardRom`). It's a simpler
  card than the Language Card -- a fixed ROM set behind a two-address
  toggle -- and also occupies slot 0.
- **Cassette output and the utility strobe** (`$C020`-`$C02F`,
  `$C040`-`$C04F`) accept accesses and do nothing; no cassette is modeled
  and nothing is wired to the strobe.
- **Other disk formats.** No 13-sector DOS 3.2 media, no ProDOS-order
  (`.po`) images, and no 3.5" WOZ disks or WOZ FLUX/WRIT chunks.

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
  screen.

- **Disk writing** is verified end to end through the real soft
  switches with DOS 3.3's own write-loop timing: every byte must reach
  the track as exactly 8 bits, and sectors written to a DSK must decode
  back with DOS's own checksum and epilog checks.
- **Real software** drives the rest: DOS 3.3 `INIT`, `SAVE` and reboot
  from a freshly written disk, and games that depend on uninitialized
  RAM (Joust's random-number seed) behaving as they do on hardware.

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

Two more are needed only if a VideoTerm card is configured (they're
loaded when the card is, so other setups never touch them):

| File | Path | CRC32 |
| --- | --- | --- |
| VideoTerm firmware 2.4 (1024 bytes) | `src/main/resources/com/nordstrom/emulator/expansion/videx-videoterm-firmware-2_4.rom` | `4DDBE669` |
| VideoTerm character generator (2048 bytes) | `src/main/resources/com/nordstrom/emulator/expansion/videx-videoterm-charset-normal.rom` | `87F89F08` |

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
java -cp build/classes/java/main:build/resources/main com.nordstrom.emulator.Apple2Plus [options]
```

| Option | Meaning |
|---|---|
| `--config FILE` | Slot configuration (INI). Without it every slot stays empty and the machine boots to Applesoft. |
| `--plugins DIR` | Directory of external peripheral jars to load. |
| `--input FILE` | Game controller mapping; defaults apply without it. |
| `--network-input PORT` | Take controller input from PadBridge over UDP on this port, instead of a local gamepad. |

To boot from disk, configure a Disk II controller (conventionally slot 6)
with the images to insert at startup:

```
[0]
type=languageCard

[3]
type=videoterm
display=switched

[6]
type=disk2
drive1=path/to/disk1.woz
drive2=path/to/disk2.dsk
```

`[3]` is optional: it adds the VideoTerm (`PR#3` activates it). It must
be slot 3 -- the card's firmware is written for it, and any other slot is
refused at startup.
`display` sets the mode it starts in -- `switched` (Soft Switch, the
default), `apple` (Apple Video), `slot3` (Slot 3 Video) or `separate`
(Dual Monitor); the toolbar changes it while running.

`drive1` and `drive2` are both optional. If drive 1 is empty at startup,
the emulator offers to insert a boot disk first -- otherwise the
Autostart ROM would wait forever for one. Disks can be inserted,
created, ejected and write-protected at any time from the Disk menu.

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

- `src/main/java/com/nordstrom/emulator/` -- the application:
  `Apple2Plus` (entry point and machine assembly), `EmulationLoop`
  (audio-paced emulation thread), `ScreenPanel`, `DiskMenu`, `EditMenu`,
  `ToolbarControls`, keyboard input and text injection, plus the
  `MemoryBus` and `InterruptLines` contracts shared by `cpu` and
  `system`.
- `.../cpu/` -- the 6502 core.
- `.../system/` -- the motherboard: the memory bus and its handlers,
  RAM and ROMs, video (scanner, per-line modes, text/lo-res/hi-res
  renderers), speaker, keyboard, game I/O, and the peripheral
  architecture (`SlotCard`, plug-in loading, slot configuration).
- `.../expansion/` -- expansion cards and their ROMs: `Disk2Controller`
  with its logic sequencer and disk image formats (`WozDiskImage`,
  `DskDiskImage`), `LanguageCard`, and `VideoTerm` with its renderer.
- `.../input/` -- game controller input: providers (Jamepad, network),
  polling, and mapping onto the paddles and buttons.
- `android-bridge/` -- PadBridge, the Android companion app that forwards
  a Bluetooth controller to `--network-input`.
- `src/test/java/` -- verification tests; `src/test/resources/` -- the
  Klaus2m5 test ROM (see NOTICE for its license).

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
