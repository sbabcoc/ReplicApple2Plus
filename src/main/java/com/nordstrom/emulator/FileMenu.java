package com.nordstrom.emulator;

import com.nordstrom.emulator.expansion.VideoTerm;
import com.nordstrom.emulator.expansion.VideoTermRenderer;
import com.nordstrom.emulator.system.SlotCardLoader;
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
import java.nio.file.Path;
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
 * Slot Configuration... chooses a slot configuration other than the
 * emulator folder's own slots.ini (EmulatorFolder), remembered, and
 * applied at the next Reboot; {@code --config}, when given, takes
 * precedence. Emulator Folder... chooses another emulator folder, for the
 * next start; Open Emulator Folder shows the current one, where the ROM
 * images, plug-ins and disks go.
 * <p>
 * Deliberately no keyboard shortcuts: these are menu actions only.
 */
final class FileMenu {

    /** Where the last Type File... dialog left off -- separate from the disk dialogs' folder. */
    private static final Preferences PREFS = Preferences.userNodeForPackage(FileMenu.class);
    private static final String LAST_DIRECTORY_KEY = "lastTypeFileDirectory";

    private FileMenu() {}

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
     * @param startup         how the emulator was started: for checking a slot configuration, and
     *                        whether the command line overrides what's chosen here
     * @return the built menu, ready to add to a {@code JMenuBar}
     */
    static JMenu build(TypingFeeder feeder, Component parent, Executor emulationThread,
                       HostTransferCard transferCard, VideoTerm videoTerm, Apple2Plus.StartupFiles startup) {
        JMenu menu = new JMenu("File");

        JMenuItem startCpmTransfer = new JMenuItem("Start CP/M Transfer");
        AtomicBoolean starting = new AtomicBoolean();
        startCpmTransfer.addActionListener(event ->
            startCpmTransfer(feeder, parent, emulationThread, videoTerm, starting));
        menu.add(startCpmTransfer);

        JMenuItem typeFile = new JMenuItem("Type File...");
        typeFile.addActionListener(event -> promptAndTypeFile(feeder, parent, emulationThread));
        menu.add(typeFile);

        menu.addSeparator();

        JMenuItem slotConfiguration = new JMenuItem("Slot Configuration...");
        slotConfiguration.addActionListener(event -> chooseSlotConfiguration(parent, startup));
        menu.add(slotConfiguration);

        JMenuItem emulatorFolder = new JMenuItem("Emulator Folder...");
        emulatorFolder.addActionListener(event -> chooseEmulatorFolder(parent));
        menu.add(emulatorFolder);

        JMenuItem openEmulatorFolder = new JMenuItem("Open Emulator Folder");
        openEmulatorFolder.addActionListener(event -> EmulatorFolder.open(parent, EmulatorFolder.path()));
        menu.add(openEmulatorFolder);

        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                startCpmTransfer.setEnabled(transferCard != null && videoTerm != null
                    && transferCard.servedByCpm() && !starting.get());
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

    /**
     * Chooses another emulator folder -- an existing one, or a new one,
     * which is set up -- and remembers it for the next start, when its ROM
     * images and plug-ins are loaded.
     */
    private static void chooseEmulatorFolder(Component parent) {
        Path folder = EmulatorFolder.choose(parent);
        if (folder == null) {
            return;
        }
        EmulatorFolder.remember(folder);
        JOptionPane.showMessageDialog(parent,
            "ReplicApple2Plus will use " + folder + " the next time it starts.",
            "Emulator Folder", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Chooses the slot configuration file, and remembers it once it loads
     * cleanly. It takes effect at the next Reboot, which rebuilds the
     * machine from it -- with the new configuration's own disks.
     */
    private static void chooseSlotConfiguration(Component parent, Apple2Plus.StartupFiles startup) {
        JFileChooser chooser = new JFileChooser(RememberedFiles.slotConfiguration() != null
            ? RememberedFiles.slotConfiguration().toFile() : null);
        chooser.setDialogTitle("Slot Configuration");
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        try {
            SlotCardLoader.load(file, startup.classLoader()); // the cards made only to check it are discarded
        } catch (IOException | RuntimeException | LinkageError e) {
            JOptionPane.showMessageDialog(parent, "That slot configuration can't be used:\n" + e.getMessage(),
                "Slot Configuration", JOptionPane.WARNING_MESSAGE);
            return;
        }
        RememberedFiles.rememberSlotConfiguration(file);
        JOptionPane.showMessageDialog(parent, startup.configGiven()
                ? "Remembered " + file + " for when ReplicApple2Plus starts without --config.\n"
                    + "This run was started with --config, which Reboot keeps using."
                : "The machine will use " + file + " when you press Reboot.",
            "Slot Configuration", JOptionPane.INFORMATION_MESSAGE);
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
