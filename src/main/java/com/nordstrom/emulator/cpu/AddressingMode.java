package com.nordstrom.emulator.cpu;

/**
 * Addressing modes of the NMOS 6502. Deliberately excludes "(zp)" indirect
 * with no index -- that mode (and the opcodes that use it, e.g. $72 ADC)
 * exists only on the 65C02. On real NMOS hardware those opcode bytes are
 * undefined/illegal instead; see the illegal-opcode work still pending.
 */
enum AddressingMode {
    /** No operand at all -- e.g. INX, RTS. */
    IMPLIED,
    /** Operand is the accumulator itself -- e.g. ASL A. */
    ACCUMULATOR,
    /** Operand is the literal byte following the opcode. */
    IMMEDIATE,
    /** Operand address is a single byte, within $0000-$00FF. */
    ZERO_PAGE,
    /** Zero-page address plus X, wrapping within page zero. */
    ZERO_PAGE_X,
    /** Zero-page address plus Y, wrapping within page zero. */
    ZERO_PAGE_Y,
    /** Operand address is the two bytes following the opcode. */
    ABSOLUTE,
    /** Absolute address plus X; may cross a page boundary. */
    ABSOLUTE_X,
    /** Absolute address plus Y; may cross a page boundary. */
    ABSOLUTE_Y,
    /** JMP (abs) only -- carries the famous NMOS page-wrap bug. */
    INDIRECT,
    /** (zp,X) -- X is applied to the pointer address before the indirection. */
    INDEXED_INDIRECT_X,
    /** (zp),Y -- Y is applied to the address after the indirection; may cross a page boundary. */
    INDIRECT_INDEXED_Y,
    /** Signed 8-bit branch offset, relative to the address of the following instruction. */
    RELATIVE
}
