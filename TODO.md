# TODO

Open work only. Completed tasks, with the reasoning and verification
history behind them, are in [TASK_RETRO.md](TASK_RETRO.md).

## Emulated printer (PDF)

An interface card plus a printer -- say, Epson-compatible -- rendering to
PDF, for program output on paper, graphics included. Text export is
already covered by printing to the transfer card (TRANSFER-CARD.md
section 7).

## Transfer card: CP/M import/export -- pinned

A CP/M adapter for the host file transfer card, so CP/M files can be
imported and exported like ProDOS and DOS 3.3 ones, on the Microsoft
SoftCard II emulated by the [SoftCard2](https://github.com/sbabcoc/SoftCard2)
project. Designed in [TRANSFER-CARD.md](TRANSFER-CARD.md) section 8: the
card presents a 6850 serial interface in slot 2, delivers its own Z80
adapter through CP/M's reader device, and the adapter reaches the
card's registers through the SoftCard II's memory-access commands.
Section 8.5 lists the decisions still open.

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
