package com.nordstrom.emulator;

import com.nordstrom.emulator.expansion.Disk2Controller;
import com.nordstrom.emulator.system.RemovableMediaDrive;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.FileChooserUI;
import javax.swing.plaf.basic.BasicFileChooserUI;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.prefs.Preferences;

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
 * "Drive 1: diskname.woz [Writable]", "Drive 1: diskname.woz [Protected]",
 * or "Drive 1 (empty)" -- visible without even opening the submenu, kept
 * current via a {@link MenuListener} on the top-level "Disk" menu that
 * refreshes both drives' titles and write-protect controls each time
 * the menu is opened. Swing menus don't update themselves
 * automatically when the underlying state changes elsewhere (an
 * insert from this same menu, say) -- refreshing on {@code
 * menuSelected} is the standard way to keep one "live" without wiring
 * a listener into every place that could change drive state.
 * <p>
 * Drive 1's Insert... dialog has a second confirm button, Insert and
 * Reboot, since drive 1 is the drive the Autostart ROM boots from:
 * booting a different disk is then one step, not an insert followed by a
 * separate Reboot.
 */
final class DiskMenu {

    private DiskMenu() {}

    /**
     * Where the last disk-image dialog (Insert or New) left off, so the
     * next one opens there instead of the home folder. Stored with the
     * JDK's own user preferences (on macOS, the
     * {@code com.apple.java.util.prefs} plist under
     * {@code ~/Library/Preferences}), so it carries across launches, not
     * just within a session. Shared by both dialogs: a disk just created
     * is usually inserted again later from the same place.
     */
    private static final Preferences PREFS = Preferences.userNodeForPackage(DiskMenu.class);
    private static final String LAST_DIRECTORY_KEY = "lastDiskImageDirectory";

    /**
     * Folder of the disk image the configuration loaded at launch (drive
     * 1's if it has one, otherwise drive 2's), used when nothing is
     * remembered yet -- see {@link #chooserAtLastDirectory}. Captured
     * once, by the first {@link #build}: that runs after the config's
     * {@code driveN=} images are inserted, but every later build (one per
     * reboot) sees whatever disks were inserted since, not the
     * configuration's. Null if the configuration named no image.
     */
    private static java.io.File configuredImageDirectory;
    private static boolean configuredImageDirectoryCaptured;

    /** See {@link #configuredImageDirectory}. Only the first call has any effect. */
    private static void captureConfiguredImageDirectory(List<RemovableMediaDrive> drives) {
        if (configuredImageDirectoryCaptured) {
            return;
        }
        configuredImageDirectoryCaptured = true;
        for (RemovableMediaDrive drive : drives) {
            java.nio.file.Path parent = drive.currentImagePath().map(java.nio.file.Path::getParent).orElse(null);
            if (parent != null) {
                configuredImageDirectory = parent.toAbsolutePath().toFile();
                return;
            }
        }
    }

    /**
     * A chooser that starts in the remembered directory; failing that
     * (nothing remembered yet, or it no longer exists), in the folder of
     * the disk image the configuration loaded; failing that, in the
     * default (the home folder).
     */
    private static JFileChooser chooserAtLastDirectory() {
        String last = PREFS.get(LAST_DIRECTORY_KEY, null);
        java.io.File remembered = last == null ? null : new java.io.File(last);
        if (remembered != null && remembered.isDirectory()) {
            return new JFileChooser(remembered);
        }
        java.io.File configured = configuredImageDirectory;
        return new JFileChooser(configured != null && configured.isDirectory() ? configured : null);
    }

    /**
     * Remembers wherever {@code chooser} was left -- after a cancel as
     * well as a confirm, since navigating somewhere and backing out
     * usually still means "this is where my disks are." The backing
     * store write is asynchronous and best-effort by design; a failure
     * there costs only this convenience, never a disk operation.
     */
    private static void rememberDirectory(JFileChooser chooser) {
        java.io.File dir = chooser.getCurrentDirectory();
        if (dir != null) {
            PREFS.put(LAST_DIRECTORY_KEY, dir.getAbsolutePath());
        }
    }

    /**
     * Builds a "Disk" menu wired to {@code disk}'s two drives.
     *
     * @param disk the controller whose drives this menu operates on
     * @param parent the component to anchor dialogs to (typically the main frame)
     * @param emulationThread runs each insert, eject, and write-protect change on the
     *                        emulation thread -- menu actions arrive on the Swing event
     *                        thread, and these change several fields the running machine
     *                        reads, so they must not happen concurrently with a tick
     * @param onReboot runs a Reboot, for drive 1's Insert and Reboot; called on the event thread
     * @return the built menu, ready to add to a {@code JMenuBar}
     */
    static JMenu build(Disk2Controller disk, Component parent, Executor emulationThread, Runnable onReboot) {
        captureConfiguredImageDirectory(disk.removableDrives());
        JMenu menu = new JMenu("Disk");
        RemovableMediaDrive drive1 = disk.removableDrives().get(0);
        RemovableMediaDrive drive2 = disk.removableDrives().get(1);
        DriveMenu driveMenu1 = driveMenuItems(drive1, "Drive 1", parent, emulationThread, onReboot);
        DriveMenu driveMenu2 = driveMenuItems(drive2, "Drive 2", parent, emulationThread, null);
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
    private record DriveMenu(JMenu menu, RemovableMediaDrive drive, String label,
                             ButtonGroup protection, JRadioButtonMenuItem writable, JRadioButtonMenuItem writeProtected) {

        /**
         * Updates this drive's submenu title and write-protect controls
         * to match its actual current state. Safe to call anytime --
         * reads {@code drive}'s own current state fresh each time,
         * never caching anything stale.
         * <p>
         * The title names the protection state either way, never only
         * when protected: a state shown solely by something's absence is
         * easy to misread.
         */
        void refresh() {
            boolean present = drive.isPresent();
            writable.setEnabled(present);
            writeProtected.setEnabled(present);
            if (!present) {
                menu.setText(label + " (empty)");
                protection.clearSelection(); // neither state applies to an empty drive
                return;
            }
            String name = drive.currentImagePath().orElseThrow().getFileName().toString();
            boolean protectedNow = drive.isWriteProtected();
            menu.setText(label + ": " + name + (protectedNow ? " [Protected]" : " [Writable]"));
            (protectedNow ? writeProtected : writable).setSelected(true);
        }
    }

    /**
     * Since a single {@link JMenu} can only be added once, this returns
     * a small submenu per drive rather than trying to flatten both
     * drives' items into one menu with ambiguous labels.
     *
     * @param onReboot runs a Reboot, offered with Insert; null for a drive that isn't booted from
     */
    private static DriveMenu driveMenuItems(RemovableMediaDrive drive, String label, Component parent,
                                             Executor emulationThread, Runnable onReboot) {
        JMenu driveMenu = new JMenu(label);

        // A radio pair rather than one checkbox, so both states are always
        // named: "Writable" gives "Protected" its context.
        JRadioButtonMenuItem writable = new JRadioButtonMenuItem("Writable");
        JRadioButtonMenuItem writeProtected = new JRadioButtonMenuItem("Protected");
        ButtonGroup protection = new ButtonGroup();
        protection.add(writable);
        protection.add(writeProtected);
        driveMenu.add(writable);
        driveMenu.add(writeProtected);
        driveMenu.addSeparator();

        JMenuItem newBlank = new JMenuItem("New...");
        newBlank.addActionListener(event -> promptAndCreateBlank(drive, parent, emulationThread));
        driveMenu.add(newBlank);

        JMenuItem insert = new JMenuItem("Insert...");
        insert.addActionListener(event -> promptAndInsert(drive, label, parent, emulationThread, onReboot));
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

        DriveMenu result = new DriveMenu(driveMenu, drive, label, protection, writable, writeProtected);

        // Posted to the emulation thread like insert/eject, since this also
        // touches drive state the running machine reads. Choosing the item
        // that's already selected still fires an action event, so the
        // drive's actual state is compared first -- otherwise that would
        // rewrite the host file for no change. If the change fails (e.g.
        // this process can't change the host file's permission), the
        // selection is put back on the event thread: the click already
        // moved it before this listener ran, so a failure needs to visibly
        // undo that, not just report an error while showing a state that
        // was never actually reached.
        java.awt.event.ActionListener onProtectionChoice = event -> {
            boolean requested = writeProtected.isSelected();
            emulationThread.execute(() -> {
                if (!drive.isPresent() || drive.isWriteProtected() == requested) {
                    return;
                }
                try {
                    drive.setWriteProtected(requested);
                } catch (IOException | IllegalStateException e) {
                    EventQueue.invokeLater(() -> {
                        (requested ? writable : writeProtected).setSelected(true);
                        JOptionPane.showMessageDialog(parent,
                            "Could not change write-protect state:\n" + e.getMessage(),
                            "Disk Error", JOptionPane.ERROR_MESSAGE);
                    });
                }
            });
        };
        writable.addActionListener(onProtectionChoice);
        writeProtected.addActionListener(onProtectionChoice);

        return result;
    }

    /**
     * Shows a file chooser (filtered to {@code .woz}/{@code .dsk}/
     * {@code .do}/{@code .po}) and inserts whatever's chosen into
     * {@code drive}, or does nothing if the chooser is dismissed. A load
     * failure (bad format, checksum mismatch) is reported via a dialog
     * rather than left to the caller. Package-visible so {@code Apple2Plus}
     * can reuse this exact logic to prompt for an initial disk at startup,
     * rather than duplicating it.
     * <p>
     * With {@code onReboot} given, the dialog also offers Insert and
     * Reboot: the insert runs on the emulation thread first, and only once
     * it has succeeded is the Reboot posted to the event thread -- so the
     * new disk is in the drive whose media the Reboot carries forward, and a
     * disk that fails to load is reported instead of rebooting into the
     * old one.
     *
     * @param drive the drive to insert into
     * @param label the drive's name, for the dialog's title
     * @param parent the component to anchor dialogs to
     * @param emulationThread runs the insert itself; pass {@code Runnable::run} when
     *                        emulation is not running yet (the startup prompt)
     * @param onReboot runs a Reboot, for Insert and Reboot; null to offer only Insert
     */
    static void promptAndInsert(RemovableMediaDrive drive, String label, Component parent,
                                Executor emulationThread, Runnable onReboot) {
        JFileChooser chooser = chooserAtLastDirectory();
        chooser.setFileFilter(new FileNameExtensionFilter(
            "Disk images (*.woz, *.dsk, *.do, *.po)", "woz", "dsk", "do", "po"));
        InsertChoice choice = showInsertDialog(chooser, "Insert Disk in " + label, parent, onReboot != null);
        rememberDirectory(chooser);
        if (choice == InsertChoice.CANCEL) {
            return;
        }
        java.nio.file.Path image = chooser.getSelectedFile().toPath();
        emulationThread.execute(() -> {
            try {
                drive.insert(image);
            } catch (IOException | IllegalArgumentException | IllegalStateException e) {
                showDiskError(parent, "load disk image", e);
                return;
            }
            if (choice == InsertChoice.INSERT_AND_REBOOT) {
                EventQueue.invokeLater(onReboot);
            }
        });
    }

    /** How the Insert dialog was closed. */
    private enum InsertChoice { INSERT, INSERT_AND_REBOOT, CANCEL }

    /**
     * Shows {@code chooser} in a dialog of its own, with its control buttons
     * replaced by Insert (and, if {@code offerReboot}, Insert and Reboot) and
     * Cancel -- a {@link JFileChooser} has only one approve button of its own.
     * Approving the chooser directly (double-clicking a file, or Enter in its
     * name field) counts as Insert.
     * <p>
     * A button doesn't take the selection itself: it asks the chooser's own
     * UI to approve, which commits a typed name and opens a selected folder
     * instead of choosing it, exactly as the chooser's own button would.
     * The buttons are ordered as {@link JOptionPane} orders them under the
     * current look and feel ({@code OptionPane.isYesLast}), so the confirm
     * button is on the side the platform puts it.
     */
    private static InsertChoice showInsertDialog(JFileChooser chooser, String title, Component parent,
                                                 boolean offerReboot) {
        Window owner = parent == null ? null
            : parent instanceof Window window ? window : SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        chooser.setControlButtonsAreShown(false);

        InsertChoice[] requested = {InsertChoice.INSERT}; // what an approval means: the button that asked for it
        InsertChoice[] result = {InsertChoice.CANCEL};
        chooser.addActionListener(event -> {
            if (JFileChooser.APPROVE_SELECTION.equals(event.getActionCommand())) {
                result[0] = requested[0];
            }
            dialog.dispose(); // approve or cancel, the dialog is done
        });

        JButton insert = new JButton("Insert");
        insert.addActionListener(event -> approve(chooser, requested, InsertChoice.INSERT));
        JButton insertAndReboot = new JButton("Insert and Reboot");
        insertAndReboot.addActionListener(event -> approve(chooser, requested, InsertChoice.INSERT_AND_REBOOT));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(event -> chooser.cancelSelection());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 6, 6, 6));
        if (UIManager.getBoolean("OptionPane.isYesLast")) {
            buttons.add(cancel);
            if (offerReboot) {
                buttons.add(insertAndReboot);
            }
            buttons.add(insert);
        } else {
            buttons.add(insert);
            if (offerReboot) {
                buttons.add(insertAndReboot);
            }
            buttons.add(cancel);
        }

        dialog.getContentPane().add(chooser, BorderLayout.CENTER);
        dialog.getContentPane().add(buttons, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(insert);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true); // modal: returns once the dialog is closed
        return result[0];
    }

    /** Approves {@code chooser} through its own UI, as {@code choice}. */
    private static void approve(JFileChooser chooser, InsertChoice[] requested, InsertChoice choice) {
        requested[0] = choice;
        FileChooserUI ui = chooser.getUI();
        if (ui instanceof BasicFileChooserUI basic) {
            basic.getApproveSelectionAction().actionPerformed(
                new ActionEvent(chooser, ActionEvent.ACTION_PERFORMED, JFileChooser.APPROVE_SELECTION));
        } else {
            java.io.File selected = chooser.getSelectedFile();
            if (selected != null && !selected.isDirectory()) {
                chooser.approveSelection();
            }
        }
        requested[0] = InsertChoice.INSERT; // a later approval made by the chooser itself is a plain Insert
    }

    /**
     * {@code name} with its disk-image extension ({@code .woz}, {@code .dsk}
     * or {@code .do}, any case) replaced by {@code extension}, or with
     * {@code extension} appended if it has none; a blank name becomes
     * {@code untitled} plus the extension.
     */
    static String withDiskExtension(String name, String extension) {
        String base = name == null ? "" : name.trim();
        String lower = base.toLowerCase(java.util.Locale.ROOT);
        for (String known : new String[] {".woz", ".dsk", ".do", ".po"}) {
            if (lower.endsWith(known)) {
                base = base.substring(0, base.length() - known.length());
                break;
            }
        }
        return (base.isEmpty() ? "untitled" : base) + extension;
    }

    /**
     * What's currently in a save dialog's name field. {@link JFileChooser}
     * has no API for this -- {@code getSelectedFile()} isn't updated by
     * typing until the dialog is confirmed -- so this reads the dialog's
     * own text field, the only one a save dialog has, falling back to the
     * selected file if no text field is found.
     */
    private static String typedFileName(JFileChooser chooser) {
        javax.swing.JTextField field = findTextField(chooser);
        if (field != null) {
            return field.getText();
        }
        java.io.File selected = chooser.getSelectedFile();
        return selected == null ? "" : selected.getName();
    }

    private static javax.swing.JTextField findTextField(java.awt.Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof javax.swing.JTextField field) {
                return field;
            }
            if (child instanceof java.awt.Container nested) {
                javax.swing.JTextField found = findTextField(nested);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Shows a save dialog and creates a new, blank, formattable disk
     * image at the chosen location and name, inserted directly into
     * {@code drive}. The format is WOZ, DSK (DOS order) or PO (ProDOS
     * order): a typed extension decides it, and with none the selected
     * file type does.
     *
     * @param drive the drive to create the new disk into
     * @param parent the component to anchor dialogs to
     * @param emulationThread runs the actual creation -- this also touches drive
     *                        state the running machine reads, the same as insert/eject
     */
    private static void promptAndCreateBlank(RemovableMediaDrive drive, Component parent, Executor emulationThread) {
        JFileChooser chooser = chooserAtLastDirectory();
        FileNameExtensionFilter woz = new FileNameExtensionFilter("WOZ disk images (*.woz)", "woz");
        FileNameExtensionFilter dsk = new FileNameExtensionFilter("DSK disk images, DOS order (*.dsk, *.do)", "dsk", "do");
        FileNameExtensionFilter po = new FileNameExtensionFilter("PO disk images, ProDOS order (*.po)", "po");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.addChoosableFileFilter(woz);
        chooser.addChoosableFileFilter(dsk);
        chooser.addChoosableFileFilter(po);
        chooser.setFileFilter(woz);
        chooser.setSelectedFile(new java.io.File("untitled.woz"));
        // Switching the type swaps the name's extension to match, keeping whatever name was typed.
        chooser.addPropertyChangeListener(JFileChooser.FILE_FILTER_CHANGED_PROPERTY, event -> {
            Object filter = event.getNewValue();
            String extension = filter == dsk ? ".dsk" : filter == po ? ".po" : ".woz";
            chooser.setSelectedFile(new java.io.File(withDiskExtension(typedFileName(chooser), extension)));
        });
        int result = chooser.showSaveDialog(parent);
        rememberDirectory(chooser);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        // A typed extension decides the format; with none, the selected filter does.
        java.io.File chosen = chooser.getSelectedFile();
        String lower = chosen.getName().toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".woz") && !lower.endsWith(".dsk") && !lower.endsWith(".do") && !lower.endsWith(".po")) {
            Object filter = chooser.getFileFilter();
            String extension = filter == dsk ? ".dsk" : filter == po ? ".po" : ".woz";
            chosen = new java.io.File(chosen.getParentFile(), chosen.getName() + extension);
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
