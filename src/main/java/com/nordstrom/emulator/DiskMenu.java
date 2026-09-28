package com.nordstrom.emulator;

import com.nordstrom.emulator.expansion.Disk2Controller;
import com.nordstrom.emulator.system.RemovableMediaDrive;

import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.EventQueue;
import java.io.IOException;
import java.util.concurrent.Executor;

/**
 * Builds the "Disk" menu for runtime insert/eject on both of a
 * {@link Disk2Controller}'s drives -- matching real hardware, where
 * swapping a floppy is exactly the same operation whether the machine
 * just booted or has been running for an hour. Nothing here duplicates
 * {@link Disk2Controller#configure}'s own boot-time loading; both call
 * the same {@link RemovableMediaDrive#insert}, which is the entire
 * point {@code SlotCardLoader}'s own Javadoc makes about there being no
 * separate "initial media" path.
 * <p>
 * "Insert" always replaces whatever's currently in that drive, the same
 * way a real floppy swap works -- no separate "eject first" step is
 * required. "Eject" is left enabled even when a drive is already
 * empty; calling it then is harmless (real hardware doesn't complain
 * either).
 */
final class DiskMenu {

    private DiskMenu() {}

    /**
     * Builds a "Disk" menu wired to {@code disk}'s two drives.
     *
     * @param disk the controller whose drives this menu operates on
     * @param parent the component to anchor dialogs to (typically the main frame)
     * @param emulationThread runs each insert and eject on the emulation thread --
     *                        menu actions arrive on the Swing event thread, and a
     *                        disk swap changes several fields the running machine
     *                        reads, so it must not happen concurrently with a tick
     * @return the built menu, ready to add to a {@code JMenuBar}
     */
    static JMenu build(Disk2Controller disk, Component parent, Executor emulationThread) {
        JMenu menu = new JMenu("Disk");
        menu.add(driveMenuItems(disk.removableDrives().get(0), "Drive 1", parent, emulationThread));
        menu.addSeparator();
        menu.add(driveMenuItems(disk.removableDrives().get(1), "Drive 2", parent, emulationThread));
        return menu;
    }

    /**
     * Since a single {@link JMenu} can only be added once, this returns
     * a small submenu per drive rather than trying to flatten both
     * drives' items into one menu with ambiguous labels.
     */
    private static JMenu driveMenuItems(RemovableMediaDrive drive, String label, Component parent,
                                        Executor emulationThread) {
        JMenu driveMenu = new JMenu(label);

        JMenuItem insert = new JMenuItem("Insert...");
        insert.addActionListener(event -> promptAndInsert(drive, parent, emulationThread));
        driveMenu.add(insert);

        JMenuItem eject = new JMenuItem("Eject");
        eject.addActionListener(event -> emulationThread.execute(drive::eject));
        driveMenu.add(eject);

        return driveMenu;
    }

    /**
     * Shows a file chooser (filtered to {@code .woz}/{@code .dsk}/
     * {@code .do}) and inserts
     * whatever's chosen into {@code drive}, or does nothing if the
     * chooser is dismissed. A load failure (bad format, checksum
     * mismatch) is reported via a dialog rather than left to the
     * caller. Package-visible so {@code Apple2Plus} can reuse this
     * exact logic to prompt for an initial disk at startup, rather than
     * duplicating it.
     *
     * @param drive the drive to insert into
     * @param parent the component to anchor dialogs to
     * @param emulationThread runs the insert itself; pass {@code Runnable::run} when
     *                        emulation is not running yet (the startup prompt)
     */
    static void promptAndInsert(RemovableMediaDrive drive, Component parent, Executor emulationThread) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter(
            "Disk images (*.woz, *.dsk, *.do)", "woz", "dsk", "do"));
        int result = chooser.showOpenDialog(parent);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        java.nio.file.Path image = chooser.getSelectedFile().toPath();
        emulationThread.execute(() -> {
            try {
                drive.insert(image);
            } catch (IOException | IllegalArgumentException | IllegalStateException e) {
                showLoadError(parent, e);
            }
        });
    }

    /** The insert may be running on the emulation thread; dialogs belong on the event thread. */
    private static void showLoadError(Component parent, Exception e) {
        Runnable show = () -> JOptionPane.showMessageDialog(parent,
            "Could not load disk image:\n" + e.getMessage(), "Disk Error", JOptionPane.ERROR_MESSAGE);
        if (EventQueue.isDispatchThread()) {
            show.run();
        } else {
            EventQueue.invokeLater(show);
        }
    }
}
