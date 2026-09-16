package com.nordstrom.emulator.system;

import com.nordstrom.emulator.cpu.Cpu6502;

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
 * Deliberately has no notion of peripherals, listeners, or periodic
 * callbacks at all -- there is no real consumer to wire up yet
 * ({@code Disk2Controller}'s LSS ticking and any future video scanner
 * both still need to be built). Whichever of those lands first adds its
 * own specific hook here, sized to what it actually needs, rather than
 * this class guessing at a generic registration mechanism in advance of
 * any concrete requirement.
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
    private long cycleCount;
    private volatile boolean running;

    /**
     * @param cpu the CPU this clock drives, already wired to a real memory bus
     */
    public SystemClock(Cpu6502 cpu) {
        this.cpu = cpu;
    }

    /**
     * Executes exactly one CPU instruction and accumulates its exact cycle count.
     *
     * @return the number of cycles that instruction took
     */
    public int step() {
        int cycles = cpu.step();
        cycleCount += cycles;
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
