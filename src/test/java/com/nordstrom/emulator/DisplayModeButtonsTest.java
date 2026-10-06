package com.nordstrom.emulator;

import com.nordstrom.emulator.expansion.VideoTerm;
import org.junit.jupiter.api.Test;

import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import java.awt.Component;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayModeButtonsTest {

    private static VideoTerm card(String display) {
        VideoTerm card = new VideoTerm();
        Properties props = new Properties();
        props.setProperty("display", display);
        card.configure(props);
        return card;
    }

    private static JToggleButton button(JToolBar toolbar, String label) {
        for (Component c : toolbar.getComponents()) {
            if (c instanceof JToggleButton b && b.getText().equals(label)) {
                return b;
            }
        }
        throw new AssertionError("no " + label + " button");
    }

    @Test
    void theInitialSelectionFollowsTheConfiguredMode() {
        assertTrue(new DisplayModeButtons(card("switched"), () -> {}).isSelected(VideoTerm.Display.SWITCHED));
        assertTrue(new DisplayModeButtons(card("separate"), () -> {}).isSelected(VideoTerm.Display.SEPARATE));
    }

    @Test
    void clickingAButtonChangesTheModeOnceAndRunsTheCallback() {
        VideoTerm card = card("switched");
        int[] changes = {0};
        DisplayModeButtons buttons = new DisplayModeButtons(card, () -> changes[0]++);
        JToolBar toolbar = new JToolBar();
        buttons.addTo(toolbar);

        button(toolbar, "Dual Monitor").doClick();
        assertEquals(VideoTerm.Display.SEPARATE, card.display());
        assertEquals(1, changes[0]);

        button(toolbar, "Dual Monitor").doClick(); // already selected: no change
        assertEquals(1, changes[0]);

        button(toolbar, "Soft Switch").doClick();
        assertEquals(VideoTerm.Display.SWITCHED, card.display());
        assertEquals(2, changes[0]);
    }

    @Test
    void selectingProgrammaticallyUpdatesTheButtonsAndTheMode() {
        VideoTerm card = card("separate");
        int[] changes = {0};
        DisplayModeButtons buttons = new DisplayModeButtons(card, () -> changes[0]++);
        buttons.select(VideoTerm.Display.SWITCHED); // as closing the 80-column window does
        assertEquals(VideoTerm.Display.SWITCHED, card.display());
        assertTrue(buttons.isSelected(VideoTerm.Display.SWITCHED));
        assertFalse(buttons.isSelected(VideoTerm.Display.SEPARATE));
        assertEquals(1, changes[0]);
    }

    @Test
    void theButtonsNeverTakeKeyboardFocus() {
        JToolBar toolbar = new JToolBar();
        new DisplayModeButtons(card("switched"), () -> {}).addTo(toolbar);
        assertFalse(button(toolbar, "Soft Switch").isFocusable());
        assertFalse(button(toolbar, "Dual Monitor").isFocusable());
    }
}
