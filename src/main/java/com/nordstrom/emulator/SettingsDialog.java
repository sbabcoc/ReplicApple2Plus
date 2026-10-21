package com.nordstrom.emulator;

import com.nordstrom.emulator.system.SlotCardLoader;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Settings dialog: the choices that are remembered across launches
 * and take effect later, each shown with its current value and when it
 * applies, so what's chosen is visible before anything is changed.
 * <p>
 * Slot Configuration chooses a slot configuration other than the emulator
 * folder's own slots.ini (EmulatorFolder); it's checked by loading it, and
 * applied at the next Reboot. {@code --config}, when given, takes
 * precedence for this run. Emulator Folder chooses another emulator folder
 * -- an existing one, or a new one, which is set up -- for the next start,
 * when its ROM images and plug-ins are loaded.
 * <p>
 * A choice is remembered as soon as it's made, so the dialog has only a
 * Close button. Controls that act on the running machine at once -- the
 * Shift Key Mod, the VideoTerm display mode, write protection -- stay with
 * the machine, in its menus and toolbar.
 */
final class SettingsDialog {

    private SettingsDialog() {}

    /**
     * Shows the dialog, modally.
     *
     * @param parent  anchors the dialog
     * @param startup how the emulator was started: the class loader a slot
     *                configuration is checked with, and whether {@code --config}
     *                overrides it for this run
     */
    static void show(Component parent, Apple2Plus.StartupFiles startup) {
        Window owner = parent instanceof Window window ? window : SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, "Settings", Dialog.ModalityType.APPLICATION_MODAL);

        JTextField slotField = pathField();
        JLabel slotNote = note(startup.configGiven()
            ? "This run was started with --config, which Reboot keeps using. This choice applies when "
                + "ReplicApple2Plus starts without it."
            : "Takes effect at the next Reboot.");
        JTextField folderField = pathField();
        JLabel folderNote = note("Holds the ROM images, plug-ins and disks. Takes effect the next time "
            + "ReplicApple2Plus starts.");
        Runnable refresh = () -> {
            slotField.setText(slotConfigurationText());
            folderField.setText(emulatorFolderText());
        };
        refresh.run();

        JButton slotBrowse = new JButton("Browse...");
        slotBrowse.addActionListener(event -> {
            chooseSlotConfiguration(dialog, startup);
            refresh.run();
        });
        JButton folderBrowse = new JButton("Browse...");
        folderBrowse.addActionListener(event -> {
            chooseEmulatorFolder(dialog);
            refresh.run();
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
        addRow(form, 0, "Slot Configuration:", slotField, slotBrowse, slotNote);
        addRow(form, 2, "Emulator Folder:", folderField, folderBrowse, folderNote);

        JButton close = new JButton("Close");
        close.addActionListener(event -> dialog.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(close);

        dialog.getContentPane().add(form, BorderLayout.CENTER);
        dialog.getContentPane().add(buttons, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(close);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    /** One setting: its label, value and Browse... button on one line, its note on the next. */
    private static void addRow(JPanel form, int row, String label, JTextField field, JButton browse, JLabel note) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.insets = new Insets(4, 0, 0, 6);
        c.anchor = GridBagConstraints.LINE_START;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, c);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(4, 0, 0, 0);
        form.add(browse, c);
        c.gridx = 1;
        c.gridy = row + 1;
        c.gridwidth = 2;
        c.insets = new Insets(2, 0, 10, 0);
        form.add(note, c);
    }

    private static JTextField pathField() {
        JTextField field = new JTextField(40);
        field.setEditable(false); // chosen with Browse..., which checks the choice
        return field;
    }

    private static JLabel note(String text) {
        JLabel label = new JLabel("<html><div style='width:360px'>" + text + "</div></html>");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, label.getFont().getSize2D() - 1));
        return label;
    }

    /** The slot configuration Reboot will use, absent {@code --config}. */
    private static String slotConfigurationText() {
        Path chosen = RememberedFiles.slotConfiguration();
        if (chosen != null) {
            return chosen.toString();
        }
        Path folderConfiguration = EmulatorFolder.slotConfiguration();
        return folderConfiguration != null && Files.isRegularFile(folderConfiguration)
            ? folderConfiguration + " (the emulator folder's)"
            : "None: every slot empty";
    }

    /** The emulator folder the next start will use. */
    private static String emulatorFolderText() {
        Path remembered = EmulatorFolder.remembered();
        return remembered != null ? remembered.toString() : "None";
    }

    /**
     * Chooses another emulator folder -- an existing one, or a new one,
     * which is set up -- and remembers it for the next start.
     */
    private static void chooseEmulatorFolder(Component parent) {
        Path folder = EmulatorFolder.choose(parent);
        if (folder != null) {
            EmulatorFolder.remember(folder);
        }
    }

    /** Chooses the slot configuration file, and remembers it once it loads cleanly. */
    private static void chooseSlotConfiguration(Component parent, Apple2Plus.StartupFiles startup) {
        JFileChooser chooser = new JFileChooser(RememberedFiles.slotConfiguration() != null
            ? RememberedFiles.slotConfiguration().toFile() : null);
        chooser.setDialogTitle("Slot Configuration");
        if (chooser.showDialog(parent, "Use This Configuration") != JFileChooser.APPROVE_OPTION) {
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
    }
}
