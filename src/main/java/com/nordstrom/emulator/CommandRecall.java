package com.nordstrom.emulator;

import com.nordstrom.emulator.system.KeyboardRegister;
import com.nordstrom.emulator.system.ShiftKeyMod;

import java.util.List;

/**
 * Command history at the Apple's own prompts: the host's Up and Down
 * arrows step through lines entered before at the same prompt, which then
 * edit and enter like anything typed.
 * <p>
 * This is a device that plugs in between the 6502 and its socket and sees
 * only the CPU's pins: each access's address, data and direction, and SYNC
 * on opcode fetches ({@link MemoryBus#fetchOpcode}). It passes every
 * access through unchanged and makes none of its own. It also sits on the
 * keyboard cable ({@link KeyboardPort}): every key -- typed, pasted or
 * recalled -- passes through it to the keyboard connector, so it knows when
 * a key is waiting, and the strobe clears when it sees the CPU touch
 * {@code $C010}. Recalled lines go in by the same {@link TypingFeeder} as
 * Paste, which this device owns ({@link #typing}), and from there through
 * whatever input routine is installed -- the ROM's, DOS 3.3's,
 * BASIC.SYSTEM's or the VideoTerm's -- exactly as if typed. It needs no
 * activation and changes nothing in the Apple's input chain.
 * <p>
 * For the feeder's line pacing it counts the CPU's writes to screen memory:
 * the text pages ({@code $0400}-{@code $0BFF}) and the VideoTerm's
 * display-memory window ({@code $CC00}-{@code $CDFF}). On a stock II+ only
 * the CPU changes what's on screen, so a count that stops moving means a
 * screen that has settled.
 * <p>
 * Everything it knows comes from the Monitor's line-input routine, GETLN,
 * which Applesoft and the Monitor read their command lines with, and whose
 * key loop Integer BASIC uses (HARDWARE-REFERENCE.md section 4):
 * <ul>
 *   <li><b>A line starts</b> on the fetch of {@code $FD6F}
 *       ({@code LDX #$01}), which GETLN's entries pass, as does a line
 *       restarted by Ctrl-X or by backspacing past index 0; the line then
 *       starts at index 0. Integer BASIC instead prints its own prompt and
 *       enters GETLN's key loop directly ({@code JSR NXTCHAR}, {@code $FD75})
 *       with its own X -- 0 at its prompt, 2 in {@code AUTO} mode, where
 *       the buffer holds the line number first. So a fetch of {@code $FD75}
 *       that doesn't follow GETLN's own {@code $FD74} or {@code $FD60} also
 *       starts a line, at an index learned from the first key stored. The
 *       prompt is the value of {@code PROMPT} ({@code $33}) last read or
 *       written by the CPU. The opcode fetched must be GETLN's
 *       ({@code $A2}, {@code $20}); anything else (other code mapped at
 *       {@code $F800}) is not GETLN, and nothing is followed.</li>
 *   <li><b>The line</b> is what GETLN stores, at {@code $FD84}
 *       ({@code STA IN,X}): a write to {@code $0200+X} carrying the key,
 *       so X is on the address bus. After a Return the line is complete;
 *       after a left arrow ({@code $88}) the index is one less; otherwise
 *       it is one more. The line's length is the index less the index it
 *       started at -- which is also the most a recall may back up.
 *       Every write to {@code $0200}-{@code $02FF} is kept in a copy of
 *       the buffer -- the VideoTerm's lowercase corrections included.</li>
 *   <li><b>GETLN waits for a key</b> from the fetch of {@code $FD35}
 *       ({@code RDCHAR}) after GETLN's {@code JSR RDCHAR} at {@code $FD75},
 *       until the fetch of {@code $FD78}, where {@code RDCHAR} returns with
 *       the key, or of {@code $FD2F}, where it reads ESC and enters escape
 *       mode instead. Escape mode ends by falling into {@code $FD35}
 *       again.</li>
 *   <li><b>A line is entered</b> when the CPU clears the keyboard strobe
 *       ({@code $C010}) after reading a Return from {@code $C000}, while
 *       GETLN waits. That is the one point every command interpreter
 *       shares: DOS 3.3 acts on its commands when GETLN echoes the Return
 *       and leaves with its own saved stack pointer, and BASIC.SYSTEM's
 *       input hook takes the Return itself and makes GETLN restart the
 *       line -- neither returns to GETLN's caller.</li>
 *   <li><b>A RESET</b> -- the CPU reading the reset vector at
 *       {@code $FFFC} -- abandons the line.</li>
 *   <li><b>An interrupt</b> doesn't count as what came before an
 *       instruction. The CPU takes one without an opcode fetch: three pushes
 *       to the stack, then a read of the IRQ/BRK vector ({@code $FFFE}) or
 *       the NMI vector ({@code $FFFA}). The device remembers the last
 *       instruction fetched before it, and after the handler's {@code RTI}
 *       treats that as the one before the next fetch -- so whether a fetch of
 *       {@code $FD75} continues GETLN's loop or starts a line is decided as if
 *       the interrupt never happened. Interrupts can nest.</li>
 * </ul>
 * Recalling a line types: one left arrow for each character in the line
 * (GETLN backs its index up without erasing the screen; one more would
 * restart the line), the recalled line, and, if the line on screen was
 * longer, as many spaces as it overhangs followed by as many left arrows.
 * The spaces blank the leftover characters; the left arrows take them back
 * out of the line, which ends wherever GETLN's index is when Return is
 * pressed.
 * <p>
 * Lines are recorded only if no Paste or Type File... was typing when
 * their prompt appeared, so typing a file in doesn't fill the history.
 * <p>
 * Threading: everything runs on the emulation thread -- the bus accesses
 * as the CPU makes them, {@link #send}, {@link #older} and {@link #newer}
 * posted by the keyboard listener.
 */
final class CommandRecall implements MemoryBus, KeyboardPort {

    /** Where every line starts: GETLN's {@code LDX #$01}. */
    static final int LINE_START = 0xFD6F;
    /** GETLN's {@code JSR RDCHAR} ({@code NXTCHAR}), asking for the line's next key. */
    static final int CALLS_RDCHAR = 0xFD75;
    /** GETLN's {@code DEX} falling into {@code NXTCHAR}. */
    static final int BACKED_UP = 0xFD74;
    /** GETLN's {@code BNE NXTCHAR} after moving on to the next index. */
    static final int MOVED_ON = 0xFD60;
    /** The Monitor's read-a-key-for-GETLN routine. */
    static final int RDCHAR = 0xFD35;
    /** Where {@code RDCHAR} returns to GETLN with a key. */
    static final int KEY_TAKEN = 0xFD78;
    /** Escape mode, entered from {@code RDCHAR} when ESC is read. */
    static final int ESCAPE = 0xFD2F;
    /** GETLN's {@code STA IN,X}, storing each key in the line. */
    static final int STORES_KEY = 0xFD84;

    private static final int LDX_IMMEDIATE = 0xA2;
    private static final int JSR = 0x20;
    private static final int PROMPT = 0x33;
    private static final int INPUT_BUFFER = 0x0200;
    private static final int KEYBOARD_DATA = 0xC000;  // $C000-$C00F
    private static final int KEYBOARD_STROBE = 0xC010; // $C010-$C01F
    private static final int RESET_VECTOR = 0xFFFC;
    private static final int NMI_VECTOR = 0xFFFA;
    private static final int IRQ_VECTOR = 0xFFFE; // IRQ and BRK
    private static final int RTI = 0x40;
    private static final int STACK_PAGE = 0x0100;
    private static final int TEXT_PAGES = 0x0400;     // $0400-$0BFF: text and lo-res pages 1 and 2
    private static final int TEXT_PAGES_END = 0x0C00;
    private static final int VIDEOTERM_WINDOW = 0xCC00; // $CC00-$CDFF: the VideoTerm's display memory
    private static final int VIDEOTERM_WINDOW_END = 0xCE00;
    private static final int LEFT_ARROW = 0x08;
    private static final int SPACE = 0x20;
    private static final int RETURN = 0x0D;
    /**
     * How long Shift stays down after a capital's strobe clears, in bus
     * accesses (about one per cycle): some 50 ms, as a finger lifts off
     * Shift after the key, so software that reads the shift-key mod after
     * taking the key still sees it held.
     */
    static final int SHIFT_RELEASE_ACCESSES = 50_000;

    private final MemoryBus board;
    private final KeyboardRegister keyboard;
    private final TypingFeeder feeder;
    private final CommandHistory history;
    private final ShiftKeyMod shiftKeyMod;
    private boolean shiftedKeyWaiting; // the key waiting was sent with Shift held for it
    private int shiftRelease = -1;     // accesses until that Shift lets go, once the key is taken

    // What has been seen on the pins.
    private final int[] buffer = new int[0x100]; // the CPU's writes to $0200-$02FF
    private int promptByte;     // the CPU's last read or write of $0033
    private int keyboardByte;   // the CPU's last read of $C000
    private int screenWrites;   // the CPU's writes to screen memory, counted
    private boolean keyWaiting; // a key sent whose strobe the CPU hasn't cleared
    private int instruction;    // the address of the current instruction's opcode fetch
    private int previous;       // the address of the one before it, interrupts aside
    private int stackWrites;    // the CPU's writes to the stack since that fetch
    private boolean returning;  // the instruction fetched was RTI
    private final int[] interrupted = new int[8]; // the instruction before each interrupt in progress
    private int interrupts;

    // The line GETLN is collecting.
    private boolean active;
    private char prompt;
    private boolean startedWhileIdle;
    private boolean placed; // whether the line's starting index is known yet
    private int start;      // the index the line started at
    private int index;      // GETLN's X

    // Whether GETLN is waiting for the line's next key, and the line's length then.
    private boolean inRdchar;
    private boolean ready;
    private int readyLength;
    /** How far the line has reached on screen: characters past the index may still show. */
    private int extent;

    // Stepping through history: position in the prompt's entries, or -1 when not stepping.
    private int position = -1;
    private String draft;
    // A recall still being typed, and the line's length once it's done.
    private boolean recallInFlight;
    private int projectedLength;

    /**
     * @param board    the motherboard: the socket the CPU would otherwise plug into
     * @param keyboard the motherboard side of the keyboard connector, where keys sent through this device arrive
     * @param history  the history recorded into and recalled from
     * @param shiftKeyMod the shift-key mod, whose Shift a capital is sent with
     */
    CommandRecall(MemoryBus board, KeyboardRegister keyboard, CommandHistory history, ShiftKeyMod shiftKeyMod) {
        this.board = board;
        this.keyboard = keyboard;
        this.history = history;
        this.shiftKeyMod = shiftKeyMod;
        this.feeder = new TypingFeeder(this);
        feeder.setScreenSignature(this::screenWrites);
    }

    /** @return the feeder that types Paste, Type File... and recalled lines through this device */
    TypingFeeder typing() {
        return feeder;
    }

    // ---- the keyboard cable ----

    @Override
    public void send(int key) {
        releaseShift();
        keyboard.keyPressed(key);
        keyWaiting = true;
    }

    /**
     * Presses a key with Shift held for it, as a person types a capital:
     * Shift goes down first, and lets go {@link #SHIFT_RELEASE_ACCESSES}
     * after the CPU takes the key -- or as soon as another key is sent. The
     * host's own Shift key, if it's held, holds the shift-key mod's wire
     * regardless.
     *
     * @param key the key's Apple II ASCII value
     */
    @Override
    public void sendShifted(int key) {
        send(key);
        shiftKeyMod.setShiftTyped(true);
        shiftedKeyWaiting = true;
    }

    private void releaseShift() {
        if (shiftedKeyWaiting || shiftRelease >= 0) {
            shiftKeyMod.setShiftTyped(false);
            shiftedKeyWaiting = false;
            shiftRelease = -1;
        }
    }

    /** Counts down to letting go of a capital's Shift, once its key has been taken. */
    private void countShiftRelease() {
        if (shiftRelease > 0 && --shiftRelease == 0) {
            releaseShift();
        }
    }

    @Override
    public boolean keyWaiting() {
        return keyWaiting;
    }

    /** @return how many writes the CPU has made to screen memory -- it changes whenever the screen does */
    int screenWrites() {
        return screenWrites;
    }

    // ---- the CPU's pins ----

    @Override
    public int fetchOpcode(int address) {
        countShiftRelease();
        int opcode = board.fetchOpcode(address);
        previous = (returning && interrupts > 0) ? interrupted[--interrupts] : instruction;
        returning = opcode == RTI;
        instruction = address;
        stackWrites = 0;
        switch (address) {
            case LINE_START -> {
                if (opcode == LDX_IMMEDIATE) {
                    lineStarted(true);
                } else {
                    active = false; // not GETLN
                    ready = false;
                }
            }
            case CALLS_RDCHAR -> {
                if (opcode != JSR) {
                    active = false; // not GETLN
                    ready = false;
                } else {
                    if (previous != BACKED_UP && previous != MOVED_ON) {
                        lineStarted(false); // entered from outside, as Integer BASIC does
                    }
                    inRdchar = active;
                }
            }
            case RDCHAR -> {
                if (inRdchar && opcode == JSR) {
                    ready = true;
                    readyLength = length();
                    if (!typingRecall()) {
                        recallInFlight = false;
                        // a recall's own spaces reach past its line only to blank what was there
                        extent = Math.max(extent, readyLength);
                    }
                }
            }
            case ESCAPE -> ready = false;
            case KEY_TAKEN -> {
                ready = false;
                inRdchar = false;
            }
            default -> { }
        }
        return opcode;
    }

    @Override
    public int read(int address) {
        countShiftRelease();
        int value = board.read(address);
        if (address == PROMPT) {
            promptByte = value;
        } else if ((address & 0xFFF0) == KEYBOARD_DATA) {
            keyboardByte = value;
        } else if ((address & 0xFFF0) == KEYBOARD_STROBE) {
            strobeCleared();
        } else if (address == RESET_VECTOR) {
            active = false; // a RESET never returns to the line it interrupted
            ready = false;
            inRdchar = false;
            interrupts = 0;
        } else if ((address == IRQ_VECTOR || address == NMI_VECTOR) && stackWrites >= 3) {
            interruptTaken(); // PC and status pushed, now the vector: not a data read
        }
        return value;
    }

    @Override
    public void write(int address, int value) {
        countShiftRelease();
        board.write(address, value);
        if ((address & 0xFF00) == STACK_PAGE) {
            stackWrites++;
        }
        if ((address >= TEXT_PAGES && address < TEXT_PAGES_END)
                || (address >= VIDEOTERM_WINDOW && address < VIDEOTERM_WINDOW_END)) {
            screenWrites++;
        }
        if (address == PROMPT) {
            promptByte = value & 0xFF;
        } else if ((address & 0xFF00) == INPUT_BUFFER) {
            int at = address & 0xFF;
            buffer[at] = value & 0xFF;
            if (active && instruction == STORES_KEY) {
                keyStored(at, value & 0xFF);
            }
        } else if ((address & 0xFFF0) == KEYBOARD_STROBE) {
            strobeCleared();
        }
    }

    /** Remembers the instruction an interrupt came after, for the fetch after its RTI. */
    private void interruptTaken() {
        if (interrupts == interrupted.length) {
            // a handler that never returns (a BRK into the Monitor, say): forget the oldest
            System.arraycopy(interrupted, 1, interrupted, 0, interrupted.length - 1);
            interrupts--;
        }
        interrupted[interrupts++] = instruction;
    }

    // ---- following GETLN ----

    /** @param atZero whether the line starts at index 0 (GETLN's own start) rather than at an index learned later */
    private void lineStarted(boolean atZero) {
        active = true;
        prompt = (char) (promptByte & 0x7F);
        startedWhileIdle = !feeder.isTyping();
        placed = atZero;
        start = 0;
        index = 0;
        inRdchar = false;
        ready = false;
        extent = 0;
        position = -1;
        draft = null;
        recallInFlight = false;
    }

    /** GETLN stored {@code key} at index {@code at}: where its index goes next. */
    private void keyStored(int at, int key) {
        if (!placed) {
            start = at; // the first key stored is at the index the line started at
            placed = true;
        }
        // Ctrl-X, a left arrow at index 0, and a key past index $FF restart
        // the line; the fetch of LINE_START that follows resets the index.
        switch (key) {
            case 0x88 -> index = Math.max(0, at - 1); // left arrow
            case 0x8D -> { }                           // Return: the line is complete
            default -> index = (at + 1) & 0xFF;
        }
    }

    /** The line's length: keys in it since it started. */
    private int length() {
        return placed ? Math.max(0, index - start) : 0;
    }

    private void strobeCleared() {
        keyWaiting = false;
        if (shiftedKeyWaiting) {
            shiftedKeyWaiting = false;
            shiftRelease = SHIFT_RELEASE_ACCESSES;
        }
        if (active && ready && keyboardByte == (0x80 | RETURN)) {
            active = false;
            ready = false;
            inRdchar = false;
            if (startedWhileIdle) {
                history.add(prompt, line(readyLength));
            }
        }
    }

    // ---- recall ----

    /** Recalls the line before the one shown, if there is one. */
    void older() {
        recall(-1);
    }

    /** Recalls the line after the one shown -- past the newest, what was being typed before stepping began. */
    void newer() {
        recall(1);
    }

    private void recall(int direction) {
        if (!active || !CommandHistory.isCommandPrompt(prompt)) {
            return;
        }
        int typed;
        int shown;
        if (typingRecall()) {
            typed = projectedLength; // build on the recall still being typed
            shown = projectedLength;
        } else {
            recallInFlight = false;
            if (feeder.isTyping()) {
                return; // Paste or Type File... is typing; its keys go first and stay unaffected
            }
            if (!ready || keyWaiting) {
                return; // GETLN isn't waiting for the line's next key
            }
            typed = readyLength;
            shown = Math.max(extent, readyLength);
        }

        List<String> entries = history.entries(prompt);
        if (position < 0) {
            if (direction > 0) {
                return; // nothing is newer than the line being typed
            }
            draft = line(typed);
            position = entries.size();
        }
        int target = position + direction;
        if (target < 0 || target > entries.size()) {
            return;
        }
        String text = (target == entries.size()) ? draft : entries.get(target);
        position = target;
        feeder.enqueueKeys(replacement(typed, shown, text));
        recallInFlight = true;
        projectedLength = text.length();
        extent = text.length();
    }

    /**
     * The keys that turn a line of {@code index} characters, reaching
     * {@code shown} characters on screen, into {@code text}.
     *
     * @param index the line's length: keys in it since it started
     * @param shown how many characters of it may show on screen, at least {@code index}
     * @param text  the line wanted
     * @return the keys to type
     */
    static int[] replacement(int index, int shown, String text) {
        int overhang = Math.max(0, shown - text.length());
        int[] keys = new int[index + text.length() + 2 * overhang];
        int k = 0;
        for (int i = 0; i < index; i++) {
            keys[k++] = LEFT_ARROW;
        }
        for (int i = 0; i < text.length(); i++) {
            keys[k++] = text.charAt(i);
        }
        for (int i = 0; i < overhang; i++) {
            keys[k++] = SPACE;
        }
        for (int i = 0; i < overhang; i++) {
            keys[k++] = LEFT_ARROW;
        }
        return keys;
    }

    /** The line's first {@code length} characters, from the copy of the buffer. */
    private String line(int length) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int c = buffer[(start + i) & 0xFF] & 0x7F;
            if (c == RETURN) {
                break;
            }
            line.append((char) c);
        }
        return line.toString();
    }

    /**
     * Whether a recall's keys are still going in: queued, or the last one
     * loaded but not yet taken.
     */
    private boolean typingRecall() {
        return recallInFlight && (feeder.isTyping() || keyWaiting);
    }
}
