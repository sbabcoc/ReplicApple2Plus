package com.nordstrom.emulator.system;

import com.nordstrom.emulator.MemoryBus;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the text of a hi-res text display, such as a2lib's Soft-70, from
 * the 80 x 24 buffer it announces with a marker in memory -- text that
 * never touches the text page, so {@link ScreenText} can't see it.
 * <p>
 * The marker is a2lib's documented format (a2lib DESIGN.md 3.9): 16 bytes
 * directly before the buffer, anywhere in {@code $0800-$1FFF}, starting
 * with ASCII {@code A2SCR80} (bit 7 clear) and a version byte. Version 1
 * fixes the layout this class reads: 80 columns by 24 rows, top row
 * first, in the encoding named by the marker's byte 11:
 * <ul>
 *   <li>1, a2lib's Soft-70: each byte XOR {@code $A0} gives a screen code
 *       whose bit 7 only chooses normal or inverse video;</li>
 *   <li>2, plain ASCII, as Uthernet2's TELNET and GOPHER keep their
 *       screen: each byte is the character, {@code $20} a blank.</li>
 * </ul>
 * A marker in any other encoding (0 is reserved for the text screen's own
 * codes) is ignored, rather than copied as garbage. Columns the program
 * has panned out of view are copied too, since they are part of the
 * screen's text.
 * <p>
 * This reads memory the same way {@link ScreenText} reads the text page;
 * nothing is observed from the CPU. A marker left behind by a program
 * that crashed or was reset is indistinguishable from a live one, as the
 * format itself acknowledges.
 */
public final class MarkedScreenText {

    private static final byte[] SIGNATURE = {'A', '2', 'S', 'C', 'R', '8', '0'};
    private static final int VERSION = 1;
    private static final int ENCODING = 11;
    static final int SOFT70 = 1;
    static final int ASCII = 2;
    private static final int MARKER_LENGTH = 16;
    private static final int SEARCH_START = 0x0800;
    private static final int SEARCH_END = 0x2000; // exclusive: the marker lies wholly inside $0800-$1FFF
    private static final int COLUMNS = 80;
    private static final int ROWS = 24;

    private MarkedScreenText() {}

    /**
     * Searches for a version-1 marker in an encoding this class reads.
     *
     * @param bus memory to search
     * @return the marker's address, or -1 if there is none
     */
    public static int find(MemoryBus bus) {
        for (int address = SEARCH_START; address <= SEARCH_END - MARKER_LENGTH; address++) {
            if (isMarker(bus, address)) {
                return address;
            }
        }
        return -1;
    }

    private static boolean isMarker(MemoryBus bus, int address) {
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (bus.read(address + i) != SIGNATURE[i]) {
                return false;
            }
        }
        if (bus.read(address + SIGNATURE.length) != VERSION) {
            return false;
        }
        int encoding = bus.read(address + ENCODING);
        return encoding == SOFT70 || encoding == ASCII;
    }

    /**
     * Captures the whole 80 x 24 buffer following a marker, trimmed the
     * way {@link ScreenText#capture} trims the text page.
     * <p>
     * The buffer is taken to start directly after the marker, where the
     * format puts it, rather than at the marker's own address field: a
     * stale marker's field could point anywhere, including the I/O
     * space, where a read has side effects.
     *
     * @param bus    memory to read the buffer from
     * @param marker the marker's address, as from {@link #find}
     * @return the text, rows separated by {@code \n}
     */
    public static String capture(MemoryBus bus, int marker) {
        int buffer = marker + MARKER_LENGTH;
        int encoding = bus.read(marker + ENCODING);
        List<CharSequence> rows = new ArrayList<>(ROWS);
        for (int row = 0; row < ROWS; row++) {
            StringBuilder line = new StringBuilder(COLUMNS);
            for (int col = 0; col < COLUMNS; col++) {
                line.append(decode(encoding, bus.read(buffer + row * COLUMNS + col)));
            }
            rows.add(line);
        }
        return ScreenText.joinTrimmed(rows);
    }

    /**
     * One byte of the buffer to the character it displays as. Unlike the
     * II+ character generator, these fonts have lowercase, so the
     * character keeps its case.
     *
     * @param encoding the marker's encoding, {@link #SOFT70} or {@link #ASCII}
     * @param stored   a byte from the buffer
     * @return the displayed character, as plain ASCII
     */
    static char decode(int encoding, int stored) {
        if (encoding == ASCII) {
            int character = stored & 0x7F;
            return character < 0x20 || character == 0x7F ? ' ' : (char) character;
        }
        int character = (stored ^ 0xA0) & 0x7F;
        return (char) (character < 0x20 ? character + 0x40 : character);
    }
}
