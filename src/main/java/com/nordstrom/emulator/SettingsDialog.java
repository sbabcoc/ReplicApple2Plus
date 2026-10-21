package com.nordstrom.emulator;

import com.nordstrom.emulator.system.CliArgs;
import com.nordstrom.emulator.system.RomFiles;
import com.nordstrom.emulator.system.ShiftKeyMod;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
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
import java.util.List;

/**
 * The Settings dialog, in two tabs:
 * <ul>
 *   <li><b>Machine</b> -- the slot configuration a Reboot will use, edited
 *       slot by slot ({@link SlotEditorPanel}) and saved explicitly; and the
 *       Shift Key Mod's wiring, which takes effect at once;</li>
 *   <li><b>Emulator</b> -- the emulator folder, used from the next start and
 *       remembered as soon as it's chosen, and what this run is using: its
 *       ROM folder, plug-ins and game input, each noting a command-line
 *       override.</li>
 * </ul>
 * Closing the dialog with unsaved slot changes asks whether to save them.
 * Controls used while the machine runs -- the VideoTerm display mode, the
 * disks and their write protection -- stay with it, in its toolbar and menus.
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
        JPanel machineTab = new JPanel(new BorderLayout());
        machineTab.add(machine, BorderLayout.CENTER);
        machineTab.add(shiftKeyModRow(), BorderLayout.SOUTH);
        tabs.addTab("Machine", machineTab);
        tabs.addTab("Emulator", emulatorTab(dialog, machine, startup));

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

    /** The Shift Key Mod's wiring: a choice applied to the running machine at once, and remembered. */
    private static JPanel shiftKeyModRow() {
        JComboBox<ShiftKeyMod.Wiring> wirings = new JComboBox<>(ShiftKeyMod.Wiring.values());
        wirings.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, ((ShiftKeyMod.Wiring) value).label(), index,
                    isSelected, cellHasFocus);
            }
        });
        wirings.setSelectedItem(ShiftKeyModSetting.remembered());
        wirings.addActionListener(event ->
            Apple2Plus.setShiftKeyMod((ShiftKeyMod.Wiring) wirings.getSelectedItem()));
        JLabel note = new JLabel("How Shift is wired to the game port, for lowercase. Takes effect at once.");
        note.setFont(note.getFont().deriveFont(note.getFont().getSize2D() - 1));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
            BorderFactory.createEmptyBorder(8, 4, 8, 4)));
        row.add(new JLabel("Shift Key Mod:"));
        row.add(wirings);
        row.add(note);
        return row;
    }

    /**
     * The emulator folder -- shown, chosen with Browse..., and used from the
     * next start -- and what this run is using.
     */
    private static JPanel emulatorTab(Component parent, SlotEditorPanel machine, Apple2Plus.StartupFiles startup) {
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
        c.insets = new Insets(6, 0, 0, 0);
        tab.add(note, c);

        JLabel heading = new JLabel("This run is using");
        heading.setFont(heading.getFont().deriveFont(java.awt.Font.BOLD));
        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 3;
        c.insets = new Insets(18, 0, 6, 0);
        tab.add(heading, c);
        c.gridwidth = 1;
        int row = 3;
        for (String[] line : thisRun(startup)) {
            c.gridx = 0;
            c.gridy = row;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            c.insets = new Insets(0, 0, 4, 6);
            tab.add(new JLabel(line[0]), c);
            JTextField value = new JTextField(line[1]);
            value.setEditable(false);
            value.setBorder(BorderFactory.createEmptyBorder());
            value.setOpaque(false);
            c.gridx = 1;
            c.gridwidth = 2;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            tab.add(value, c);
            c.gridwidth = 1;
            row++;
        }
        c.gridy = row;
        c.weighty = 1;
        tab.add(new JPanel(), c); // takes the spare height, keeping the rows at the top
        return tab;
    }

    /**
     * What this run is using, fixed when it started: each line a label and a
     * value, noting the command-line option that set it, if one did.
     */
    private static List<String[]> thisRun(Apple2Plus.StartupFiles startup) {
        CliArgs cli = startup.cli();
        Path folder = EmulatorFolder.path();
        Path roms = RomFiles.directory();
        String plugins = startup.pluginsPath();
        String networkPort = cli.get("network-input");
        String inputFile = cli.get("input");
        return List.of(
            new String[] {"Emulator Folder:", folder != null ? folder.toString() : "None"},
            new String[] {"ROM Folder:", roms == null ? "Built into the application"
                : roms + (cli.get("roms") != null ? "  (--roms)" : "")},
            new String[] {"Plug-ins:", plugins == null ? "None"
                : plugins + (cli.get("plugins") != null ? "  (--plugins)" : "")},
            new String[] {"Game Input:", networkPort != null
                ? "Network, UDP port " + networkPort + "  (--network-input)" : "Gamepad"},
            new String[] {"Input Mapping:", inputFile != null ? inputFile + "  (--input)" : "Built-in defaults"});
    }
}
