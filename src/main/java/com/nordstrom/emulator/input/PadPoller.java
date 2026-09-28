package com.nordstrom.emulator.input;

import java.io.PrintStream;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Runs a {@link PadProvider} on a thread of its own, feeding an
 * {@link InputMapper} at a steady rate.
 * <p>
 * The provider is created <i>on that thread</i>, not by the caller.
 * That is deliberate: SDL, which the gamepad provider sits on, expects
 * to be initialized, polled and shut down from one thread, and creating
 * it here guarantees that. The factory returns null (after saying why)
 * when no provider is available, in which case this thread simply ends.
 * <p>
 * When polling stops for any reason -- a stop request, or the provider
 * failing -- the mapper is given a neutral pad, so no button stays
 * pressed and no paddle stays deflected.
 */
public final class PadPoller {

    private final Supplier<PadProvider> factory;
    private final InputMapper mapper;
    private final long intervalMillis;
    private final PrintStream log;

    private volatile boolean running;
    private Thread thread;

    /**
     * Creates a poller.
     *
     * @param factory creates the provider (on the poller's own thread); may return null
     * @param mapper receives each snapshot
     * @param intervalMillis how long to wait between polls
     * @param log where to report connection changes and failures
     */
    public PadPoller(Supplier<PadProvider> factory, InputMapper mapper, long intervalMillis, PrintStream log) {
        this.factory = Objects.requireNonNull(factory);
        this.mapper = Objects.requireNonNull(mapper);
        this.intervalMillis = intervalMillis;
        this.log = log;
    }

    /** Starts the polling thread. May be called only once. */
    public synchronized void start() {
        if (thread != null) {
            throw new IllegalStateException("already started");
        }
        running = true;
        thread = new Thread(this::run, "input");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Stops polling and waits for the thread to finish cleaning up.
     *
     * @param joinMillis the longest to wait, in milliseconds
     */
    public void stop(long joinMillis) {
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
        PadProvider provider;
        try {
            provider = factory.get();
        } catch (RuntimeException | LinkageError e) {
            log.println("input: gamepad support failed to start: " + e);
            return;
        }
        if (provider == null) {
            return;
        }
        String lastDescription = null;
        try {
            while (running) {
                PadSnapshot snapshot = provider.poll();
                mapper.apply(snapshot);
                String description = provider.description();
                if (!description.equals(lastDescription)) {
                    log.println("input: " + description);
                    lastDescription = description;
                }
                try {
                    Thread.sleep(intervalMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.println("input: gamepad polling stopped after an error: " + e);
        } finally {
            try {
                mapper.apply(PadSnapshot.NEUTRAL);
            } catch (RuntimeException ignored) {
                // nothing sensible left to do about a sink that is already gone
            }
            provider.close();
        }
    }
}
