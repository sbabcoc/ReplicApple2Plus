package com.nordstrom.emulator.transfer;

/**
 * Runs a {@link FakeAdapter} on its own thread, polling the card the way the
 * firmware will -- standing in for the emulation thread, so tests have the
 * same concurrency as the application: requests made on one thread,
 * answered on another.
 */
final class AdapterThread implements AutoCloseable {

    private final FakeAdapter adapter;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile boolean silent;

    AdapterThread(FakeAdapter adapter) {
        this.adapter = adapter;
        thread = new Thread(this::run, "fake-adapter");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops answering, as if the Apple had hung, without ending anything. */
    void goSilent() {
        silent = true;
    }

    private void run() {
        while (running) {
            if (silent || adapter.serveOne() == 0) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        thread.join(2000);
    }
}
