package com.nordstrom.emulator;

import com.nordstrom.emulator.expansion.Disk2Controller;
import com.nordstrom.emulator.system.RemovableMediaDrive;

import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.io.IOException;

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
     * @return the built menu, ready to add to a {@code JMenuBar}
     */
    static JMenu build(Disk2Controller disk, Component parent) {
        JMenu menu = new JMenu("Disk");
        menu.add(driveMenuItems(disk.removableDrives().get(0), "Drive 1", parent));
        menu.addSeparator();
        menu.add(driveMenuItems(disk.removableDrives().get(1), "Drive 2", parent));
        return menu;
    }

    /**
     * Since a single {@link JMenu} can only be added once, this returns
     * a small submenu per drive rather than trying to flatten both
     * drives' items into one menu with ambiguous labels.
     */
    private static JMenu driveMenuItems(RemovableMediaDrive drive, String label, Component parent) {
        JMenu driveMenu = new JMenu(label);

        JMenuItem insert = new JMenuItem("Insert...");
        insert.addActionListener(event -> promptAndInsert(drive, parent));
        driveMenu.add(insert);

        JMenuItem eject = new JMenuItem("Eject");
        eject.addActionListener(event -> drive.eject());
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
     */
    static void promptAndInsert(RemovableMediaDrive drive, Component parent) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter(
            "Disk images (*.woz, *.dsk, *.do)", "woz", "dsk", "do"));
        int result = chooser.showOpenDialog(parent);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            drive.insert(chooser.getSelectedFile().toPath());
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            JOptionPane.showMessageDialog(parent, "Could not load disk image:\n" + e.getMessage(),
                "Disk Error", JOptionPane.ERROR_MESSAGE);
        }
    }
}
