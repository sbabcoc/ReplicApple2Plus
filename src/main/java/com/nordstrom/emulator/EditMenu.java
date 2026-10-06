package com.nordstrom.emulator;

import com.nordstrom.emulator.expansion.VideoTermRenderer;
import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.system.ScanlineModes;
import com.nordstrom.emulator.system.ScreenText;

import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Executor;
import java.util.prefs.Preferences;

/**
 * Builds the "Edit" menu: getting the display out of the machine (Copy
 * Screen Text, Copy Screen Image) and text into it (Paste, Type File...).
 * <p>
 * Paste and Type File... differ only in where the text comes from; both
 * hand it to the same {@link TypingFeeder}, which types it at whatever pace
 * the running software reads the keyboard. Stop Typing discards whatever
 * hasn't been typed yet. Deliberately no keyboard shortcuts: these are menu
 * actions only.
 * <p>
 * Item enablement is refreshed each time the menu opens, the same pattern
 * {@link DiskMenu} uses: Copy Screen Text only when some row on screen is text,
 * Paste only when the clipboard holds text, Stop Typing only while typing.
 */
final class EditMenu {

    /** Where the last Type File... dialog left off -- separate from the disk dialogs' folder. */
    private static final Preferences PREFS = Preferences.userNodeForPackage(EditMenu.class);
    private static final String LAST_DIRECTORY_KEY = "lastTypeFileDirectory";

    private EditMenu() {}

    /**
     * Builds the menu.
     *
     * @param bus             the machine whose screen memory Copy Screen Text reads
     * @param screen          the display Copy Screen Image snapshots, and whose
     *                        current content (the Apple's video, or 80 columns
     *                        when its Soft Video Switch selects them) Copy Screen
     *                        Text copies
     * @param feeder          types Paste and Type File... text into {@code bus}'s keyboard
     * @param parent          the component to anchor dialogs to (typically the main frame)
     * @param emulationThread runs everything that touches machine state -- reading
     *                        screen memory and queueing or cancelling typing
     * @return the built menu, ready to add to a {@code JMenuBar}
     */
    static JMenu build(MotherboardBus bus, ScreenPanel screen, TypingFeeder feeder, Component parent,
                       Executor emulationThread) {
        JMenu menu = new JMenu("Edit");

        JMenuItem copyText = new JMenuItem("Copy Screen Text");
        copyText.addActionListener(event -> {
            boolean eightyColumns = screen.showingEightyColumns(); // what's on screen now, decided here on the event thread
            emulationThread.execute(() -> {
                // Screen memory belongs to the emulation thread; the clipboard to the event thread.
                String text = eightyColumns
                    ? VideoTermRenderer.text(screen.switchedVideoTerm())
                    : ScreenText.capture(bus, bus.scanlineModes().completedFrame());
                EventQueue.invokeLater(() -> setClipboard(new StringSelection(text), parent));
            });
        });
        menu.add(copyText);

        JMenuItem copyImage = new JMenuItem("Copy Screen Image");
        copyImage.addActionListener(event -> setClipboard(new ImageSelection(snapshot(screen)), parent));
        menu.add(copyImage);

        JMenuItem paste = new JMenuItem("Paste");
        paste.addActionListener(event -> {
            String text = clipboardText(parent);
            if (text != null) {
                emulationThread.execute(() -> feeder.enqueue(text));
            }
        });
        menu.add(paste);

        JMenuItem typeFile = new JMenuItem("Type File...");
        typeFile.addActionListener(event -> promptAndTypeFile(feeder, parent, emulationThread));
        menu.add(typeFile);

        menu.addSeparator();

        JMenuItem stopTyping = new JMenuItem("Stop Typing");
        stopTyping.addActionListener(event -> emulationThread.execute(feeder::cancel));
        menu.add(stopTyping);

        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                copyText.setEnabled(screen.showingEightyColumns() || anyTextRow(bus.scanlineModes().completedFrame()));
                paste.setEnabled(clipboardHasText());
                stopTyping.setEnabled(feeder.isTyping());
            }

            @Override
            public void menuDeselected(MenuEvent e) { /* nothing to do */ }

            @Override
            public void menuCanceled(MenuEvent e) { /* nothing to do */ }
        });

        return menu;
    }

    /**
     * Builds the Edit menu for a separate 80-column window: copying only,
     * since typing goes to the one keyboard either window feeds.
     *
     * @param view            the 80-column window's display
     * @param parent          the component to anchor dialogs to
     * @param emulationThread runs everything that reads the card's memory
     * @return the built menu
     */
    static JMenu buildForEightyColumnWindow(EightyColumnView view, Component parent, Executor emulationThread) {
        JMenu menu = new JMenu("Edit");
        JMenuItem copyText = new JMenuItem("Copy Screen Text");
        copyText.addActionListener(event -> emulationThread.execute(() -> {
            String text = VideoTermRenderer.text(view.card());
            EventQueue.invokeLater(() -> setClipboard(new StringSelection(text), parent));
        }));
        menu.add(copyText);
        JMenuItem copyImage = new JMenuItem("Copy Screen Image");
        copyImage.addActionListener(event -> setClipboard(new ImageSelection(snapshot(view)), parent));
        menu.add(copyImage);
        return menu;
    }

    /** Whether any scan line of this frame is text -- completedFrame() is safe to read from any thread. */
    private static boolean anyTextRow(ScanlineModes.LineMode[] modes) {
        for (ScanlineModes.LineMode mode : modes) {
            if (mode.source() == ScanlineModes.Source.TEXT) {
                return true;
            }
        }
        return false;
    }

    private static void promptAndTypeFile(TypingFeeder feeder, Component parent, Executor emulationThread) {
        String last = PREFS.get(LAST_DIRECTORY_KEY, null);
        File remembered = last == null ? null : new File(last);
        JFileChooser chooser = new JFileChooser(remembered != null && remembered.isDirectory() ? remembered : null);
        int result = chooser.showOpenDialog(parent);
        File dir = chooser.getCurrentDirectory();
        if (dir != null) {
            PREFS.put(LAST_DIRECTORY_KEY, dir.getAbsolutePath());
        }
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        String text;
        try {
            // Decoded leniently: anything that isn't valid UTF-8 becomes a
            // replacement character, which the feeder then skips like any
            // other character the II+ keyboard can't produce.
            text = new String(Files.readAllBytes(chooser.getSelectedFile().toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            showError(parent, "Could not read file:\n" + e.getMessage());
            return;
        }
        emulationThread.execute(() -> feeder.enqueue(text));
    }

    private static Clipboard systemClipboard() {
        return Toolkit.getDefaultToolkit().getSystemClipboard();
    }

    private static boolean clipboardHasText() {
        try {
            return systemClipboard().isDataFlavorAvailable(DataFlavor.stringFlavor);
        } catch (IllegalStateException e) {
            return false; // clipboard momentarily unavailable -- just leave Paste disabled this time
        }
    }

    /** @return the clipboard's text, or null (after telling the user) if there isn't any */
    private static String clipboardText(Component parent) {
        try {
            return (String) systemClipboard().getData(DataFlavor.stringFlavor);
        } catch (UnsupportedFlavorException | IOException | IllegalStateException e) {
            showError(parent, "The clipboard doesn't contain text that can be pasted.");
            return null;
        }
    }

    /**
     * Captures exactly what {@code component} currently shows, at its
     * on-screen size, by having it paint itself into an offscreen image --
     * the same {@code paint} call Swing makes for the window, so the copy
     * can't drift from the display. Event thread only, like any painting.
     *
     * @param component the component to capture
     * @return a new image of the component's current appearance
     */
    static BufferedImage snapshot(Component component) {
        BufferedImage image = new BufferedImage(
            Math.max(1, component.getWidth()), Math.max(1, component.getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            component.paint(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void setClipboard(Transferable contents, Component parent) {
        try {
            systemClipboard().setContents(contents, null);
        } catch (IllegalStateException e) {
            showError(parent, "Could not copy to the clipboard:\n" + e.getMessage());
        }
    }

    private static void showError(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Edit", JOptionPane.ERROR_MESSAGE);
    }
}
