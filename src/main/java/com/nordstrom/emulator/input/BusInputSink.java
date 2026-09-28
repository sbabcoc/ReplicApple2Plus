package com.nordstrom.emulator.input;

import com.nordstrom.emulator.system.MotherboardBus;

import java.util.concurrent.Executor;

/**
 * Delivers mapped input to the real machine's paddles and pushbuttons.
 * <p>
 * Paddle and button state is emulator state, owned by the emulation
 * thread, and input arrives on another thread (a pad-polling thread, or
 * later the Swing event thread). So this never touches the machine
 * itself: it hands each change to the emulation loop via {@code
 * emulationThread}, which runs it between ticks -- the same way
 * keystrokes and disk changes are delivered.
 */
public final class BusInputSink implements InputSink {

    private final MotherboardBus bus;
    private final Executor emulationThread;

    /**
     * Creates a sink.
     *
     * @param bus the machine whose game connector this drives
     * @param emulationThread runs each change on the emulation thread
     */
    public BusInputSink(MotherboardBus bus, Executor emulationThread) {
        this.bus = bus;
        this.emulationThread = emulationThread;
    }

    @Override
    public void setPaddle(int channel, int position) {
        emulationThread.execute(() -> bus.paddleTimers().setPosition(channel, position));
    }

    @Override
    public void setButton(int button, boolean pressed) {
        emulationThread.execute(() -> bus.gameButtons().setPressed(button, pressed));
    }
}
