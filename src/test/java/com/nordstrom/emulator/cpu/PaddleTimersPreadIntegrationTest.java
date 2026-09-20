package com.nordstrom.emulator.cpu;

import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.system.SlotCard;
import com.nordstrom.emulator.system.SystemClock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strongest original confirmation of {@code PaddleTimers}' RC
 * countdown calibration: assembling and running the actual historical
 * Monitor ROM {@code PREAD} routine (real disassembled bytes, not a
 * reimplementation) against the real CPU core, {@link SystemClock}, and
 * {@link MotherboardBus} together, and confirming an exact match
 * between the paddle position set and the value that real routine
 * computes. Lives in the {@code cpu} package specifically because it
 * needs direct, package-private access to {@link Cpu6502}'s own
 * {@code pc}/{@code y} fields, which
 * {@code com.nordstrom.emulator.system.PaddleTimersTest} (covering the
 * RC countdown logic itself) doesn't have.
 */
class PaddleTimersPreadIntegrationTest {

    private static final int[] PREAD_PROGRAM = {
        0xA2, 0x00,             // LDX #$00
        0xAD, 0x70, 0xC0,       // PREAD:  LDA $C070   (trigger)
        0xA0, 0x00,             //         LDY #$00
        0xEA,                   //         NOP
        0xEA,                   //         NOP
        0xBD, 0x64, 0xC0,       // PREAD2: LDA $C064,X
        0x10, 0x04,             //         BPL RTS2D   (+4)
        0xC8,                   //         INY
        0xD0, 0xF8,             //         BNE PREAD2  (-8)
        0x88,                   //         DEY
        0x60                    // RTS2D:  RTS
    };

    @Test
    void exactMatchAcrossTheFullPositionRange() {
        int[] testPositions = {0, 1, 64, 128, 200, 254, 255};
        for (int position : testPositions) {
            assertEquals(position, runRealPread(position),
                "PREAD should return exactly the position set, for position " + position);
        }
    }

    @Test
    void monotonicallyNonDecreasingAcrossTheFullRange() {
        int previous = -1;
        for (int position = 0; position <= 255; position += 5) {
            int result = runRealPread(position);
            assertTrue(result >= previous, "PREAD output should never decrease as position increases");
            previous = result;
        }
    }

    private static int runRealPread(int position) {
        SlotCard[] slots = new SlotCard[8];
        MotherboardBus bus = new MotherboardBus(slots);
        for (int i = 0; i < PREAD_PROGRAM.length; i++) {
            bus.write(0x1000 + i, PREAD_PROGRAM[i]);
        }
        bus.paddleTimers().setPosition(0, position);

        Cpu6502 cpu = new Cpu6502(bus, 0xFFFC); // reads real ROM's own reset vector -- irrelevant, overridden below
        cpu.pc = 0x1000;
        SystemClock clock = new SystemClock(cpu);
        clock.addCycleListener(bus.paddleTimers()::tick);

        for (int i = 0; i < 10_000; i++) {
            int opcode = bus.read(cpu.pc);
            clock.step();
            if (opcode == 0x60) { // just executed RTS -- the routine is done
                break;
            }
        }
        return cpu.y;
    }
}
