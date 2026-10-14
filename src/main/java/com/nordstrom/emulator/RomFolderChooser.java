package com.nordstrom.emulator;

import com.nordstrom.emulator.system.RomFiles;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import java.awt.Component;
import java.nio.file.Path;
import java.util.List;

/**
 * Asks the user for a ROM folder ({@link RomFiles}): wherever the emulator
 * needs one -- its first start, File &gt; ROM Folder..., and recovering
 * from a ROM image that's missing or corrupt.
 */
final class RomFolderChooser {

    private RomFolderChooser() {
    }

    /**
     * Asks for a folder until the user chooses one holding the base ROM
     * images, or cancels. Doesn't remember it: the caller decides that.
     *
     * @param parent anchors the dialogs; may be null before the main window exists
     * @return the folder, or null if the user cancelled
     */
    static Path choose(Component parent) {
        Path current = RomFiles.directory();
        while (true) {
            JFileChooser chooser = new JFileChooser(current != null ? current.toFile() : null);
            chooser.setDialogTitle("ROM Folder");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (current != null) {
                chooser.setSelectedFile(current.toFile());
            }
            if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
                return null;
            }
            Path folder = chooser.getSelectedFile().toPath();
            List<String> missing = RomFiles.missingFrom(folder);
            if (missing.isEmpty()) {
                return folder;
            }
            JOptionPane.showMessageDialog(parent,
                folder + " doesn't hold " + String.join(" or ", missing)
                    + ", so it can't be the ROM folder. (README.md lists each file.)",
                "ROM Folder", JOptionPane.WARNING_MESSAGE);
            current = folder;
        }
    }
}
