package com.nordstrom.emulator;

import com.nordstrom.emulator.cpu.Cpu6502;
import com.nordstrom.emulator.system.KeyboardRegister;

import java.util.List;

/**
 * Command history at the Apple's own prompts: the host's Up and Down
 * arrows step through lines entered before at the same prompt, which then
 * edit and enter like anything typed.
 * <p>
 * Everything goes through the Monitor's line-input routine, GETLN, which
 * Applesoft, Integer BASIC and the Monitor read their command lines with.
 * This class only watches it -- the program counter, X, the stack and
 * memory, read after each instruction as a cycle listener -- and acts on
 * it only by typing keys, through the same {@link TypingFeeder} as Paste. It never writes the Apple's memory, so
 * GETLN keeps its own buffer, cursor and screen exactly as if a person had
 * typed the keys, whatever input or output hooks are in place.
 * <p>
 * The addresses below are the Autostart Monitor's; the original Monitor's
 * source has the same layout (HARDWARE-REFERENCE.md section 4). Before
 * following a line, the routine's code is checked at those addresses: with
 * other code mapped at {@code $F800}, nothing is followed and the arrows do
 * nothing.
 * <ul>
 *   <li><b>A line starts</b> each time {@code $FD6F} ({@code LDX #$01})
 *       runs -- every entry to GETLN passes it, and so does a line
 *       restarted by Ctrl-X or by backspacing past its start. The prompt
 *       that was just printed is in {@code PROMPT} ({@code $33}).</li>
 *   <li><b>GETLN waits for a key</b> when {@code RDCHAR} ({@code $FD35})
 *       runs with GETLN's return address ({@code $FD77}) on top of the
 *       stack. X then holds the line's length so far -- GETLN keeps its
 *       buffer index there -- and the next key read goes to the line.
 *       That stops being true when the key is taken: {@code RDCHAR} either
 *       returns to {@code $FD78}, or reads ESC and enters escape mode at
 *       {@code $FD2F}, where keys move the cursor instead. Escape mode
 *       ends by falling into {@code $FD35} again.</li>
 *   <li><b>A line is entered</b> when a Return is taken from the keyboard
 *       (the strobe clears with {@code $0D} in the latch) while GETLN is
 *       waiting for the line's next key. The line is then the input
 *       buffer's ({@code $0200}) first X characters. This is the only
 *       point every command interpreter shares: after it, GETLN may never
 *       store the Return or return to its caller. DOS 3.3 acts on its
 *       commands when GETLN echoes the Return through its output hook, and
 *       leaves with its own saved stack pointer; BASIC.SYSTEM's input hook
 *       takes the Return itself, runs the command, and hands GETLN a left
 *       arrow at index 0, which restarts the line.</li>
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
 * Threading: everything runs on the emulation thread -- {@link #tick} as a
 * cycle listener, {@link #older} and {@link #newer} posted by the keyboard
 * listener.
 */
final class CommandRecall {

    /** Where every line starts: GETLN's {@code LDX #$01}. */
    static final int LINE_START = 0xFD6F;
    /** The Monitor's read-a-key-for-GETLN routine. */
    static final int RDCHAR = 0xFD35;
    /** What GETLN's {@code JSR RDCHAR} at $FD75 pushes: the address of its last byte. */
    static final int RDCHAR_CALLED_FROM = 0xFD77;
    /** Where {@code RDCHAR} returns to GETLN with a key. */
    static final int KEY_TAKEN = 0xFD78;
    /** Escape mode, entered from {@code RDCHAR} when ESC is read. */
    static final int ESCAPE = 0xFD2F;

    private static final int PROMPT = 0x33;
    private static final int INPUT_BUFFER = 0x0200;
    private static final int STACK = 0x0100;
    private static final int LEFT_ARROW = 0x08;
    private static final int SPACE = 0x20;
    private static final int RETURN = 0x0D;

    /**
     * The Monitor's own bytes, as {address, value} pairs, that the
     * addresses above depend on: {@code LDX #$01} at the line start,
     * {@code JSR RDCHAR} at $FD75, {@code JSR RDKEY} at {@code RDCHAR}, and
     * {@code BEQ ESC} after it.
     */
    private static final int[][] SIGNATURE = {
        {0xFD6F, 0xA2}, {0xFD70, 0x01},
        {0xFD75, 0x20}, {0xFD76, 0x35}, {0xFD77, 0xFD},
        {0xFD35, 0x20}, {0xFD36, 0x0C}, {0xFD37, 0xFD},
        {0xFD3A, 0xF0}, {0xFD3B, 0xF3},
    };

    private final Cpu6502 cpu;
    private final MemoryBus bus;
    private final KeyboardRegister keyboard;
    private final TypingFeeder feeder;
    private final CommandHistory history;

    // The line GETLN is collecting.
    private boolean active;
    private char prompt;
    private int resetsAtStart;
    private boolean startedWhileIdle;

    // Whether GETLN is waiting for the next key of the line, and the line's length then.
    private boolean ready;
    /** Whether the keyboard strobe was set after the previous instruction. */
    private boolean strobeWasSet;
    private int readyIndex;
    /** How far the line has reached on screen: characters past the index may still show. */
    private int extent;

    // Stepping through history: position in the prompt's entries, or -1 when not stepping.
    private int position = -1;
    private String draft;
    // A recall still being typed, and the line's length once it's done.
    private boolean recallInFlight;
    private int projectedIndex;

    /**
     * @param cpu      the CPU whose registers are watched
     * @param bus      the memory read for the prompt, the stack and the line
     * @param keyboard the keyboard, checked for a key not yet taken
     * @param feeder   types recalled lines
     * @param history  the history recorded into and recalled from
     */
    CommandRecall(Cpu6502 cpu, MemoryBus bus, KeyboardRegister keyboard, TypingFeeder feeder,
                  CommandHistory history) {
        this.cpu = cpu;
        this.bus = bus;
        this.keyboard = keyboard;
        this.feeder = feeder;
        this.history = history;
    }

    /**
     * Cycle listener: follows GETLN after each instruction.
     *
     * @param cycles CPU cycles elapsed (unused -- only where execution is matters)
     */
    void tick(int cycles) {
        boolean strobeSet = keyboard.isStrobeSet();
        boolean keyTaken = strobeWasSet && !strobeSet;
        strobeWasSet = strobeSet;
        int pc = cpu.pc();
        if (pc == LINE_START) {
            lineStarted();
            return;
        }
        if (!active) {
            return;
        }
        if (cpu.resetCount() != resetsAtStart) {
            active = false; // a RESET never returns to the line it interrupted
            ready = false;
            return;
        }
        if (keyTaken && ready && keyboard.lastKey() == RETURN) {
            lineEntered();
            return;
        }
        if (pc == RDCHAR) {
            if (topOfStack() == RDCHAR_CALLED_FROM) {
                ready = true;
                readyIndex = cpu.x();
                if (!typingRecall()) {
                    recallInFlight = false;
                    // a recall's own spaces reach past its line only to blank what was there
                    extent = Math.max(extent, readyIndex);
                }
            }
        } else if (pc == KEY_TAKEN || pc == ESCAPE) {
            ready = false;
        }
    }

    /** Recalls the line before the one shown, if there is one. */
    void older() {
        recall(-1);
    }

    /** Recalls the line after the one shown -- past the newest, what was being typed before stepping began. */
    void newer() {
        recall(1);
    }

    private void lineStarted() {
        active = false;
        ready = false;
        if (!romMatches()) {
            return;
        }
        active = true;
        resetsAtStart = cpu.resetCount();
        prompt = (char) (bus.read(PROMPT) & 0x7F);
        startedWhileIdle = !feeder.isTyping();
        extent = 0;
        position = -1;
        draft = null;
        recallInFlight = false;
    }

    private void lineEntered() {
        active = false;
        ready = false;
        if (startedWhileIdle) {
            history.add(prompt, readLine(readyIndex));
        }
    }

    private void recall(int direction) {
        if (!active || cpu.resetCount() != resetsAtStart || !CommandHistory.isCommandPrompt(prompt)) {
            return;
        }
        int index;
        int shown;
        if (typingRecall()) {
            index = projectedIndex; // build on the recall still being typed
            shown = projectedIndex;
        } else {
            recallInFlight = false;
            if (feeder.isTyping()) {
                return; // Paste or Type File... is typing; its keys go first and stay unaffected
            }
            if (!ready || keyboard.isStrobeSet()) {
                return; // GETLN isn't waiting for the line's next key
            }
            index = readyIndex;
            shown = Math.max(extent, readyIndex);
        }

        List<String> entries = history.entries(prompt);
        if (position < 0) {
            if (direction > 0) {
                return; // nothing is newer than the line being typed
            }
            draft = readLine(index);
            position = entries.size();
        }
        int target = position + direction;
        if (target < 0 || target > entries.size()) {
            return;
        }
        String text = (target == entries.size()) ? draft : entries.get(target);
        position = target;
        feeder.enqueueKeys(replacement(index, shown, text));
        recallInFlight = true;
        projectedIndex = text.length();
        extent = text.length();
    }

    /**
     * The keys that turn a line of {@code index} characters, reaching
     * {@code shown} characters on screen, into {@code text}.
     *
     * @param index the line's length, GETLN's index
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

    /** The input buffer's first {@code limit} characters, or up to its carriage return, whichever comes first. */
    private String readLine(int limit) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            int c = bus.read(INPUT_BUFFER + i) & 0x7F;
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
        return recallInFlight && (feeder.isTyping() || keyboard.isStrobeSet());
    }

    private int topOfStack() {
        int sp = cpu.sp();
        return bus.read(STACK | ((sp + 1) & 0xFF)) | bus.read(STACK | ((sp + 2) & 0xFF)) << 8;
    }

    private boolean romMatches() {
        for (int[] expected : SIGNATURE) {
            if (bus.read(expected[0]) != expected[1]) {
                return false;
            }
        }
        return true;
    }
}
