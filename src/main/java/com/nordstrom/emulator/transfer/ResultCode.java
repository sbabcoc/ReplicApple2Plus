package com.nordstrom.emulator.transfer;

/** How a request ended, as the adapter reports it -- TRANSFER-CARD.md 4.2 / 5.2. */
public enum ResultCode {
    /** The request succeeded. */
    OK(0x00),
    /** No such file, directory or volume. */
    NOT_FOUND(0x01),
    /** A file of that name already exists. */
    EXISTS(0x02),
    /** No room on the disk. */
    DISK_FULL(0x03),
    /** The disk is write-protected. */
    WRITE_PROTECTED(0x04),
    /** The guest rejected the name. */
    BAD_NAME(0x05),
    /** A disk or transfer error. */
    IO_ERROR(0x06),
    /** The OS's own error, with its code and a message in the reply. */
    OTHER(0x07);

    private final int code;

    ResultCode(int code) {
        this.code = code;
    }

    /** @return the completion code on the wire */
    public int code() {
        return code;
    }

    /**
     * @param code a completion code, $00-$7F
     * @return its result; codes this version doesn't define read as OTHER
     */
    public static ResultCode of(int code) {
        for (ResultCode r : values()) {
            if (r.code == code) {
                return r;
            }
        }
        return OTHER;
    }
}
