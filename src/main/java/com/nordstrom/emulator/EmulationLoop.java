package com.nordstrom.emulator;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Runs the emulated machine on its own dedicated thread, paced by the
 * audio device rather than by a Swing timer.
 * <p>
 * The reason this exists is a measured failure, not a style preference.
 * The previous design ran everything in a {@code javax.swing.Timer}
 * callback: {@code flush()} fired every ~24ms on average (27 of 29
 * measured intervals were over 20ms), yet each flush carried only
 * 20ms of audio. Audio was produced at about 83% of real time, so the
 * audio line ran dry between chunks, and a controlled test confirmed
 * that this underrun -- not chunk size, buffer size, or thread context --
 * was what distorted the sound. It also meant the emulator itself ran
 * at about 83% of its intended speed.
 * <p>
 * Here each tick runs {@code cyclesPerTick} cycles of emulation, then
 * flushes that tick's audio. Once the audio line's buffer is full, the
 * blocking {@code write()} inside that flush holds this thread until the
 * hardware has consumed enough to accept more, so emulated time is
 * locked to the audio clock: exactly {@code cyclesPerTick} cycles per
 * tick's worth of audio played, however long the work in between takes.
 * <p>
 * With no audio device there is nothing to block on, so the loop falls
 * back to sleeping until a fixed-rate deadline instead -- still real
 * time, just paced by the system clock rather than the sound card.
 * <p>
 * <b>Threading contract:</b> once {@link #start} is called, the
 * emulator's state belongs to this thread alone. Nothing on the Swing
 * event thread may call into it directly. Work that must touch emulator
 * state -- a keystroke, a disk insert or eject -- is handed to
 * {@link #execute} and runs on this thread, between ticks. Reading the
 * screen from the event thread while this thread runs is a deliberate,
 * accepted data race: a repaint may briefly show a mix of two frames,
 * exactly like tearing on a real display, and nothing it reads has a
 * side effect.
 */
final class EmulationLoop implements Executor {

    private final IntSupplier step;
    private final int cyclesPerTick;
    private final long tickNanos;
    private final Runnable audioFlush;
    private final BooleanSupplier audioPaces;
    private final Runnable frameDone;
    private final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();

    private volatile boolean running;
    private Thread thread;

    /**
     * @param step advances the machine by one instruction and returns the cycles it took
     * @param cyclesPerTick cycles of emulation to run per tick
     * @param tickNanos wall-clock length of one tick, used only when audio does not pace the loop
     * @param audioFlush hands this tick's audio to the device; expected to block when the device is full
     * @param audioPaces whether {@code audioFlush} really blocks at the device's rate
     *                   (false when there is no audio device)
     * @param frameDone called once per tick after emulation, on this thread, to request a repaint
     */
    EmulationLoop(IntSupplier step, int cyclesPerTick, long tickNanos,
                  Runnable audioFlush, BooleanSupplier audioPaces, Runnable frameDone) {
        this.step = step;
        this.cyclesPerTick = cyclesPerTick;
        this.tickNanos = tickNanos;
        this.audioFlush = audioFlush;
        this.audioPaces = audioPaces;
        this.frameDone = frameDone;
    }

    /**
     * Queues work to run on the emulation thread before its next tick.
     * Safe to call from any thread. Before {@link #start}, queued work
     * simply waits and runs at the first tick.
     *
     * @param task the work to run
     */
    @Override
    public void execute(Runnable task) {
        pending.add(task);
    }

    /** Starts the emulation thread. May be called only once. */
    synchronized void start() {
        if (thread != null) {
            throw new IllegalStateException("already started");
        }
        running = true;
        thread = new Thread(this::run, "emulation");
        thread.setDaemon(true); // never keep the JVM alive on its own
        thread.start();
    }

    /**
     * Asks the loop to stop and waits up to {@code joinMillis} for it
     * to finish its current tick. A thread blocked handing audio to a
     * full device is released as that device drains, so the wait is
     * bounded by roughly one audio buffer.
     *
     * @param joinMillis the longest to wait, in milliseconds
     */
    void stop(long joinMillis) {
        running = false;
        Thread t;
        synchronized (this) {
            t = thread;
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(joinMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run() {
        long deadline = System.nanoTime();
        while (running) {
            try {
                runPendingTasks();
                for (int cycles = 0; cycles < cyclesPerTick; ) {
                    cycles += step.getAsInt();
                }
                frameDone.run();
                audioFlush.run();
            } catch (RuntimeException e) {
                System.err.println("Emulation stopped: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                running = false;
                return;
            }

            if (audioPaces.getAsBoolean()) {
                deadline = System.nanoTime(); // the device is the clock; keep the fallback deadline current
            } else {
                deadline += tickNanos;
                long wait = deadline - System.nanoTime();
                if (wait > 0) {
                    try {
                        Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else if (wait < -5 * tickNanos) {
                    deadline = System.nanoTime(); // badly behind (e.g. suspended): resync, don't sprint to catch up
                }
            }
        }
    }

    /**
     * One failing task must not stop the machine -- these come from
     * the user interface (a bad disk image, say), not from emulation.
     */
    private void runPendingTasks() {
        Runnable task;
        while ((task = pending.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException e) {
                System.err.println("Emulation task failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }
}
