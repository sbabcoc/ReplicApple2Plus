package com.nordstrom.emulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;

/**
 * The slot configuration chosen in Settings ({@link SettingsDialog}),
 * remembered between runs. It takes the place of the emulator folder's own
 * slots.ini (EmulatorFolder); {@code --config}, when given, takes
 * precedence over both.
 * <p>
 * The choice is remembered for the emulator folder in use, not globally:
 * a configuration chosen alongside one folder's disks and plug-ins means
 * nothing beside another's. So choosing a different emulator folder
 * brings back that folder's own choice -- its slots.ini, unless one was
 * chosen for it -- from the next start, and leaves this run alone.
 */
final class RememberedFiles {

    /** The node holding each emulator folder's choice, keyed by the folder. */
    private static final String SLOT_CONFIGURATIONS = "slotConfigurations";

    private RememberedFiles() {
    }

    /** @return the slot configuration chosen for the emulator folder in use, if it still exists; else null */
    static Path slotConfiguration() {
        String remembered = choices().get(folderKey(), null);
        return remembered != null && Files.isRegularFile(Path.of(remembered)) ? Path.of(remembered) : null;
    }

    /** @param file the slot configuration to use with the emulator folder in use */
    static void rememberSlotConfiguration(Path file) {
        choices().put(folderKey(), file.toAbsolutePath().normalize().toString());
    }

    /** Forgets the emulator folder's chosen slot configuration, so its slots.ini applies again. */
    static void forgetSlotConfiguration() {
        choices().remove(folderKey());
    }

    /**
     * A preferences key for the emulator folder in use: its path's hash,
     * since a key is limited to 80 characters and a path isn't.
     */
    private static String folderKey() {
        Path folder = EmulatorFolder.path();
        return folder == null ? "none" : Integer.toHexString(folder.toAbsolutePath().normalize().toString().hashCode());
    }

    private static Preferences choices() {
        return Preferences.userNodeForPackage(RememberedFiles.class).node(SLOT_CONFIGURATIONS);
    }
}
