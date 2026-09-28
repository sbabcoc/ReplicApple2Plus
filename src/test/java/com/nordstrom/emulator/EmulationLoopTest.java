package com.nordstrom.emulator;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the contract {@link EmulationLoop} exists to provide -- each
 * item corresponds to something that went wrong, or would go wrong, if
 * it were violated:
 * <ul>
 *   <li>Posted work runs on the emulation thread and only between
 *       ticks, never in the middle of one. The Swing event thread hands
 *       keystrokes and disk changes over this way precisely because it
 *       must never touch emulator state directly.</li>
 *   <li>A tick is exactly {@code cyclesPerTick} cycles followed by one
 *       audio flush, so emulated time and audio stay in lockstep.</li>
 *   <li>With a device that paces, the loop never adds its own sleep --
 *       the blocking flush is the clock. Without one, it paces itself to
 *       real time instead of running flat out.</li>
 *   <li>A failure stops emulation cleanly, and a failing posted task
 *       does not.</li>
 * </ul>
 */
class EmulationLoopTest {

    private static final int STEP_CYCLES = 4;
    private static final int CYCLES_PER_TICK = 20; // 5 steps per tick

    private static boolean waitFor(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(2);
        }
        return condition.getAsBoolean();
    }

    @Test
    void postedTasksRunOnTheEmulationThreadBetweenTicks() throws Exception {
        AtomicInteger steps = new AtomicInteger();
        AtomicReference<String> taskThread = new AtomicReference<>();
        AtomicInteger stepsWhenTaskRan = new AtomicInteger(-1);
        CountDownLatch taskRan = new CountDownLatch(1);

        EmulationLoop loop = new EmulationLoop(
            () -> { steps.incrementAndGet(); return STEP_CYCLES; }, CYCLES_PER_TICK, 1_000_000L,
            () -> { }, () -> false, () -> { });
        loop.execute(() -> {
            taskThread.set(Thread.currentThread().getName());
            stepsWhenTaskRan.set(steps.get());
            taskRan.countDown();
        });
        loop.start();
        try {
            assertTrue(taskRan.await(2, TimeUnit.SECONDS), "the posted task should run");
            assertEquals("emulation", taskThread.get(), "the task must run on the emulation thread, not the caller's");
            assertEquals(0, stepsWhenTaskRan.get() % (CYCLES_PER_TICK / STEP_CYCLES),
                "the task must run on a tick boundary, never partway through a tick");
        } finally {
            loop.stop(1000);
        }
    }

    @Test
    void eachTickIsExactlyCyclesPerTickFollowedByOneFlush() throws Exception {
        AtomicInteger steps = new AtomicInteger();
        AtomicInteger flushes = new AtomicInteger();
        AtomicInteger frames = new AtomicInteger();
        AtomicInteger badFlush = new AtomicInteger();
        int stepsPerTick = CYCLES_PER_TICK / STEP_CYCLES;

        EmulationLoop loop = new EmulationLoop(
            () -> { steps.incrementAndGet(); return STEP_CYCLES; }, CYCLES_PER_TICK, 1_000_000L,
            () -> {
                // At every flush, exactly (flushes+1) whole ticks' worth of steps must have run.
                if (steps.get() != (flushes.incrementAndGet()) * stepsPerTick) {
                    badFlush.incrementAndGet();
                }
            },
            () -> false, frames::incrementAndGet);
        loop.start();
        try {
            assertTrue(waitFor(() -> flushes.get() >= 5, 3000), "should complete several ticks");
        } finally {
            loop.stop(1000);
        }
        assertEquals(0, badFlush.get(), "every flush must follow exactly cyclesPerTick cycles of stepping");
        assertEquals(flushes.get(), frames.get(), "one frame callback per tick, one flush per tick");
    }

    @Test
    void whenTheDevicePacesTheLoopAddsNoSleepOfItsOwn() throws Exception {
        AtomicInteger ticks = new AtomicInteger();
        // tickNanos is a full second: if the loop wrongly slept on it, it
        // could complete at most one tick in the window below.
        EmulationLoop loop = new EmulationLoop(
            () -> STEP_CYCLES, CYCLES_PER_TICK, 1_000_000_000L,
            () -> {
                ticks.incrementAndGet();
                try {
                    Thread.sleep(5); // stands in for a blocking device write
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            },
            () -> true, () -> { });
        loop.start();
        try {
            assertTrue(waitFor(() -> ticks.get() >= 10, 3000),
                "with a pacing device, the flush is the only clock -- 10 ticks must not take 10 seconds");
        } finally {
            loop.stop(1000);
        }
    }

    @Test
    void withoutADeviceTheLoopPacesItselfToRealTime() throws Exception {
        AtomicInteger ticks = new AtomicInteger();
        long tickNanos = 20_000_000L;
        EmulationLoop loop = new EmulationLoop(
            () -> STEP_CYCLES, CYCLES_PER_TICK, tickNanos,
            () -> { }, () -> false, ticks::incrementAndGet);
        long start = System.nanoTime();
        loop.start();
        try {
            Thread.sleep(500);
        } finally {
            loop.stop(1000);
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        double expected = seconds / 0.020;
        // Unpaced, this would run thousands of ticks. Allow generous slack for scheduler jitter.
        assertTrue(ticks.get() >= expected * 0.6 && ticks.get() <= expected * 1.15,
            "expected about " + (int) expected + " ticks in " + seconds + "s at 50Hz, got " + ticks.get());
    }

    @Test
    void aStepFailureStopsEmulationCleanly() throws Exception {
        AtomicInteger steps = new AtomicInteger();
        AtomicInteger flushes = new AtomicInteger();
        EmulationLoop loop = new EmulationLoop(
            () -> {
                if (steps.incrementAndGet() > 12) {
                    throw new IllegalStateException("unmapped address");
                }
                return STEP_CYCLES;
            },
            CYCLES_PER_TICK, 1_000_000L, flushes::incrementAndGet, () -> false, () -> { });
        loop.start();
        assertTrue(waitFor(() -> steps.get() > 12, 3000), "the failing step should be reached");
        Thread.sleep(50);
        int flushesAfterFailure = flushes.get();
        Thread.sleep(100);
        loop.stop(1000);
        assertEquals(flushesAfterFailure, flushes.get(), "no more ticks may run once emulation has stopped");
        assertEquals(13, steps.get(), "stepping must stop at the failure, not continue past it");
    }

    @Test
    void aFailingPostedTaskDoesNotStopTheMachine() throws Exception {
        AtomicInteger ticks = new AtomicInteger();
        CountDownLatch laterTaskRan = new CountDownLatch(1);
        EmulationLoop loop = new EmulationLoop(
            () -> STEP_CYCLES, CYCLES_PER_TICK, 1_000_000L,
            () -> { }, () -> false, ticks::incrementAndGet);
        loop.execute(() -> { throw new IllegalArgumentException("bad disk image"); });
        loop.execute(laterTaskRan::countDown);
        loop.start();
        try {
            assertTrue(laterTaskRan.await(2, TimeUnit.SECONDS), "work queued after a failing task must still run");
            int atFailure = ticks.get();
            assertTrue(waitFor(() -> ticks.get() > atFailure + 3, 3000), "ticking must continue after a task failure");
        } finally {
            loop.stop(1000);
        }
    }

    @Test
    void stopEndsTheLoopAndItCannotBeStartedTwice() throws Exception {
        AtomicInteger ticks = new AtomicInteger();
        EmulationLoop loop = new EmulationLoop(
            () -> STEP_CYCLES, CYCLES_PER_TICK, 1_000_000L,
            () -> { }, () -> false, ticks::incrementAndGet);
        loop.start();
        assertTrue(waitFor(() -> ticks.get() >= 2, 3000));
        loop.stop(1000);
        int atStop = ticks.get();
        Thread.sleep(100);
        assertEquals(atStop, ticks.get(), "no ticks may run after stop() returns");

        boolean threw = false;
        try {
            loop.start();
        } catch (IllegalStateException e) {
            threw = true;
        }
        assertTrue(threw, "a loop that has been started must refuse a second start");
        assertFalse(Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().equals("emulation") && t.isAlive()),
            "the emulation thread must actually be gone");
    }
}
