package com.nordstrom.emulator.transfer;

import java.io.ByteArrayOutputStream;

/**
 * Characters printed to the transfer card (PR#n), collected into a print
 * job. The card can't see the end of a job -- PR#0 just stops the output
 * arriving -- so a job is taken once printing has been idle for a while
 * (TRANSFER-CARD.md 7). The emulation thread appends; the host side checks
 * and takes from its own thread.
 */
public final class PrintSpool {

    private final ByteArrayOutputStream job = new ByteArrayOutputStream();
    private long lastNanos;

    /**
     * Appends one printed character. Emulation thread.
     *
     * @param value the character, as printed
     */
    public synchronized void append(int value) {
        job.write(value);
        lastNanos = System.nanoTime();
    }

    /**
     * Takes the job if one is waiting and nothing has been printed for at
     * least {@code idleNanos}; otherwise leaves it.
     *
     * @param idleNanos how long printing must have been idle
     * @return the job's bytes, exactly as printed, or null if no job is ready
     */
    public synchronized byte[] takeIfIdle(long idleNanos) {
        if (job.size() == 0 || System.nanoTime() - lastNanos < idleNanos) {
            return null;
        }
        byte[] bytes = job.toByteArray();
        job.reset();
        return bytes;
    }

    /** @return true if a job has characters waiting */
    public synchronized boolean hasJob() {
        return job.size() > 0;
    }
}
