package com.nordstrom.emulator;

import com.nordstrom.emulator.expansion.VideoTerm;
import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.system.SlotCard;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayModeSelectorTest {

    private static VideoTerm card(String display) {
        VideoTerm card = new VideoTerm();
        Properties props = new Properties();
        props.setProperty("display", display);
        card.configure(props);
        return card;
    }

    @Test
    void offersTheFourModesInOrderAndStartsOnTheConfiguredOne() {
        DisplayModeSelector selector = new DisplayModeSelector(card("slot3"), () -> {});
        assertEquals(4, selector.comboBox().getItemCount());
        String[] labels = {"Soft Switch", "Apple Video", "Slot 3 Video", "Dual Monitor"};
        for (int i = 0; i < 4; i++) {
            assertEquals(labels[i], DisplayModeSelector.label(selector.comboBox().getItemAt(i)));
        }
        assertEquals(VideoTerm.Display.SLOT3, selector.comboBox().getSelectedItem());
    }

    @Test
    void choosingAModeChangesTheCardOnceAndRunsTheCallback() {
        VideoTerm card = card("switched");
        int[] changes = {0};
        DisplayModeSelector selector = new DisplayModeSelector(card, () -> changes[0]++);
        selector.comboBox().setSelectedItem(VideoTerm.Display.APPLE);
        assertEquals(VideoTerm.Display.APPLE, card.display());
        selector.comboBox().setSelectedItem(VideoTerm.Display.APPLE); // unchanged: no callback
        assertEquals(1, changes[0]);
        selector.select(VideoTerm.Display.SWITCHED); // as closing the 80-column window does
        assertEquals(VideoTerm.Display.SWITCHED, card.display());
        assertEquals(VideoTerm.Display.SWITCHED, selector.comboBox().getSelectedItem());
        assertEquals(2, changes[0]);
    }

    @Test
    void theDropDownNeverTakesKeyboardFocus() {
        assertFalse(new DisplayModeSelector(card("switched"), () -> {}).comboBox().isFocusable());
    }

    @Test
    void eachModeDecidesWhatTheMainWindowShows() {
        VideoTerm card = card("switched");
        SlotCard[] slots = new SlotCard[8];
        slots[3] = card;
        try (MotherboardBus bus = MotherboardBus.withoutAudio(slots)) {
            ScreenPanel screen = new ScreenPanel(bus, bus.scanlineModes(), card);
            bus.write(0xC051, 0); // text mode
            bus.write(0xC059, 0); // AN0 on: the Soft Video Switch selects 80 columns

            card.setDisplay(VideoTerm.Display.SWITCHED);
            assertTrue(screen.showingEightyColumns());
            card.setDisplay(VideoTerm.Display.APPLE);
            assertFalse(screen.showingEightyColumns(), "Apple Video ignores AN0");
            card.setDisplay(VideoTerm.Display.SEPARATE);
            assertFalse(screen.showingEightyColumns(), "Dual Monitor keeps the main window on the Apple");

            bus.write(0xC058, 0); // AN0 off, as after Merlin's VID 0 plus RESET -- or never, after VID 0 alone
            card.setDisplay(VideoTerm.Display.SLOT3);
            assertTrue(screen.showingEightyColumns(), "Slot 3 Video ignores AN0");
            card.setDisplay(VideoTerm.Display.SWITCHED);
            assertFalse(screen.showingEightyColumns());
        }
    }
}
