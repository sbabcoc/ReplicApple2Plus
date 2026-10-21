package com.nordstrom.emulator.system;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * Loads slot configuration from an INI file. Section names are bare slot
 * numbers ({@code [6]}, not {@code [slot.6]} -- the number itself is
 * already the identifier, no prefix needed). Each section's required
 * {@code type} key names a card by its own declared short name (see
 * {@link SlotCard#getShortName}), resolved by {@link CardTypes}.
 * <p>
 * Example:
 * <pre>
 *   [6]
 *   type=disk2
 *   drive1=disk1.woz
 *   drive2=disk2.woz
 * </pre>
 * {@code drive1}/{@code drive2} above name the media loaded at BOOT time
 * only -- they are not a permanent binding. A card with removable drives
 * (see {@link SlotCard#removableDrives}) is expected to load these initial
 * values by calling {@link RemovableMediaDrive#insert} on itself during
 * {@link SlotCard#configure}, the exact same method a live host UI calls
 * later to swap disks while the emulator runs. There is deliberately no
 * separate "initial media" code path -- boot-time loading and live
 * swapping are the same operation, matching real hardware, which has no
 * special "startup" way to load a disk versus a runtime one.
 * <p>
 * A card's own {@link SlotCard#configure} never sees its {@code type}
 * key or which slot it's in -- only the properties that are actually its
 * own, so a card stays testable standalone with plain, unprefixed
 * properties.
 * <p>
 * <b>Relative paths</b> in a configuration file are relative to the folder
 * holding the file, not to the emulator's working directory -- which an
 * app started from an installer doesn't control. So a configuration and
 * its disk images can live and move together. The loader passes that
 * folder to every card as the reserved entry {@link #CONFIG_DIRECTORY};
 * a card with a path setting resolves it with {@link #resolvePath}. The
 * entry is exempt from the check against a card's declared parameters,
 * and a card created without a configuration file simply has no such
 * entry, so its relative paths stay relative to the working directory.
 */
public final class SlotCardLoader {

    /**
     * The reserved property naming the folder that holds the configuration
     * file, as an absolute path. Its {@code @} keeps it apart from any real
     * setting's name.
     */
    public static final String CONFIG_DIRECTORY = "@configDirectory";

    /**
     * Resolves a path setting the way configuration files mean it: an
     * absolute path as it is, and a relative one against the folder holding
     * the configuration file, if the card was loaded from one.
     *
     * @param props the card's properties, as {@link SlotCard#configure} received them
     * @param value the setting's value
     * @return the path to use
     */
    public static Path resolvePath(Properties props, String value) {
        Path path = Path.of(value.trim());
        String folder = props.getProperty(CONFIG_DIRECTORY);
        return path.isAbsolute() || folder == null ? path : Path.of(folder).resolve(path);
    }

    /**
     * Loads and instantiates every configured card, resolving each
     * against {@code classLoader} -- normally one built by
     * {@link PluginLoader} covering both this project's own built-in
     * cards and any externally supplied plugin jars. Unconfigured slots
     * (no section for that number) are left {@code null}.
     *
     * @param configFile the INI slot configuration file
     * @param classLoader where to resolve each slot's {@code type} against
     * @return the populated slots, length 8, indices 0-7
     * @throws IOException if {@code configFile} can't be read
     */
    public static SlotCard[] load(Path configFile, ClassLoader classLoader) throws IOException {
        Map<String, Properties> sections = IniDocument.read(configFile).sections();
        SlotCard[] slots = new SlotCard[8]; // slots 0-7, slot 0 real but electrically special -- see SlotCard's Javadoc

        for (Map.Entry<String, Properties> entry : sections.entrySet()) {
            int slotNum = parseSlotNumber(entry.getKey());
            Properties cardProps = entry.getValue();

            String typeName = cardProps.getProperty("type");
            if (typeName == null) {
                throw new IllegalStateException("Slot " + slotNum + ": missing required \"type\" key");
            }
            cardProps.remove("type"); // the card itself only sees its own settings
            cardProps.setProperty(CONFIG_DIRECTORY, configFile.toAbsolutePath().getParent().toString());

            SlotCard card = CardTypes.create(typeName, cardProps, classLoader);
            if (!card.supportedSlots().contains(slotNum)) {
                throw new IllegalStateException("Slot " + slotNum + ": a \"" + typeName + "\" card only works in slot "
                    + new java.util.TreeSet<>(card.supportedSlots()).toString().replaceAll("[\\[\\]]", ""));
            }
            slots[slotNum] = card;
        }
        return slots;
    }

    private static int parseSlotNumber(String sectionName) {
        int slotNum;
        try {
            slotNum = Integer.parseInt(sectionName);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Section \"[" + sectionName + "]\" is not a valid slot number", e);
        }
        if (slotNum < 0 || slotNum > 7) {
            throw new IllegalArgumentException("Slot " + slotNum + " doesn't exist -- valid range is 0-7");
        }
        return slotNum;
    }

    private SlotCardLoader() {}
}
