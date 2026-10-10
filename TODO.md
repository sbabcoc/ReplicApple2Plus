# TODO

Open work only. Completed tasks, with the reasoning and verification
history behind them, are in [TASK_RETRO.md](TASK_RETRO.md).

## Host file transfer card -- built (ProDOS 8 and DOS 3.3)

A peripheral card, invented for this emulator, that moves files between
the host and the Apple's disks with the guest OS doing every file
operation. The emulator drives the transfer through host dialogs and an
abstract file API, knowing no guest OS; the card's firmware is the
adapter that implements that API for ProDOS (MLI) and DOS 3.3 (file
manager). The API is OS-neutral; CP/M support is pinned below.

Build phases:
1. **Card, Java side -- done.** Registers and wire format (TRANSFER-CARD.md
   5.2), sessions and the request queue, the `TransferHost` interface, the
   host naming convention, capability-driven text conversion, and the
   `SlotCard.onReset()` hook, tested headless with a register-level fake
   adapter. Not yet offered as a card type: it has no firmware ROM.
2. **Host transfer window -- done.** `TransferWindow` (Swing) as the
   card's `TransferHost`: guest volume/directory tree loaded lazily
   through the adapter, Import/Export with the platform's file dialogs and
   an editable table of suggested guest names (`GuestNames`), replace and
   overwrite questions, progress and log, Done/close sending END, and a
   "stopped responding" banner after 10 seconds. Transfer logic lives in
   `TransferOperations`, tested headless with the fake adapter on its own
   thread; the window was checked on a virtual display.
3. **ProDOS adapter firmware -- done.** `firmware/hostfiles/hostfiles.s`
   (ca65), image committed as a resource; the card is now a configurable
   type (`type=hostfiles`). Verified under real ProDOS 2.4.3 and
   BASIC.SYSTEM; `ProDosTransferIntegrationTest` runs the whole session
   when `ProDOS_2_4_3.po` is in its test resources.
4. **DOS 3.3 adapter firmware -- done.** Banks 2-3 of the same ROM;
   verified under the real DOS 3.3 System Master;
   `DosTransferIntegrationTest` runs the whole session when a System
   Master image (`DOS_3_3_System_Master.woz`/`.dsk`/`.po`) is in its test
   resources.
 Works the same on every platform, including Android
under Termux/PRoot (`/sdcard/...`). Full design, decisions and open
questions: [TRANSFER-CARD.md](TRANSFER-CARD.md).

Printing to the card is built too (`PR#n`; TRANSFER-CARD.md section 7).
Later, separately: an emulated printer -- an interface card plus, say, an
Epson-compatible printer rendering to PDF -- for program output on paper,
graphics included.

## Microsoft SoftCard II (Z80, CP/M) -- pinned

Not started; recorded so the groundwork isn't lost. No manual or
schematic is available, but the SoftCard II CP/M 2.28B master disk
(64K, 1984) carries both halves of the card's protocol -- the 6502 BIOS
and the Z80 BIOS -- so the card's behavior can be reverse-engineered
from code. Found by booting that disk with logging probe cards (card in
slot 4, registers `$C0C0`-`$C0CF`):

- `$C0n0`: read = next byte from the Z80; write = next byte to the Z80.
- `$C0n1`: bit 7 = a byte from the Z80 is waiting (`LDA`/`BPL` loops);
  bit 0 = the byte sent to the Z80 hasn't been taken yet (`ROR`/`BCS`).
- Startup: the Z80 executes instructions fed through the latch -- the
  6502 plants a 61-byte Z80 loader at `$8000` (`LD (HL),n` / `INC HL`
  pairs from the table at `$DAC1`), then sends `JP $8000`. The loader
  starts with `OUT (0),A`.
- Running: the 6502 serves the Z80 -- its loop at `$1444` waits for a
  command byte and dispatches through the table at `$0D63` (disk,
  screen, keyboard).

Still to establish, from the Z80 code: its I/O port assignments, what
starts and ends instruction feeding, and any reliance on interrupts or
timing. Needs a Z80 core (validated with a standard instruction
exerciser) plus the card itself.

## Transfer card: CP/M import/export -- pinned

A CP/M adapter for the host file transfer card, so CP/M files can be
imported and exported like ProDOS and DOS 3.3 ones. Depends on the
SoftCard II above. Because CP/M runs in the card's own RAM, the Z80
likely can't reach the transfer card's registers directly; the adapter
would go through CP/M's 6502-side server. Design notes in
[TRANSFER-CARD.md](TRANSFER-CARD.md), open question 2.

## Known issues and leads

Problems observed, limitations accepted, and leads noticed in passing.
Unless an entry says it was observed, treat it as a lead to confirm
with a real failing case before changing code.

- **Blank WOZ images leave INFO "optimal bit timing" at 0.**
  `WozDiskImage.buildBlankFileBytes` doesn't set INFO byte 39; the WOZ
  spec gives 32 (4 µs) for 5.25" disks. Not known to cause a problem.
- **VideoTerm features not modeled.** The alternate character set (VRAM
  bit 7 set; drawn with the standard glyphs instead) and the CRTC's light
  pen registers (R16/R17 read 0). Nothing known uses either.
- **No reliable in-session recovery from a skipped VideoTerm setup.**
  About 1 power-on in 32, the firmware's "initialized" marker at `$077B`
  starts out set and the first `PR#3` leaves the 80-column screen dark
  (see HARDWARE-REFERENCE.md). Reboot fixes it. Ctrl-Z `0` and
  `POKE 1915,0 : PR#3` each recovered one bad marker value but not
  another in testing -- the marker's low bits double as the firmware's
  command state -- so neither is a dependable answer yet.
