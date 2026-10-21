package com.nordstrom.emulator;

import com.nordstrom.emulator.expansion.Disk2Controller;
import com.nordstrom.emulator.system.CliArgs;
import com.nordstrom.emulator.system.RemovableMediaDrive;
import com.nordstrom.emulator.system.RomFiles;
import com.nordstrom.emulator.system.SlotCard;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Font;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Machine &gt; Show Configuration: what the running machine was actually
 * built from, as opposed to what Settings has chosen for later. When the
 * two differ -- a slot configuration chosen since the last Reboot, an
 * emulator folder chosen since launch -- a Pending section says what will
 * change, and when.
 * <p>
 * The text is a snapshot taken when the window opens, in a selectable text
 * area so it can be copied.
 */
final class ConfigurationWindow {

    private ConfigurationWindow() {}

    /** Where the running machine's slot configuration came from, in {@code Apple2Plus}'s order of precedence. */
    enum Source {
        COMMAND_LINE("--config"),
        SETTINGS("chosen in Settings"),
        EMULATOR_FOLDER("the emulator folder's slots.ini"),
        NONE("none");

        private final String description;

        Source(String description) {
            this.description = description;
        }
    }

    /**
     * @param parent anchors the window
     * @param text   the description, from {@link #describe}
     */
    static void show(Component parent, String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
        area.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new java.awt.Dimension(640, 320));
        JOptionPane.showMessageDialog(parent, scroll, "Configuration", JOptionPane.PLAIN_MESSAGE);
    }

    /**
     * Describes the running machine. Drive contents are read live, as the
     * Disk menu reads them.
     *
     * @param slots          the running machine's cards, by slot (null where empty)
     * @param config         the slot configuration it was built from, or null for none
     * @param source         where that configuration came from
     * @param nextConfig     the slot configuration Reboot would use now, or null for none
     * @param startup        how the emulator was started
     * @return the description
     */
    static String describe(SlotCard[] slots, Path config, Source source, Path nextConfig,
                           Apple2Plus.StartupFiles startup) {
        CliArgs cli = startup.cli();
        StringBuilder text = new StringBuilder();

        if (config != null) {
            text.append("Slot configuration: ").append(config).append(" (").append(source.description).append(")\n");
        } else {
            text.append("Slot configuration: none -- every slot empty\n");
        }
        for (int slot = 0; slot < slots.length; slot++) {
            SlotCard card = slots[slot];
            if (card == null) {
                continue;
            }
            String name = card.getDisplayName() + " (" + card.getShortName() + ")";
            text.append("  Slot ").append(slot).append(": ").append(name).append('\n');
            if (card instanceof Disk2Controller disk) {
                int number = 1;
                for (RemovableMediaDrive drive : disk.removableDrives()) {
                    text.append("    Drive ").append(number++).append(": ").append(describe(drive)).append('\n');
                }
            }
        }

        Path folder = EmulatorFolder.path();
        text.append("Emulator folder: ").append(folder != null ? folder : "none").append('\n');

        Path roms = RomFiles.directory();
        text.append("ROM folder: ");
        if (roms == null) {
            text.append("built into the application\n");
        } else {
            text.append(roms).append(cli.get("roms") != null ? " (--roms)" : "").append('\n');
        }

        String plugins = startup.pluginsPath();
        text.append("Plug-ins: ").append(plugins != null ? plugins : "none")
            .append(cli.get("plugins") != null ? " (--plugins)" : "").append('\n');

        String networkPort = cli.get("network-input");
        text.append("Game input: ").append(networkPort != null
            ? "network, UDP port " + networkPort + " (--network-input)" : "gamepad").append('\n');
        String inputFile = cli.get("input");
        text.append("Input mapping: ").append(inputFile != null ? inputFile + " (--input)" : "built-in defaults")
            .append('\n');

        text.append("Shift key mod: ").append(MachineMenu.rememberedWiring().label()).append('\n');

        StringBuilder pending = new StringBuilder();
        if (!Objects.equals(config, nextConfig)) {
            pending.append("  Reboot will use ")
                .append(nextConfig != null ? "slot configuration " + nextConfig : "no slot configuration")
                .append(".\n");
        }
        Path nextFolder = EmulatorFolder.remembered();
        if (nextFolder != null && !nextFolder.equals(folder)) {
            pending.append("  The next start will use emulator folder ").append(nextFolder).append(".\n");
        }
        if (!pending.isEmpty()) {
            text.append("\nPending:\n").append(pending);
        }
        return text.toString().stripTrailing();
    }

    private static String describe(RemovableMediaDrive drive) {
        if (!drive.isPresent()) {
            return "(empty)";
        }
        return drive.currentImagePath().orElseThrow() + (drive.isWriteProtected() ? " [Protected]" : " [Writable]");
    }
}
