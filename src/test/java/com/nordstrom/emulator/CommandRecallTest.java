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
 * Applesoft and the Monitor reading the lines, with the device between the
 * CPU and the motherboard and recalled lines typed through the
 * {@link TypingFeeder}, wired exactly as the application does.
 */
@RequiresRoms(Rom.SYSTEM)
class CommandRecallTest {

    private static final double KEY_SETTLE = 0.3; // seconds: plenty for GETLN to take a few dozen keys

    private final MotherboardBus bus = MotherboardBus.withoutAudio(new SlotCard[8]);
    private final CommandHistory history = new CommandHistory();
    private final CommandRecall recall = new CommandRecall(bus, bus.keyboardRegister(), history);
    private final TypingFeeder typing = recall.typing();
    private final FetchProbe probe = new FetchProbe(recall);
    private final Cpu6502 cpu = new Cpu6502(probe, 0xFFFC);
    private final SystemClock clock = new SystemClock(cpu);
    private double seconds;
    /** An address whose fetch is followed by an NMI, or -1: see {@link #nmiAfterFetchOf}. */
    private int nmiAfter = -1;

    /**
     * Passes every access between the CPU and the device through unchanged,
     * noting the last opcode fetched -- only so a test can time an interrupt.
     */
    private static final class FetchProbe implements MemoryBus {
        private final MemoryBus device;
        private int lastFetch = -1;

        FetchProbe(MemoryBus device) {
            this.device = device;
        }

        @Override public int read(int address) {
            return device.read(address);
        }

        @Override public void write(int address, int value) {
            device.write(address, value);
        }

        @Override public int fetchOpcode(int address) {
            lastFetch = address;
            return device.fetchOpcode(address);
        }
    }

    CommandRecallTest() {
        RomBoot.coldStart(bus);
        clock.addCycleListener(bus.videoScanner()::tick);
        clock.addCycleListener(bus.scanlineModes()::tick);
        clock.addCycleListener(typing::tick);
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

    /**
     * Integer BASIC prints its own prompt and enters GETLN's key loop
     * directly ({@code JSR NXTCHAR}, {@code $FD75}), never passing
     * {@code $FD6F}: with X = 0 at its prompt, and with X = 2 in
     * {@code AUTO} mode, where the line number comes first in the buffer.
     * This loop does the same, from the ROM alone.
     */
    @Test
    void aLineEnteredAtNxtcharIsRecordedAndRecalled() {
        integerStyleInput(0);
        enter("HELLO");
        assertEquals(List.of("HELLO"), history.entries('>'));
        up();
        assertEquals(">HELLO", cursorRow());
    }

    @Test
    void aLineEnteredPartWayIntoTheBufferIsRecordedWithoutWhatPrecedesIt() {
        integerStyleInput(2);
        enter("LIST");
        assertEquals(List.of("LIST"), history.entries('>'), "not the two characters already in the buffer");
        up();
        assertEquals(">LIST", cursorRow());
        enter("");
        StringBuilder entered = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            entered.append((char) (bus.read(0x202 + i) & 0x7F));
        }
        assertEquals("LIST", entered.toString(), "the recalled line went in after them, where typing goes");
    }

    /** Boots, then runs a prompt loop that enters GETLN at NXTCHAR with X = {@code index}. */
    private void integerStyleInput(int index) {
        boot();
        int[] loop = {
            0xA9, 0xBE,       // $0300 LDA #'>'
            0x85, 0x33,       //       STA PROMPT
            0x20, 0xED, 0xFD, //       JSR COUT
            0xA9, 0xD8,       //       LDA #'X'
            0x8D, 0x00, 0x02, //       STA $0200 -- what comes before the line
            0x8D, 0x01, 0x02, //       STA $0201
            0xA2, index,      //       LDX #index
            0x20, 0x75, 0xFD, //       JSR NXTCHAR
            0x4C, 0x00, 0x03, //       JMP $0300
        };
        for (int i = 0; i < loop.length; i++) {
            bus.write(0x300 + i, loop[i]);
        }
        enter("CALL 768");
        assertEquals(">", cursorRow());
    }

    /**
     * An interrupt taken between GETLN's {@code DEX} at {@code $FD74} and its
     * {@code JSR RDCHAR} at {@code $FD75}: the fetch of {@code $FD75} after the
     * RTI must still be seen as GETLN carrying on with the line, not a new
     * line starting.
     */
    @Test
    void aLineSurvivesAnInterruptInsideGetlnsKeyLoop() {
        boot();
        bus.write(0x03FB, 0x40); // the NMI vector's target: RTI
        type("PRINT 12");
        nmiAfterFetchOf(0xFD74);
        typing.enqueueKeys(0x08); // left arrow: BCKSPC's DEX at $FD74, then $FD75
        run(KEY_SETTLE);
        assertEquals(-1, nmiAfter, "the NMI was taken");
        enter("");
        assertEquals(List.of("PRINT 1"), history.entries(']'));
        up();
        assertEquals("]PRINT 1", cursorRow());
    }

    /**
     * An interrupt taken between Integer BASIC's {@code JSR NXTCHAR} and the
     * fetch of {@code $FD75}: after the RTI, that fetch still starts a line --
     * what came before the interrupt decides, not the RTI.
     */
    @Test
    void aLineEnteredAtNxtcharStartsEvenWhenAnInterruptPrecedesIt() {
        integerStyleInput(0);
        bus.write(0x03FB, 0x40); // the NMI vector's target: RTI
        nmiAfterFetchOf(0x0311); // the loop's JSR NXTCHAR, for the line after FIRST
        enter("FIRST");
        assertEquals(-1, nmiAfter, "the NMI was taken");
        enter("SECOND");
        assertEquals(List.of("FIRST", "SECOND"), history.entries('>'));
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
    void recallBacksUpOnlyAsFarAsTheLineAfterLeftArrows() {
        boot();
        enter("PRINT 5");
        type("PRINT 123");
        typing.enqueueKeys(0x08, 0x08); // the line is now "PRINT 1", with "23" still on screen
        run(KEY_SETTLE);
        up();
        assertEquals("]PRINT 5", cursorRow(), "the line replaced in place, leftovers blanked");
        assertEquals("5", outputAbovePrompt(), "the line was not restarted by backing up too far");
        enter("");
        assertEquals("5", outputAbovePrompt());
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
    void pasteTypesAProgramThroughTheDeviceWithoutLosingAKey() {
        boot();
        typing.enqueue("10 A = 5\n20 B = 7\n30 PRINT A * B\nRUN\n");
        run(3);
        assertTrue(!typing.isTyping(), "everything typed");
        assertEquals("35", outputAbovePrompt(), "every line arrived intact:\n" + screen());
    }

    @Test
    void aKeySentIsWaitingUntilTheCpuClearsTheStrobe() {
        recall.send('A');
        assertTrue(recall.keyWaiting());
        assertTrue(bus.keyboardRegister().isStrobeSet(), "the key is in the latch");
        assertEquals(0xC1, recall.read(0xC000), "reading the key doesn't take it");
        assertTrue(recall.keyWaiting());
        recall.read(0xC010);
        assertTrue(!recall.keyWaiting(), "touching $C010 takes it");
        assertTrue(!bus.keyboardRegister().isStrobeSet());

        recall.send('B');
        recall.write(0xC01F, 0); // any access to $C010-$C01F, read or write
        assertTrue(!recall.keyWaiting());
        assertTrue(!bus.keyboardRegister().isStrobeSet());
    }

    @Test
    void onlyWritesToScreenMemoryAreCounted() {
        int before = recall.screenWrites();
        for (int address : new int[] {0x03FF, 0x0C00, 0xCBFF, 0xCE00, 0x2000}) {
            recall.write(address, 0);
        }
        assertEquals(before, recall.screenWrites(), "not screen memory");
        for (int address : new int[] {0x0400, 0x07FF, 0x0800, 0x0BFF, 0xCC00, 0xCDFF}) {
            recall.write(address, 0);
        }
        assertEquals(before + 6, recall.screenWrites(), "both text pages and the VideoTerm's window");
        recall.read(0x0400);
        assertEquals(before + 6, recall.screenWrites(), "reads don't change the screen");
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
            seconds += RomBoot.notHalted(clock.step()) / 1_023_000.0;
            if (nmiAfter >= 0 && probe.lastFetch == nmiAfter) {
                cpu.raiseNmi(); // taken before the next instruction's fetch
                cpu.lowerNmi();
                nmiAfter = -1;
            }
        }
    }

    /** Raises an NMI right after the next fetch of {@code address}, before the instruction after it. */
    private void nmiAfterFetchOf(int address) {
        nmiAfter = address;
        probe.lastFetch = -1;
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
