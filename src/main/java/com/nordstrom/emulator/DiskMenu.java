package com.nordstrom.emulator;

import com.nordstrom.emulator.expansion.Disk2Controller;
import com.nordstrom.emulator.system.RemovableMediaDrive;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
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
 * <p>
 * Each drive's own submenu title doubles as its status display --
 * "Drive 1: diskname.woz" or "Drive 1: diskname.woz [Protected]" or
 * "Drive 1 (empty)" -- visible without even opening the submenu, kept
 * current via a {@link MenuListener} on the top-level "Disk" menu that
 * refreshes both drives' titles and write-protect checkboxes each time
 * the menu is opened. Swing menus don't update themselves
 * automatically when the underlying state changes elsewhere (an
 * insert from this same menu, say) -- refreshing on {@code
 * menuSelected} is the standard way to keep one "live" without wiring
 * a listener into every place that could change drive state.
 */
final class DiskMenu {

    private DiskMenu() {}

    /**
     * Builds a "Disk" menu wired to {@code disk}'s two drives.
     *
     * @param disk the controller whose drives this menu operates on
     * @param parent the component to anchor dialogs to (typically the main frame)
     * @param emulationThread runs each insert, eject, and write-protect change on the
     *                        emulation thread -- menu actions arrive on the Swing event
     *                        thread, and these change several fields the running machine
     *                        reads, so they must not happen concurrently with a tick
     * @return the built menu, ready to add to a {@code JMenuBar}
     */
    static JMenu build(Disk2Controller disk, Component parent, Executor emulationThread) {
        JMenu menu = new JMenu("Disk");
        RemovableMediaDrive drive1 = disk.removableDrives().get(0);
        RemovableMediaDrive drive2 = disk.removableDrives().get(1);
        DriveMenu driveMenu1 = driveMenuItems(drive1, "Drive 1", parent, emulationThread);
        DriveMenu driveMenu2 = driveMenuItems(drive2, "Drive 2", parent, emulationThread);
        menu.add(driveMenu1.menu());
        menu.addSeparator();
        menu.add(driveMenu2.menu());

        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                driveMenu1.refresh();
                driveMenu2.refresh();
            }

            @Override
            public void menuDeselected(MenuEvent e) { /* nothing to do */ }

            @Override
            public void menuCanceled(MenuEvent e) { /* nothing to do */ }
        });

        return menu;
    }

    /** One drive's submenu, plus the pieces {@link #refresh} needs to keep it current. */
    private record DriveMenu(JMenu menu, RemovableMediaDrive drive, String label, JCheckBoxMenuItem writeProtect) {

        /**
         * Updates this drive's submenu title and write-protect checkbox
         * to match its actual current state. Safe to call anytime --
         * reads {@code drive}'s own current state fresh each time,
         * never caching anything stale.
         */
        void refresh() {
            boolean present = drive.isPresent();
            if (!present) {
                menu.setText(label + " (empty)");
            } else {
                String name = drive.currentImagePath().orElseThrow().getFileName().toString();
                boolean protectedNow = drive.isWriteProtected();
                menu.setText(label + ": " + name + (protectedNow ? " [Protected]" : ""));
            }
            writeProtect.setEnabled(present);
            writeProtect.setSelected(present && drive.isWriteProtected());
        }
    }

    /**
     * Since a single {@link JMenu} can only be added once, this returns
     * a small submenu per drive rather than trying to flatten both
     * drives' items into one menu with ambiguous labels.
     */
    private static DriveMenu driveMenuItems(RemovableMediaDrive drive, String label, Component parent,
                                             Executor emulationThread) {
        JMenu driveMenu = new JMenu(label);

        JCheckBoxMenuItem writeProtect = new JCheckBoxMenuItem("Write-Protected");
        driveMenu.add(writeProtect);
        driveMenu.addSeparator();

        JMenuItem newBlank = new JMenuItem("New...");
        newBlank.addActionListener(event -> promptAndCreateBlank(drive, parent, emulationThread));
        driveMenu.add(newBlank);

        JMenuItem insert = new JMenuItem("Insert...");
        insert.addActionListener(event -> promptAndInsert(drive, parent, emulationThread));
        driveMenu.add(insert);

        JMenuItem eject = new JMenuItem("Eject");
        eject.addActionListener(event -> emulationThread.execute(() -> {
            try {
                drive.eject();
            } catch (IOException e) {
                showDiskError(parent, "eject disk (its unpersisted writes could not be saved)", e);
            }
        }));
        driveMenu.add(eject);

        DriveMenu result = new DriveMenu(driveMenu, drive, label, writeProtect);

        // Posted to the emulation thread like insert/eject, since this also
        // touches drive state the running machine reads. If it fails (e.g.
        // this process can't change the host file's permission), the
        // checkbox's own visual state is reverted on the event thread --
        // its click already flipped it before this listener ran, so a
        // failure needs to visibly undo that, not just report an error
        // while leaving the checkbox showing a state that was never
        // actually reached.
        writeProtect.addActionListener(event -> {
            boolean requested = writeProtect.isSelected();
            emulationThread.execute(() -> {
                try {
                    drive.setWriteProtected(requested);
                } catch (IOException | IllegalStateException e) {
                    EventQueue.invokeLater(() -> {
                        writeProtect.setSelected(!requested);
                        JOptionPane.showMessageDialog(parent,
                            "Could not change write-protect state:\n" + e.getMessage(),
                            "Disk Error", JOptionPane.ERROR_MESSAGE);
                    });
                }
            });
        });

        return result;
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
                showDiskError(parent, "load disk image", e);
            }
        });
    }

    /**
     * Shows a save dialog and creates a new, blank, formattable disk
     * image at the chosen location and name, inserted directly into
     * {@code drive}. Only WOZ is offered as a format -- this project's
     * DSK write support doesn't exist yet (see
     * {@link RemovableMediaDrive#insertNewBlankDisk}'s own Javadoc), so
     * a blank DSK file would be a format nothing could actually write
     * to, not a real option to present as equivalent to WOZ.
     *
     * @param drive the drive to create the new disk into
     * @param parent the component to anchor dialogs to
     * @param emulationThread runs the actual creation -- this also touches drive
     *                        state the running machine reads, the same as insert/eject
     */
    private static void promptAndCreateBlank(RemovableMediaDrive drive, Component parent, Executor emulationThread) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("WOZ disk images (*.woz)", "woz"));
        chooser.setSelectedFile(new java.io.File("untitled.woz"));
        int result = chooser.showSaveDialog(parent);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        java.io.File chosen = chooser.getSelectedFile();
        if (!chosen.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".woz")) {
            chosen = new java.io.File(chosen.getParentFile(), chosen.getName() + ".woz");
        }
        java.nio.file.Path path = chosen.toPath();
        emulationThread.execute(() -> {
            try {
                drive.insertNewBlankDisk(path);
            } catch (IOException e) {
                showDiskError(parent, "create new disk image", e);
            }
        });
    }

    /** The triggering action may be running on the emulation thread; dialogs belong on the event thread. */
    private static void showDiskError(Component parent, String action, Exception e) {
        Runnable show = () -> JOptionPane.showMessageDialog(parent,
            "Could not " + action + ":\n" + e.getMessage(), "Disk Error", JOptionPane.ERROR_MESSAGE);
        if (EventQueue.isDispatchThread()) {
            show.run();
        } else {
            EventQueue.invokeLater(show);
        }
    }
}
