; =====================================================================
; Host file transfer card firmware -- the ProDOS adapter.
;
; Implements the abstract file API of TRANSFER-CARD.md for ProDOS 8:
; the emulator asks (volumes, list, read, write, make directory, delete,
; end), this code answers by calling ProDOS's Machine Language Interface.
; The emulator never touches the guest's disks or memory.
;
; Layout (see hostfiles.cfg):
;   $Cn00 page  -- entry and finish; position-independent (any slot)
;   $C800       -- detection, borrowing RAM, starting the agent
;   AGENT       -- request loop and every MLI call, copied to and run
;                  from RAM at $0800: MLI calls can't be made from ROM
;                  space safely, and other cards may take $C800 away
;                  while ProDOS works.
;
; Rules kept throughout:
;   - Code running at $C800 never calls out except to COUT1 ($FDF0),
;     which touches no card. All MLI calls happen from RAM.
;   - $CFFF is touched before running at $C800, so no other card's
;     expansion ROM is selected alongside ours.
;   - RAM $0800-$1BFF and zero page $06-$09 are borrowed: saved to the
;     card first, restored at the end. Memory is left as it was. Agent
;     code runs at $0800-$15FF; its buffers sit above that.
; =====================================================================

        .setcpu "6502"
        .import __AGENT_LOAD__, __AGENT_RUN__, __AGENT_SIZE__ ; from ld65
        .import __DOSAGENT_LOAD__, __DOSAGENT_SIZE__

; ---- Apple II, ProDOS and BASIC.SYSTEM locations --------------------
CH       = $24          ; cursor column
CSW      = $36          ; character output hook (DOS 3.3 / plain BASIC)
ZP       = $06          ; borrowed zero page: $06-$09
PTR      = $06          ;   general pointer
PTR2     = $08          ;   second pointer
MSLOT    = $07F8        ; slot whose firmware owns $C800, as $Cn
VECTOUT  = $BE30        ; BASIC.SYSTEM's output vector (Tech Note #4)
MLI      = $BF00        ; ProDOS MLI entry: a JMP when ProDOS is present
BITMAP   = $BF58        ; ProDOS memory bit map, high bit = lowest page
KVERSION = $BFFF        ; ProDOS kernel version, e.g. $24
DOSHOOK  = $03EA        ; DOS 3.3: reconnect DOS to the I/O hooks
COUT1    = $FDF0        ; Monitor screen output: no card involved
IORTS    = $FF58        ; a known RTS in the Monitor ROM

; ---- card registers, at $C080 + slot*16 (index with X = slot*16) ----
CARD_REQ  = $C080       ; read: next request, 0 = none
CARD_DONE = $C081       ; write: completion code / message code
CARD_DATA = $C082       ; data port
CARD_STAT = $C083       ; read: version, bit 7 = host window available

MSG_BEGIN  = $80
MSG_STASH  = $81
MSG_RECALL = $82

RES_OK          = $00
RES_NOT_FOUND   = $01
RES_EXISTS      = $02
RES_DISK_FULL   = $03
RES_WRITE_PROT  = $04
RES_BAD_NAME    = $05
RES_IO_ERROR    = $06
RES_OTHER       = $07

; ---- borrowed RAM ----------------------------------------------------
REGION_FIRST = $08      ; first borrowed page
REGION_END   = $1C      ; first page after the region ($0800-$1BFF)
IOBUF   = $1000         ; ProDOS I/O buffer: 1K, page-aligned
DATABUF = $1400         ; 512-byte data buffer
COPYBANK = $1BFB        ; used while copying an agent: above every agent's
COPYVEC  = $1BFC        ;   destination ($0800-$15FF), which the copy
COPYCNT  = $1BFE        ;   overwrites

PRODOS_BANK = 1         ; expansion ROM bank holding the ProDOS agent
DOS_BANK    = 2         ; expansion ROM bank holding the DOS 3.3 agent

; Every agent runs at $0800 and starts with the same header, so the main
; code can start either without knowing its labels:
AGENT_FINPTR = $0800    ; word: address of finish, in the $Cn page
AGENT_SLOTX  = $0802    ; byte: slot * 16
AGENT_ENTRY  = $0803    ; code

; ---- MLI calls ---------------------------------------------------------
MLI_CREATE   = $C0
MLI_DESTROY  = $C1
MLI_SET_INFO = $C3
MLI_GET_INFO = $C4
MLI_ON_LINE  = $C5
MLI_OPEN     = $C8
MLI_READ     = $CA
MLI_WRITE    = $CB
MLI_CLOSE    = $CC
ERR_EOF      = $4C

.macro  MLI_CALL cmd, params
        jsr     MLI
        .byte   cmd
        .addr   params
.endmacro

; =====================================================================
; The card's own page, $Cn00. Runs at $C100-$C700 depending on the slot,
; so nothing here refers to its own addresses absolutely.
; =====================================================================
        .segment "SLOT"
entry:  pha                     ; the character BASIC asked us to print
        txa
        pha
        tya
        pha
        jsr     IORTS           ; find our slot: the high byte of the
        tsx                     ; return address just left on the stack
        lda     $0100,x         ;   is $Cn
        sta     MSLOT
        bit     $CFFF           ; release every card's expansion ROM; the
        jmp     main            ; next fetch from $Cn re-selects ours

; Finish: entered from the agent with X = slot*16. Gets the borrowed RAM
; back from the card and restores it -- overwriting the agent, which is
; why this runs from ROM -- then returns output to the screen and prints
; the character we were called with.
finish: lda     #MSG_RECALL
        sta     CARD_DONE,x
        ldy     #4              ; zero page $06-$09 first: onto the stack,
@zp:    lda     CARD_DATA,x     ; since PTR is needed for the region
        pha
        dey
        bne     @zp
        lda     #<(REGION_FIRST*256)
        sta     PTR
        lda     #REGION_FIRST
        sta     PTR+1
        ldy     #0
@copy:  lda     CARD_DATA,x
        sta     (PTR),y
        iny
        bne     @copy
        inc     PTR+1
        lda     PTR+1
        cmp     #REGION_END
        bne     @copy
        pla
        sta     ZP+3
        pla
        sta     ZP+2
        pla
        sta     ZP+1
        pla
        sta     ZP
        lda     #<COUT1         ; output back to the screen -- PR#0 -- the way
        sta     CSW             ; the running OS needs. CSW in both cases:
        lda     #>COUT1         ; BASIC.SYSTEM keeps the device there too,
        sta     CSW+1           ; and would call us again otherwise
        lda     MLI
        cmp     #$4C
        bne     @dos
        lda     #<COUT1         ; ProDOS: BASIC.SYSTEM's own vector as well
        sta     VECTOUT
        lda     #>COUT1
        sta     VECTOUT+1
        bne     @out            ; (always: >COUT1 isn't 0)
@dos:   jsr     DOSHOOK         ; DOS 3.3: let DOS reconnect its hooks
@out:   pla
        tay
        pla
        tax
        pla
        jmp     COUT1

; Copies COPYCNT bytes of the agent image from (PTR), starting in bank
; COPYBANK, to (PTR2); then selects bank 0 again and returns to
; main_continue. An image may run on into the next bank: past $CFFF the
; copy continues at $C800 of the next one. (Reading $CFFF releases the
; expansion ROM, but each instruction fetched from this $Cn page selects
; ours again.) X = slot*16 throughout.
copy_agent:
        lda     COPYBANK
        sta     CARD_STAT,x
        ldy     #0
@copy:  lda     (PTR),y
        sta     (PTR2),y
        iny
        bne     @count
        inc     PTR2+1
        inc     PTR+1
        lda     PTR+1
        cmp     #$D0            ; past $CFFF: on into the next bank
        bne     @count
        lda     #$C8
        sta     PTR+1
        inc     COPYBANK
        lda     COPYBANK
        sta     CARD_STAT,x
@count: lda     COPYCNT
        bne     :+
        dec     COPYCNT+1
:       dec     COPYCNT
        lda     COPYCNT
        ora     COPYCNT+1
        bne     @copy
        lda     #0
        sta     CARD_STAT,x
        jmp     main_continue

; =====================================================================
; $C800: detection, borrowing RAM, starting the agent.
; =====================================================================
        .segment "MAIN"
main:   jsr     slot_x
        lda     MLI             ; ProDOS? ($BF00 holds a JMP)
        cmp     #$4C
        beq     @prodos
        jsr     is_dos33
        beq     @dos
        jmp     no_os

@prodos:
        lda     CARD_STAT,x     ; a host transfer window to talk to?
        bmi     :+
        jmp     no_host
:       lda     BITMAP+1        ; pages $08-$0F free?
        beq     :+
        jmp     no_memory
:       lda     BITMAP+2        ; pages $10-$17 free?
        beq     :+
        jmp     no_memory
:       lda     BITMAP+3        ; pages $18-$1B free?
        and     #$F0
        beq     :+
        jmp     no_memory
:       lda     #PRODOS_BANK    ; which agent: kept on the stack until the
        pha                     ; region is stashed, since the copy's own
        lda     #<__AGENT_LOAD__ ; variables live inside the region
        pha
        lda     #>__AGENT_LOAD__
        pha
        lda     #<__AGENT_SIZE__
        pha
        lda     #>__AGENT_SIZE__
        pha
        jmp     borrow

@dos:   lda     CARD_STAT,x     ; DOS keeps no memory map: nothing to check
        bmi     :+
        jmp     no_host
:       lda     #DOS_BANK
        pha
        lda     #<__DOSAGENT_LOAD__
        pha
        lda     #>__DOSAGENT_LOAD__
        pha
        lda     #<__DOSAGENT_SIZE__
        pha
        lda     #>__DOSAGENT_SIZE__
        pha
        ; fall through

; Lend the card zero page $06-$09 and $0800-$15FF exactly as they are,
; then copy the chosen agent into RAM -- from the $Cn page, since its image
; is in another bank and code can't switch away the bank it's running
; from. On the stack: bank, image address (lo, hi), size (lo, hi).
borrow: lda     ZP
        sta     CARD_DATA,x
        lda     ZP+1
        sta     CARD_DATA,x
        lda     ZP+2
        sta     CARD_DATA,x
        lda     ZP+3
        sta     CARD_DATA,x
        lda     #0
        sta     PTR
        lda     #REGION_FIRST
        sta     PTR+1
        ldy     #0
@stash: lda     (PTR),y
        sta     CARD_DATA,x
        iny
        bne     @stash
        inc     PTR+1
        lda     PTR+1
        cmp     #REGION_END
        bne     @stash
        lda     #MSG_STASH
        sta     CARD_DONE,x
        pla                     ; now the region is safe to use
        sta     COPYCNT+1
        pla
        sta     COPYCNT
        pla
        sta     PTR+1
        pla
        sta     PTR
        pla
        sta     COPYBANK
        lda     #<AGENT_FINPTR  ; the agent goes to $0800
        sta     PTR2
        lda     #>AGENT_FINPTR
        sta     PTR2+1
        lda     #<copy_agent
        sta     COPYVEC
        lda     MSLOT
        sta     COPYVEC+1
        jmp     (COPYVEC)       ; returns to main_continue, X unchanged
main_continue:
        stx     AGENT_SLOTX     ; tell the agent its slot and where finish is
        lda     MSLOT
        sta     AGENT_FINPTR+1
        lda     #<finish
        sta     AGENT_FINPTR
        jmp     AGENT_ENTRY

; X = slot * 16, from MSLOT ($Cn)
slot_x: lda     MSLOT
        asl     a
        asl     a
        asl     a
        asl     a
        tax
        rts

no_os:  jsr     undo_pr
        ldy     #msg_no_os - messages ; (offset 0: a BNE here would fall through)
        jmp     say_and_leave

no_host:
        jsr     undo_pr
        ldy     #msg_no_host - messages
        jmp     say_and_leave

no_memory:
        jsr     undo_pr
        ldy     #msg_memory - messages
        ; fall through

; Print the message at messages+Y, restore the caller's registers and
; print the character we were called with.
say_and_leave:
        jsr     new_line
@next:  lda     messages,y
        beq     @done
        jsr     COUT1           ; preserves Y
        iny
        bne     @next
@done:  pla
        tay
        pla
        tax
        pla
        jmp     COUT1

new_line:                       ; start a new line unless already at column 0
        lda     CH
        beq     :+
        lda     #$8D
        jsr     COUT1
:       rts

; Undo PR#n -- output back to the screen -- the way the running OS needs:
; CSW always; under ProDOS BASIC.SYSTEM's VECTOUT too; under DOS 3.3 let
; DOS reconnect its hooks.
undo_pr:
        lda     #<COUT1
        sta     CSW
        lda     #>COUT1
        sta     CSW+1
        lda     MLI
        cmp     #$4C
        bne     @not_prodos
        lda     #<COUT1
        sta     VECTOUT
        lda     #>COUT1
        sta     VECTOUT+1
        rts
@not_prodos:
        jsr     is_dos33
        bne     @done
        jsr     DOSHOOK
@done:  rts

; Z set if DOS 3.3's page-3 file manager vectors are present: a JMP at
; $3D6, and at $3DC the routine LDA abs / LDY abs / RTS.
is_dos33:
        lda     $03D6
        cmp     #$4C
        bne     @no
        lda     $03DC
        cmp     #$AD
        bne     @no
        lda     $03DF
        cmp     #$AC
        bne     @no
        lda     $03E2
        cmp     #$60
@no:    rts

        .macro  apple_string str
        .repeat .strlen(str), i
        .byte   .strat(str, i) | $80
        .endrepeat
        .endmacro

messages:
msg_no_os:   apple_string "HOST TRANSFER NEEDS PRODOS OR DOS 3.3"
             .byte $8D, 0
msg_no_host: apple_string "HOST TRANSFER IS NOT AVAILABLE"
             .byte $8D, 0
msg_memory:  apple_string "HOST TRANSFER: $800-$1BFF IS IN USE"
             .byte $8D, 0

; =====================================================================
; The agent: runs from RAM at $0800. X is reloaded from slotx before
; every card access, since MLI calls don't promise to keep it.
; =====================================================================
        .segment "AGENT"
        .scope  prodos_agent
finptr: .word   0               ; the shared agent header: $0800, where JMP
slotx:  .byte   0               ; (finptr) is safe from the 6502's page-wrap
agent:  lda     CH              ; bug; slot*16 at $0802; entry at $0803
        beq     :+
        lda     #$8D
        jsr     COUT1
:       ldy     #0              ; "HOST TRANSFER: PRODOS 2.4"
@title: lda     title,y
        beq     @ver
        jsr     COUT1
        iny
        bne     @title
@ver:   lda     KVERSION
        lsr     a
        lsr     a
        lsr     a
        lsr     a
        ora     #'0' | $80
        sta     version
        jsr     COUT1
        lda     #'.' | $80
        jsr     COUT1
        lda     KVERSION
        and     #$0F
        ora     #'0' | $80
        sta     version+2
        jsr     COUT1
        lda     #$8D
        jsr     COUT1
        lda     version         ; the capability record wants plain ASCII
        and     #$7F
        sta     version
        lda     version+2
        and     #$7F
        sta     version+2

        lda     #1              ; BEGIN: protocol version, then capabilities
        jsr     put
        ldy     #0
@caps:  lda     capabilities,y
        jsr     put
        iny
        cpy     #capabilities_end - capabilities
        bne     @caps
        lda     #MSG_BEGIN
        jsr     complete

loop:   ldx     slotx
        lda     CARD_REQ,x
        beq     loop
        cmp     #1
        beq     do_volumes
        cmp     #2
        bne     :+
        jmp     do_list
:       cmp     #3
        bne     :+
        jmp     do_read
:       cmp     #4
        bne     :+
        jmp     do_write
:       cmp     #5
        bne     :+
        jmp     do_make_dir
:       cmp     #6
        bne     :+
        jmp     do_delete
:       cmp     #7
        bne     :+
        jmp     do_end
:       lda     #RES_IO_ERROR   ; not a request we know
        jsr     complete
        jmp     loop

; ---- VOLUMES: every online volume's name ----------------------------
do_volumes:
        lda     #0
        sta     online_unit
        MLI_CALL MLI_ON_LINE, online_params
        bcc     :+
        jmp     fail
:       lda     #<DATABUF
        sta     PTR
        lda     #>DATABUF
        sta     PTR+1
@entry: ldy     #0
        lda     (PTR),y
        and     #$0F            ; name length; 0 = no volume / an error
        beq     @next
        sta     count
        jsr     put
        ldy     #1
@name:  lda     (PTR),y
        jsr     put
        iny
        dec     count
        bne     @name
@next:  clc
        lda     PTR
        adc     #16
        sta     PTR
        bne     @entry          ; 16 entries of 16 bytes fill one page
        lda     #0              ; end of the list
        jsr     put
        jmp     ok

; ---- LIST: a volume's or directory's entries -------------------------
do_list:
        jsr     read_path
        bcc     :+
        jmp     bad_name
:       MLI_CALL MLI_OPEN, open_params
        bcc     :+
        jmp     fail
:       lda     open_ref
        sta     rw_ref
        lda     #<512
        sta     rw_request
        lda     #>512
        sta     rw_request+1
        lda     #1
        sta     first_block
@block: MLI_CALL MLI_READ, rw_params
        bcc     @got
        cmp     #ERR_EOF
        beq     @end
        jmp     close_and_fail
@got:   lda     #<(DATABUF+4)   ; entries follow the two block pointers
        sta     PTR
        lda     #>(DATABUF+4)
        sta     PTR+1
        lda     #0
        sta     index
        lda     first_block     ; the key block's first entry is the header
        beq     @each
        lda     DATABUF+4+$1F
        sta     entry_length
        lda     DATABUF+4+$20
        sta     per_block
        lda     #0
        sta     first_block
        jsr     next_entry
        inc     index
@each:  lda     index
        cmp     per_block
        bcs     @block
        jsr     list_entry
        jsr     next_entry
        inc     index
        jmp     @each
@end:   jsr     close
        lda     #0              ; end of the list
        jsr     put
        jmp     ok

next_entry:
        clc
        lda     PTR
        adc     entry_length
        sta     PTR
        bcc     :+
        inc     PTR+1
:       rts

list_entry:
        ldy     #0
        lda     (PTR),y
        and     #$F0            ; storage type 0: an unused entry
        bne     :+
        rts
:       lda     (PTR),y
        and     #$0F
        sta     count
        jsr     put             ; name
        ldy     #1
@name:  lda     (PTR),y
        jsr     put
        iny
        dec     count
        bne     @name
        ldy     #0
        lda     (PTR),y
        and     #$F0
        cmp     #$D0            ; storage type $D: a directory
        beq     :+
        lda     #0
        beq     @kind
:       lda     #1
@kind:  jsr     put
        ldy     #$10            ; type, as a tag
        lda     (PTR),y
        jsr     put_tag
        ldy     #$1F            ; aux type
        lda     (PTR),y
        jsr     put
        iny
        lda     (PTR),y
        jsr     put
        ldy     #$1E            ; access: the attributes
        lda     (PTR),y
        jsr     put
        ldy     #$15            ; EOF: 3 bytes, then a zero
        lda     (PTR),y
        jsr     put
        iny
        lda     (PTR),y
        jsr     put
        iny
        lda     (PTR),y
        jsr     put
        lda     #0
        jmp     put

; ---- READ: a file's bytes ----------------------------------------------
do_read:
        jsr     read_path
        bcc     :+
        jmp     bad_name
:       MLI_CALL MLI_OPEN, open_params
        bcc     :+
        jmp     fail
:       lda     open_ref
        sta     rw_ref
        lda     #<512
        sta     rw_request
        lda     #>512
        sta     rw_request+1
@chunk: MLI_CALL MLI_READ, rw_params
        bcc     @send
        cmp     #ERR_EOF
        beq     @end
        jmp     close_and_fail
@send:  lda     #<DATABUF
        sta     PTR
        lda     #>DATABUF
        sta     PTR+1
        lda     rw_transferred
        sta     count
        lda     rw_transferred+1
        sta     count+1
        jsr     send_bytes
        jmp     @chunk
@end:   jsr     close
        jmp     ok

; send count (16 bits) bytes from (PTR)
send_bytes:
        ldy     #0
@loop:  lda     count
        ora     count+1
        beq     @done
        lda     (PTR),y
        jsr     put
        iny
        bne     :+
        inc     PTR+1
:       lda     count
        bne     :+
        dec     count+1
:       dec     count
        jmp     @loop
@done:  rts

; ---- WRITE: create a file from the bytes that follow --------------------
do_write:
        jsr     read_path
        php
        jsr     get             ; type tag: up to 3 characters
        sta     count
        ldy     #0
@tag:   cpy     count
        beq     @tagged
        jsr     get
        cpy     #3
        bcs     :+
        sta     tag,y
:       iny
        bne     @tag
@tagged:
        jsr     get             ; aux type
        sta     create_aux
        jsr     get
        sta     create_aux+1
        jsr     get             ; attributes
        sta     attributes
        jsr     get             ; size: 24 bits used, the top byte must be 0
        sta     remaining
        jsr     get
        sta     remaining+1
        jsr     get
        sta     remaining+2
        jsr     get
        beq     @far15
        jmp     @too_big
@far15:
        plp                     ; was the path too long?
        bcc     :+
        jmp     bad_name
:       lda     count
        cmp     #3
        beq     @far14
        jmp     @bad_type
@far14:
        jsr     tag_to_type
        bcc     @far13
        jmp     @bad_type
@far13:
        sta     create_type
        lda     #$E3            ; create writable; set the real access last
        sta     create_access
        lda     #1              ; storage type 1: an ordinary (seedling) file
        sta     create_storage
        MLI_CALL MLI_CREATE, create_params
        bcc     :+
        jmp     fail
:       MLI_CALL MLI_OPEN, open_params
        bcc     :+
        jmp     fail
:       lda     open_ref
        sta     rw_ref
@chunk: lda     remaining       ; the next chunk: 512 bytes, or what's left
        ora     remaining+1
        ora     remaining+2
        beq     @close
        lda     remaining+2
        bne     @full
        lda     remaining+1
        cmp     #2
        bcs     @full
        lda     remaining
        sta     rw_request
        lda     remaining+1
        sta     rw_request+1
        jmp     @fill
@full:  lda     #<512
        sta     rw_request
        lda     #>512
        sta     rw_request+1
@fill:  lda     #<DATABUF       ; take the chunk from the card...
        sta     PTR
        lda     #>DATABUF
        sta     PTR+1
        lda     rw_request
        sta     count
        lda     rw_request+1
        sta     count+1
        jsr     receive_bytes
        MLI_CALL MLI_WRITE, rw_params ; ...and write it
        bcc     :+
        jmp     close_and_fail
:       sec                     ; remaining -= chunk
        lda     remaining
        sbc     rw_request
        sta     remaining
        lda     remaining+1
        sbc     rw_request+1
        sta     remaining+1
        lda     remaining+2
        sbc     #0
        sta     remaining+2
        jmp     @chunk
@close: jsr     close
        lda     attributes      ; the access the emulator asked for
        cmp     #$E3
        beq     @done
        lda     #$0A
        sta     info_params
        MLI_CALL MLI_GET_INFO, info_params
        bcc     @far12
        jmp     fail
@far12:
        lda     #7
        sta     info_params
        lda     attributes
        sta     info_access
        MLI_CALL MLI_SET_INFO, info_params
        bcc     @far11
        jmp     fail
@far11:
@done:  jmp     ok
@too_big:
        plp
        lda     #RES_DISK_FULL
        jsr     complete
        jmp     loop
@bad_type:
        lda     #0              ; a type tag this adapter doesn't know:
        jsr     put_other_code  ; OTHER, with code 0
        jmp     loop

; receive count (16 bits) bytes into (PTR)
receive_bytes:
        ldy     #0
@loop:  lda     count
        ora     count+1
        beq     @done
        jsr     get
        sta     (PTR),y
        iny
        bne     :+
        inc     PTR+1
:       lda     count
        bne     :+
        dec     count+1
:       dec     count
        jmp     @loop
@done:  rts

; ---- MAKE_DIR, DELETE, END -------------------------------------------
do_make_dir:
        jsr     read_path
        bcs     bad_name
        lda     #$E3
        sta     create_access
        lda     #$0F            ; file type $0F: directory
        sta     create_type
        lda     #0
        sta     create_aux
        sta     create_aux+1
        lda     #$0D            ; storage type $D: directory
        sta     create_storage
        MLI_CALL MLI_CREATE, create_params
        bcs     fail
        jmp     ok

do_delete:
        jsr     read_path
        bcs     bad_name
        MLI_CALL MLI_DESTROY, destroy_params
        bcs     fail
        jmp     ok

do_end: lda     #RES_OK
        jsr     complete
        ldx     slotx
        jmp     (finptr)        ; restore memory from ROM, then return

; ---- completion --------------------------------------------------------
ok:     lda     #RES_OK
        jsr     complete
        jmp     loop

bad_name:
        lda     #RES_BAD_NAME
        jsr     complete
        jmp     loop

close_and_fail:
        pha
        jsr     close
        pla
        ; fall through

; A = an MLI error code: report it as an abstract result
fail:   sta     error_code_hold
        ldy     #0
@find:  lda     error_map,y
        beq     @other
        cmp     error_code_hold
        beq     @found
        iny
        iny
        bne     @find
@found: lda     error_map+1,y
        jsr     complete
        jmp     loop
@other: lda     error_code_hold
        jsr     put_other_code
        jmp     loop

; OTHER, carrying the OS's own code (A) and an empty message
put_other_code:
        jsr     put
        lda     #0
        jsr     put
        lda     #RES_OTHER
        jmp     complete

close:  lda     open_ref
        sta     close_ref
        MLI_CALL MLI_CLOSE, close_params
        rts

; ---- card access: X is always reloaded ----------------------------------
put:    ldx     slotx
        sta     CARD_DATA,x
        rts

get:    ldx     slotx
        lda     CARD_DATA,x
        rts

complete:
        ldx     slotx
        sta     CARD_DONE,x
        rts

; ---- paths ---------------------------------------------------------------
; Reads a path from the card into pathname as "/A/B/C" (length-prefixed).
; Carry set if it's longer than ProDOS allows (64); the rest is still read.
read_path:
        jsr     get
        sta     components
        lda     #0
        sta     pathname
        sta     overflow
@comp:  lda     components
        beq     @done
        lda     #'/'
        jsr     path_append
        jsr     get
        sta     count
@char:  lda     count
        beq     @next
        jsr     get
        jsr     path_append
        dec     count
        jmp     @char
@next:  dec     components
        jmp     @comp
@done:  lda     overflow
        cmp     #1              ; carry set if overflow
        rts

path_append:
        ldy     pathname
        cpy     #64
        bcs     @full
        iny
        sta     pathname,y
        sty     pathname
        rts
@full:  lda     #1
        sta     overflow
        rts

; ---- file types as tags ------------------------------------------------
; put_tag: A = ProDOS file type; sends its 3-character tag: a name from
; the table, or "$hh".
put_tag:
        sta     type_hold
        ldy     #0
@find:  lda     type_table+1,y
        beq     @hex            ; end of table
        lda     type_table,y
        cmp     type_hold
        beq     @named
        tya
        clc
        adc     #4
        tay
        bne     @find
@named: lda     #3
        jsr     put
        lda     type_table+1,y
        jsr     put
        lda     type_table+2,y
        jsr     put
        lda     type_table+3,y
        jmp     put
@hex:   lda     #3
        jsr     put
        lda     #'$'
        jsr     put
        lda     type_hold
        lsr     a
        lsr     a
        lsr     a
        lsr     a
        jsr     hex_digit
        jsr     put
        lda     type_hold
        and     #$0F
        jsr     hex_digit
        jmp     put

hex_digit:                      ; 0-15 -> '0'-'9' / 'A'-'F'
        cmp     #10
        bcc     :+
        adc     #6              ; carry is set: +7 skips ':' to '@'
:       adc     #'0'            ; carry is clear here
        rts

; tag_to_type: the 3 characters in tag -> A = file type; carry set if unknown
tag_to_type:
        ldy     #0
@find:  lda     type_table+1,y
        beq     @hex
        cmp     tag
        bne     @next
        lda     type_table+2,y
        cmp     tag+1
        bne     @next
        lda     type_table+3,y
        cmp     tag+2
        bne     @next
        lda     type_table,y
        clc
        rts
@next:  tya
        clc
        adc     #4
        tay
        bne     @find
@hex:   lda     tag             ; "$hh"
        cmp     #'$'
        bne     @bad
        lda     tag+1
        jsr     hex_value
        bcs     @bad
        asl     a
        asl     a
        asl     a
        asl     a
        sta     type_hold
        lda     tag+2
        jsr     hex_value
        bcs     @bad
        ora     type_hold
        clc
        rts
@bad:   sec
        rts

hex_value:                      ; A = '0'-'9' / 'A'-'F' -> 0-15; carry set if not hex
        sec
        sbc     #'0'
        cmp     #10
        bcc     @ok
        sbc     #'A' - '0'
        cmp     #6
        bcs     @bad
        adc     #10
@ok:    clc
        rts
@bad:   sec
        rts

; type, then its tag; a zero tag ends the table
type_table:
        .byte   $04, "TXT"
        .byte   $06, "BIN"
        .byte   $0F, "DIR"
        .byte   $19, "ADB"
        .byte   $1A, "AWP"
        .byte   $1B, "ASP"
        .byte   $F0, "CMD"
        .byte   $FA, "INT"
        .byte   $FB, "IVR"
        .byte   $FC, "BAS"
        .byte   $FD, "VAR"
        .byte   $FE, "REL"
        .byte   $FF, "SYS"
        .byte   $00, 0

; MLI error -> abstract result; a zero ends the table
error_map:
        .byte   $44, RES_NOT_FOUND      ; path not found
        .byte   $45, RES_NOT_FOUND      ; volume not found
        .byte   $46, RES_NOT_FOUND      ; file not found
        .byte   $47, RES_EXISTS         ; duplicate filename
        .byte   $48, RES_DISK_FULL      ; volume full
        .byte   $49, RES_DISK_FULL      ; volume directory full
        .byte   $2B, RES_WRITE_PROT     ; write protected
        .byte   $40, RES_BAD_NAME       ; invalid pathname
        .byte   $27, RES_IO_ERROR       ; I/O error
        .byte   $28, RES_IO_ERROR       ; no device connected
        .byte   $2E, RES_IO_ERROR       ; disk switched
        .byte   $52, RES_IO_ERROR       ; not a ProDOS disk
        .byte   0

title:  apple_string "HOST TRANSFER: PRODOS "
        .byte   0

; The capability record (TRANSFER-CARD.md 5.2): tag, length, value.
capabilities:
        .byte   $01, 6, "PRODOS"
        .byte   $02, 3
version:
        .byte   "0.0"                   ; filled in from KVERSION
        .byte   $03, 1, 1               ; hierarchical
        .byte   $04, 1, 15              ; names up to 15 characters
        .byte   $05, 1, $03             ; upper case, starting with a letter
        .byte   $06, 1, "."             ; letters, digits and periods
        .byte   $07, 1, $0D             ; lines end with CR
        .byte   $08, 1, 0               ; text has bit 7 clear
        .byte   $09, 4, 3, "TXT"        ; TXT is text
        .byte   $0A, 6, 3, "TXT", 0, 0  ; default text type: TXT $0000
        .byte   $0B, 6, 3, "BIN", 0, 0  ; default other type: BIN $0000
        .byte   $0C, 1, $E3             ; default access: unlocked
        .byte   $0D, 1, 1               ; a TXT aux value is a record length
        .byte   $00
capabilities_end:

; ---- MLI parameter lists -------------------------------------------------
online_params:
        .byte   2
online_unit:
        .byte   0
        .addr   DATABUF

open_params:
        .byte   3
        .addr   pathname
        .addr   IOBUF
open_ref:
        .byte   0

rw_params:
        .byte   4
rw_ref: .byte   0
        .addr   DATABUF
rw_request:
        .word   0
rw_transferred:
        .word   0

close_params:
        .byte   1
close_ref:
        .byte   0

create_params:
        .byte   7
        .addr   pathname
create_access:
        .byte   $E3
create_type:
        .byte   0
create_aux:
        .word   0
create_storage:
        .byte   1
        .word   0, 0                    ; date and time: ProDOS fills in now

destroy_params:
        .byte   1
        .addr   pathname

; GET_FILE_INFO ($0A parameters) and SET_FILE_INFO (7) share the offsets
; of access, type, aux type and modification date and time.
info_params:
        .byte   $0A
        .addr   pathname
info_access:
        .byte   0
        .res    14              ; type, aux, storage, blocks, two dates and times

; ---- variables -------------------------------------------------------------
count:          .word   0
components:     .byte   0
overflow:       .byte   0
first_block:    .byte   0
index:          .byte   0
entry_length:   .byte   0
per_block:      .byte   0
type_hold:      .byte   0
error_code_hold: .byte  0
attributes:     .byte   0
remaining:      .res    3
tag:            .res    3
pathname:       .res    65
        .endscope

; =====================================================================
; The DOS 3.3 agent: also runs from RAM at $0800, with the same header.
;
; Files go through DOS's file manager ($3DC finds its parameter list,
; $3D6 calls it) with this agent's own buffers -- DOS's are left alone.
; LIST reads the VTOC and catalog through RWTS ($3E3 finds DOS's I/O
; block, $3D9 runs it): the file manager's CATALOG call only prints.
; Source: Beneath Apple DOS, chapters 4-6. Two of its details matter:
;   - a WRITE range length is one less than the number of bytes;
;   - OPEN with X = 0 returns "file not found" after creating the file.
; File type bits are $08 = S and $10 = R, as chapter 4 and DOS's own
; CATALOG code have them (chapter 6's OPEN list swaps the two).
;
; Containers are drives, named "Sn,Dd". The API carries plain data: this
; agent adds and strips DOS's headers -- B files: load address and
; length; A and I files: length.
; =====================================================================

DOS_FMPARMS = $03DC     ; returns the file manager's parameter list: Y lo, A hi
DOS_FM      = $03D6     ; the file manager; X = 0 lets OPEN create the file
DOS_IOB     = $03E3     ; returns DOS's RWTS I/O block: Y lo, A hi
DOS_RWTS    = $03D9     ; RWTS, with the I/O block in Y (lo) and A (hi)

FM_OPEN     = $01
FM_CLOSE    = $02
FM_READ     = $03
FM_WRITE    = $04
FM_DELETE   = $05
FM_LOCK     = $07
FM_POSITION = $0A
FM_ONE      = $01       ; READ/WRITE sub-call: one byte
FM_RANGE    = $02       ;                      a range of bytes

DOS_END_OF_DATA = 5
DOS_NOT_FOUND   = 6

FM_WORKAREA = $1600     ; 45 bytes -- all above the agent's code
FM_TSLIST   = $1700     ; 256 bytes
FM_DATASEC  = $1800     ; 256 bytes
CATBUF      = $1900     ; a catalog sector, read through RWTS
AUXBUF      = $1A00     ; another sector; and the 512-byte data buffer
DOSDATA     = $1A00

        .segment "DOSAGENT"
        .scope  dos_agent
finptr: .word   0               ; the shared agent header
slotx:  .byte   0
agent:  lda     CH              ; "HOST TRANSFER: DOS 3.3"
        beq     :+
        lda     #$8D
        jsr     COUT1
:       ldy     #0
@title: lda     title,y
        beq     @find
        jsr     COUT1
        iny
        bne     @title
@find:  jsr     DOS_FMPARMS     ; where DOS keeps its parameter list...
        sty     fmparms
        sta     fmparms+1
        jsr     DOS_IOB         ; ...and its RWTS I/O block
        sty     iob
        sta     iob+1

        lda     #1              ; BEGIN: protocol version, then capabilities
        jsr     put
        ldy     #0
@caps:  lda     capabilities,y
        jsr     put
        iny
        cpy     #capabilities_end - capabilities
        bne     @caps
        lda     #MSG_BEGIN
        jsr     complete

loop:   ldx     slotx
        lda     CARD_REQ,x
        beq     loop
        cmp     #1
        bne     :+
        jmp     do_volumes
:       cmp     #2
        bne     :+
        jmp     do_list
:       cmp     #3
        bne     :+
        jmp     do_read
:       cmp     #4
        bne     :+
        jmp     do_write
:       cmp     #6
        bne     :+
        jmp     do_delete
:       cmp     #7
        bne     :+
        jmp     do_end
:       lda     #0              ; MAKE_DIR (DOS is flat) or unknown: OTHER, 0
        jsr     put_other_code
        jmp     loop

; ---- VOLUMES: two drives for every Disk II controller ------------------
do_volumes:
        ldy     #1              ; slot 1-7
@slot:  sty     slot
        bit     $CFFF           ; release every expansion ROM first, so
        tya                     ; reading this slot's page leaves at most
        ora     #$C0            ; one card selected, never two at once
        sta     PTR+1
        lda     #0
        sta     PTR
        ldy     #1              ; a Disk II's ROM: $Cn01 = $20, $Cn03 = $00,
        lda     (PTR),y         ; $Cn05 = $03, $CnFF = $00 (16 sectors)
        cmp     #$20
        bne     @next
        ldy     #3
        lda     (PTR),y
        bne     @next
        ldy     #5
        lda     (PTR),y
        cmp     #$03
        bne     @next
        ldy     #$FF
        lda     (PTR),y
        bne     @next
        lda     #1
        jsr     put_drive
        lda     #2
        jsr     put_drive
@next:  ldy     slot
        iny
        cpy     #8
        bne     @slot
        bit     $CFFF           ; and release the last card read
        lda     #0
        jsr     put
        jmp     ok

put_drive:                      ; "Sn,Dd" for slot, drive A
        pha
        lda     #5
        jsr     put
        lda     #'S'
        jsr     put
        lda     slot
        ora     #'0'
        jsr     put
        lda     #','
        jsr     put
        lda     #'D'
        jsr     put
        pla
        ora     #'0'
        jmp     put

; ---- LIST: a drive's catalog, read through RWTS ------------------------
do_list:
        jsr     read_path
        bcs     @bad
        lda     has_name        ; a drive, not a file
        bne     @bad
        lda     #$11            ; the VTOC: track 17, sector 0
        ldy     #0
        ldx     #>CATBUF
        jsr     read_sector
        bcs     @io
        lda     CATBUF+1        ; the first catalog sector
        sta     cat_track
        lda     CATBUF+2
        sta     cat_sector
@sector:
        lda     cat_track
        beq     @done
        ldy     cat_sector
        ldx     #>CATBUF
        jsr     read_sector
        bcs     @io
        lda     CATBUF+1        ; the next one, kept before anything else
        sta     cat_track       ; is read
        lda     CATBUF+2
        sta     cat_sector
        lda     #<(CATBUF+$0B)  ; 7 entries of $23 bytes from $0B
        sta     PTR
        lda     #>CATBUF
        sta     PTR+1
        lda     #7
        sta     index
@entry: ldy     #0
        lda     (PTR),y
        beq     @done           ; never used: the end of the catalog
        cmp     #$FF            ; deleted
        beq     @skip
        jsr     list_entry
        bcs     @io
@skip:  clc
        lda     PTR
        adc     #$23
        sta     PTR
        dec     index
        bne     @entry
        jmp     @sector
@done:  lda     #0
        jsr     put
        jmp     ok
@bad:   jmp     bad_name
@io:    lda     #RES_IO_ERROR
        jsr     complete
        jmp     loop

; One catalog entry at (PTR): name, kind, tag, aux, attributes, size.
; Carry set on a read error.
list_entry:
        ldy     #$20            ; the name: 30 characters, space-padded
@len:   lda     (PTR),y
        and     #$7F
        cmp     #' '
        bne     @named
        dey
        cpy     #$02
        bne     @len
@named: tya
        sec
        sbc     #2              ; characters at +3..+Y
        jsr     put
        sta     count
        ldy     #3
@name:  lda     (PTR),y
        and     #$7F
        jsr     put
        iny
        dec     count
        bne     @name
        lda     #0              ; kind: file
        jsr     put
        ldy     #2
        lda     (PTR),y
        and     #$7F
        sta     type_hold
        jsr     put_tag
        lda     #0              ; aux and exact size: from the file's own
        sta     aux_hold        ; header, for B, A and I files
        sta     aux_hold+1
        lda     type_hold
        cmp     #$04
        beq     @header
        cmp     #$02
        beq     @header
        cmp     #$01
        beq     @header
        ldy     #$21            ; others: an approximate size -- sectors
        lda     (PTR),y         ; less the T/S list, times 256
        sec
        sbc     #1
        sta     size_hold+1
        iny
        lda     (PTR),y
        sbc     #0
        sta     size_hold+2
        bcs     :+
        lda     #0
        sta     size_hold+1
        sta     size_hold+2
:       lda     #0
        sta     size_hold
        jmp     @send
@header:
        lda     #0
        sta     size_hold
        sta     size_hold+1
        sta     size_hold+2
        ldy     #0              ; the T/S list...
        lda     (PTR),y
        pha
        iny
        lda     (PTR),y
        tay
        pla
        ldx     #>AUXBUF
        jsr     read_sector
        bcc     :+
        rts
:       lda     AUXBUF+$0C      ; ...its first data sector
        beq     @send           ; (none: an empty file)
        ldy     AUXBUF+$0D
        ldx     #>AUXBUF
        jsr     read_sector
        bcc     :+
        rts
:       lda     type_hold
        cmp     #$04
        bne     @length
        lda     AUXBUF          ; B: load address, then length
        sta     aux_hold
        lda     AUXBUF+1
        sta     aux_hold+1
        lda     AUXBUF+2
        sta     size_hold
        lda     AUXBUF+3
        sta     size_hold+1
        jmp     @send
@length:
        lda     AUXBUF          ; A, I: length
        sta     size_hold
        lda     AUXBUF+1
        sta     size_hold+1
@send:  lda     aux_hold
        jsr     put
        lda     aux_hold+1
        jsr     put
        ldy     #2              ; attributes: $80 = locked
        lda     (PTR),y
        and     #$80
        jsr     put
        lda     size_hold
        jsr     put
        lda     size_hold+1
        jsr     put
        lda     size_hold+2
        jsr     put
        lda     #0
        jsr     put
        clc
        rts

; Reads track A, sector Y into page X through RWTS. Carry set on error.
read_sector:
        sta     rw_track
        sty     rw_sector
        stx     rw_page
        lda     iob             ; fill DOS's I/O block through PTR2
        sta     PTR2
        lda     iob+1
        sta     PTR2+1
        ldy     #1
        lda     slot            ; slot * 16
        asl     a
        asl     a
        asl     a
        asl     a
        sta     (PTR2),y
        iny
        lda     drive
        sta     (PTR2),y
        iny
        lda     #0              ; any volume
        sta     (PTR2),y
        iny
        lda     rw_track
        sta     (PTR2),y
        iny
        lda     rw_sector
        sta     (PTR2),y
        ldy     #8
        lda     #0
        sta     (PTR2),y
        iny
        lda     rw_page
        sta     (PTR2),y
        ldy     #$0C
        lda     #1              ; READ
        sta     (PTR2),y
        ldy     iob
        lda     iob+1
        jsr     DOS_RWTS
        rts

; ---- READ: a file's bytes, without DOS's headers ------------------------
do_read:
        jsr     read_path
        bcc     @far10
        jmp     @bad
@far10:
        lda     has_name
        bne     @far9
        jmp     @bad
@far9:
        ldx     #1              ; open, but don't create
        jsr     fm_open
        bcc     :+
        jmp     fail
:       lda     fm+$07          ; the file's actual type, as OPEN reports it
        and     #$7F
        sta     type_hold
        jsr     fm_position
        bcc     @far8
        jmp     @fail_close
@far8:  lda     type_hold
        cmp     #$04
        beq     @binary
        cmp     #$02
        beq     @program
        cmp     #$01
        beq     @program
@bytes: jsr     fm_read_one     ; text and the rest: byte by byte, to the
        bcs     @end            ; end of the data -- or, for text, the
        lda     fm+$08          ; first $00
        bne     :+
        ldy     type_hold
        beq     @close
:       jsr     put
        jmp     @bytes
@end:   cmp     #DOS_END_OF_DATA
        beq     @close
        bne     @fail_close
@binary:
        lda     #0              ; a 16-bit length: clear the top byte
        sta     remaining+2
        lda     #4              ; load address and length
        jsr     read_header
        bcs     @fail_close
        lda     header+2
        sta     remaining
        lda     header+3
        sta     remaining+1
        jmp     @stream
@program:
        lda     #0
        sta     remaining+2
        lda     #2              ; length
        jsr     read_header
        bcs     @fail_close
        lda     header
        sta     remaining
        lda     header+1
        sta     remaining+1
@stream:
        lda     remaining
        ora     remaining+1
        beq     @close
        jsr     chunk_size      ; up to 512 bytes at a time
        lda     #FM_READ
        jsr     fm_range
        bcs     @fail_close
        lda     #<DOSDATA
        sta     PTR
        lda     #>DOSDATA
        sta     PTR+1
        lda     chunk
        sta     count
        lda     chunk+1
        sta     count+1
        jsr     send_bytes
        jsr     subtract_chunk
        jmp     @stream
@close: jsr     fm_close
        jmp     ok
@fail_close:
        pha
        jsr     fm_close
        pla
        jmp     fail
@bad:   jmp     bad_name

; reads A (2 or 4) header bytes into header; carry set on error
read_header:
        sta     chunk
        lda     #0
        sta     chunk+1
        lda     #<header
        sta     fm+$08
        lda     #>header
        sta     fm+$09
        lda     #FM_READ
        jmp     fm_range_at

; ---- WRITE: create a file from the bytes that follow, adding its header ---
do_write:
        jsr     read_path
        php
        jsr     get             ; the type tag
        sta     count
        ldy     #0
@tag:   cpy     count
        beq     @tagged
        jsr     get
        cpy     #3
        bcs     :+
        sta     tag,y
:       iny
        bne     @tag
@tagged:
        jsr     get             ; aux: a B file's load address
        sta     aux_hold
        jsr     get
        sta     aux_hold+1
        jsr     get
        sta     attributes
        jsr     get             ; size
        sta     remaining
        jsr     get
        sta     remaining+1
        jsr     get
        sta     remaining+2
        jsr     get
        beq     @far7
        jmp     @too_big
@far7:
        plp
        bcc     @far6
        jmp     @bad
@far6:
        lda     has_name
        bne     @far5
        jmp     @bad
@far5:
        jsr     tag_to_type
        bcc     @far4
        jmp     @bad_type
@far4:
        sta     type_hold
        cmp     #$04            ; B, A and I files carry a 16-bit length
        beq     :+
        cmp     #$02
        beq     :+
        cmp     #$01
        bne     @exists
:       lda     remaining+2
        beq     @far3
        jmp     @too_big2
@far3:
@exists:
        ldx     #1              ; already there?
        jsr     fm_open
        bcs     :+
        jsr     fm_close
        lda     #RES_EXISTS
        jsr     complete
        jmp     loop
:       cmp     #DOS_NOT_FOUND
        beq     :+
        jmp     fail
:       lda     type_hold       ; create it: OPEN with X = 0 answers "not
        sta     fm+$07          ; found" even as it creates the file
        ldx     #0
        jsr     fm_open_typed
        bcc     :+
        cmp     #DOS_NOT_FOUND
        beq     :+
        jmp     fail
:       jsr     fm_position
        bcc     @far2
        jmp     @fail_close
@far2:
        lda     type_hold       ; the header DOS keeps in the file
        cmp     #$04
        bne     @program
        lda     aux_hold
        sta     header
        lda     aux_hold+1
        sta     header+1
        lda     remaining
        sta     header+2
        lda     remaining+1
        sta     header+3
        lda     #4
        jsr     write_header
        bcs     @fail_close
        jmp     @data
@program:
        cmp     #$02
        beq     :+
        cmp     #$01
        bne     @data
:       lda     remaining
        sta     header
        lda     remaining+1
        sta     header+1
        lda     #2
        jsr     write_header
        bcs     @fail_close
@data:  lda     remaining
        ora     remaining+1
        ora     remaining+2
        beq     @close
        jsr     chunk_size
        lda     #<DOSDATA
        sta     PTR
        lda     #>DOSDATA
        sta     PTR+1
        lda     chunk
        sta     count
        lda     chunk+1
        sta     count+1
        jsr     receive_bytes
        lda     #FM_WRITE
        jsr     fm_range
        bcs     @fail_close
        jsr     subtract_chunk
        jmp     @data
@close: jsr     fm_close
        bcs     @failed
        lda     attributes      ; locked?
        bpl     :+
        lda     #FM_LOCK
        ldx     #1
        jsr     fm_named
        bcs     @failed
:       jmp     ok
@fail_close:
        pha
        jsr     fm_close
        pla
@failed:
        jmp     fail
@too_big:
        plp
@too_big2:
        lda     #RES_DISK_FULL
        jsr     complete
        jmp     loop
@bad:   jmp     bad_name
@bad_type:
        lda     #0
        jsr     put_other_code
        jmp     loop

write_header:
        sta     chunk
        lda     #0
        sta     chunk+1
        lda     #<header
        sta     fm+$08
        lda     #>header
        sta     fm+$09
        lda     #FM_WRITE
        jmp     fm_range_at

; ---- DELETE, END ----------------------------------------------------------
do_delete:
        jsr     read_path
        bcs     :+
        lda     has_name
        beq     :+
        lda     #FM_DELETE
        ldx     #1
        jsr     fm_named
        bcc     @far1
        jmp     fail
@far1:
        jmp     ok
:       jmp     bad_name

do_end: lda     #RES_OK
        jsr     complete
        ldx     slotx
        jmp     (finptr)        ; restore memory from ROM, then return

; ---- the file manager -----------------------------------------------------
; fm is this agent's copy of the parameter list: set up here, copied into
; DOS's list for each call, and copied back for the results.

fm_open:                        ; X: 0 = may create
        lda     #0              ; type for a new file: set by fm_open_typed
        sta     fm+$07
fm_open_typed:
        lda     #FM_OPEN
fm_named:                       ; A = call type, X: 0 = may create
        sta     fm
        lda     #0
        sta     fm+$02          ; record length: variable
        sta     fm+$03
        sta     fm+$04          ; any volume
        lda     drive
        sta     fm+$05
        lda     slot
        sta     fm+$06
        lda     #<name
        sta     fm+$08
        lda     #>name
        sta     fm+$09
        jmp     fm_call

fm_position:
        lda     #FM_POSITION
        sta     fm
        lda     #0
        sta     fm+$02
        sta     fm+$03
        sta     fm+$04
        sta     fm+$05
        ldx     #1
        jmp     fm_call

fm_close:
        lda     #FM_CLOSE
        sta     fm
        ldx     #1
        jmp     fm_call

fm_read_one:                    ; the byte is at fm+$08
        lda     #FM_READ
        sta     fm
        lda     #FM_ONE
        sta     fm+$01
        ldx     #1
        jmp     fm_call

; READ or WRITE (A) chunk bytes at DOSDATA
fm_range:
        pha
        lda     #<DOSDATA
        sta     fm+$08
        lda     #>DOSDATA
        sta     fm+$09
        pla
; ...or at the address already in fm+$08
fm_range_at:
        sta     fm
        lda     #FM_RANGE
        sta     fm+$01
        lda     chunk
        sta     fm+$06
        lda     chunk+1
        sta     fm+$07
        lda     fm
        cmp     #FM_WRITE       ; WRITE wants the length less one
        bne     :+
        lda     fm+$06
        bne     @low
        dec     fm+$07
@low:   dec     fm+$06
:       ldx     #1
        ; fall through

; Calls the file manager with this agent's list; X: 0 = OPEN may create.
; Carry set, A = the return code, on an error.
fm_call:
        txa
        pha
        lda     #<FM_WORKAREA   ; this agent's buffers, every time
        sta     fm+$0C
        lda     #>FM_WORKAREA
        sta     fm+$0D
        lda     #<FM_TSLIST
        sta     fm+$0E
        lda     #>FM_TSLIST
        sta     fm+$0F
        lda     #<FM_DATASEC
        sta     fm+$10
        lda     #>FM_DATASEC
        sta     fm+$11
        lda     fmparms
        sta     PTR2
        lda     fmparms+1
        sta     PTR2+1
        ldy     #$11
@in:    lda     fm,y
        sta     (PTR2),y
        dey
        bpl     @in
        pla
        tax
        jsr     DOS_FM
        ldy     #$11
@out:   lda     (PTR2),y
        sta     fm,y
        dey
        bpl     @out
        lda     fm+$0A          ; the return code
        cmp     #1              ; carry set if non-zero
        rts

; ---- shared helpers ----------------------------------------------------------
chunk_size:                     ; chunk = min(512, remaining)
        lda     remaining+2
        bne     @full
        lda     remaining+1
        cmp     #2
        bcs     @full
        lda     remaining
        sta     chunk
        lda     remaining+1
        sta     chunk+1
        rts
@full:  lda     #<512
        sta     chunk
        lda     #>512
        sta     chunk+1
        rts

subtract_chunk:
        sec
        lda     remaining
        sbc     chunk
        sta     remaining
        lda     remaining+1
        sbc     chunk+1
        sta     remaining+1
        lda     remaining+2
        sbc     #0
        sta     remaining+2
        rts

send_bytes:                     ; count bytes from (PTR) to the card
        ldy     #0
@loop:  lda     count
        ora     count+1
        beq     @done
        lda     (PTR),y
        jsr     put
        iny
        bne     :+
        inc     PTR+1
:       lda     count
        bne     :+
        dec     count+1
:       dec     count
        jmp     @loop
@done:  rts

receive_bytes:                  ; count bytes from the card to (PTR)
        ldy     #0
@loop:  lda     count
        ora     count+1
        beq     @done
        jsr     get
        sta     (PTR),y
        iny
        bne     :+
        inc     PTR+1
:       lda     count
        bne     :+
        dec     count+1
:       dec     count
        jmp     @loop
@done:  rts

; Reads a path: "Sn,Dd" (sets slot, drive) and optionally a file name
; (into name: 30 characters, high bit set, space-padded). Carry set if
; malformed; the rest is still read.
read_path:
        lda     #0
        sta     has_name
        sta     overflow
        jsr     get             ; components: 1 or 2
        sta     components
        cmp     #1
        beq     :+
        cmp     #2
        beq     :+
        inc     overflow
:       jsr     get             ; "Sn,Dd"
        sta     count
        ldy     #0
@drive: cpy     count
        beq     @parsed
        jsr     get
        cpy     #5
        bcs     :+
        sta     container,y
:       iny
        bne     @drive
@parsed:
        lda     count
        cmp     #5
        bne     @bad_drive
        lda     container
        cmp     #'S'
        bne     @bad_drive
        lda     container+2
        cmp     #','
        bne     @bad_drive
        lda     container+3
        cmp     #'D'
        bne     @bad_drive
        lda     container+1
        sec
        sbc     #'1'
        cmp     #7
        bcs     @bad_drive
        adc     #1
        sta     slot
        lda     container+4
        sec
        sbc     #'1'
        cmp     #2
        bcs     @bad_drive
        adc     #1
        sta     drive
        jmp     @name
@bad_drive:
        inc     overflow
@name:  lda     components
        cmp     #2
        bne     @done
        inc     has_name
        ldy     #29             ; space-fill, then the name over it
        lda     #' ' | $80
:       sta     name,y
        dey
        bpl     :-
        jsr     get
        sta     count
        cmp     #31
        bcc     :+
        inc     overflow
:       ldy     #0
@char:  cpy     count
        beq     @done
        jsr     get
        cpy     #30
        bcs     :+
        ora     #$80
        sta     name,y
:       iny
        bne     @char
@done:  lda     overflow
        cmp     #1
        rts

; ---- tags: DOS's type letters, from its own CATALOG table ------------------
put_tag:                        ; type_hold = type (lock bit clear)
        ldy     #0
@find:  lda     type_letters+1,y ; the end: a 0 letter (T's type is 0)
        beq     @hex
        lda     type_letters,y
        cmp     type_hold
        beq     @letter
        iny
        iny
        bne     @find
@letter:
        lda     #1
        jsr     put
        lda     type_letters+1,y
        jmp     put
@hex:   lda     #3              ; anything else: "$hh"
        jsr     put
        lda     #'$'
        jsr     put
        lda     type_hold
        lsr     a
        lsr     a
        lsr     a
        lsr     a
        jsr     hex_digit
        jsr     put
        lda     type_hold
        and     #$0F
        jsr     hex_digit
        jmp     put

tag_to_type:                    ; tag (count characters) -> A; carry set if unknown
        lda     count
        cmp     #1
        bne     @hex
        ldy     #0
@find:  lda     type_letters+1,y ; the end: a 0 letter (T's type is 0)
        beq     @bad
        cmp     tag
        beq     @found
        iny
        iny
        bne     @find
@found: lda     type_letters,y
        clc
        rts
@hex:   cmp     #3
        bne     @bad
        lda     tag
        cmp     #'$'
        bne     @bad
        lda     tag+1
        jsr     hex_value
        bcs     @bad
        asl     a
        asl     a
        asl     a
        asl     a
        sta     hex_hold
        lda     tag+2
        jsr     hex_value
        bcs     @bad
        ora     hex_hold
        clc
        rts
@bad:   sec
        rts

hex_digit:
        cmp     #10
        bcc     :+
        adc     #6
:       adc     #'0'
        rts

hex_value:
        sec
        sbc     #'0'
        cmp     #10
        bcc     @ok
        sbc     #'A' - '0'
        cmp     #6
        bcs     @bad
        adc     #10
@ok:    clc
        rts
@bad:   sec
        rts

; type, letter; the first entry, T, has type 0, so the table ends with
; a 0 *letter* -- checked through the type byte of the entry after it
type_letters:
        .byte   $01, 'I'
        .byte   $02, 'A'
        .byte   $04, 'B'
        .byte   $08, 'S'
        .byte   $10, 'R'
        .byte   $00, 'T'
        .byte   $00, 0

; ---- completion and card access ------------------------------------------
ok:     lda     #RES_OK
        jsr     complete
        jmp     loop

bad_name:
        lda     #RES_BAD_NAME
        jsr     complete
        jmp     loop

; A = a file manager return code (or $FF for an RWTS error)
fail:   cmp     #4
        bne     :+
        lda     #RES_WRITE_PROT
        bne     @result
:       cmp     #DOS_NOT_FOUND
        bne     :+
        lda     #RES_NOT_FOUND
        bne     @result
:       cmp     #9
        bne     :+
        lda     #RES_DISK_FULL
        bne     @result
:       cmp     #8
        beq     @io
        cmp     #7              ; volume mismatch
        beq     @io
        jsr     put_other_code  ; e.g. $0A, file locked
        jmp     loop
@io:    lda     #RES_IO_ERROR
@result:
        jsr     complete
        jmp     loop

put_other_code:
        jsr     put
        lda     #0
        jsr     put
        lda     #RES_OTHER
        jmp     complete

put:    ldx     slotx
        sta     CARD_DATA,x
        rts

get:    ldx     slotx
        lda     CARD_DATA,x
        rts

complete:
        ldx     slotx
        sta     CARD_DONE,x
        rts

title:  apple_string "HOST TRANSFER: DOS 3.3"
        .byte   $8D, 0

capabilities:
        .byte   $01, 3, "DOS"
        .byte   $02, 3, "3.3"
        .byte   $03, 1, 0               ; flat: drives
        .byte   $04, 1, 30              ; names up to 30 characters
        .byte   $05, 1, $03             ; upper case, starting with a letter
        .byte   $06, 28, ". !#$%&'()*+-/:;<=>?@[]^_", $22, $5C, $60
        .byte   $07, 1, $0D             ; lines end with CR
        .byte   $08, 1, 1               ; text has bit 7 set
        .byte   $09, 2, 1, "T"          ; T is text
        .byte   $0A, 4, 1, "T", 0, 0    ; default text type: T
        .byte   $0B, 4, 1, "B", 0, 0    ; default other type: B
        .byte   $0C, 1, 0               ; default attributes: unlocked
        .byte   $0D, 1, 0               ; no record length is kept
        .byte   $00
capabilities_end:

; ---- variables ---------------------------------------------------------------
fmparms:        .word   0
iob:            .word   0
fm:             .res    $12
slot:           .byte   0
drive:          .byte   0
components:     .byte   0
has_name:       .byte   0
overflow:       .byte   0
container:      .res    5
name:           .res    30
count:          .word   0
index:          .byte   0
cat_track:      .byte   0
cat_sector:     .byte   0
rw_track:       .byte   0
rw_sector:      .byte   0
rw_page:        .byte   0
type_hold:      .byte   0
hex_hold:       .byte   0
aux_hold:       .word   0
size_hold:      .res    3
attributes:     .byte   0
remaining:      .res    3
chunk:          .word   0
header:         .res    4
tag:            .res    3
        .endscope
