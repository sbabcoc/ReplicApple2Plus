# Host File Transfer Card -- Design

A peripheral card, invented for this emulator, that moves files between
the host and the Apple II's own disks. Not period hardware: this
document is the design, the way HARDWARE-REFERENCE.md records real
cards.

## 1. Principles

- **The guest OS owns its disks.** The emulator never reads or writes a
  file system inside a disk image. Every guest file operation is a real
  call into the guest OS, made by guest code.
- **The emulator knows no guest OS.** It defines an abstract file API
  and drives the whole transfer -- host dialogs, naming, conversions,
  errors -- through it. It never sees an OS call number, a parameter
  list, or a guest memory address.
- **The firmware is an adapter.** The card's firmware implements the
  abstract API for whichever OS is running: ProDOS through its Machine
  Language Interface (MLI), DOS 3.3 through its file manager. Adding an
  OS means adding an adapter, not changing the emulator.
- **The software ships with the card.** Nothing for the user to obtain.
- **Same on every platform**: macOS, Windows, Linux, and Android under
  Termux/PRoot (shared storage at `/sdcard/...`, verified readable and
  writable).

## 2. Configuration

```
[2]
type=hostfiles
```

Any slot 1-7, one card per machine. An optional `dir=` sets the folder
the host dialogs open in first.

## 3. User flow

1. From BASIC, the user types `PR#n` (n = the card's slot). The Apple
   screen shows a one-line status in 40 columns, e.g.
   `HOST TRANSFER: PRODOS 2.4` -- the work happens on the host.
2. The emulator opens a host window: **Import to Apple** or **Export to
   host**.
   - **Import:** the user picks host files with the platform's file
     dialog, then picks the destination on the Apple side -- a ProDOS
     volume and directory, or a DOS 3.3 drive -- from a tree the
     emulator builds by asking the adapter (`VOLUMES`, `LIST`).
   - **Export:** the user picks Apple files from that tree, then a host
     folder.
3. Names are adjusted to the guest's rules (reported by the adapter),
   shown for confirmation, and existing files are replaced only on
   confirmation.
4. The transfer runs; progress and any errors appear in the host
   window.
5. On close, the adapter restores borrowed memory and returns to BASIC
   with normal input and output, as if `PR#0` had been typed.

### 3.1 How a session starts -- within the hardware model

Every step is either the guest running code or the card reacting to its
own registers; the emulator never moves the program counter, writes
guest RAM, or interrupts the guest.

1. **The guest starts it.** `PR#n` (or `CALL` to the card's entry)
   makes the 6502 run the card's ROM through the ordinary slot
   mechanism, as with any real card.
2. **The firmware announces itself.** After detecting the OS, it writes
   "begin session" to the command register, then its `DESCRIBE` reply
   (protocol version, capability record).
3. **The card tells the host side.** That write runs the card's Java
   code on the emulation thread. The card passes the capabilities to a
   `TransferHost` interface it was given at startup and returns at once.
4. **The window opens on the UI thread.** The application's
   `TransferHost` schedules the transfer window with `invokeLater`; the
   emulation thread never waits on the UI.
5. **Requests flow back** through the card's queue, which the firmware
   polls; while the user is in a dialog it simply keeps polling.
6. **The session ends** with `END` or abandonment (5.1); the card tells
   `TransferHost`, which closes or updates the window.

The card never references Swing: it knows only `TransferHost`, wired by
the application as the VideoTerm's views are. Tests supply a scripted
`TransferHost`, so the whole protocol runs headless. With no host UI
present, "begin session" is answered with "not available", and the
firmware prints a message and returns.

Interrupts are deliberately not used to start a session: on a II+ the
interrupt vector belongs to the running software, ProDOS halts on an
interrupt no handler claims, and under DOS 3.3 the vector is whatever
is there. An optional menu item could type `PR#n` through the emulated
keyboard, as Paste does -- faithful, but only useful at a BASIC prompt.

## 4. The abstract API

Defined by the emulator, implemented by each adapter.

### 4.1 Requests

| Request | Arguments | Reply |
|---|---|---|
| `DESCRIBE` | -- | capability record (4.3) |
| `VOLUMES` | -- | list of containers at the top level: volumes or drives |
| `LIST` | container path | entries: name, kind (file or directory), type tag, aux value, attributes, size |
| `READ` | file path | the file's bytes, streamed |
| `WRITE` | file path, type tag, aux value, attributes | creates the file; bytes streamed in |
| `MAKE_DIR` | directory path | -- (only when hierarchical) |
| `DELETE` | file path | -- (to replace an existing file, after confirmation) |
| `END` | -- | restore memory, return to BASIC |

Paths are sequences of name components -- the adapter, not the
emulator, turns them into `/VOL/DIR/FILE` or a DOS drive and name.

### 4.2 Results

`OK`, `NOT_FOUND`, `EXISTS`, `DISK_FULL`, `WRITE_PROTECTED`,
`BAD_NAME`, `IO_ERROR`, or `OTHER` carrying the OS's own error code and
an optional message for display.

The emulator also has to cope with a request that never completes --
see 5.1.

### 4.3 Capability record

What the emulator needs to do its job without knowing the OS.

**Encoding: extensible.** The record is a sequence of tagged,
length-prefixed fields; a reader skips fields it doesn't know. New
fields can therefore be added later -- for an OS not yet supported --
without changing anything that reads the record today. The fields
defined now are the ones ProDOS and DOS 3.3 need; the CP/M column below
is a check that the scheme can grow, not work to do.

| Field | ProDOS | DOS 3.3 | CP/M (future) |
|---|---|---|---|
| display name, version | ProDOS 2.4 | DOS 3.3 | CP/M 2.2 |
| structure | hierarchical | flat, per drive | flat, per drive and user area |
| name rules | 15 chars, letter first, letters/digits/`.`, upper case | 30 chars, most printable characters, upper case | 8 + 3, upper case |
| text line ending | CR | CR | CR LF |
| text high bit | clear | set | clear |
| text end marker | -- | -- | `^Z` |
| size granularity | byte | byte | 128-byte record |
| file types | tag + aux | tag (T/I/A/B/S/R) + load address for B | none |
| attributes | access bits (locked etc.) | locked flag | read-only, system |
| default attributes | unlocked | unlocked | none set |
| *future:* pad byte for partial records | -- | -- | `$1A` |
| *future:* two-part names, forbidden characters | -- | -- | 8 + 3, `< > . , ; : = ? * [ ]` |

Rows are the adapters' declarations, not emulator code: the emulator
only applies whatever a record says. Rows marked *future* are fields
that would be added with an adapter that needs them; under CP/M they
mean an exported binary always comes out a whole number of 128-byte
records -- CP/M doesn't store exact lengths, so none can be preserved.

### 4.4 Types and attributes are opaque

An adapter reports each file's type as a short **tag** and an **aux
value** (e.g. `BIN` and `$2000` under ProDOS, `B` and its load address
under DOS 3.3), and its **attributes** as one opaque byte (e.g.
ProDOS's access bits, DOS 3.3's lock flag). The emulator never
interprets any of them: it encodes them in the host file name on export
and passes them back on import, so round trips are lossless.

**The host naming convention -- settled now, because it outlives every
other choice here** (files already exported must keep importing
faithfully):

```
NAME[#tag,aux[,attr]][.TXT]
```

- `#tag,aux` -- type tag and aux value in hex, e.g. `GAME#BIN,2000`.
- `,attr` -- the attribute byte in hex, present only when it differs
  from the adapter's declared default, e.g. `GAME#BIN,2000,21`.
- `.TXT` -- the file is text, converted for the host. A text file with
  default type and attributes is just `NAME.TXT`.
- Every character used is legal on Windows, macOS and Linux.
- Importing a host file with no type in its name: the adapter's
  declared default for text (if the file is valid text) or binary,
  with default attributes.

### 4.5 Conversions, driven by capabilities

Applied by the emulator, only to files whose tag the adapter marks as
text, and only for sequential files -- random-access text files, whose
records are addressed by byte position, are copied unchanged; the
adapter declares how to recognize them (capability `$0D`):

- line endings: the capability record's, to and from the host's (LF;
  CR LF and lone CR accepted on import);
- high bit: set or clear as the record says;
- *future:* end marker and size granularity, for an OS that needs them.

Everything else is byte-for-byte. Format details inside a file, such
as DOS 3.3's load-address header on B files, are the adapter's
business: the API always carries plain data.

## 5. Transport: the card's registers

CPU-neutral: whichever processor runs the adapter (the 6502 today, a
Z80 under a CP/M card some day) talks to the same registers.

| Offset (`$C0n0`+) | Read | Write |
|---|---|---|
| `$0` | request register: next request opcode, or `0` = none yet | -- |
| `$1` | -- | result register: completes the current request |
| `$2` | data port: next byte from the emulator | data port: next byte to the emulator |
| `$3` | card signature / protocol version | ROM bank select (`$C800`-`$CFFF`) |

- **Requests and replies** are byte streams through the data port:
  strings as a length byte plus characters, numbers little-endian.
- **The adapter polls.** It loops reading the request register; `0`
  means the user is still busy in a host dialog. The emulation thread
  never blocks; requests reach the card from the host UI thread through
  a thread-safe queue.
- **No DMA.** The emulator never touches guest RAM. Everything,
  including file data, crosses the data port. A tight 6502 copy loop
  moves a 64K file in about a second of emulated time.
- **Protocol version.** Register `$3` reads back the card's signature
  and protocol version, and the adapter states the version it speaks in
  its `DESCRIBE` reply; each side uses only what both support.

### 5.1 When the adapter goes away

A request can be left outstanding with no adapter to finish it: the
user presses RESET or Reboot mid-transfer, or (under CP/M 2.2) an OS
reacts to a disk error by ending the running program rather than
returning an error code. The card must never wait forever:

- **RESET:** the card abandons the transfer session at once. This needs
  a small addition to the card interface -- `SlotCard.onReset()`, a
  default no-op, called when the RESET line is asserted (on real
  hardware, the slot connector carries RESET to every card).
- **Reboot:** builds a new machine with a new card, so the session
  ends with the old card.
- **Silence:** if the adapter neither completes the request nor polls
  for a long time, the host window says the Apple stopped responding
  and offers to keep waiting or cancel.

In each case the host window reports which files completed and which
didn't. The emulator never retries a write on its own: a partly
written file is reported, and the guest OS's own tools decide what
happens to it.

### 5.2 Wire format (protocol version 1)

**Registers** (offset from `$C0n0`):

| Offset | Read | Write |
|---|---|---|
| `$0` | next request opcode; `0` = none yet; `END` whenever no session is open. Reading a request makes its arguments readable on `$2` | -- |
| `$1` | -- | completion code: ends the message the adapter just wrote to `$2` |
| `$2` | next byte of the current request's arguments (or of recalled memory); `0` once exhausted | next byte of the adapter's message |
| `$3` | bits 0-6: protocol version; bit 7: a host transfer window is available | ROM bank select |

**Adapter-initiated messages** -- bytes written to `$2`, then the code to `$1`:

| Code | Message | Payload |
|---|---|---|
| `$80` | BEGIN | protocol version spoken (1 byte), then the capability record |
| `$81` | STASH | borrowed memory to keep (opaque, any length) |
| `$82` | RECALL | none; the stashed bytes become readable on `$2` |

**Requests** (card to adapter) and their arguments:

| Opcode | Request | Arguments | Reply payload (with code `$00`) |
|---|---|---|---|
| `$01` | VOLUMES | -- | names, each a string; then an empty string |
| `$02` | LIST | path | entries; then an empty string |
| `$03` | READ | path | the file's bytes |
| `$04` | WRITE | path, tag, aux, attr, size, then `size` data bytes | -- |
| `$05` | MAKE_DIR | path | -- |
| `$06` | DELETE | path | -- |
| `$07` | END | -- | -- (session over) |

**Completion codes `$00`-`$7F`** end a request: `$00` OK, `$01`
NOT_FOUND, `$02` EXISTS, `$03` DISK_FULL, `$04` WRITE_PROTECTED, `$05`
BAD_NAME, `$06` IO_ERROR, `$07` OTHER (payload: the OS's own code, 1
byte, then a message string).

**Encodings:** a *string* is a length byte and that many bytes; a
*path* is a count byte and that many strings; *tag* is a string; *aux*
is 2 bytes, *size* 4 bytes, little-endian; *attr* is 1 byte. A list
*entry* is: name (string, never empty), kind (1 byte: 0 file, 1
directory), tag, aux, attr, size.

**Capability record:** fields of tag (1 byte), length (1 byte), value;
ended by tag `$00`. Readers skip unknown tags.

| Tag | Field | Value |
|---|---|---|
| `$01` | OS name | string bytes |
| `$02` | OS version | string bytes |
| `$03` | structure | 0 = flat, 1 = hierarchical |
| `$04` | maximum name length | 1 byte |
| `$05` | name rules | bit 0: upper case only; bit 1: must start with a letter |
| `$06` | allowed non-alphanumeric name characters | the characters |
| `$07` | text line ending | the byte sequence |
| `$08` | text high bit | 0 = clear, 1 = set |
| `$09` | tags that mean text | strings |
| `$0A` | default type for text files | tag (string), aux (2 bytes) |
| `$0B` | default type for other files | tag (string), aux (2 bytes) |
| `$0C` | default attributes | 1 byte |
| `$0D` | text aux is a record length | 1 = a text file with a non-zero aux value is random-access, and is never converted |

**Host names** -- the convention of 4.4, plus one rule: characters not
legal in host file names on every platform (`/ \ : * ? " < > |`,
control characters), the convention's own `#` and `%`, and a trailing
space or period are written as `%` and two hex digits, and decoded on
import.

## 6. Firmware (the ProDOS and DOS 3.3 adapters)

- **ROM:** 256 bytes at `$Cn00` (entry, signature) plus banked 2K
  pages at `$C800`-`$CFFF`. Size is not a constraint.
- **OS detection** -- ProDOS first, then DOS 3.3, confirmed against
  real systems booted in the emulator:
  - ProDOS: `$BF00` holds `JMP` (`$4C`), the MLI entry point in the
    system global page (ProDOS 8 Technical Reference, 5.2.4). Observed
    under ProDOS 2.4.3: `$BF00: 4C B7 BF`, `MACHID` (`$BF98`) = `$60`
    (II+, 64K), `KVERSION` (`$BFFF`) = `$24`.
  - DOS 3.3: the page-3 file manager vectors (*Beneath Apple DOS*,
    ch. 5-6). Observed under the DOS 3.3 System Master: `$3D6: 4C FD AA`
    (`JMP` to the file manager) and `$3DC: AD 0F 9D AC 0E 9D 60`
    (`LDA $9D0F / LDY $9D0E / RTS`: parameter-list address, high in A,
    low in Y). DOS occupies `$BF00` with its own code (`D3`), so it never
    looks like ProDOS.
  - Under ProDOS, page 3 can hold anything -- RAM powers up random -- so
    DOS is recognized by the whole `$3DC` routine shape plus the `JMP`
    at `$3D6`, never by a single byte.
  - Neither found: the status line says so and the card does nothing.
- **OS calls run from RAM.** The ProDOS manual states that MLI calls
  cannot be executed from the Language Card area, and while the OS is
  working, other cards' firmware can take over the shared `$C800`
  space. So the adapter copies its working code into RAM and runs it
  there.
- **RAM is borrowed, then restored.** Before starting, the adapter
  streams the RAM it will use -- its working code, the OS's buffers
  (ProDOS: a 1024-byte page-aligned I/O buffer plus a data buffer;
  DOS 3.3: 557 bytes of workarea and sector buffers), and the zero-page
  bytes it needs -- out to the card as an opaque block, and streams it
  back at `END`. Memory, including a BASIC program, is left exactly as
  it was. Under ProDOS the borrowed pages are ones the system memory
  bit map (`$BF58`-`$BF6F`) marks free, read at run time, because
  ProDOS refuses buffers in protected pages.
- **ProDOS calls:** CREATE ($C0), OPEN ($C8), READ ($CA), WRITE ($CB),
  CLOSE ($CC), GET_FILE_INFO ($C4), DESTROY ($C1), ON_LINE ($C5) --
  `JSR $BF00`, call byte, parameter-list pointer; carry set and A = error
  code on failure (Technical Reference, ch. 4).
- **DOS 3.3 calls:** OPEN, READ, WRITE, CLOSE, DELETE, CATALOG via
  `JSR $3DC` / `JSR $3D6`, with the file manager's own error codes
  (*Beneath Apple DOS*, ch. 6). Apple never published this interface,
  but its vectors and parameter-list format are unchanged across DOS
  versions, and Apple's FID uses it.
  - **`LIST` under DOS 3.3 (to confirm when building):** the file
    manager's CATALOG call is expected to print the catalog through the
    output hook rather than return entries, so the adapter would capture
    that output by pointing the hook at its own routine. DOS also counts
    file sizes in sectors, so `LIST` sizes are approximate under DOS
    3.3; exact sizes come from reading the file.

## 7. Open questions

1. **Assembler -- decided: ca65** (cc65 suite; macOS, Windows, Linux),
   with source and assembled ROM both committed, so building the
   emulator needs no 6502 toolchain. Merlin 32 remains an option to
   migrate to if its period syntax (and possibly assembling the source
   on the Apple itself) proves worth it; neither integrates with the
   Gradle build differently, since both are external tools.
2. **CP/M, for the Microsoft SoftCard II -- pinned.** No manual or
   schematic exists, but the SoftCard II CP/M 2.28B master disk has been
   obtained and its 6502-side protocol partly worked out; see TODO.md
   (SoftCard II, and Transfer card: CP/M import/export). Notes: the target card is the
   SoftCard II, not the original Z-80 SoftCard, and the difference
   matters here. On the original, the Z80 runs in the Apple's own RAM
   and can reach the Apple's I/O space through address translation --
   so it could use this card's registers directly. The SoftCard II has
   a 6 MHz Z80 with its own 64K of RAM as CP/M's execution space, so
   the Z80 most likely cannot address the Apple's I/O at all: disk,
   screen and keyboard go through a channel to CP/M's 6502-side code.
   A CP/M adapter would then be Z80 code talking to that 6502 side,
   which relays to this card:
   `Z80 adapter -> SoftCard II channel -> CP/M's 6502 host code -> this card`.
   - **Needed first:** the SoftCard II emulated, and the rest of how
     its Z80 and 6502 exchange data, from the master disk's Z80 code.
     The 6502 side is confirmed: two latches and two flags at
     `$C0n0`/`$C0n1`, with the 6502 acting as a server for the Z80.
   - **Unverified so far:** release date, CP/M version numbers and
     recommended slots, from an unsourced summary; collectors on
     Applefritter were still looking for a SoftCard II manual.
   - The API, capability record and register protocol above stay as
     they are: they are meant to cover a CP/M adapter unchanged.

## 8. Testing

- **Card and protocol:** unit tests drive the registers with a scripted
  fake adapter -- requests, replies, results, polling while a dialog is
  open -- against a temporary host folder.
- **Emulator logic:** naming, type encoding and conversions tested
  against capability records alone, so nothing depends on a particular
  OS. The host naming convention round-trips every type, aux value and
  attribute byte exactly; capability records with unknown fields are
  read without error.
- **Abandonment:** RESET mid-request ends the session at once; a silent
  adapter triggers the "stopped responding" choice; partial transfers
  are reported, never retried.
- **Adapters:** end-to-end runs in the emulator with real ProDOS 2.4.3
  and real DOS 3.3: import a text file and a binary, check them with the
  guest's own `CATALOG` and `BLOAD`/`LOAD`, export and compare host
  files. The guest OS's own commands are the oracle, not the card.
