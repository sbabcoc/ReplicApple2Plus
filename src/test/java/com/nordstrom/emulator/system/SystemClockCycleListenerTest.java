package com.nordstrom.emulator.system;

import com.nordstrom.emulator.MemoryBus;
import com.nordstrom.emulator.cpu.Cpu6502;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms {@link SystemClock#addCycleListener} genuinely drives a real
 * consumer forward as the CPU executes -- not just that
 * {@link PaddleTimers#tick} works when called directly, which every
 * other {@code PaddleTimers} test already covers, but that
 * {@code SystemClock} actually calls it, with the real, unmodified
 * per-instruction cycle counts, as instructions execute. Also confirms
 * multiple listeners are independently notified -- the actual reason
 * this is a list rather than a single callback slot, since
 * {@code Disk2Controller}'s still-pending LSS ticking and
 * {@link VideoScanner} are both expected to register their own,
 * separate listeners once wired up for real.
 */
class SystemClockCycleListenerTest {

    private static final class FlatBus implements MemoryBus {
        final int[] mem = new int[0x10000];
        public int read(int a) { return mem[a & 0xFFFF]; }
        public void write(int a, int v) { mem[a & 0xFFFF] = v & 0xFF; }
    }

    @Test
    void addCycleListenerDrivesPaddleTimersForwardAsTheCpuExecutes() {
        FlatBus bus = new FlatBus();
        for (int i = 0; i < 6; i++) {
            bus.mem[0x1000 + i] = 0xEA; // six NOPs = 12 real cycles
        }
        bus.mem[0xFFFC] = 0x00;
        bus.mem[0xFFFD] = 0x10;
        Cpu6502 cpu = new Cpu6502(bus, 0xFFFC);
        SystemClock clock = new SystemClock(cpu);

        PaddleTimers shortChannel = new PaddleTimers();
        clock.addCycleListener(shortChannel::tick);
        shortChannel.setPosition(0, 1); // (1 * 2816) / 255 = 11 trip cycles
        shortChannel.trigger();
        assertTrue(shortChannel.isRunning(0), "channel should be running immediately after trigger");

        PaddleTimers longChannel = new PaddleTimers();
        FlatBus bus2 = new FlatBus();
        for (int i = 0; i < 6; i++) {
            bus2.mem[0x1000 + i] = 0xEA;
        }
        bus2.mem[0xFFFC] = 0x00;
        bus2.mem[0xFFFD] = 0x10;
        Cpu6502 cpu2 = new Cpu6502(bus2, 0xFFFC);
        SystemClock clock2 = new SystemClock(cpu2);
        clock2.addCycleListener(longChannel::tick);
        longChannel.setPosition(0, 255); // 2816 trip cycles
        longChannel.trigger();

        for (int i = 0; i < 6; i++) {
            clock.step();
            clock2.step();
        }

        assertFalse(shortChannel.isRunning(0), "an 11-trip-cycle channel should have expired after 12 real elapsed cycles");
        assertTrue(longChannel.isRunning(0), "a 2816-trip-cycle channel should still be running after only 12 real cycles");
    }

    @Test
    void multipleListenersAreEachIndependentlyNotified() {
        FlatBus bus = new FlatBus();
        bus.mem[0x1000] = 0xEA;
        bus.mem[0xFFFC] = 0x00;
        bus.mem[0xFFFD] = 0x10;
        Cpu6502 cpu = new Cpu6502(bus, 0xFFFC);
        SystemClock clock = new SystemClock(cpu);

        int[] firstListenerTotal = {0};
        int[] secondListenerTotal = {0};
        clock.addCycleListener(cycles -> firstListenerTotal[0] += cycles);
        clock.addCycleListener(cycles -> secondListenerTotal[0] += cycles);

        int cycles = clock.step();

        assertEquals(cycles, firstListenerTotal[0], "first listener should receive the exact elapsed cycles");
        assertEquals(cycles, secondListenerTotal[0], "second listener should independently receive the same cycles");
    }
}
