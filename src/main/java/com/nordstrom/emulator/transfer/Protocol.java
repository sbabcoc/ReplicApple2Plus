package com.nordstrom.emulator.transfer;

/**
 * Constants of the host file transfer card's wire protocol, version 1 --
 * see TRANSFER-CARD.md, section 5.2, which this mirrors.
 */
public final class Protocol {

    private Protocol() {
    }

    /** The protocol version this emulator speaks. */
    public static final int VERSION = 1;

    // Register offsets from $C0n0.
    /** Read: next request opcode, 0 = none yet. */
    public static final int REG_REQUEST = 0x0;
    /** Write: completion code ending the adapter's current message. */
    public static final int REG_COMPLETE = 0x1;
    /** Read/write: the data port. */
    public static final int REG_DATA = 0x2;
    /** Read: version and host-available bit; write: ROM bank select. */
    public static final int REG_STATUS = 0x3;
    /** Write: the next printed character (PR#n). */
    public static final int REG_PRINT = 0x4;
    /** Read/write: the firmware's place in a recipe it's typing; 0 = not typing. */
    public static final int REG_TYPING = 0x5;

    /** LIST entry kind bit: a directory. */
    public static final int KIND_DIRECTORY = 0x01;
    /** LIST entry kind bit: the size is approximate. */
    public static final int KIND_SIZE_APPROXIMATE = 0x40;
    /** LIST entry kind bit: the aux value isn't known until the file is read. */
    public static final int KIND_AUX_UNKNOWN = 0x80;

    /** {@link #REG_STATUS} bit: a host transfer window is available. */
    public static final int STATUS_HOST_AVAILABLE = 0x80;

    // Adapter-initiated messages (completion codes $80 and up).
    /** BEGIN: payload = protocol version byte, then the capability record. */
    public static final int MSG_BEGIN = 0x80;
    /** STASH: payload = borrowed memory to keep. */
    public static final int MSG_STASH = 0x81;
    /** RECALL: the stashed bytes become readable on the data port. */
    public static final int MSG_RECALL = 0x82;

    // Request opcodes (card to adapter).
    /** VOLUMES: list the top-level containers. */
    public static final int REQ_VOLUMES = 0x01;
    /** LIST: list a container's entries. */
    public static final int REQ_LIST = 0x02;
    /** READ: send a file's bytes. */
    public static final int REQ_READ = 0x03;
    /** WRITE: create a file from the bytes that follow. */
    public static final int REQ_WRITE = 0x04;
    /** MAKE_DIR: create a directory. */
    public static final int REQ_MAKE_DIR = 0x05;
    /** DELETE: delete a file. */
    public static final int REQ_DELETE = 0x06;
    /** END: restore borrowed memory and return to BASIC. */
    public static final int REQ_END = 0x07;
    /** PRINT_LISTING: end the session, then print the BASIC program's listing. */
    public static final int REQ_PRINT_LISTING = 0x08;

    // Capability record field tags.
    /** Capability field: end of the record. */
    public static final int CAP_END = 0x00;
    /** Capability field: the OS's name, for display. */
    public static final int CAP_OS_NAME = 0x01;
    /** Capability field: the OS's version, for display. */
    public static final int CAP_OS_VERSION = 0x02;
    /** Capability field: 0 = flat, 1 = hierarchical. */
    public static final int CAP_STRUCTURE = 0x03;
    /** Capability field: the longest name allowed. */
    public static final int CAP_NAME_MAX_LENGTH = 0x04;
    /** Capability field: name rule bits ({@link #NAME_UPPER_CASE_ONLY}, {@link #NAME_STARTS_WITH_LETTER}). */
    public static final int CAP_NAME_RULES = 0x05;
    /** Capability field: the non-alphanumeric characters allowed in names. */
    public static final int CAP_NAME_EXTRA_CHARS = 0x06;
    /** Capability field: the byte sequence ending each line of text. */
    public static final int CAP_TEXT_LINE_END = 0x07;
    /** Capability field: 1 if text has bit 7 set. */
    public static final int CAP_TEXT_HIGH_BIT = 0x08;
    /** Capability field: the type tags that mean text. */
    public static final int CAP_TEXT_TAGS = 0x09;
    /** Capability field: the type for imported text that names none. */
    public static final int CAP_DEFAULT_TEXT_TYPE = 0x0A;
    /** Capability field: the type for other imported files that name none. */
    public static final int CAP_DEFAULT_BINARY_TYPE = 0x0B;
    /** Capability field: the attribute byte of an ordinary file. */
    public static final int CAP_DEFAULT_ATTRIBUTES = 0x0C;
    /** Capability field: 1 if a text file's non-zero aux value marks it random-access. */
    public static final int CAP_TEXT_AUX_IS_RECORD_LENGTH = 0x0D;
    /** Capability field: 1 if the adapter can print the BASIC program's listing (PRINT_LISTING). */
    public static final int CAP_PRINTS_LISTING = 0x0E;

    /** {@link #CAP_NAME_RULES} bit: names are upper case only. */
    public static final int NAME_UPPER_CASE_ONLY = 0x01;
    /** {@link #CAP_NAME_RULES} bit: names must start with a letter. */
    public static final int NAME_STARTS_WITH_LETTER = 0x02;
}
