package com.nordstrom.emulator.transfer;

/**
 * A request that didn't succeed: the adapter's result, or the session ending
 * before the request could complete.
 */
public class TransferException extends Exception {

    private static final long serialVersionUID = 1L;

    /** The abstract result; null when the session ended instead. */
    private final ResultCode result;
    /** The OS's own error code, or -1. */
    private final int nativeCode;

    /**
     * @param result     the abstract result
     * @param nativeCode the OS's own error code, or -1 if none was given
     * @param message    a message for display
     */
    public TransferException(ResultCode result, int nativeCode, String message) {
        super(message);
        this.result = result;
        this.nativeCode = nativeCode;
    }

    /** @return the abstract result; null if the session ended instead */
    public ResultCode result() {
        return result;
    }

    /** @return the OS's own error code, or -1 */
    public int nativeCode() {
        return nativeCode;
    }

    /** The session ended -- RESET, reboot, or END -- before this request could complete. */
    public static final class Abandoned extends TransferException {
        /** @param why why the session ended, for display */
        public Abandoned(String why) {
            super(null, -1, why);
        }
    }
}
