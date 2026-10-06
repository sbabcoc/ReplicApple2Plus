package com.nordstrom.emulator;

import com.nordstrom.emulator.expansion.VideoTerm;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JToolBar;
import java.awt.Component;
import java.util.Map;

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
 */
final class DisplayModeSelector {

    private static final Map<VideoTerm.Display, String> LABELS = Map.of(
        VideoTerm.Display.SWITCHED, "Soft Switch",
        VideoTerm.Display.APPLE, "Apple Video",
        VideoTerm.Display.SLOT3, "Slot 3 Video",
        VideoTerm.Display.SEPARATE, "Dual Monitor");

    private final VideoTerm card;
    private final Runnable onChange;
    private final JComboBox<VideoTerm.Display> comboBox = new JComboBox<>(new VideoTerm.Display[] {
        VideoTerm.Display.SWITCHED, VideoTerm.Display.APPLE, VideoTerm.Display.SLOT3, VideoTerm.Display.SEPARATE});

    /**
     * @param card     the card whose display mode this controls
     * @param onChange runs on the event thread after the mode changes, to
     *                 repaint and show or hide the 80-column window
     */
    DisplayModeSelector(VideoTerm card, Runnable onChange) {
        this.card = card;
        this.onChange = onChange;
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
            onChange.run();
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
