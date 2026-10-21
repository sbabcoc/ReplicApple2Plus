package com.nordstrom.emulator;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The Settings dialog, in two tabs:
 * <ul>
 *   <li><b>Machine</b> -- the slot configuration a Reboot will use, edited
 *       slot by slot ({@link SlotEditorPanel}), saved explicitly;</li>
 *   <li><b>Emulator</b> -- the emulator folder, used from the next start
 *       and remembered as soon as it's chosen.</li>
 * </ul>
 * Closing the dialog with unsaved slot changes asks whether to save them.
 * Controls that act on the running machine at once -- the Shift Key Mod,
 * the VideoTerm display mode, write protection -- stay with the machine,
 * in its menus and toolbar.
 */
final class SettingsDialog {

    private SettingsDialog() {}

    /**
     * Shows the dialog, modally.
     *
     * @param parent  anchors the dialog
     * @param startup how the emulator was started: the class loader cards come from,
     *                and whether {@code --config} fixes the slot configuration for this run
     */
    static void show(Component parent, Apple2Plus.StartupFiles startup) {
        SlotEditorPanel machine;
        try {
            machine = new SlotEditorPanel(startup);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parent, "Couldn't read the slot configuration:\n" + e.getMessage(),
                "Settings", JOptionPane.ERROR_MESSAGE);
            return;
        }
        Window owner = parent instanceof Window window ? window : SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, "Settings", Dialog.ModalityType.APPLICATION_MODAL);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Machine", machine);
        tabs.addTab("Emulator", emulatorTab(dialog, machine));

        Runnable close = () -> {
            if (machine.confirmClose()) {
                dialog.dispose();
            }
        };
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(event -> close.run());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(closeButton);

        dialog.getContentPane().add(tabs, BorderLayout.CENTER);
        dialog.getContentPane().add(buttons, BorderLayout.SOUTH);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                close.run();
            }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    /** The emulator folder: shown, chosen with Browse..., and used from the next start. */
    private static JPanel emulatorTab(Component parent, SlotEditorPanel machine) {
        JTextField folderField = new JTextField(40);
        folderField.setEditable(false); // chosen with Browse..., which checks the choice
        Runnable refresh = () -> {
            Path remembered = EmulatorFolder.remembered();
            folderField.setText(remembered != null ? remembered.toString() : "None");
        };
        refresh.run();
        JButton browse = new JButton("Browse...");
        browse.addActionListener(event -> {
            Path folder = EmulatorFolder.choose(parent);
            if (folder != null) {
                EmulatorFolder.remember(folder);
                refresh.run();
                machine.configurationChoiceChanged();
            }
        });
        JLabel note = new JLabel("<html><div style='width:420px'>Holds the ROM images, plug-ins, disks and its "
            + "own slots.ini. Takes effect the next time ReplicApple2Plus starts, along with the slot "
            + "configuration chosen for that folder.</div></html>");
        note.setFont(note.getFont().deriveFont(note.getFont().getSize2D() - 1));

        JPanel tab = new JPanel(new GridBagLayout());
        tab.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.FIRST_LINE_START;
        c.insets = new Insets(0, 0, 0, 6);
        tab.add(new JLabel("Emulator Folder:"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        tab.add(folderField, c);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(0, 0, 0, 0);
        tab.add(browse, c);
        c.gridx = 1;
        c.gridy = 1;
        c.gridwidth = 2;
        c.weighty = 1;
        c.insets = new Insets(6, 0, 0, 0);
        tab.add(note, c);
        return tab;
    }
}
