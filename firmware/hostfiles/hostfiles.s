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
;   - RAM $0800-$15FF and zero page $06-$09 are borrowed: saved to the
;     card first, restored at the end. Memory is left as it was.
; =====================================================================

        .setcpu "6502"
        .import __AGENT_LOAD__, __AGENT_RUN__, __AGENT_SIZE__ ; from ld65

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
REGION_END   = $16      ; first page after the region ($0800-$15FF)
IOBUF   = $1000         ; ProDOS I/O buffer: 1K, page-aligned
DATABUF = $1400         ; 512-byte data buffer
COPYVEC = $15FC         ; used while copying the agent: outside its image,
COPYCNT = $15FE         ;   which the copy overwrites

AGENT_BANK = 1          ; expansion ROM bank holding the ProDOS agent

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
        lda     #<COUT1         ; output back to the screen: PR#0. Both
        sta     VECTOUT         ; vectors: BASIC.SYSTEM keeps CSW pointing
        sta     CSW             ; at the output device too, and would
        lda     #>COUT1         ; otherwise call us again with its next
        sta     VECTOUT+1       ; character
        sta     CSW+1
        pla
        tay
        pla
        tax
        pla
        jmp     COUT1

; Copies COPYCNT bytes of the agent image from (PTR), in its own bank,
; to (PTR2); then selects bank 0 again and returns to main_continue.
; X = slot*16 throughout.
copy_agent:
        lda     #AGENT_BANK
        sta     CARD_STAT,x
        ldy     #0
@copy:  lda     (PTR),y
        sta     (PTR2),y
        iny
        bne     :+
        inc     PTR+1
        inc     PTR2+1
:       lda     COPYCNT
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
        beq     :+
        jmp     no_prodos
:
        lda     CARD_STAT,x     ; a host transfer window to talk to?
        bmi     :+
        jmp     no_host
:
        lda     BITMAP+1        ; pages $08-$0F free?
        beq     :+
        jmp     no_memory
:
        lda     BITMAP+2        ; pages $10-$15 free?
        and     #$FC
        beq     :+
        jmp     no_memory
:

        lda     ZP              ; lend the card zero page $06-$09...
        sta     CARD_DATA,x
        lda     ZP+1
        sta     CARD_DATA,x
        lda     ZP+2
        sta     CARD_DATA,x
        lda     ZP+3
        sta     CARD_DATA,x
        lda     #0              ; ...and $0800-$15FF
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

        lda     #<__AGENT_LOAD__ ; copy the agent into RAM -- from the $Cn
        sta     PTR             ; page, since its image is in another bank
        lda     #>__AGENT_LOAD__ ; and code can't switch away the bank it's
        sta     PTR+1           ; running from
        lda     #<__AGENT_RUN__
        sta     PTR2
        lda     #>__AGENT_RUN__
        sta     PTR2+1
        lda     #<__AGENT_SIZE__
        sta     COPYCNT
        lda     #>__AGENT_SIZE__
        sta     COPYCNT+1
        lda     #<copy_agent
        sta     COPYVEC
        lda     MSLOT
        sta     COPYVEC+1
        jmp     (COPYVEC)       ; returns to main_continue, X unchanged
main_continue:
        stx     slotx           ; tell the agent its slot and where finish is
        lda     MSLOT
        sta     finptr+1
        lda     #<finish
        sta     finptr
        jmp     agent

; X = slot * 16, from MSLOT ($Cn)
slot_x: lda     MSLOT
        asl     a
        asl     a
        asl     a
        asl     a
        tax
        rts

no_prodos:
        lda     #<COUT1         ; undo PR#n: output back to the screen
        sta     CSW
        lda     #>COUT1
        sta     CSW+1
        jsr     is_dos33
        bne     @plain
        jsr     DOSHOOK         ; DOS 3.3: let DOS reconnect its hooks
        ldy     #msg_dos - messages ; (offset 0: a BNE here would fall through)
        jmp     say_and_leave
@plain: ldy     #msg_prodos - messages
        jmp     say_and_leave

no_host:
        jsr     screen_output
        ldy     #msg_no_host - messages
        jmp     say_and_leave

no_memory:
        jsr     screen_output
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

screen_output:                  ; under ProDOS: PR#0 -- both vectors, as in finish
        lda     #<COUT1
        sta     VECTOUT
        sta     CSW
        lda     #>COUT1
        sta     VECTOUT+1
        sta     CSW+1
        rts

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
msg_dos:     apple_string "HOST TRANSFER: NO DOS 3.3 SUPPORT YET"
             .byte $8D, 0
msg_prodos:  apple_string "HOST TRANSFER NEEDS PRODOS"
             .byte $8D, 0
msg_no_host: apple_string "HOST TRANSFER IS NOT AVAILABLE"
             .byte $8D, 0
msg_memory:  apple_string "HOST TRANSFER: $800-$15FF IS IN USE"
             .byte $8D, 0

; =====================================================================
; The agent: runs from RAM at $0800. X is reloaded from slotx before
; every card access, since MLI calls don't promise to keep it.
; =====================================================================
        .segment "AGENT"
finptr: .word   0               ; first, at $0800: JMP (finptr) must not sit
                                ; on a page's last byte (a 6502 bug)
agent:  lda     CH              ; start a new line unless at column 0
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
        beq     :+
        jmp     @too_big
:
        plp                     ; was the path too long?
        bcc     :+
        jmp     bad_name
:       lda     count
        cmp     #3
        beq     :+
        jmp     @bad_type
:
        jsr     tag_to_type
        bcc     :+
        jmp     @bad_type
:
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
        bcc     :+
        jmp     fail
:
        lda     #7
        sta     info_params
        lda     attributes
        sta     info_access
        MLI_CALL MLI_SET_INFO, info_params
        bcc     :+
        jmp     fail
:
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
slotx:          .byte   0
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
