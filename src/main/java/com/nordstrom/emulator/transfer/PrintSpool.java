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
     * Trims the job to its non-blank lines: drops empty lines at its head,
     * and at its tail an unterminated last line and any empty lines before
     * it. Applesoft's LIST starts with a carriage return; after any listing,
     * BASIC starts its prompt on a fresh line -- a carriage return, then the
     * prompt -- still to the card. The firmware has these removed when the
     * listing is over. Spaces within lines are kept: Integer BASIC's
     * right-align its line numbers. Emulation thread.
     */
    public synchronized void trimListing() {
        byte[] bytes = job.toByteArray();
        int end = bytes.length;
        while (end > 0 && !isReturn(bytes[end - 1])) {
            end--; // the unterminated last line
        }
        while (end > 1 && isReturn(bytes[end - 1]) && isReturn(bytes[end - 2])) {
            end--; // trailing empty lines
        }
        int start = 0;
        while (start < end && isReturn(bytes[start])) {
            start++; // leading empty lines
        }
        job.reset();
        job.write(bytes, start, end - start);
    }

    private static boolean isReturn(byte b) {
        return (b & 0x7F) == '\r';
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
