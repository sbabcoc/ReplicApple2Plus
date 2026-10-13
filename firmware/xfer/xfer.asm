; =====================================================================
; XFER -- the host file transfer card's CP/M adapter (TRANSFER-CARD.md 8).
;
; A CP/M transient program, run from the CCP at $0100. The card delivers
; it as Intel HEX through CP/M's reader device; PIP and LOAD turn that
; into XFER.COM (8.2).
;
; It serves the abstract file API of TRANSFER-CARD.md 4 with BDOS calls,
; so CP/M itself reads and writes every file; the emulator never touches
; the guest's disks or memory. The Z80 can't address the Apple's I/O
; space, so every access to the card's registers goes through the
; SoftCard II BIOS entry each warm boot publishes at $0040: command 1
; reads a 6502 address, command 2 writes one (8.1).
;
; Containers are a drive and user area, "A0:" to "P15:" (8.3). Files
; carry no type: tags are empty, and the emulator decides what is text
; from the capability record (4.3, 8.4).
;
; Errors in a request's arguments are answered at once, without reading
; the rest: the card replaces the arguments with the next request's.
; =====================================================================

; ---- CP/M ------------------------------------------------------------
WBOOTV     equ  $0001       ; warm boot vector: BIOS jump table + 3
BDOS       equ  $0005
TBUFF      equ  $0080       ; default DMA buffer

C_WRITESTR equ 9
DRV_SET    equ 14
F_OPEN     equ 15
F_CLOSE    equ 16
F_SFIRST   equ 17
F_SNEXT    equ 18
F_DELETE   equ 19
F_READ     equ 20
F_WRITE    equ 21
F_MAKE     equ 22
DRV_GET    equ 25
F_DMAOFF   equ 26
DRV_ROVEC  equ 29
F_USERNUM  equ 32
F_SIZE     equ 35

; offsets of entries in the BIOS jump table
BIOS_SELDSK  equ 27
BIOS_SETTRK  equ 30
BIOS_SETSEC  equ 33
BIOS_SETDMA  equ 36
BIOS_READ    equ 39
BIOS_SECTRAN equ 48

; ---- the SoftCard II BIOS's memory-access entry (8.1) ----------------
SOFTCARD   equ  $0040       ; call with the command number in SC_CMD;
SC_VALUE   equ  $0045       ;   keeps BC, DE and HL
SC_CMD     equ  $0049
SC_ADDR    equ  $004A       ; 6502 address, low byte first
CMD_READ   equ  1           ; read SC_ADDR into SC_VALUE
CMD_WRITE  equ  2           ; write SC_VALUE to SC_ADDR

; ---- the card, in slot 2: CP/M's reader slot (8.2) -------------------
CARD_IO    equ  $C0A0       ; $C080 + slot * 16
REG_REQ    equ  $0
REG_DONE   equ  $1
REG_DATA   equ  $2
REG_STAT   equ  $3

VERSION    equ  1           ; the protocol version spoken
MSG_BEGIN  equ  $80

REQ_VOLUMES equ 1
REQ_LIST    equ 2
REQ_READ    equ 3
REQ_WRITE   equ 4
REQ_DELETE  equ 6
REQ_END     equ 7
REQ_CHECK   equ 8

RES_OK         equ 0
RES_NOT_FOUND  equ 1
RES_EXISTS     equ 2
RES_DISK_FULL  equ 3
RES_WRITE_PROT equ 4
RES_BAD_NAME   equ 5
RES_IO_ERROR   equ 6
RES_OTHER      equ 7

PAD        equ  $1A         ; ^Z: fills out a file's last record

        org  $0100

start:  ld   (savesp),sp
        ld   sp,stack
        ld   e,$FF
        ld   c,F_USERNUM
        call BDOS
        ld   (homeuser),a
        ld   c,DRV_GET
        call BDOS
        ld   (homedrive),a
        ld   de,TBUFF
        ld   c,F_DMAOFF
        call BDOS

        ld   a,REG_STAT         ; version, and bit 7: a host window
        call card_rd
        ld   b,a
        and  $7F
        cp   VERSION
        ld   de,msg_no_card
        jr   nz,quit
        bit  7,b
        ld   de,msg_no_host
        jr   z,quit

        ld   de,msg_title
        call print
        call begin
        call serve
        ld   de,msg_done

; Prints DE's message, puts the user area and drive back as they were,
; and returns to the CCP.
quit:   call print
        ld   a,(homeuser)
        ld   e,a
        ld   c,F_USERNUM
        call BDOS
        ld   a,(homedrive)
        ld   e,a
        ld   c,DRV_SET
        call BDOS
        ld   sp,(savesp)
        ret

print:  ld   c,C_WRITESTR
        jp   BDOS

; ---- the session -----------------------------------------------------

; BEGIN: the protocol version, then the capability record (4.3).
begin:  ld   a,VERSION
        call data_wr
        ld   hl,caps
        ld   b,caps_end - caps
.next:  ld   a,(hl)
        call data_wr
        inc  hl
        djnz .next
        ld   a,MSG_BEGIN
        jp   complete

; Answers requests until END. Polling the request register is also what
; tells the card the adapter is still here.
serve:  ld   a,REG_REQ
        call card_rd
        or   a
        jr   z,serve
        cp   REQ_END
        jr   z,.end
        ld   hl,serve
        push hl                 ; every handler returns to the loop
        cp   REQ_VOLUMES
        jp   z,volumes
        cp   REQ_LIST
        jp   z,list
        cp   REQ_READ
        jp   z,read
        cp   REQ_WRITE
        jp   z,write
        cp   REQ_DELETE
        jp   z,delete
        cp   REQ_CHECK
        jp   z,check
        ld   a,RES_OTHER        ; MAKE_DIR, or anything newer: CP/M is flat
        jp   complete
.end:   ld   a,RES_OK           ; the session is over: back to start
        jp   complete

; ---- VOLUMES: each drive's area 0, and every other area holding files -

volumes:
        ld   c,0                ; C = drive
.drive: push bc
        call drive_present
        pop  bc
        jr   z,.skip
        push bc
        call areas_used         ; HL = bit mask of user areas
        pop  bc
        ld   b,0                ; B = user area
.area:  bit  0,l
        jr   z,.unused
        push bc
        push hl
        call send_container
        pop  hl
        pop  bc
.unused:
        srl  h
        rr   l
        inc  b
        ld   a,b
        cp   16
        jr   c,.area
.skip:  inc  c
        ld   a,c
        cp   16
        jr   c,.drive
        xor  a                  ; the empty string ending the list
        call data_wr
        ld   a,RES_OK
        jp   complete

; Sends the string "Dn:" for drive C (0 = A) and user area B.
send_container:
        ld   a,b
        cp   10
        ld   a,3
        jr   c,.len
        inc  a
.len:   call data_wr
        ld   a,c
        add  a,'A'
        call data_wr
        ld   a,b
        cp   10
        jr   c,.digit
        ld   a,'1'
        call data_wr
        ld   a,b
        sub  10
.digit: add  a,'0'
        call data_wr
        ld   a,':'
        jp   data_wr

; Z clear if drive C (0 = A) exists and holds a readable disk. Asks the
; BIOS: BDOS would stop and wait for a key, with "Bdos Err On d: Select"
; for a drive that isn't there, or "Bad Sector" for an empty one (8.3).
; Reads the first directory sector, then points the BIOS back at the
; drive BDOS has selected.
drive_present:
        ld   e,0
        ld   a,BIOS_SELDSK
        call bios               ; HL = the drive's DPH, or 0
        ld   a,h
        or   l
        jr   z,.result
        push hl
        ld   e,(hl)             ; the sector translation table: DPH + 0
        inc  hl
        ld   d,(hl)
        ld   (xlt),de
        pop  hl
        ld   de,10              ; the disk parameter block: DPH + 10
        add  hl,de
        ld   e,(hl)
        inc  hl
        ld   d,(hl)
        ld   hl,13              ; the reserved tracks: DPB + 13
        add  hl,de
        ld   c,(hl)
        inc  hl
        ld   b,(hl)
        ld   a,BIOS_SETTRK      ; the directory's first track
        call bios
        ld   bc,0
        ld   de,(xlt)
        ld   a,BIOS_SECTRAN
        call bios
        ld   b,h
        ld   c,l
        ld   a,BIOS_SETSEC
        call bios
        ld   bc,sector
        ld   a,BIOS_SETDMA
        call bios
        ld   a,BIOS_READ
        call bios               ; A = 0 if it read
        or   a
        ld   a,1                ; Z clear: there
        jr   z,.result
        xor  a                  ; Z set: no readable disk
.result:
        or   a
        push af
        call restore_drive
        pop  af
        ret

; Points the BIOS's selection back at the drive BDOS has selected. (BDOS
; sets the DMA address itself before each disk access.)
restore_drive:
        ld   c,DRV_GET
        call BDOS
        ld   c,a
        ld   e,1                ; not a first selection
        ld   a,BIOS_SELDSK
        ; falls through

; Calls the BIOS jump table entry at offset A, with BC and DE as given.
bios:   ld   hl,(WBOOTV)        ; the table's address + 3
        sub  3
        add  a,l
        ld   l,a
        jr   nc,.go
        inc  h
.go:    jp   (hl)

; The user areas holding files on drive C, as a bit mask in HL (bit n =
; area n), with area 0 always set. One directory search with '?' in the
; FCB's drive byte returns every entry, whatever its user area.
areas_used:
        ld   e,c
        ld   c,DRV_SET
        call BDOS
        ld   hl,fcb
        ld   b,12
.wild:  ld   (hl),'?'
        inc  hl
        djnz .wild
        call clear_fcb_tail
        ld   hl,1
        ld   (mask),hl
        ld   c,F_SFIRST
.search:
        ld   de,fcb
        call BDOS
        cp   $FF
        jr   z,.done
        call entry_address      ; HL = the entry: its user area first
        ld   a,(hl)             ; $E5: a deleted entry
        cp   16
        jr   nc,.another
        ld   b,a
        ld   hl,1
        inc  b
.shift: dec  b
        jr   z,.set
        add  hl,hl
        jr   .shift
.set:   ld   de,(mask)
        ld   a,l
        or   e
        ld   e,a
        ld   a,h
        or   d
        ld   d,a
        ld   (mask),de
.another:
        ld   c,F_SNEXT
        jr   .search
.done:  ld   a,(homedrive)      ; BDOS back on the drive it was on
        ld   e,a
        ld   c,DRV_SET
        call BDOS
        ld   hl,(mask)
        ret

; HL = the directory entry a search returned in A (0-3): TBUFF + A * 32.
entry_address:
        rrca
        rrca
        rrca
        add  a,TBUFF & $FF
        ld   l,a
        ld   h,TBUFF >> 8
        ret

; ---- LIST: one container's files -------------------------------------

list:   call read_container     ; selects its user area; sets (drive)
        jp   nz,not_found
        call fill_wild
        ld   hl,names
        ld   (nameend),hl
        ld   c,F_SFIRST
.search:
        ld   de,fcb
        call BDOS
        cp   $FF
        jr   z,.collected
        call entry_address
        inc  hl                 ; past the user area, to the name
        call add_name           ; once per file, however many extents
        ld   c,F_SNEXT
        jr   .search
.collected:
        ld   hl,names
.entry: ld   de,(nameend)
        or   a
        sbc  hl,de
        add  hl,de
        jr   z,.end
        push hl
        call set_fcb_name
        call send_name
        xor  a                  ; kind: a file, size exact to the record
        call data_wr
        xor  a                  ; tag: empty
        call data_wr
        xor  a                  ; aux
        call data_wr
        xor  a
        call data_wr
        xor  a                  ; attributes: not carried (4.3)
        call data_wr
        call send_size
        pop  hl
        ld   de,11
        add  hl,de
        jr   .entry
.end:   xor  a
        call data_wr
        ld   a,RES_OK
        jp   complete

; Adds the 11-byte name at HL to the list at names, unless it's there:
; a file over 16K has a directory entry per extent. The attribute bits
; (each character's bit 7) are cleared.
add_name:
        ld   de,cand
        ld   b,11
.copy:  ld   a,(hl)
        and  $7F
        ld   (de),a
        inc  hl
        inc  de
        djnz .copy
        ld   hl,names
.compare:
        ld   de,(nameend)
        or   a
        sbc  hl,de
        add  hl,de
        jr   z,.add
        push hl
        ld   de,cand
        ld   b,11
.same:  ld   a,(de)
        cp   (hl)
        jr   nz,.differ
        inc  hl
        inc  de
        djnz .same
        pop  hl
        ret                     ; already listed
.differ:
        pop  hl
        ld   de,11
        add  hl,de
        jr   .compare
.add:   ex   de,hl
        ld   hl,cand
        ld   bc,11
        ldir
        ld   (nameend),de
        ret

; Sends the FCB's name as a string: "NAME.EXT", or "NAME" without one.
send_name:
        ld   hl,fcb + 1
        ld   b,8
        call trimmed
        ld   c,a                ; the name part's length
        ld   hl,fcb + 9
        ld   b,3
        call trimmed
        ld   b,a                ; the extension's length
        ld   a,c
        add  a,b
        inc  b
        dec  b
        jr   z,.len
        inc  a                  ; the dot
.len:   call data_wr
        ld   hl,fcb + 1
        ld   a,c
        call send_chars
        ld   a,b
        or   a
        ret  z
        ld   a,'.'
        call data_wr
        ld   hl,fcb + 9
        ld   a,b
        ; falls through

; Sends A characters from HL.
send_chars:
        or   a
        ret  z
        push bc
        ld   b,a
.next:  ld   a,(hl)
        call data_wr
        inc  hl
        djnz .next
        pop  bc
        ret

; The length, in A, of the B characters at HL without trailing spaces.
trimmed:
        ld   e,b
        ld   d,0
        add  hl,de
.back:  dec  hl
        ld   a,(hl)
        cp   ' '
        jr   nz,.found
        djnz .back
.found: ld   a,b
        ret

; Sends the FCB's file's size in bytes, 4 bytes little-endian. CP/M
; counts 128-byte records, so it's records * 128: shifted left 7 bits.
send_size:
        ld   de,fcb
        ld   c,F_SIZE
        call BDOS
        ld   hl,(fcb + 33)      ; the record count, low 16 bits
        ld   a,(fcb + 35)       ;   and its high byte
        ld   b,a
        xor  a
        srl  b
        rr   h
        rr   l
        rra
        call data_wr
        ld   a,l
        call data_wr
        ld   a,h
        call data_wr
        ld   a,b
        jp   data_wr

; ---- READ: a flag (0: no type follows), then the file's bytes ---------

read:   call read_path
        jp   nz,not_found
        ld   de,fcb
        ld   c,F_OPEN
        call BDOS
        cp   $FF
        jp   z,not_found
        xor  a
        call data_wr
.record:
        ld   de,fcb
        ld   c,F_READ
        call BDOS
        or   a
        jr   nz,.end            ; end of file
        ld   hl,TBUFF
        ld   a,128
        call send_chars
        jr   .record
.end:   ld   a,RES_OK
        jp   complete

; ---- WRITE: path, tag, aux, attributes, size, then the data -----------

write:  call read_path
        jp   nz,bad_name
        call data_rd            ; tag: CP/M files have none
        call skip_bytes
        call data_rd            ; aux
        call data_rd
        call data_rd            ; attributes: not carried
        call data_rd            ; size, little-endian; CP/M files fit in 24 bits
        ld   l,a
        call data_rd
        ld   h,a
        call data_rd
        ld   (left + 2),a
        call data_rd
        ld   (left),hl
        call drive_read_only
        ld   a,RES_WRITE_PROT
        jp   nz,complete        ; BDOS would stop with "Bdos Err On d: R/O"
        ld   de,fcb
        ld   c,F_OPEN
        call BDOS
        cp   $FF
        ld   a,RES_EXISTS
        jp   nz,complete
        call clear_fcb_tail
        ld   de,fcb
        ld   c,F_MAKE
        call BDOS
        cp   $FF
        ld   a,RES_DISK_FULL    ; no directory space
        jp   z,complete
.record:
        ld   hl,(left)
        ld   a,(left + 2)
        or   h
        or   l
        jr   z,.close
        ld   hl,TBUFF
        ld   b,128
.byte:  push hl
        call take_byte
        pop  hl
        ld   (hl),a
        inc  hl
        djnz .byte
        ld   de,fcb
        ld   c,F_WRITE
        call BDOS
        or   a
        jr   z,.record
        ld   de,fcb             ; the disk is full: leave nothing half-written
        ld   c,F_CLOSE
        call BDOS
        ld   de,fcb
        ld   c,F_DELETE
        call BDOS
        ld   a,RES_DISK_FULL
        jp   complete
.close: ld   de,fcb
        ld   c,F_CLOSE
        call BDOS
        cp   $FF
        ld   a,RES_IO_ERROR
        jp   z,complete
        ld   a,RES_OK
        jp   complete

; The next byte of the file being written, or the pad byte once its
; declared size is used up.
take_byte:
        ld   hl,(left)
        ld   a,(left + 2)
        or   h
        or   l
        ld   a,PAD
        ret  z
        ld   a,h
        or   l
        jr   nz,.count
        ld   a,(left + 2)
        dec  a
        ld   (left + 2),a
.count: dec  hl
        ld   (left),hl
        jp   data_rd

; ---- DELETE -----------------------------------------------------------

delete: call read_path
        jp   nz,not_found
        call drive_read_only
        ld   a,RES_WRITE_PROT
        jp   nz,complete
        ld   de,fcb
        ld   c,F_DELETE
        call BDOS
        cp   $FF
        jp   z,not_found
        ld   a,RES_OK
        jp   complete

; ---- CHECK_WRITABLE: the drive is there, and not read-only ------------

check:  call read_container
        jp   nz,not_found
        ld   a,(drive)
        ld   c,a
        call drive_present
        jp   z,not_found
        call drive_read_only
        ld   a,RES_WRITE_PROT
        jp   nz,complete
        ld   a,RES_OK
        jp   complete

; Z clear if the drive in (drive) is marked read-only.
drive_read_only:
        ld   c,DRV_ROVEC
        call BDOS               ; HL: bit n = drive n
        ld   a,(drive)
        ld   b,a
        inc  b
.shift: dec  b
        jr   z,.bit
        srl  h
        rr   l
        jr   .shift
.bit:   bit  0,l
        ret

not_found:
        ld   a,RES_NOT_FOUND
        jp   complete

bad_name:
        ld   a,RES_BAD_NAME
        jp   complete

; ---- paths ------------------------------------------------------------

; A path of one element, a container: sets (drive) and selects the user
; area. Z set if it's a container.
read_container:
        call data_rd            ; the count of path elements
        cp   1
        jr   nz,bad_path
        jr   container

; A path of two elements, a container and a file name: sets (drive),
; selects the user area, and fills in the FCB. Z set if they're valid.
read_path:
        call data_rd
        cp   2
        jr   nz,bad_path
        call container
        ret  nz
        jr   file_name

; A container string, "Dn:" -- drive A-P, user area 0-15.
container:
        call data_rd            ; its length: 3 or 4
        ld   b,a
        cp   3
        jr   c,bad_path
        cp   5
        jr   nc,bad_path
        call data_rd
        sub  'A'
        cp   16
        jr   nc,bad_path
        ld   (drive),a
        call data_rd
        sub  '0'
        cp   10
        jr   nc,bad_path
        ld   c,a
        ld   a,b
        cp   4
        jr   nz,.colon
        ld   a,c                ; two digits: 10-15
        cp   1
        jr   nz,bad_path
        call data_rd
        sub  '0'
        cp   6
        jr   nc,bad_path
        add  a,10
        ld   c,a
.colon: call data_rd
        cp   ':'
        jr   nz,bad_path
        ld   e,c
        ld   c,F_USERNUM
        call BDOS
        xor  a                  ; Z: valid
        ret

bad_path:
        or   1                  ; NZ: not valid
        ret

; A file name string into the FCB: "NAME.EXT", 1-8 and 0-3 characters,
; upper case. Z set if CP/M can hold it.
file_name:
        call clear_fcb
        call data_rd
        ld   b,a                ; characters left in the string
        or   a
        jr   z,bad_path
        ld   hl,fcb + 1
        ld   c,9                ; room in the name part, plus one
.base:  call data_rd
        dec  b
        cp   '.'
        jr   z,.ext
        dec  c
        jr   z,bad_path
        call fcb_char
        jr   nz,bad_path
        ld   (hl),a
        inc  hl
        inc  b
        dec  b
        jr   nz,.base
        ld   a,c                ; Z only if the name part is empty
        cp   9
        jr   z,bad_path
        xor  a
        ret
.ext:   ld   a,c
        cp   9
        jr   z,bad_path         ; no name part before the dot
        ld   hl,fcb + 9
        ld   c,4
.extch: inc  b
        dec  b
        jr   z,.done
        call data_rd
        dec  b
        dec  c
        jr   z,bad_path
        call fcb_char
        jr   nz,bad_path
        ld   (hl),a
        inc  hl
        jr   .extch
.done:  xor  a
        ret

; A in upper case; Z clear if CP/M doesn't allow it in a name.
fcb_char:
        cp   'a'
        jr   c,.upper
        cp   'z' + 1
        jr   nc,.upper
        sub  'a' - 'A'
.upper: cp   '!'
        jr   c,.no              ; controls and space
        cp   $7F
        jr   nc,.no
        push hl
        push bc
        ld   hl,forbidden
        ld   bc,forbidden_end - forbidden
        cpir
        pop  bc
        pop  hl
        jr   z,.no
        cp   a                  ; Z: allowed
        ret
.no:    or   1
        ret

forbidden:
        db   "<>.,;:=?*[]"
forbidden_end:

; An FCB for the container's drive, with a blank name.
clear_fcb:
        ld   hl,fcb
        ld   a,(drive)
        inc  a
        ld   (hl),a
        inc  hl
        ld   b,11
.blank: ld   (hl),' '
        inc  hl
        djnz .blank
        ; falls through

; Clears everything in the FCB after the name.
clear_fcb_tail:
        ld   hl,fcb + 12
        ld   b,24
.zero:  ld   (hl),0
        inc  hl
        djnz .zero
        ret

; An FCB matching every file of the container.
fill_wild:
        call clear_fcb
        ld   hl,fcb + 1
        ld   b,11
.wild:  ld   (hl),'?'
        inc  hl
        djnz .wild
        ret

; The FCB, for the container's drive, named by the 11 bytes at HL.
set_fcb_name:
        push hl
        call clear_fcb
        pop  hl
        ld   de,fcb + 1
        ld   bc,11
        ldir
        ret

; Reads and discards A bytes of the request's arguments.
skip_bytes:
        or   a
        ret  z
        push bc
        ld   b,a
.next:  call data_rd
        djnz .next
        pop  bc
        ret

; ---- the card's registers, through the SoftCard II BIOS (8.1) ---------
; Each keeps BC, DE and HL, as the BIOS entry does.

data_rd:
        ld   a,REG_DATA
        ; falls through

; Reads card register A into A.
card_rd:
        add  a,CARD_IO & $FF
        ld   (SC_ADDR),a
        ld   a,CARD_IO >> 8
        ld   (SC_ADDR + 1),a
        ld   a,CMD_READ
        ld   (SC_CMD),a
        call SOFTCARD
        ld   a,(SC_VALUE)
        ret

; Writes A to the data port.
data_wr:
        ld   (SC_VALUE),a
        ld   a,REG_DATA
        jr   card_wr

; Ends the current message with completion code A.
complete:
        ld   (SC_VALUE),a
        ld   a,REG_DONE
        ; falls through

; Writes (SC_VALUE) to card register A.
card_wr:
        add  a,CARD_IO & $FF
        ld   (SC_ADDR),a
        ld   a,CARD_IO >> 8
        ld   (SC_ADDR + 1),a
        ld   a,CMD_WRITE
        ld   (SC_CMD),a
        jp   SOFTCARD

; ---- the capability record (4.3, 5.2) ---------------------------------

caps:   db   $01, 4, "CP/M"                 ; OS name
        db   $02, 3, "2.2"                  ; OS version
        db   $03, 1, 0                      ; structure: flat
        db   $04, 1, 12                     ; longest name: 8 + dot + 3
        db   $05, 1, $01                    ; upper case only
        db   $06, extra_end - extra         ; other characters names may use
extra:  db   "!\"#$%&'()+-/@\\^_`{|}~"
extra_end:
        db   $07, 2, $0D, $0A               ; text line ending: CR LF
        db   $08, 1, 0                      ; text high bit clear
        db   $0A, 3, 0, 0, 0                ; default text type: none
        db   $0B, 3, 0, 0, 0                ; default other type: none
        db   $0C, 1, 0                      ; default attributes
        db   $0E, 1, PAD                    ; text ends at ^Z
        db   $0F, 3, $80, $00, PAD          ; 128-byte records, padded with ^Z
        db   $10, 2, 8, 3                   ; names are NAME.EXT, 8 + 3
        db   $11, text_end - text           ; no file types; text by extension
text:   db   3, "ASM", 3, "MAC", 3, "LIB", 3, "PRN", 3, "SYM", 3, "HEX"
        db   3, "SUB", 3, "DOC", 3, "TXT", 2, "ME", 3, "FOR", 3, "COB"
        db   3, "PAS", 1, "C", 1, "H", 3, "BAK"
text_end:
        db   $00                            ; end of the record
caps_end:

msg_title:   db "HOST TRANSFER: CP/M 2.2", 13, 10, '$'
msg_done:    db "HOST TRANSFER ENDED", 13, 10, '$'
msg_no_card: db "XFER: NO HOST FILE TRANSFER CARD IN SLOT 2", 13, 10, '$'
msg_no_host: db "HOST TRANSFER IS NOT AVAILABLE", 13, 10, '$'

; ---- data -------------------------------------------------------------

savesp:    dw 0
homeuser:  db 0
homedrive: db 0
drive:     db 0
mask:      dw 0
left:      ds 3             ; bytes of the file being written still to come
nameend:   dw 0
fcb:       ds 36
cand:      ds 11
xlt:       dw 0
sector:    ds 128           ; the directory sector drive_present reads
           ds 64
stack:
names:                      ; LIST's names, 11 bytes each, up to the BDOS
