package com.nordstrom.emulator.cpu;

import com.nordstrom.emulator.MemoryBus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SYNC: the 6502 marks each opcode fetch, and only those, so a device on
 * the CPU's pins can tell where instructions start.
 */
class Cpu6502SyncTest {

    private static final class WatchedBus implements MemoryBus {
        final int[] mem = new int[0x10000];
        final List<Integer> fetches = new ArrayList<>();
        final List<Integer> reads = new ArrayList<>();

        @Override public int read(int address) {
            reads.add(address);
            return mem[address & 0xFFFF];
        }

        @Override public void write(int address, int value) {
            mem[address & 0xFFFF] = value & 0xFF;
        }

        @Override public int fetchOpcode(int address) {
            fetches.add(address);
            return mem[address & 0xFFFF];
        }
    }

    @Test
    void onlyOpcodeFetchesAreMarked() {
        WatchedBus bus = new WatchedBus();
        int[] program = {
            0xA9, 0x01,       // $0400 LDA #$01
            0x8D, 0x00, 0x03, // $0402 STA $0300
            0xEA,             // $0405 NOP
        };
        for (int i = 0; i < program.length; i++) {
            bus.mem[0x0400 + i] = program[i];
        }
        bus.mem[0xFFFC] = 0x00;
        bus.mem[0xFFFD] = 0x04;
        Cpu6502 cpu = new Cpu6502(bus, 0xFFFC);
        bus.reads.clear(); // the reset vector

        cpu.step();
        cpu.step();
        cpu.step();

        assertEquals(List.of(0x0400, 0x0402, 0x0405), bus.fetches);
        assertEquals(List.of(0x0401, 0x0403, 0x0404), bus.reads.stream().filter(a -> a >= 0x0400 && a < 0x0406).toList(),
            "operands are ordinary reads, not marked");
        assertEquals(1, bus.mem[0x0300]);
    }
}
