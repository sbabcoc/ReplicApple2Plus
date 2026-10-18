package com.nordstrom.emulator;

import java.awt.Component;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;

/**
 * The folder holding everything a machine needs that isn't part of the
 * emulator itself, chosen by the user and remembered:
 * <pre>
 *   (emulator folder)/
 *     slots.ini    the slot configuration
 *     roms/        the ROM images, which aren't distributed with the emulator
 *     plugins/     plug-in card jars, such as SoftCard2
 *     disks/       disk images, for convenience
 * </pre>
 * The user chooses it on first start -- an existing folder, or a new one.
 * A folder that already holds {@code slots.ini} is taken as set up, and
 * used as it is: a hierarchy prepared beforehand, or carried over from
 * another machine. A folder without one is set up: the subfolders are
 * created, with a starter {@code slots.ini}. Relative paths in
 * {@code slots.ini} are relative to this folder (SlotCardLoader), so a disk
 * in {@code disks/} is just {@code disks/name.dsk}. Each command-line
 * option, when given, takes the place of its part of the folder.
 */
final class EmulatorFolder {

    /** The configuration file whose presence marks a folder as set up. */
    static final String CONFIGURATION = "slots.ini";

    private static final String REMEMBERED = "emulatorFolder";

    private static final String STARTER_CONFIGURATION = String.join(System.lineSeparator(),
        "# ReplicApple2Plus slot configuration -- see the README for every card",
        "# and its settings. Relative paths, like the drive1 example below, are",
        "# relative to this folder.",
        "",
        "[0]",
        "type=languageCard",
        "",
        "[6]",
        "type=disk2",
        "# drive1=disks/your-disk.dsk",
        "");

    private static volatile Path root;

    private EmulatorFolder() {
    }

    /** @return the emulator folder in use this run, or null if there is none */
    static Path path() {
        return root;
    }

    /** @param folder the emulator folder to use this run, or null for none */
    static void use(Path folder) {
        root = folder;
    }

    /** @return the ROM folder within it, or null if there's no emulator folder */
    static Path roms() {
        return root != null ? root.resolve("roms") : null;
    }

    /** @return the plugins folder within it, or null if there's no emulator folder */
    static Path plugins() {
        return root != null ? root.resolve("plugins") : null;
    }

    /** @return its slot configuration, or null if there's no emulator folder */
    static Path slotConfiguration() {
        return root != null ? root.resolve(CONFIGURATION) : null;
    }

    /** @return the folder chosen before, if it still exists; else null */
    static Path remembered() {
        String remembered = preferences().get(REMEMBERED, null);
        return remembered != null && Files.isDirectory(Path.of(remembered)) ? Path.of(remembered) : null;
    }

    static void remember(Path folder) {
        preferences().put(REMEMBERED, folder.toAbsolutePath().toString());
    }

    /**
     * @param folder a candidate emulator folder
     * @return true if it's already set up: it holds {@code slots.ini}
     */
    static boolean isSetUp(Path folder) {
        return Files.isRegularFile(folder.resolve(CONFIGURATION));
    }

    /**
     * Sets up a folder that isn't set up yet: creates it if need be, its
     * subfolders, and the starter {@code slots.ini}. A folder that's already
     * set up is left exactly as it is.
     *
     * @param folder the emulator folder
     * @return true if it was set up just now; false if it already was
     * @throws IOException if it can't be created
     */
    static boolean setUp(Path folder) throws IOException {
        if (isSetUp(folder)) {
            return false;
        }
        for (String part : new String[] {"roms", "plugins", "disks"}) {
            Files.createDirectories(folder.resolve(part));
        }
        Files.writeString(folder.resolve(CONFIGURATION), STARTER_CONFIGURATION);
        return true;
    }

    /**
     * Asks the user for an emulator folder -- an existing one, or a new one,
     * which the dialog can create, or name to be created -- and sets it up
     * if it isn't. Doesn't remember it: the caller decides that.
     *
     * @param parent anchors the dialogs; may be null before the main window exists
     * @return the folder, set up; or null if the user cancelled
     */
    static Path choose(Component parent) {
        Path current = root != null ? root : remembered();
        JFileChooser chooser = new JFileChooser(current != null ? current.getParent().toFile()
            : new File(System.getProperty("user.home")));
        chooser.setDialogTitle("Emulator Folder: an existing one, or a new one");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setSelectedFile(current != null ? current.toFile()
            : new File(System.getProperty("user.home"), "ReplicApple2Plus"));
        while (chooser.showDialog(parent, "Use This Folder") == JFileChooser.APPROVE_OPTION) {
            Path folder = chooser.getSelectedFile().toPath();
            try {
                setUp(folder);
                return folder;
            } catch (IOException e) {
                JOptionPane.showMessageDialog(parent, "Could not set up " + folder + ":\n" + e.getMessage(),
                    "Emulator Folder", JOptionPane.WARNING_MESSAGE);
            }
        }
        return null;
    }

    /**
     * Opens a folder in the platform's file manager, where the user can
     * copy files into it; if the platform can't, says where it is.
     *
     * @param parent anchors the message; may be null
     * @param folder the folder
     */
    static void open(Component parent, Path folder) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(folder.toFile());
                return;
            }
        } catch (IOException | UnsupportedOperationException e) {
            // fall through to saying where it is
        }
        JOptionPane.showMessageDialog(parent, "The folder is " + folder, "ReplicApple2Plus",
            JOptionPane.INFORMATION_MESSAGE);
    }

    private static Preferences preferences() {
        return Preferences.userNodeForPackage(EmulatorFolder.class);
    }
}
