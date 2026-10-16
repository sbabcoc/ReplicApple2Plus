package com.nordstrom.emulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;

/**
 * The slot configuration and plugins folder chosen from the File menu,
 * remembered between runs. They stand in for {@code --config} and
 * {@code --plugins} where those aren't given -- an app started from an
 * installer gets no command-line options -- and the options, when given,
 * take precedence.
 */
final class RememberedFiles {

    private static final String SLOT_CONFIGURATION = "slotConfiguration";
    private static final String PLUGINS_FOLDER = "pluginsFolder";

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

    /** @return the remembered plugins folder, if it still exists; else null */
    static Path pluginsFolder() {
        String remembered = preferences().get(PLUGINS_FOLDER, null);
        return remembered != null && Files.isDirectory(Path.of(remembered)) ? Path.of(remembered) : null;
    }

    static void rememberPluginsFolder(Path folder) {
        preferences().put(PLUGINS_FOLDER, folder.toAbsolutePath().toString());
    }

    private static Preferences preferences() {
        return Preferences.userNodeForPackage(RememberedFiles.class);
    }
}
