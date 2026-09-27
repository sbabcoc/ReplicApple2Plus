# ReplicApple2Plus Hardware Reference

Reference notes for anyone extending this emulator with new peripherals or
digging into its existing ones. Each section is marked with its confidence
level: **Confirmed** means verified against a primary source (an actual
schematic, an actual factory manual, a checksummed ROM dump) and, where
applicable, already implemented and tested in this project. **Researched**
means a primary source has been read and the spec below is drawn from it,
but the code does not exist yet. Where a claim's source is anything less
solid than that, it is called out explicitly rather than presented as fact.

---

## 1. Disk II Controller & Motherboard Esoterica (Confirmed, Implemented)

This section documents what `Disk2Controller`, `DiskBootRom`, and
`DiskLogicSequencerRom` actually model, and why each piece is believed
correct. Full narrative history, including every dead end, is in
`DOS33-BOOT-INVESTIGATION.md` at the repo root.

### ROM contents

Two ROMs on a real Disk II controller card are modeled as genuine,
verbatim byte tables (not hand-written behavioral approximations), each
guarded by a checksum verified at class-load time:

| ROM | Class | CRC32 | Verified against |
|---|---|---|---|
| P5 boot ROM (256 bytes, `$C600`-`$C6FF`) | `DiskBootRom` | `CE7144F6` | Two independent sources: a hardware PROM dump and MAME's own source |
| P6/P6A logic sequencer ROM (256 bytes) | `DiskLogicSequencerRom` | `B72A2C70` | Two independent sources: the Apple-Disk-II-PROM-Verify project and a2kit's decoded table |

The system ROM (`system-rom.rom`, 12,288 bytes — the three 4K Apple II+
Monitor/Integer BASIC/Applesoft ROMs) is a real, copyrighted dump and is
deliberately **not** checked into the git repository; it must be supplied
locally at
`src/main/resources/com/nordstrom/emulator/system/system-rom.rom` before
the project will build and run (missing it throws a clear
`IllegalStateException` at startup rather than failing silently).

### Phase stepping (seek mechanism)

Two confirmed, fixed bugs, both in `Disk2Controller`:

- **Magnitude**: `turnOffPhase()` was calling `step(1)` per phase
  transition; real hardware steps 2 quarter-tracks per transition. Fixed
  to `step(2)`, verified against real Virtual ][ breakpoint data (exactly
  2x the distance for the same target).
- **BOOT0 recalibration pattern**: the phase-stepping model only
  recognized the normal SEEKABS overlap pattern (new phase on while the
  old one is still on). BOOT0's own recalibration loop turns each phase
  fully off before turning the next one on — never overlapping — which
  produced zero head movement on a PR#6 warm reboot. Fixed by splitting
  into `turnOffPhase` (tracks `currentPhase`) and `turnOnPhase` (only
  steps when no other phase is currently on, using `currentPhase` as
  reference).

### WozDiskImage quarter-track fallback

`trackAt()` originally returned an empty-track sentinel for any unmapped
quarter-track, even when a mapped neighbor existed nearby. Fixed with
`resolveTrksIndex()`, a 3-quarter-track nearest-neighbor fallback.

### Floating bus (soft-switch offsets `0x0`-`0xB`)

Real Disk II soft switches at offsets `0x0`-`0xB` (phase/motor/drive
control) don't drive the data bus on a read; real hardware shows the
floating bus — whatever the video circuitry last fetched — not a fixed
value. `Disk2Controller.readIoSwitch` models this via
`setFloatingBusSupplier`, wired to the real `FloatingBus` in
`MotherboardBus`. This matters because at least one real, verified DOS
3.3 RWTS path (`LDA $C08A,X` / `$C08B,X`) genuinely reads the result and
uses it as a delay-loop count; a hardcoded 0 there caused an
8-bit-counter wraparound (256 iterations instead of a handful) on every
touch.

### Motor-off timing (the real fix behind the ~5x boot slowdown)

The Disk II controller card has a real 556 dual-timer chip whose one-shot
half keeps the drive motor spinning for a bounded grace period after
software commands it off — specifically so quick, repeated accesses
(retries, successive sector loads) don't pay a real spin-down/spin-up
cost each time. `Disk2Controller` originally modeled motor-off as an
instant cutoff (`motorOn = false` immediately, `tick()` gating *all* LSS
advancement on it) — real hardware does not behave this way.

**Component values**, read directly off the real Disk II controller
schematic (image at `bigmessowires.com/wp-content/uploads/2021/11/
disk2controller.jpg`) and independently corroborated by multiple
descriptions of the same schematic (e.g. a comp.sys.apple2/Applefritter
thread explicitly identifying the 556 as "responsible for keeping the
motor running for a few seconds after the drive is deselected, to save
spin-up time if it is soon reselected"):

- R = 47 kΩ, C = 22 µF
- Standard 555/556 monostable formula: `T = 1.1 × R × C`
- `T = 1.1 × 47,000 × 22×10⁻⁶ = 1.1374 seconds`
- Converted to CPU cycles at Sather's documented primary 6502 clock rate
  (~1.0227 MHz, *Understanding the Apple II*, Chapter 3):
  `1.1374 × 1,022,727 ≈ 1,163,250 cycles`

This is now `Disk2Controller.MOTOR_OFF_DELAY_CYCLES = 1_163_250L`,
implemented as a retriggerable countdown: the off-switch starts (or
restarts) it rather than cutting power immediately; the motor stays
reported on (and the LSS keeps advancing) until `tick()` observes the
countdown actually reach zero. The on-switch cancels any pending
countdown immediately and unconditionally — confirmed via every real
trace captured this investigation that there is no equivalent delay on
power-up.

An earlier, wrong guess along the way is worth recording so it isn't
repeated: a first pass at this diagnosis assumed the ~1-second interval
between observed motor-off events was itself a documented Disk II
"auto-off timer," without checking what code was actually running at
that PC. Direct disassembly showed the motor-off touches were explicit,
deliberate 6502 code (the same RWTS fragment mirrored at two addresses —
`$3E4D` during early boot-stage loading, `$BE4D`/`$BD4F` during the main
retry loop), not any kind of automatic timeout. The *real* one-shot lives
on the controller card's own hardware, one level below the 6502 code
that touches the switch.

**Verified result**: the diagnostic this was built around (`$BD60`→
`$B9A0` cycle delta across all 16 boot-time retries) now reads exactly
148 cycles on every single retry — an exact match to real Virtual ][''s
own measured value. Total boot settle time dropped from ~46-53,000,000
cycles to 7,801,512 cycles, in the same range as real hardware's own
~11,000,000-cycle boot.

### CPU clock rate

Sather's documented primary 6502 clock rate is ~1.0227 MHz (1,022,727 Hz,
excluding the periodic "long cycle" elongation that produces the
slightly lower 1.0205 MHz composite/effective rate). This project's own
`SystemClock` does not throttle to real time and has no single named
"cycles per second" constant elsewhere in the codebase; the motor-off
timer above is the first place a real-world time duration needed
converting to cycles, and it uses 1,022,727 explicitly for that purpose,
cited to Sather rather than left as a bare literal.

---

## 2. Saturn Systems 64K/128K RAM Board (Researched, Not Yet Implemented)

Primary source: the actual Saturn Systems Operations Manual (1982),
Chapter 9, "Technical Information" — fetched directly, not summarized
secondhand. (Available at, among other mirrors,
`garrettsworkshop.com/files/RAM128/SATURN128MAN.pdf` and
`applelogic.org/files/SATURN128MAN.pdf`.)

### What it is

A slot-0 RAM expansion built on top of the standard Apple Language
Card's 16K bank-switching scheme. The 128K board is eight independent
16K banks (64K board: four); the *first* 16K bank behaves exactly like a
standard Language Card, which is why existing Language-Card-aware
software (PASCAL, FORTRAN, etc.) works on it unmodified. This project's
existing `LanguageCard.java` is a correct, verified implementation of
that base mechanism and should be treated as the foundation to extend,
not reimplemented from scratch — with one important behavioral
divergence noted below.

### Memory layout

Each 16K bank occupies `$D000`-`$FFFF` (the same space as the system
ROM), split into two independently-selectable 4K sub-banks ("4K Bank A"
and "4K Bank B") at `$D000`-`$DFFF`, plus one 8K region at `$E000`-`$FFFF`
that belongs entirely to whichever 16K bank is currently selected (i.e.
each of the 8 banks has its own complete, private 16K — 4K + 4K + 8K —
not a shared upper region).

### Addressing

Control range: `$C0N0`-`$C0NF`, where `N = 8 + slot`. For slot 0 (the
manual's and this project's typical placement): `$C080`-`$C08F`.

Address line A2 selects between two modes:

- **A2 = 0 (state select)**: A0 = write-protect (0) / write-enable (1);
  A0+A1 together select ROM-read vs. RAM-read; A3 selects 4K Bank A (0)
  vs. 4K Bank B (1) within the *currently selected* 16K bank.
- **A2 = 1 (16K bank select)**: A0, A1, A3 together select which of the 8
  16K banks is current.

Full truth table, verbatim from the manual:

```
$C0N0: 4K Bank A; RAM read;  write protect
$C0N1: 4K Bank A; ROM read;  write enabled
$C0N2: 4K Bank A; ROM read;  write protect   *
$C0N3: 4K Bank A; RAM read;  write enabled
$C0N8: 4K Bank B; RAM read;  write protect
$C0N9: 4K Bank B; ROM read;  write enabled
$C0NA: 4K Bank B; ROM read;  write protect   *
$C0NB: 4K Bank B; RAM read;  write enabled
$C0N4: select 16K Bank 1        $C0NC: select 16K Bank 5
$C0N5: select 16K Bank 2        $C0ND: select 16K Bank 6
$C0N6: select 16K Bank 3        $C0NE: select 16K Bank 7
$C0N7: select 16K Bank 4        $C0NF: select 16K Bank 8
```

`*` = this combination (ROM read + write-protect) effectively disables
the board. This is the power-up default state, with 16K Bank 1 selected.

### Write-enable — the one genuine divergence from `LanguageCard`

The standard Apple Language Card requires two consecutive **reads** of a
qualifying odd address to arm write-enable; any write resets the
sequence. The Saturn manual states plainly that its board differs: "a
memory access... can be accomplished by either reading or writing to the
location" — i.e. on real Saturn hardware, *either* a read or a write to
a write-enable-eligible address counts toward the double-access arming
sequence. A from-scratch Saturn implementation cannot simply reuse
`LanguageCard`'s existing `applyControlAccess` unmodified; it needs its
own state machine that treats reads and writes identically for arming
purposes. (If already write-enabled and a different write-enabled mode
is selected, only one access to the new mode's address is needed — same
nuance as the standard card.)

### Memory-mapped uses documented in the manual (for context, not required to emulate the card itself)

- Relocated DOS occupies 16K Bank 2 + 4K Bank 2A (enabled via `$C0N3` +
  bank-select `$C0N5`)
- Alternate BASIC (Integer on a II+, Applesoft on a II) occupies 16K
  Bank 1 + 4K Bank 1A (enabled via `$C0N0` + bank-select `$C0N4`)
- 4 status LEDs reflect the 3-bit bank number plus RAM-read state

---

## 3. Videx VideoTerm 80-Column Card (Researched, Not Yet Implemented)

Primary source: the actual Videx VideoTerm Installation and Operation
Manual, Third Edition (January 1982), read in full — including the
memory-mapping section and firmware listing that earlier fetch attempts
against a hosted copy could not extract (see sourcing note at the end of
this section) — **plus an independent binary dump of the actual
Firmware 2.4 ROM** (`Videx Videoterm ROM 2.4.bin`, 1024 bytes, CRC32
`4DDBE669`), used to verify specific addresses byte-for-byte rather than
relying on the manual's printed (and OCR'd) listing alone. Where the two
disagreed, the ROM binary is authoritative.

**Firmware note**: as of Firmware 2.4 (the version documented in this
manual's own errata), the card **must** be in slot 3 — slot
independence was a feature of earlier firmware and was explicitly
removed.

### RAM scratch locations (Table 1, all addresses `+ n` where n = slot)

| Description | Address |
|---|---|
| Screen base addr. (low) | `$478 + n` |
| Screen base addr. (high) | `$4F8 + n` |
| Cursor horizontal position | `$578 + n` |
| Cursor vertical position | `$5F8 + n` |
| Pascal char. write location | `$678 + n` |
| First line on screen | `$6F8 + n` |
| Power-off/leading counter | `$778 + n` |
| Video set-up flags | `$7F8 + n` |

### Device select (`$C0(8+n)x`; slot 3 → `$C0B0`-`$C0BF`)

- **Bit 0**: 0 = register-select access (which of 18 CRTC registers), 1 =
  register-data access (the value to write). Two-write sequence to
  change a register: write the index to the even offset, then the value
  to the odd offset (e.g. `$C0B0` then `$C0B1`).
- **Bit 1**: 8- vs. 9-cell matrix width; VideoTerm always uses 0.
- **Bits 2-3**: which of 4 pages (0-3) of the 2KB on-board VRAM is
  currently mapped into a fixed `$CC00`-`$CDFF` window. A read of
  `$C0B0`/`$C0B4`/`$C0B8`/`$C0BC` (bits 2-3 = 00/01/10/11) selects the
  page; the access itself (value read is irrelevant) is what performs
  the selection.

### CRTC registers (Hitachi HD46505SP / Motorola MC6845-compatible, 18 total)

| Reg | Width | R/W | Purpose |
|---|---|---|---|
| R0 | 8-bit | W | Horizontal Total |
| R1 | 8-bit | W | Horizontal Displayed |
| R2 | 8-bit | W | Horizontal Sync Position |
| R3 | 4-bit | W | Horizontal Sync Width |
| R4 | 7-bit | W | Vertical Total |
| R5 | 5-bit | W | Vertical Total Adjust |
| R6 | 7-bit | W | Vertical Displayed |
| R7 | 7-bit | W | Vertical Sync Position |
| R8 | 2-bit | W | Interlace Mode (bit0: interlaced; bit1, if bit0 set: with-video) |
| R9 | 5-bit | W | Max Scan Line Address (scan lines per char row, minus 1) |
| R10 | 7-bit | W | Cursor Start (bits 0-4: start row 0-11; bits 5-6: blink mode) |
| R11 | 5-bit | W | Cursor End (bits 0-4: end row, ≥ start row) |
| R12/R13 | 6/8-bit | W | Start Address hi/lo — **do not modify**; breaks scrolling |
| R14/R15 | 6/8-bit | R/W | Cursor Position hi/lo |
| R16/R17 | 6/8-bit | R | Light Pen register hi/lo (captured on light-pen strobe) |

R10 bits 5-6 (cursor blink): bit6=0 → no blink (bit5 then selects
displayed/not); bit6=1, bit5=0 → 1/16-field-rate blink; bit6=1, bit5=1 →
1/32-field-rate blink.

### Video set-up flags (`$7F8 + n`)

Only 4 of 8 bits are used:

| Bit | Meaning |
|---|---|
| 0 | Character set: 0 = standard, 1 = alternate (or, with the inverse-video hardware mod, 0 = normal, 1 = inverse) |
| 4 | Rows: 0 = 18 lines, 1 = 24 lines |
| 6 | Case conversion: 0 = no conversion, 1 = convert entered text to lowercase (toggled by CTRL-A) |
| 7 | Input source flag: 0 = came from a GET, 1 = came from GETLN (INPUT statement or direct keyboard) |

### VIDEOTERM memory mapping (`$C800`-`$CFFF`), confirmed from the manual's own text

- `$C800`-`$CBFF` (1K): the controlling firmware itself
- `$CC00`-`$CDFF` (512 bytes of address space): the paged window onto the
  2K on-board VRAM (see paging, below)
- `$CE00`-`$CFFF`: explicitly stated as **not presently used**

**Paging**, spelled out in the manual's own words: the 2048-byte VRAM is
divided into four 512-byte pages. A `PEEK` at a page-select address
(`$C0B0`, `$C0B4`, `$C0B8`, or `$C0BC` for slot 3 — i.e. bits 2-3 of the
device-select offset) activates that page; the value read is
irrelevant, only the access matters. Once active, any access to
`$CC00`-`$CDFF` reads/writes that page, at the page-relative address
(`full_address MOD 512`). Given the current screen-start-line pointer
at `$6F8 + n` (already in the RAM table above), the manual's own
algorithm for locating a character at screen column X (0-79), row Y
(0-23) is:

```
ADDRESS = X + Y * 80 + PEEK($6F8 + n) * 16      ' mod 2048 to wrap
PAGE = ADDRESS / 512
SELECT = PEEK($C080 + n*16 + PAGE * 4)          ' activates the page
POKE $CC00 + (ADDRESS MOD 512), character_code  ' writes the character
```

### C8-space ownership — confirmed against the actual firmware ROM binary

An independent binary dump of Firmware 2.4 (`Videx Videoterm ROM 2.4.bin`,
exactly 1024 bytes, covering `$C800`-`$CBFF`; CRC32 `4DDBE669`) confirms
the general mechanism (claim ownership by touching `$C3xx`, release by
touching `$CFFF`) byte-for-byte, not just from the manual's printed
listing:

```
$CB36: 8D FF CF   STA $CFFF   ; confirmed in the ROM binary, exact match
```
(at the "BASIC INPUT ENTRY POINT," immediately after `INENTR` — the
manual's own comment reads "TURN OFF CO-RESIDENT MEMORY").

A third-party source (a modern FPGA reimplementation project's write-up)
described this same general mechanism but cited the release entry point
as `$C336` — **this is not correct**; the real address, confirmed
against the actual ROM bytes, is `$CB36`.

**The `$CC00`/`ROMSW` oddity flagged in an earlier pass of this document
is now resolved**, not just noted as unexplained: `$CC00` falls entirely
outside this 1024-byte ROM image (which ends at `$CBFF`). The "ROMSW"
routine (`STA $CFFF` / `STA $C300` / `RTS`) shown printed contiguously
with the firmware listing in the manual is genuinely **not part of the
resident VideoTerm EPROM** — it's separate code (likely something loaded
into RAM by other software) that the manual's printout happens to show
right after the actual ROM listing ends, not something baked into the
physical 2708 chip itself. Anyone implementing this in the emulator
should model `$CB36`'s behavior as the card's own, and treat `$CC00`
as out of scope for the VideoTerm ROM specifically.

### 40/80-column switching — confirmed, and corrects the third-party account

Real mechanism, from the manual's "Soft Video Switch Theory of
Operation" section, quoted directly: the Soft Video Switch (an optional
accessory; the older Switchplate is its manual, non-firmware-controlled
predecessor) watches the state of **annunciator 0** — a standard Apple
II annunciator output, not VideoTerm-specific hardware — together with
the color-killer signal. **"If the annunciator is off, the Soft Video
Switch displays 40 columns. If the annunciator is on, 80 columns is
displayed."** The firmware sets annunciator 0 on as each character is
output. To turn it on: a memory reference to `$C058`. To turn it off:
`$C059`.

**This corrects the third-party source directly**: that write-up's
Verilog snippet had the polarity backwards (`$C058` = off/40-col,
`$C059` = on/80-col in their code) — the real polarity, per Videx's own
manual, is the reverse: `$C058` = on = 80 columns, `$C059` = off = 40
columns. The confirmed firmware listing shows a `$C059` write at
`SETEXIT` (end of the `SETUP`/initialization routine at `$C800`) —
consistent with resetting to the 40-column/pass-through state as part
of a cold entry, before normal character output (which sets `$C058`
per-character) begins.

One scope note: this 40/80 switching lives in the *Soft Video Switch*
accessory's behavior specifically. The base VideoTerm card (with only
the older mechanical Switchplate, or no switch at all) still writes to
the annunciator the same way — the difference is purely in what
external hardware, if any, is watching that annunciator to decide what
actually reaches the monitor.

### Remaining gaps

The bulk of the firmware listing (over 1,000 bytes, `$C800` onward) has
now been read in full from the actual manual, and its two most
load-bearing claims (the `$CB36` and `$C82A` addresses used above) have
been independently confirmed byte-for-byte against the real ROM binary.
The `$CC00`/`ROMSW` question is now fully resolved (see above), not just
flagged. No open items remain in this section beyond the general caution
that only two specific addresses were spot-checked against the binary,
not the entire listing — if a future implementation needs some other
specific routine, checking it against the actual `Videx Videoterm ROM
2.4.bin` bytes directly (rather than trusting the OCR'd manual text) is
worth the few minutes it takes, given the OCR errors already caught in
the process of writing this section (digit confusions like `8D`→`80`/
`BD`, `F0`→`FO`, `C9`→`09`, all silently "corrected" by the ROM binary
during this pass).

### A note on sourcing this specific manual

The copy this section is drawn from is RC4-encrypted (print allowed,
copy disallowed) and was scanned/OCR'd via Adobe Photoshop and Acrobat
PDFWriter. Web-based text-extraction tools fetching it by URL
consistently, reproducibly cut off text extraction at the same exact
point (right at the start of "VIDEOTERM Memory Mapping") across
multiple attempts — very plausibly related to the copy restriction,
though the exact mechanism wasn't pinned down. Downloading the file
directly (`curl`) and running `pdftotext` locally on the actual file
extracted the complete document without issue. Worth remembering if
this happens again with a similarly-restricted PDF: the fetch-by-URL
path and the direct-file path can behave very differently on the same
document.
