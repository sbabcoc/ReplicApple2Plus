package com.nordstrom.emulator.system;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * Loads slot configuration from an INI file. Section names are bare slot
 * numbers ({@code [6]}, not {@code [slot.6]} -- the number itself is
 * already the identifier, no prefix needed). Each section's required
 * {@code type} key names either a card's own declared short name (see
 * {@link SlotCard#getShortName}) or a fully-qualified class name -- see
 * {@link CardTypes} for exactly how that resolves, and why it stays open
 * rather than a closed set.
 * <p>
 * Example:
 * <pre>
 *   [6]
 *   type=diskII
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
 */
public final class SlotCardLoader {

    /**
     * Loads and instantiates every configured card, resolving each
     * against {@code classLoader} -- normally one built by
     * {@link PluginLoader} covering both this project's own built-in
     * cards and any externally supplied plugin jars. Unconfigured slots
     * (no section for that number) are left {@code null}.
     *
     * @param configFile the INI slot configuration file
     * @param classLoader where to resolve each slot's {@code type} against
     * @return the populated slots, length 8, index 0 unused
     * @throws IOException if {@code configFile} can't be read
     */
    public static SlotCard[] load(Path configFile, ClassLoader classLoader) throws IOException {
        Map<String, Properties> sections = IniFile.parse(configFile);
        SlotCard[] slots = new SlotCard[8]; // index 0 unused; slots are 1-7

        for (Map.Entry<String, Properties> entry : sections.entrySet()) {
            int slotNum = parseSlotNumber(entry.getKey());
            Properties cardProps = entry.getValue();

            String typeName = cardProps.getProperty("type");
            if (typeName == null) {
                throw new IllegalStateException("Slot " + slotNum + ": missing required \"type\" key");
            }
            cardProps.remove("type"); // the card itself only sees its own settings

            slots[slotNum] = CardTypes.create(typeName, cardProps, classLoader);
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
        if (slotNum < 1 || slotNum > 7) {
            throw new IllegalArgumentException("Slot " + slotNum + " doesn't exist -- valid range is 1-7");
        }
        return slotNum;
    }

    private SlotCardLoader() {}
}
