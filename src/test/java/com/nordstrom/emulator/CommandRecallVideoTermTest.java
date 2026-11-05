package com.nordstrom.emulator;

import com.nordstrom.emulator.RequiresRoms.Rom;
import com.nordstrom.emulator.cpu.Cpu6502;
import com.nordstrom.emulator.expansion.VideoTerm;
import com.nordstrom.emulator.expansion.VideoTermRenderer;
import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.system.SlotCard;
import com.nordstrom.emulator.system.SystemClock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paste and command history in 80 columns: the real VideoTerm firmware 2.4
 * reading the keys and drawing the screen, the real Apple II+ ROM, and the
 * device between the CPU and the motherboard typing through the keyboard
 * cable -- its line pacing watching the CPU's writes to the VideoTerm's
 * display memory, wired exactly as the application does.
 */
@RequiresRoms({Rom.SYSTEM, Rom.VIDEOTERM})
class CommandRecallVideoTermTest {

    /** The firmware's "already initialized" marker; see VideoTermFirmwareIntegrationTest. */
    private static final int INIT_MARKER = 0x077B;

    private final VideoTerm card = new VideoTerm();
    private final MotherboardBus bus;
    private final CommandHistory history = new CommandHistory();
    private final CommandRecall recall;
    private final TypingFeeder typing;
    private final SystemClock clock;
    private double seconds;

    CommandRecallVideoTermTest() {
        card.configure(new Properties());
        SlotCard[] slots = new SlotCard[8];
        slots[3] = card;
        bus = MotherboardBus.withoutAudio(slots);
        RomBoot.coldStart(bus);
        recall = new CommandRecall(bus, bus.keyboardRegister(), history);
        typing = recall.typing();
        clock = new SystemClock(new Cpu6502(recall, 0xFFFC));
        clock.addCycleListener(bus.videoScanner()::tick);
        clock.addCycleListener(bus.scanlineModes()::tick);
        clock.addCycleListener(typing::tick);
    }

    @Test
    void pasteAndRecallWorkWithTheVideoTermReadingTheKeys() {
        run(3); // cold boot to the Applesoft prompt
        bus.write(INIT_MARKER, 0x00); // a power-on that didn't happen to leave the marker set
        typing.enqueue("PR#3\n");
        run(1);
        assertTrue(bus.videoSoftSwitches().softVideoSwitchSelects80Columns(), "80 columns after PR#3");

        typing.enqueue("10 A = 5\n20 B = 7\n30 PRINT A * B\nRUN\n");
        run(3);
        assertTrue(!typing.isTyping(), "everything typed");
        assertTrue(rows().contains("35"), "every pasted line arrived intact:\n" + VideoTermRenderer.text(card));

        typing.enqueue("PRINT 6 * 7\n");
        run(1);
        assertEquals("PRINT 6 * 7", history.entries(']').get(history.entries(']').size() - 1));
        recall.older();
        run(1);
        assertEquals("]PRINT 6 * 7", lastRow(), "recalled on the 80-column screen");
        typing.enqueueKeys(0x0D);
        run(1);
        assertEquals(2L, rows().stream().filter(row -> row.strip().equals("42")).count(),
            "typed once, recalled once, run both times:\n" + VideoTermRenderer.text(card));
    }

    private void run(double s) {
        double until = seconds + s;
        while (seconds < until) {
            seconds += RomBoot.notHalted(clock.step()) / 1_023_000.0;
        }
    }

    private List<String> rows() {
        return VideoTermRenderer.text(card).lines().toList();
    }

    private String lastRow() {
        List<String> rows = rows();
        return rows.get(rows.size() - 1).stripTrailing();
    }
}
