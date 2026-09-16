package com.nordstrom.emulator;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Font;

/**
 * The application's entry point. Currently a placeholder -- a plain
 * Swing window with no emulator content at all -- deliberately built
 * before the real CPU/bus/scheduler wiring exists, specifically to
 * validate the {@code jlink}/{@code jpackage} packaging pipeline on
 * real hardware early, rather than discovering platform-specific
 * packaging problems only after a complete application is riding on
 * top of an unvalidated assumption.
 * <p>
 * Swing, not JavaFX: Swing ships in the JDK's base modules, so a
 * {@code jlink} image built from this stays simple with no per-platform
 * JavaFX module to manage. Real hardware is a 280x192 display; nothing
 * here needs JavaFX's capabilities to begin with.
 * <p>
 * This class will be replaced once {@link com.nordstrom.emulator.system.MotherboardBus},
 * a {@code SystemClock}, and real video/keyboard/audio wiring exist --
 * at that point this becomes the actual application window, not a
 * placeholder for one.
 */
public final class Apple2Plus {

    /**
     * Application entry point.
     *
     * @param args command-line arguments (currently unused)
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(Apple2Plus::createAndShowWindow);
    }

    private static void createAndShowWindow() {
        JFrame frame = new JFrame("ReplicApple2Plus");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JLabel placeholder = new JLabel(
            "<html><center>ReplicApple2Plus<br>"
            + "<small>Packaging pipeline placeholder -- the emulator itself isn't wired up yet</small>"
            + "</center></html>",
            SwingConstants.CENTER);
        placeholder.setFont(placeholder.getFont().deriveFont(Font.PLAIN, 16f));

        frame.getContentPane().add(placeholder);
        frame.setPreferredSize(new Dimension(560, 384)); // 2x the real 280x192 display, for a visible placeholder
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private Apple2Plus() {}
}
