package com.nordstrom.emulator;

import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Executor;
import java.util.prefs.Preferences;

/**
 * Builds the "File" menu: actions that bring a host file into the machine.
 * <p>
 * Type File... reads a text file and hands it to the {@link TypingFeeder},
 * which types it at whatever pace the running software reads the keyboard
 * -- the same feeder as the Edit menu's Paste. The typing progress
 * window's Stop Typing button discards whatever hasn't been typed yet. Deliberately no keyboard
 * shortcuts: these are menu actions only.
 */
final class FileMenu {

    /** Where the last Type File... dialog left off -- separate from the disk dialogs' folder. */
    private static final Preferences PREFS = Preferences.userNodeForPackage(FileMenu.class);
    private static final String LAST_DIRECTORY_KEY = "lastTypeFileDirectory";

    private FileMenu() {}

    /**
     * Builds the menu.
     *
     * @param feeder          types the chosen file's text into the machine's keyboard
     * @param parent          the component to anchor dialogs to (typically the main frame)
     * @param emulationThread runs everything that touches machine state -- queueing typing
     * @return the built menu, ready to add to a {@code JMenuBar}
     */
    static JMenu build(TypingFeeder feeder, Component parent, Executor emulationThread) {
        JMenu menu = new JMenu("File");

        JMenuItem typeFile = new JMenuItem("Type File...");
        typeFile.addActionListener(event -> promptAndTypeFile(feeder, parent, emulationThread));
        menu.add(typeFile);

        return menu;
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
            JOptionPane.showMessageDialog(parent, "Could not read file:\n" + e.getMessage(), "File",
                JOptionPane.ERROR_MESSAGE);
            return;
        }
        emulationThread.execute(() -> feeder.enqueue(text));
    }
}
