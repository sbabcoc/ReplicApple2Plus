package com.nordstrom.emulator.expansion;

import com.nordstrom.emulator.cpu.Cpu6502;
import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.system.SlotCard;
import com.nordstrom.emulator.system.SystemClock;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real VideoTerm firmware 2.4 and the real Apple II+ system ROM, run
 * together: cold boot to Applesoft, {@code PR#3}, then output -- checking
 * what the card shows, the CRTC setup the firmware programs, and what the
 * Soft Video Switch selects as the program moves between text and graphics.
 */
class VideoTermFirmwareIntegrationTest {

    private static final int CYCLES_PER_SECOND = 1_023_000;

    private final VideoTerm card = new VideoTerm();
    private final MotherboardBus bus;
    private final SystemClock clock;
    private final Deque<Integer> keys = new ArrayDeque<>();

    VideoTermFirmwareIntegrationTest() {
        card.configure(new Properties());
        SlotCard[] slots = new SlotCard[8];
        slots[3] = card;
        bus = MotherboardBus.withoutAudio(slots);
        clock = new SystemClock(new Cpu6502(bus, 0xFFFC));
        clock.addCycleListener(bus.videoScanner()::tick);
        clock.addCycleListener(bus.scanlineModes()::tick);
    }

    /** Runs for {@code seconds} of machine time, typing any queued keys as fast as software reads them. */
    private void run(double seconds) {
        long cycles = 0;
        long end = (long) (seconds * CYCLES_PER_SECOND);
        while (cycles < end) {
            if (!keys.isEmpty() && !bus.keyboardRegister().isStrobeSet()) {
                bus.keyboardRegister().keyPressed(keys.poll());
            }
            cycles += clock.step();
        }
    }

    private void type(String line) {
        for (char c : line.toCharArray()) {
            keys.add((int) c);
        }
        keys.add(0x0D);
    }

    @Test
    void pr3HandsOutputToTheCardAndTheSoftVideoSwitchFollowsTextAndGraphics() {
        run(2); // cold boot to the Applesoft prompt
        assertFalse(bus.videoSoftSwitches().softVideoSwitchSelects80Columns(), "40 columns before PR#3");

        type("PR#3");
        run(1);
        int[] firmwareTable = {0x7B, 0x50, 0x5E, 0x29, 0x1B, 0x08, 0x18, 0x19, 0x00, 0x08, 0xE0, 0x08};
        for (int r = 0; r < firmwareTable.length; r++) {
            assertEquals(firmwareTable[r], card.register(r), "CRTC R" + r + " as the firmware's table sets it");
        }
        assertTrue(bus.videoSoftSwitches().softVideoSwitchSelects80Columns(), "PR#3 turns AN0 on, in text mode");

        type("PRINT \"HELLO FROM THE VIDEOTERM\"");
        run(1);
        String screen = VideoTermRenderer.text(card);
        assertTrue(screen.contains("]PRINT \"HELLO FROM THE VIDEOTERM\"\nHELLO FROM THE VIDEOTERM\n"),
            "the 80-column screen should show the command and its output:\n" + screen);

        type("GR");
        run(1);
        assertFalse(bus.videoSoftSwitches().softVideoSwitchSelects80Columns(), "graphics on: the Apple's video");

        type("TEXT");
        run(1);
        assertTrue(bus.videoSoftSwitches().softVideoSwitchSelects80Columns(), "back to text: 80 columns again");
    }
}
