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

    @Test
    void withNothingPluggedInTheRealPreadRoutineReturns255OnEveryChannel() {
        // Real hardware: an empty game port leaves the timer's resistor open, so the
        // timer never trips and PREAD runs into its own counting cap. Not 0, and not 128.
        for (int channel = 0; channel < 4; channel++) {
            assertEquals(255, runPread(channel, null), "PREAD(" + channel + ") with nothing plugged in");
        }
    }

    @Test
    void aPluggedInChannelIsUnaffectedByOthersBeingUnplugged() {
        assertEquals(64, runPread(2, 64), "channel 2 plugged in at 64");
    }

    private static int runRealPread(int position) {
        return runPread(0, position);
    }

    /** Runs the real ROM PREAD on a channel; a null position leaves that channel unplugged. */
    private static int runPread(int channel, Integer position) {
        try (MotherboardBus bus = MotherboardBus.withoutAudio(new SlotCard[8])) {
            int[] program = PREAD_PROGRAM.clone();
            program[1] = channel; // LDX #channel
            for (int i = 0; i < program.length; i++) {
                bus.write(0x1000 + i, program[i]);
            }
            if (position != null) {
                bus.paddleTimers().setPosition(channel, position);
            }

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
}
