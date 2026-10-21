package com.nordstrom.emulator;

import com.nordstrom.emulator.expansion.VideoTerm;
import com.nordstrom.emulator.expansion.VideoTermRenderer;
import com.nordstrom.emulator.transfer.CpmTransferStarter;
import com.nordstrom.emulator.transfer.HostTransferCard;

import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;

/**
 * Builds the "File" menu: actions that bring a host file into the machine.
 * <p>
 * Type File... reads a text file and hands it to the {@link TypingFeeder},
 * which types it at whatever pace the running software reads the keyboard
 * -- the same feeder as the Edit menu's Paste. The typing progress
 * window's Stop Typing button discards whatever hasn't been typed yet.
 * <p>
 * Start CP/M Transfer starts a host file transfer session under CP/M on
 * the Microsoft SoftCard II, installing the card's CP/M adapter, XFER,
 * first if the disk lacks it -- all by typing at CP/M's prompt and reading
 * what it prints on the VideoTerm (TRANSFER-CARD.md 8.5). It's enabled
 * once CP/M has initialized the transfer card since the last RESET.
 * <p>
 * Open Emulator Folder shows the emulator folder in use, where the ROM
 * images, plug-ins and disks go; choosing a different one, or a different
 * slot configuration, is in Settings ({@link SettingsDialog}).
 * <p>
 * Settings... and Quit end the menu where the platform has no application
 * menu of its own to hold them ({@link AppMenu}).
 * <p>
 * Deliberately no keyboard shortcuts: these are menu actions only.
 */
final class FileMenu {

    /** Where the last Type File... dialog left off -- separate from the disk dialogs' folder. */
    private static final Preferences PREFS = Preferences.userNodeForPackage(FileMenu.class);
    private static final String LAST_DIRECTORY_KEY = "lastTypeFileDirectory";

    private FileMenu() {}

    /**
     * Whether a CP/M transfer is being started. Shared by every window's
     * copy of this menu, so a second start can't begin from the other one.
     */
    private static final AtomicBoolean STARTING = new AtomicBoolean();

    /** How long one CP/M command may run while starting a transfer: PIP copying XFER is the longest. */
    private static final long COMMAND_TIMEOUT_MILLIS = 120_000;
    /** How long the screen must stay unchanged for a command to count as finished. */
    private static final long SETTLE_MILLIS = 500;

    /**
     * Builds the menu.
     *
     * @param feeder          types into the machine's keyboard
     * @param parent          the component to anchor dialogs to (typically the main frame)
     * @param emulationThread runs everything that touches machine state -- queueing typing
     * @param transferCard    the machine's host file transfer card, or null if it has none
     * @param videoTerm       the machine's VideoTerm, CP/M's console on a II+, or null if it has none
     * @param startup         how the emulator was started, for the Settings dialog
     * @return the built menu, ready to add to a {@code JMenuBar}
     */
    static JMenu build(TypingFeeder feeder, Component parent, Executor emulationThread,
                       HostTransferCard transferCard, VideoTerm videoTerm, Apple2Plus.StartupFiles startup) {
        JMenu menu = new JMenu("File");

        JMenuItem startCpmTransfer = new JMenuItem("Start CP/M Transfer");
        startCpmTransfer.addActionListener(event ->
            startCpmTransfer(feeder, parent, emulationThread, videoTerm, STARTING));
        menu.add(startCpmTransfer);

        JMenuItem typeFile = new JMenuItem("Type File...");
        typeFile.addActionListener(event -> promptAndTypeFile(feeder, parent, emulationThread));
        menu.add(typeFile);

        menu.addSeparator();

        JMenuItem openEmulatorFolder = new JMenuItem("Open Emulator Folder");
        openEmulatorFolder.addActionListener(event -> EmulatorFolder.open(parent, EmulatorFolder.path()));
        menu.add(openEmulatorFolder);

        if (!AppMenu.systemHasSettings()) {
            menu.addSeparator();
            JMenuItem settings = new JMenuItem("Settings...");
            settings.addActionListener(event -> SettingsDialog.show(parent, startup));
            menu.add(settings);
        }
        if (!AppMenu.systemHasQuit()) {
            menu.addSeparator();
            JMenuItem quit = new JMenuItem("Quit");
            quit.addActionListener(event -> AppMenu.quit());
            menu.add(quit);
        }

        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                startCpmTransfer.setEnabled(transferCard != null && videoTerm != null
                    && transferCard.servedByCpm() && !STARTING.get());
                openEmulatorFolder.setEnabled(EmulatorFolder.path() != null);
            }

            @Override
            public void menuDeselected(MenuEvent e) {
            }

            @Override
            public void menuCanceled(MenuEvent e) {
            }
        });
        return menu;
    }

    /** Runs the start on a thread of its own: it waits on the machine, command by command. */
    private static void startCpmTransfer(TypingFeeder feeder, Component parent, Executor emulationThread,
                                         VideoTerm videoTerm, AtomicBoolean starting) {
        if (!starting.compareAndSet(false, true)) {
            return;
        }
        AtomicInteger submitted = new AtomicInteger(); // typing handed over but not yet queued
        CpmTransferStarter.Keyboard keyboard = new CpmTransferStarter.Keyboard() {
            @Override
            public void type(String text) {
                submit(() -> feeder.enqueue(text));
            }

            @Override
            public void press(int key) {
                submit(() -> feeder.enqueueKeys(key));
            }

            @Override
            public boolean isTyping() {
                return submitted.get() > 0 || feeder.isTyping();
            }

            private void submit(Runnable queueing) {
                submitted.incrementAndGet();
                emulationThread.execute(() -> {
                    queueing.run();
                    submitted.decrementAndGet();
                });
            }
        };
        CpmTransferStarter starter = new CpmTransferStarter(keyboard, () -> VideoTermRenderer.text(videoTerm),
            COMMAND_TIMEOUT_MILLIS, SETTLE_MILLIS);
        Thread worker = new Thread(() -> {
            CpmTransferStarter.Result result;
            try {
                result = starter.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result = new CpmTransferStarter.Result(false, "Interrupted.");
            } finally {
                starting.set(false);
            }
            if (!result.started()) {
                String message = result.message();
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(parent, message,
                    "Start CP/M Transfer", JOptionPane.WARNING_MESSAGE));
            }
            // On success the transfer window opens by itself, as the session begins.
        }, "Start CP/M Transfer");
        worker.setDaemon(true);
        worker.start();
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
