package com.nordstrom.emulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;

/**
 * The slot configuration chosen with File &gt; Slot Configuration...,
 * remembered between runs. It takes the place of the emulator folder's own
 * slots.ini (EmulatorFolder); {@code --config}, when given, takes
 * precedence over both.
 */
final class RememberedFiles {

    private static final String SLOT_CONFIGURATION = "slotConfiguration";

    private RememberedFiles() {
    }

    /** @return the remembered slot configuration file, if it still exists; else null */
    static Path slotConfiguration() {
        String remembered = preferences().get(SLOT_CONFIGURATION, null);
        return remembered != null && Files.isRegularFile(Path.of(remembered)) ? Path.of(remembered) : null;
    }

    static void rememberSlotConfiguration(Path file) {
        preferences().put(SLOT_CONFIGURATION, file.toAbsolutePath().toString());
    }

    private static Preferences preferences() {
        return Preferences.userNodeForPackage(RememberedFiles.class);
    }
}
