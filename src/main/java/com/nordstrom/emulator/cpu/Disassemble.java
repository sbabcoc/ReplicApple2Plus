package com.nordstrom.emulator.cpu;

import com.nordstrom.emulator.MemoryBus;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A disassembler built directly from the CPU's own real opcode table
 * ({@link Opcodes#TABLE}) rather than a separate, independently-typed
 * mnemonic/addressing-mode listing -- so it can never disagree with
 * what the CPU actually executes. Built during a real debugging
 * session (tracing a DOS 3.3 boot failure) specifically because manual
 * byte-by-byte disassembly of captured memory dumps is genuinely
 * error-prone at any real complexity; using the CPU's own table
 * removes that whole class of mistake.
 * <p>
 * This is a code-level tool, not a replacement for the Monitor's own
 * disassembler when working interactively against the running
 * application -- it exists for headless investigation (test harnesses,
 * this project's own future debugging sessions) where no interactive
 * Monitor session is available at all.
 */
public final class Disassemble {

    /**
     * Disassembles and prints {@code [start, end]} (inclusive) from
     * {@code bus}, one instruction per line, in the form
     * {@code $ADDR: XX XX XX  MNEMONIC operand}. An instruction whose
     * last byte(s) fall past {@code end} is still printed in full
     * (its operand bytes are still read from {@code bus}).
     *
     * @param bus the memory to read instruction bytes from
     * @param start the first address to disassemble
     * @param end the last address to disassemble up to (inclusive)
     */
    public static void run(MemoryBus bus, int start, int end) {
        int addr = start;
        while (addr <= end) {
            int opcode = bus.read(addr);
            OpcodeDef def = Opcodes.TABLE[opcode];
            if (def == null) {
                System.out.printf("$%04X: %02X       ???%n", addr, opcode);
                addr++;
                continue;
            }
            int len = operandLength(def.mode());
            StringBuilder bytes = new StringBuilder(String.format("%02X ", opcode));
            for (int i = 1; i <= len; i++) {
                bytes.append(String.format("%02X ", bus.read(addr + i)));
            }
            while (bytes.length() < 10) {
                bytes.append("   ");
            }

            String operandStr = switch (def.mode()) {
                case IMPLIED, ACCUMULATOR -> "";
                case IMMEDIATE -> String.format("#$%02X", bus.read(addr + 1));
                case ZERO_PAGE -> String.format("$%02X", bus.read(addr + 1));
                case ZERO_PAGE_X -> String.format("$%02X,X", bus.read(addr + 1));
                case ZERO_PAGE_Y -> String.format("$%02X,Y", bus.read(addr + 1));
                case ABSOLUTE -> String.format("$%02X%02X", bus.read(addr + 2), bus.read(addr + 1));
                case ABSOLUTE_X -> String.format("$%02X%02X,X", bus.read(addr + 2), bus.read(addr + 1));
                case ABSOLUTE_Y -> String.format("$%02X%02X,Y", bus.read(addr + 2), bus.read(addr + 1));
                case INDIRECT -> String.format("($%02X%02X)", bus.read(addr + 2), bus.read(addr + 1));
                case INDEXED_INDIRECT_X -> String.format("($%02X,X)", bus.read(addr + 1));
                case INDIRECT_INDEXED_Y -> String.format("($%02X),Y", bus.read(addr + 1));
                case RELATIVE -> {
                    int offset = (byte) bus.read(addr + 1);
                    yield String.format("$%04X", addr + 2 + offset);
                }
            };

            System.out.printf("$%04X: %s%-4s %s%n", addr, bytes, def.mnemonic(), operandStr);
            addr += 1 + len;
        }
    }

    private static int operandLength(AddressingMode mode) {
        return switch (mode) {
            case IMPLIED, ACCUMULATOR -> 0;
            case IMMEDIATE, ZERO_PAGE, ZERO_PAGE_X, ZERO_PAGE_Y,
                 INDEXED_INDIRECT_X, INDIRECT_INDEXED_Y, RELATIVE -> 1;
            case ABSOLUTE, ABSOLUTE_X, ABSOLUTE_Y, INDIRECT -> 2;
        };
    }

    /**
     * Standalone entry point: disassembles a flat binary file loaded
     * at a given base address. Useful for disassembling a raw memory
     * dump or ROM image outside of any running emulator state.
     *
     * @param args {@code <path> <loadAddressHex> [startHex] [endHex]} --
     *             {@code loadAddress} is where byte 0 of the file is
     *             assumed to sit in memory; {@code start}/{@code end}
     *             default to the whole file if omitted
     * @throws Exception if the file can't be read or {@code args} contains a malformed hex number
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: Disassemble <path> <loadAddressHex> [startHex] [endHex]");
            System.exit(1);
            return;
        }
        byte[] data = Files.readAllBytes(Path.of(args[0]));
        int loadAddress = Integer.parseInt(args[1], 16);
        int start = args.length > 2 ? Integer.parseInt(args[2], 16) : loadAddress;
        int end = args.length > 3 ? Integer.parseInt(args[3], 16) : loadAddress + data.length - 1;

        MemoryBus flatBus = new MemoryBus() {
            @Override
            public int read(int address) {
                int offset = address - loadAddress;
                return (offset >= 0 && offset < data.length) ? (data[offset] & 0xFF) : 0;
            }

            @Override
            public void write(int address, int value) {
                // read-only view of a static file -- writes are simply discarded
            }
        };
        run(flatBus, start, end);
    }

    private Disassemble() {}
}
