package com.nordstrom.emulator;

import com.nordstrom.emulator.expansion.VideoTerm;

import javax.swing.AbstractButton;
import javax.swing.ButtonGroup;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;

/**
 * Toolbar buttons choosing how a VideoTerm's 80-column output is shown:
 * <b>Soft Switch</b>, one window switching between the Apple's video and 80
 * columns the way Videx's Soft Video Switch does, or <b>Dual Monitor</b>,
 * the 80 columns in their own window beside an always-Apple main window.
 * <p>
 * The buttons aren't focusable, so clicking one leaves keyboard focus --
 * and with it the Apple's keyboard -- where it was.
 */
final class DisplayModeButtons {

    private final VideoTerm card;
    private final Runnable onChange;
    private final JToggleButton softSwitch = new JToggleButton("Soft Switch");
    private final JToggleButton dualMonitor = new JToggleButton("Dual Monitor");

    /**
     * @param card     the card whose display mode these buttons control
     * @param onChange runs on the event thread after the mode changes, to
     *                 show or hide the 80-column window
     */
    DisplayModeButtons(VideoTerm card, Runnable onChange) {
        this.card = card;
        this.onChange = onChange;
        ButtonGroup group = new ButtonGroup();
        for (AbstractButton button : new AbstractButton[] {softSwitch, dualMonitor}) {
            button.setFocusable(false);
            group.add(button);
        }
        softSwitch.setToolTipText("One window: 80 columns when the Soft Video Switch selects them");
        dualMonitor.setToolTipText("80 columns in their own window; the main window always shows the Apple's video");
        buttonFor(card.display()).setSelected(true);
        softSwitch.addActionListener(e -> apply(VideoTerm.Display.SWITCHED));
        dualMonitor.addActionListener(e -> apply(VideoTerm.Display.SEPARATE));
    }

    /** Adds the buttons to the end of a toolbar, set apart from what's already there. */
    void addTo(JToolBar toolbar) {
        toolbar.addSeparator();
        toolbar.add(softSwitch);
        toolbar.add(dualMonitor);
    }

    /**
     * Switches to a mode as if its button were clicked -- e.g. back to Soft
     * Switch when the 80-column window is closed.
     */
    void select(VideoTerm.Display display) {
        buttonFor(display).setSelected(true);
        apply(display);
    }

    private void apply(VideoTerm.Display display) {
        if (card.display() != display) {
            card.setDisplay(display);
            onChange.run();
        }
    }

    private JToggleButton buttonFor(VideoTerm.Display display) {
        return display == VideoTerm.Display.SEPARATE ? dualMonitor : softSwitch;
    }

    /** Package-visible for tests: whether a mode's button is the selected one. */
    boolean isSelected(VideoTerm.Display display) {
        return buttonFor(display).isSelected();
    }
}
