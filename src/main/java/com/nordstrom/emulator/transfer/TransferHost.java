package com.nordstrom.emulator.transfer;

/**
 * The host side of the transfer card -- in the application, the transfer
 * window. The card calls these on the emulation thread and expects them to
 * return at once: an implementation hands the work to its own thread (for
 * Swing, with {@code invokeLater}). See TRANSFER-CARD.md 3.1.
 */
public interface TransferHost {

    /**
     * A guest adapter began a session.
     *
     * @param session the session, through which the host makes requests
     */
    void sessionStarted(TransferSession session);

    /**
     * A session ended. Requests still outstanding have already completed
     * exceptionally with {@link TransferException.Abandoned}.
     *
     * @param session the session
     * @param reason  why it ended
     */
    void sessionEnded(TransferSession session, EndReason reason);

    /** Why a session ended. */
    enum EndReason {
        /** The host asked the adapter to END, and it did. */
        COMPLETED,
        /** RESET was asserted mid-session. */
        RESET,
        /** The adapter began a new session while this one was still open. */
        SUPERSEDED
    }
}
