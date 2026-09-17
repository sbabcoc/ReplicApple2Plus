package com.nordstrom.emulator.system;

import com.nordstrom.emulator.cpu.Cpu6502;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Drives a {@link Cpu6502} forward, one instruction at a time, with
 * exact cycle accounting.
 * <p>
 * Deliberately does not attempt to interleave execution with video or
 * any other peripheral at the sub-instruction, alternating-half-cycle
 * level real Apple II+ hardware uses to share RAM between the CPU and
 * video circuitry. Modeling that would mean restructuring every opcode
 * executor and addressing-mode resolver in the CPU core into discrete
 * per-cycle micro-steps -- a full rewrite of code that is currently
 * complete and verified, for no software-visible benefit: that
 * alternating scheme exists purely so video and CPU never electrically
 * contend for the same RAM cell in the same instant, which is
 * invisible to software either way. The one thing that IS observable
 * -- the exact relationship between elapsed CPU cycles and video
 * scanline/pixel position -- is preserved exactly here, because the
 * cycle counts this class accumulates are the real, unmodified values
 * {@link Cpu6502#step} already returns, not an approximation.
 * <p>
 * {@link #addCycleListener} exists because it now has real, concrete
 * consumers -- {@link PaddleTimers}' RC countdowns, and
 * {@code Disk2Controller}'s still-not-yet-wired LSS ticking -- not
 * because peripherals in general might someday want one. It's
 * deliberately a plain list of callbacks, not a full registration
 * system with names, priorities, or removal: nothing here needs any of
 * that yet, and it can grow if something concrete ever does.
 * <p>
 * Deliberately does not throttle to real Apple II+ timing (~1.023 MHz)
 * either -- that is a genuinely separate concern from cycle-accurate
 * execution, not a smaller version of it. A test harness wants to run
 * as fast as the host allows; an interactive frontend wants real-time
 * pacing. Baking wall-clock throttling into this class would force
 * every consumer to deal with a concern only one of them actually has.
 * Real-time pacing, when it's needed, belongs in whatever drives this
 * class (calling {@link #step} on a timer, say), not in this class
 * itself.
 */
public final class SystemClock {

    private final Cpu6502 cpu;
    private final List<IntConsumer> cycleListeners = new ArrayList<>();
    private long cycleCount;
    private volatile boolean running;

    /**
     * @param cpu the CPU this clock drives, already wired to a real memory bus
     */
    public SystemClock(Cpu6502 cpu) {
        this.cpu = cpu;
    }

    /**
     * Registers a listener to be called after every {@link #step}, with
     * the exact number of cycles that instruction just took.
     *
     * @param listener called with the elapsed cycle count after every step
     */
    public void addCycleListener(IntConsumer listener) {
        cycleListeners.add(listener);
    }

    /**
     * Executes exactly one CPU instruction, accumulates its exact cycle
     * count, and notifies every registered cycle listener.
     *
     * @return the number of cycles that instruction took
     */
    public int step() {
        int cycles = cpu.step();
        cycleCount += cycles;
        for (IntConsumer listener : cycleListeners) {
            listener.accept(cycles);
        }
        return cycles;
    }

    /**
     * Calls {@link #step} continuously until {@link #stop} is called
     * (typically from another thread -- {@code running} is volatile for
     * exactly that reason). Runs as fast as the host allows; see this
     * class's own Javadoc for why real-time pacing is deliberately not
     * this method's job.
     */
    public void run() {
        running = true;
        while (running) {
            step();
        }
    }

    /** Signals {@link #run} to return after its current instruction completes. */
    public void stop() {
        running = false;
    }

    /**
     * Total cycles executed since this clock was constructed.
     *
     * @return the total cycle count
     */
    public long cycleCount() {
        return cycleCount;
    }
}
