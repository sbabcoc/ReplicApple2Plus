package com.nordstrom.emulator;

import com.nordstrom.emulator.expansion.VideoTerm;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JToolBar;
import java.awt.Component;
import java.util.Map;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * Toolbar drop-down choosing how a VideoTerm's 80-column output is shown:
 * <ul>
 *   <li><b>Soft Switch</b> -- one window, switching between the Apple's
 *       video and 80 columns the way Videx's Soft Video Switch does;</li>
 *   <li><b>Apple Video</b> / <b>Slot 3 Video</b> -- one window, held on one
 *       source, like a manual monitor switch (for software, such as Merlin,
 *       that leaves 80-column mode without turning annunciator 0 off);</li>
 *   <li><b>Dual Monitor</b> -- the 80 columns in their own window, beside an
 *       always-Apple main window.</li>
 * </ul>
 * Not focusable, so choosing a mode leaves keyboard focus -- and with it the
 * Apple's keyboard -- where it was.
 * <p>
 * The mode chosen is remembered with the JDK's user preferences, so a
 * machine built later -- after a Reboot, or in the next launch -- starts in
 * it ({@link #rememberedDisplay}). It's the only place the mode is set:
 * it's a choice about the emulator's windows, not a card setting.
 */
final class DisplayModeSelector {

    private static final Map<VideoTerm.Display, String> LABELS = Map.of(
        VideoTerm.Display.SWITCHED, "Soft Switch",
        VideoTerm.Display.APPLE, "Apple Video",
        VideoTerm.Display.SLOT3, "Slot 3 Video",
        VideoTerm.Display.SEPARATE, "Dual Monitor");

    private static final Preferences PREFS = Preferences.userNodeForPackage(DisplayModeSelector.class);
    private static final String DISPLAY_KEY = "videoTermDisplay";

    private final VideoTerm card;
    private final Runnable onChange;
    private final Consumer<VideoTerm.Display> remember;
    private final JComboBox<VideoTerm.Display> comboBox = new JComboBox<>(new VideoTerm.Display[] {
        VideoTerm.Display.SWITCHED, VideoTerm.Display.APPLE, VideoTerm.Display.SLOT3, VideoTerm.Display.SEPARATE});

    /**
     * @param card     the card whose display mode this controls
     * @param onChange runs on the event thread after the mode changes, to
     *                 repaint and show or hide the 80-column window
     */
    DisplayModeSelector(VideoTerm card, Runnable onChange) {
        this(card, onChange, display -> PREFS.put(DISPLAY_KEY, display.name()));
    }

    /**
     * Package-visible for tests, which mustn't change the user's remembered mode.
     *
     * @param card     the card whose display mode this controls
     * @param onChange runs after the mode changes
     * @param remember records the mode chosen
     */
    DisplayModeSelector(VideoTerm card, Runnable onChange, Consumer<VideoTerm.Display> remember) {
        this.card = card;
        this.onChange = onChange;
        this.remember = remember;
        comboBox.setFocusable(false);
        comboBox.setToolTipText("How the VideoTerm's 80-column output is displayed");
        comboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, LABELS.get((VideoTerm.Display) value),
                    index, isSelected, cellHasFocus);
            }
        });
        comboBox.setMaximumSize(comboBox.getPreferredSize()); // keep the toolbar from stretching it
        comboBox.setSelectedItem(card.display());
        comboBox.addActionListener(e -> apply((VideoTerm.Display) comboBox.getSelectedItem()));
    }

    /** Adds the drop-down to the end of a toolbar, set apart from what's already there. */
    void addTo(JToolBar toolbar) {
        toolbar.addSeparator();
        toolbar.add(comboBox);
    }

    /** Switches to a mode as if it were chosen from the drop-down -- e.g. Soft Switch when the 80-column window closes. */
    void select(VideoTerm.Display display) {
        comboBox.setSelectedItem(display); // fires the action listener, which applies it
    }

    private void apply(VideoTerm.Display display) {
        if (display != null && card.display() != display) {
            card.setDisplay(display);
            remember.accept(display);
            onChange.run();
        }
    }

    /**
     * The mode last chosen, for a newly built machine's VideoTerm.
     *
     * @return the mode last chosen, or {@link VideoTerm.Display#SWITCHED} if none was
     *         (or what was remembered is no longer a mode this build knows)
     */
    static VideoTerm.Display rememberedDisplay() {
        try {
            return VideoTerm.Display.valueOf(PREFS.get(DISPLAY_KEY, VideoTerm.Display.SWITCHED.name()));
        } catch (IllegalArgumentException e) {
            return VideoTerm.Display.SWITCHED;
        }
    }

    /** Package-visible for tests: the drop-down itself. */
    JComboBox<VideoTerm.Display> comboBox() {
        return comboBox;
    }

    /** Package-visible for tests: the label shown for a mode. */
    static String label(VideoTerm.Display display) {
        return LABELS.get(display);
    }
}
