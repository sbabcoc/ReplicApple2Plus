package com.nordstrom.emulator;

import com.nordstrom.emulator.RequiresRoms.Rom;
import com.nordstrom.emulator.cpu.Cpu6502;
import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.system.SlotCard;
import com.nordstrom.emulator.system.SystemClock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Command history on a real machine: the Apple II+ ROM's own GETLN,
 * Applesoft and the Monitor reading the lines, with recalled lines typed
 * through the {@link TypingFeeder} exactly as the application does.
 */
@RequiresRoms(Rom.SYSTEM)
class CommandRecallTest {

    private static final double KEY_SETTLE = 0.3; // seconds: plenty for GETLN to take a few dozen keys

    private final MotherboardBus bus = MotherboardBus.withoutAudio(new SlotCard[8]);
    private final Cpu6502 cpu = new Cpu6502(bus, 0xFFFC);
    private final SystemClock clock = new SystemClock(cpu);
    private final TypingFeeder typing = new TypingFeeder(bus.keyboardRegister());
    private final CommandHistory history = new CommandHistory();
    private final CommandRecall recall = new CommandRecall(cpu, bus, bus.keyboardRegister(), typing, history);
    private double seconds;

    CommandRecallTest() {
        clock.addCycleListener(bus.videoScanner()::tick);
        clock.addCycleListener(bus.scanlineModes()::tick);
        typing.setScreenSignature(() -> {
            int hash = 1;
            for (int address = 0x400; address < 0x800; address++) {
                hash = 31 * hash + bus.read(address);
            }
            return hash;
        });
        clock.addCycleListener(typing::tick);
        clock.addCycleListener(recall::tick);
    }

    @Test
    void upRecallsEnteredLinesNewestFirstAndTheRecalledLineRuns() {
        boot();
        enter("PRINT 1+1");
        enter("PRINT 3*3");
        assertEquals(List.of("PRINT 1+1", "PRINT 3*3"), history.entries(']'));

        up();
        assertEquals("]PRINT 3*3", cursorRow());
        up();
        assertEquals("]PRINT 1+1", cursorRow());
        up(); // nothing older: no change
        assertEquals("]PRINT 1+1", cursorRow());

        enter("");
        assertEquals("2", outputAbovePrompt(), "the recalled line ran:\n" + screen());
        assertEquals(List.of("PRINT 1+1", "PRINT 3*3", "PRINT 1+1"), history.entries(']'));
    }

    /**
     * DOS 3.3 acts on a command when GETLN echoes its Return through the
     * output hook, then leaves with its own saved stack pointer, so GETLN
     * never returns to its caller. This hook does the same -- on a Return,
     * reset the stack and re-enter Applesoft at {@code RESTART} -- without
     * needing a DOS disk.
     */
    @Test
    void aLineIsRecordedWhenTheEchoOfItsReturnNeverComesBack() {
        boot();
        int[] hook = {
            0xC9, 0x8D,       // $0300 CMP #$8D
            0xD0, 0x06,       //       BNE $030A
            0xA2, 0xF8,       //       LDX #$F8
            0x9A,             //       TXS
            0x4C, 0x3C, 0xD4, //       JMP RESTART ($D43C)
            0x4C, 0xF0, 0xFD, // $030A JMP COUT1
        };
        for (int i = 0; i < hook.length; i++) {
            bus.write(0x300 + i, hook[i]);
        }
        bus.write(0x36, 0x00); // CSW -> $0300
        bus.write(0x37, 0x03);

        enter("CATALOG");
        assertEquals(List.of("CATALOG"), history.entries(']'));
        up();
        assertEquals("]CATALOG", cursorRow());
    }

    /**
     * ProDOS BASIC.SYSTEM's input hook takes the Return itself, runs the
     * command, and hands GETLN a left arrow at index 0, so GETLN never
     * stores the Return -- it restarts the line. This hook does the same,
     * without needing a ProDOS disk.
     */
    @Test
    void aLineIsRecordedWhenAnInputHookTakesItsReturn() {
        boot();
        int[] hook = {
            0x20, 0x1B, 0xFD, // $0310 JSR KEYIN
            0xC9, 0x8D,       //       CMP #$8D
            0xD0, 0x04,       //       BNE $031B
            0xA2, 0x00,       //       LDX #$00
            0xA9, 0x88,       //       LDA #$88
            0x60,             // $031B RTS
        };
        for (int i = 0; i < hook.length; i++) {
            bus.write(0x310 + i, hook[i]);
        }
        bus.write(0x38, 0x10); // KSW -> $0310
        bus.write(0x39, 0x03);

        enter("PREFIX");
        assertEquals(List.of("PREFIX"), history.entries(']'));
        up();
        assertEquals("]PREFIX", cursorRow());
    }

    @Test
    void aShorterRecallBlanksTheRestOfTheLongerLineItReplaces() {
        boot();
        enter("PRINT 7");
        type("PRINT 123456789");
        up();
        assertEquals("]PRINT 7", cursorRow(), "no leftovers of the longer line on screen");
        enter("");
        assertEquals("7", outputAbovePrompt(), "only the recalled line was entered:\n" + screen());
    }

    @Test
    void recallWorksAcrossWrappedScreenRows() {
        boot();
        String longLine = "PRINT \"" + "A".repeat(40) + "\"";
        enter(longLine);
        enter("PRINT 8");
        up();
        up();
        assertEquals(("]" + longLine).substring(0, 40), cursorRowAbove(1), "wrapped onto the next row");
        down(); // the short line again: the second row is blanked
        assertEquals("]PRINT 8", cursorRow());
        assertEquals("", row(bus.read(0x25) + 1).strip(), "nothing left on the row the long line wrapped onto");
        enter("");
        assertEquals("8", outputAbovePrompt());
    }

    @Test
    void downPastTheNewestBringsBackWhatWasBeingTyped() {
        boot();
        enter("PRINT 5");
        type("PRINT 4");
        up();
        assertEquals("]PRINT 5", cursorRow());
        down();
        assertEquals("]PRINT 4", cursorRow());
        down(); // nothing newer than the draft
        assertEquals("]PRINT 4", cursorRow());
    }

    @Test
    void stepsPressedFasterThanTheyAreTypedAllLand() {
        boot();
        enter("PRINT 10");
        enter("PRINT 200");
        enter("PRINT 3000");
        recall.older();
        recall.older(); // before the first has been typed
        recall.older();
        run(KEY_SETTLE);
        assertEquals("]PRINT 10", cursorRow());
    }

    @Test
    void eachPromptRecallsOnlyItsOwnLines() {
        boot();
        enter("PRINT 9");
        enter("CALL -151");
        up(); // the Monitor's '*' has no history yet
        assertEquals("*", cursorRow());
        enter("300.301");
        up();
        assertEquals("*300.301", cursorRow());
        assertEquals(List.of("300.301"), history.entries('*'));
        assertEquals(List.of("PRINT 9", "CALL -151"), history.entries(']'));
    }

    @Test
    void nothingIsTypedInEscapeMode() {
        boot();
        enter("PRINT 1");
        typing.enqueueKeys(0x1B); // ESC: keys now move the cursor
        run(KEY_SETTLE);
        up();
        assertEquals("]", cursorRow(), "nothing recalled while in escape mode");
    }

    @Test
    void linesTypedByPasteAfterTheFirstAreNotRecorded() {
        boot();
        typing.enqueue("10 PRINT 1\n20 PRINT 2\n30 PRINT 3\n");
        run(3);
        assertEquals(List.of("10 PRINT 1"), history.entries(']'),
            "the first line's prompt appeared before typing began; the others' during it");
    }

    @Test
    void replacementBacksUpTheLineThenBlanksWhatOverhangs() {
        assertArrayEquals(new int[] {0x08, 0x08, 0x08, 'A', 'B', ' ', ' ', 0x08, 0x08},
            CommandRecall.replacement(3, 4, "AB"));
        assertArrayEquals(new int[] {'A', 'B', 'C'}, CommandRecall.replacement(0, 0, "ABC"));
    }

    // ---- driving the machine ----

    private void boot() {
        run(3);
        assertTrue(cursorRow().startsWith("]"), "booted to Applesoft:\n" + screen());
    }

    private void up() {
        recall.older();
        run(KEY_SETTLE);
    }

    private void down() {
        recall.newer();
        run(KEY_SETTLE);
    }

    private void type(String text) {
        typing.enqueue(text);
        run(KEY_SETTLE);
    }

    private void enter(String line) {
        type(line + "\n");
        run(KEY_SETTLE);
    }

    private void run(double s) {
        double until = seconds + s;
        while (seconds < until) {
            seconds += clock.step() / 1_023_000.0;
        }
    }

    /** The row {@code n} above the cursor's, trailing spaces trimmed. */
    private String cursorRowAbove(int n) {
        return row(bus.read(0x25) - n).stripTrailing();
    }

    /** The cursor's row, trailing spaces trimmed. */
    private String cursorRow() {
        return cursorRowText().stripTrailing();
    }

    private String cursorRowText() {
        return row(bus.read(0x25)); // CV
    }

    private String row(int row) {
        int base = 0x400 + 0x80 * (row % 8) + 0x28 * (row / 8);
        StringBuilder sb = new StringBuilder();
        for (int col = 0; col < 40; col++) {
            int g = bus.read(base + col) & 0x3F;
            sb.append((char) (g < 0x20 ? g + 0x40 : g));
        }
        return sb.toString();
    }

    /** The nearest non-blank row above the prompt: the last line a command printed. */
    private String outputAbovePrompt() {
        for (int r = bus.read(0x25) - 1; r >= 0; r--) {
            String text = row(r).strip();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    private String screen() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 24; r++) {
            sb.append(row(r).stripTrailing()).append('\n');
        }
        return sb.toString();
    }
}
