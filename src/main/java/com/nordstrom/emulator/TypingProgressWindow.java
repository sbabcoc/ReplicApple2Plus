package com.nordstrom.emulator;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.concurrent.Executor;

/**
 * Shows the progress of Paste or Type File... while it types, with a Stop
 * Typing button -- the one way to cancel typing, so it is in view exactly
 * when there is typing to stop.
 * <p>
 * The window opens only once typing has run past {@link #SHOW_DELAY_MS}, so
 * a short paste that finishes almost at once never flashes it, and closes
 * as soon as typing stops. It never takes keyboard focus: the user may be
 * typing alongside, and those keystrokes belong to the emulated machine,
 * not to this window. Its button still takes clicks.
 * <p>
 * It sits just below the main window, so it doesn't hide the screen the
 * typed text appears on; if there's no room below, it overlaps the main
 * window's bottom edge instead.
 */
final class TypingProgressWindow {

    /** How long typing must run before the window appears. */
    static final int SHOW_DELAY_MS = 1000;
    private static final int REFRESH_MS = 100;

    private final Window owner;
    private final TypingFeeder feeder;
    private final JDialog dialog;
    private final JProgressBar bar = new JProgressBar();
    private final JLabel status = new JLabel(" ");
    private final Timer showTimer;
    private final Timer refreshTimer;

    /**
     * Builds the window and starts following {@code feeder}.
     *
     * @param owner           the main window, which the progress window sits below
     * @param feeder          the typing to follow and cancel
     * @param emulationThread runs the cancel, which touches machine state
     */
    TypingProgressWindow(Window owner, TypingFeeder feeder, Executor emulationThread) {
        this.owner = owner;
        this.feeder = feeder;

        dialog = new JDialog(owner, "Typing");
        dialog.setFocusableWindowState(false);
        dialog.setAutoRequestFocus(false);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE); // typing ends it, or Stop Typing

        JButton stop = new JButton("Stop Typing");
        stop.setFocusable(false);
        stop.addActionListener(e -> emulationThread.execute(feeder::cancel));

        JPanel panel = new JPanel(new BorderLayout(8, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        panel.add(status, BorderLayout.NORTH);
        panel.add(bar, BorderLayout.CENTER);
        panel.add(stop, BorderLayout.EAST);
        dialog.setContentPane(panel);
        dialog.pack();

        showTimer = new Timer(SHOW_DELAY_MS, e -> show());
        showTimer.setRepeats(false);
        refreshTimer = new Timer(REFRESH_MS, e -> refresh());

        feeder.setTypingListener(typing -> EventQueue.invokeLater(() -> typingChanged(typing)));
    }

    /** Stops following the feeder and releases the window, for a machine being replaced. */
    void dispose() {
        feeder.setTypingListener(typing -> { });
        showTimer.stop();
        refreshTimer.stop();
        dialog.dispose();
    }

    private void typingChanged(boolean typing) {
        if (typing) {
            showTimer.restart();
        } else {
            showTimer.stop();
            refreshTimer.stop();
            dialog.setVisible(false);
        }
    }

    private void show() {
        if (!feeder.isTyping()) {
            return;
        }
        refresh();
        dialog.setSize(Math.max(dialog.getPreferredSize().width, owner.getWidth() / 2),
            dialog.getPreferredSize().height);
        dialog.setLocation(owner.getX(), placementY());
        dialog.setVisible(true);
        refreshTimer.start();
    }

    /** Just below the main window if that fits on its screen, else overlapping its bottom edge. */
    private int placementY() {
        GraphicsConfiguration config = owner.getGraphicsConfiguration();
        Rectangle screen = config != null ? config.getBounds() : new Rectangle(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE);
        int below = owner.getY() + owner.getHeight();
        return below + dialog.getHeight() <= screen.y + screen.height ? below : below - dialog.getHeight();
    }

    private void refresh() {
        int total = Math.max(1, feeder.queuedCount());
        int done = Math.min(feeder.typedCount(), total);
        bar.setMaximum(total);
        bar.setValue(done);
        status.setText("Typing: " + done + " of " + total + " keystrokes");
    }
}
