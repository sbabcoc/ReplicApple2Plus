# ReplicApple2Plus

A from-scratch Apple II+ emulator, written in Java, prioritizing hardware
fidelity over performance. The design goal is to reproduce the real machine's
behavior down to documented (and, where necessary, undocumented) hardware
quirks — not just "an" Apple II+ emulator, but one whose CPU, timing, and
peripheral behavior can be checked against known-good references rather than
taken on faith.

This project supersedes an earlier, abandoned attempt
([6502-Emulator](https://github.com/sbabcoc/6502-Emulator)) written in x86
assembly targeting real x86 hardware directly. That attempt never got past
memory-mapped I/O dispatch. This one is built for portability instead, and
gets its raw-performance cost back through careful design rather than
low-level tricks.

## Current status

**The NMOS 6502 CPU core is complete and independently verified.**
Everything else — the memory bus, system clock, video, and disk subsystems —
exists only as designed classes and code sketches from early project
discussion. There is no assembled, bootable system yet; `build.gradle`'s
`mainClass` points at a class (`com.nordstrom.emulator.Apple2Plus`) that does
not exist. That's the next major piece of work.

### What's implemented

- **Every documented NMOS 6502 opcode**, all addressing modes, cycle-accurate
  timing including the conditional page-boundary-crossing penalty (present
  for reads, absent for writes and read-modify-write instructions, exactly
  as real hardware behaves).
- **Every stable illegal/undocumented opcode** (`SLO`, `RLA`, `SRE`, `RRA`,
  `SAX`, `LAX`, `DCP`, `ISC`, `ANC`, `ALR`, `ARR`, `SBX`/`AXS`, the illegal
  NOPs, the duplicate `SBC` encoding, and `LAS`), plus `JAM`/`KIL` modeled as
  a genuine CPU-halt state rather than an emulator error.
- **Decimal (BCD) mode**, including the specific NMOS flag quirks that
  differ from clean binary-mode arithmetic — traced from a public-domain
  reference algorithm, not reverse-engineered from guesswork.
- **`IRQ`/`NMI`/`RESET` line semantics**, modeled as physically distinct
  signals rather than one generic "interrupt" concept: `IRQ` is
  level-sensitive and wire-ORed across multiple simultaneous sources, `NMI`
  is edge-sensitive and non-maskable, `RESET`'s meaningful transition is on
  *release* rather than assertion. Includes the one-instruction interrupt-
  polling lag that makes `SEI`/`CLI` timing match real silicon (an
  already-pending IRQ can still land immediately after `SEI`; `CLI`
  guarantees at least one more instruction runs before an IRQ can land).
- **`reset()`**, modeled as a real hardware reset (SP decremented by 3, A/X/Y
  and most flags left untouched, D flag deliberately left indeterminate)
  rather than conflated with power-on initialization.

### Deliberately not implemented

- **The genuinely chip-unstable illegal opcodes** (`ANE`/`XAA`, `LXA`,
  `SHA`, `SHX`, `SHY`, `TAS`) throw `UnsupportedOperationException` rather
  than encode a guess. Different real NMOS chips disagree with each other on
  these — some behaviors are documented as temperature-dependent on a
  *single* chip — so there is no canonical behavior to be faithful to.
  Real Apple II+ software essentially never depends on them; this class of
  opcode was explored almost entirely on Commodore 64/Atari hardware.
- **Any actual system integration.** No `SystemClock`, no memory-mapped
  soft switches wired to anything live, no slot cards, no keyboard, no
  video output. Nothing currently calls `raiseIrq()`/`raiseNmi()`/
  `raiseReset()` — those exist on `Cpu6502` but have no wired source yet.

## Verification

This project treats "I traced it from a reference" as a claim to be checked,
not a conclusion. Two JUnit tests exist for that purpose:

- **`FunctionalTestHarnessTest`** runs
  [Klaus2m5's 6502 functional test suite](https://github.com/Klaus2m5/6502_65C02_functional_tests)
  — an industry-standard, cycle-exact validation ROM covering every
  documented opcode and addressing mode, including exhaustive binary and
  decimal ADC/SBC testing — and asserts it reaches the documented success
  trap. A previous version of this same test caught a real bug (the
  overflow flag was silently stuck at zero on every ADC/SBC, both modes,
  from a bit-position mismatch that looked correct on casual inspection).
- **`ArrDecimalCrossCheckTest`** exhaustively checks the illegal `ARR`
  opcode's decimal-mode behavior — a genuinely obscure corner with its own
  BCD correction logic — against an independently-transcribed copy of the
  reference algorithm, across all 131,072 possible `(A, operand, carry-in)`
  combinations.

Run both with:

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

## Project layout

```
src/main/java/com/nordstrom/emulator/cpu/    the CPU core (see above)
src/main/java/com/nordstrom/emulator/disk/   Disk II boot ROM and LSS sequencer table
                                              (verified byte-for-byte against MAME's
                                              published checksums), not yet wired to
                                              a live disk controller
src/test/java/com/nordstrom/emulator/cpu/    verification tests, see above
src/test/resources/                          the Klaus2m5 test ROM (binary and source --
                                              see NOTICE for its license)
```

## Design conventions

A few decisions run consistently through the CPU core and are worth knowing
before reading the source:

- **Table-driven dispatch over conditionals**, wherever a table faithfully
  reflects how the actual hardware decodes something (the LSS sequencer, the
  opcode table, per-mode cycle costs). Genuine algorithm-mode selections
  (binary vs. decimal arithmetic) remain real conditionals rather than being
  forced into a branchless shape that would obscure them.
- **Branchless bit arithmetic where it mirrors real hardware mechanisms**,
  not merely as a performance trick — e.g. page-boundary-crossing detection
  is computed as a literal carry-out bit from the low-byte addition, because
  that is how the real 6502 detects it internally.
- **Unverified is treated the same as unimplemented.** Where a documented
  behavior couldn't be confirmed against a trustworthy source, the code
  throws rather than guesses (see `ARR`'s decimal mode note above, and the
  unstable illegal opcodes).

## Acknowledgments

This project's accuracy rests on the work of people who originally
reverse-engineered this hardware, often decades ago, usually without any
official documentation to work from. Full attributions, including the
license terms for bundled third-party material, are in [NOTICE](NOTICE).
