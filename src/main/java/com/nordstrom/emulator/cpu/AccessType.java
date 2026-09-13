package com.nordstrom.emulator.cpu;

/** How an instruction touches its operand -- cycle cost depends on this crossed with AddressingMode, not on either alone. */
enum AccessType {
    /** Fetches a value from memory only; can get a conditional page-cross discount. */
    READ,
    /** Writes a value to memory only; always pays the worst-case cycle count, no page-cross discount. */
    WRITE,
    /** Reads, modifies, and writes back in one instruction (INC, ASL abs, etc.); always worst-case, no discount. */
    READ_MODIFY_WRITE
}
