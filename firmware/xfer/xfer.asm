; =====================================================================
; XFER -- the host file transfer card's CP/M adapter (TRANSFER-CARD.md 8).
;
; A CP/M transient program, run from the CCP at $0100. The card delivers
; it as Intel HEX through CP/M's reader device; PIP and LOAD turn that into
; XFER.COM (TRANSFER-CARD.md 8.2).
;
; Placeholder: until the adapter is built, this only announces itself and
; returns to the CCP, so the delivery path can be tested end to end.
; =====================================================================

BDOS     equ  $0005
C_WRITESTR equ 9          ; BDOS: print a $-terminated string

        org  $0100

        ld   de,message
        ld   c,C_WRITESTR
        call BDOS
        ret                 ; back to the CCP

message:
        db   "XFER: the CP/M adapter isn't built yet.",13,10,'$'
