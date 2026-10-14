package com.nordstrom.emulator.system;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Where the ROM images come from. They're copyrighted firmware, so they
 * aren't part of this project's source or its published artifacts: a
 * released build finds them in a ROM folder -- the one named by
 * {@code --roms}, or else the one the user chose when the emulator first
 * asked, which is remembered -- and a build from source can also keep
 * them on the classpath, under {@code src/main/resources} (README.md lists
 * each file and its place).
 * <p>
 * The folder, if there is one, is searched first, by file name alone;
 * then the classpath. The ROM classes load their images once, when first
 * used, so {@link #setDirectory} must be called before any of them is.
 */
public final class RomFiles {

    /** The ROM images every machine needs; others belong to particular cards. */
    public static final List<String> BASE_ROMS = List.of("system-rom.rom", "character-rom.rom");

    private static final String REMEMBERED_DIRECTORY = "romDirectory";

    private static volatile Path directory;

    private RomFiles() {
    }

    /**
     * Names the directory the ROM images are read from first.
     *
     * @param romDirectory the directory, as given with {@code --roms}
     */
    public static void setDirectory(Path romDirectory) {
        directory = romDirectory;
    }

    /** @return the ROM folder this run reads from, or null if it reads only the classpath */
    public static Path directory() {
        return directory;
    }

    /**
     * Opens a ROM image.
     *
     * @param owner the ROM class, whose package holds the image on the classpath
     * @param name  the image's file name
     * @return a stream of the image, or null if it's in neither place
     * @throws IOException if the file is there but can't be opened
     */
    public static InputStream open(Class<?> owner, String name) throws IOException {
        Path dir = directory;
        if (dir != null) {
            Path file = dir.resolve(name);
            if (Files.isRegularFile(file)) {
                return Files.newInputStream(file);
            }
        }
        return owner.getResourceAsStream(name);
    }

    /**
     * @param name a ROM image's file name
     * @return the message for a ROM image found in neither place
     */
    public static String missing(String name) {
        Path dir = directory;
        return name + " wasn't found " + (dir != null ? "in " + dir + " or " : "") + "on the classpath. "
            + "The ROM images aren't distributed with the emulator: put the file in your ROM folder -- the one "
            + "chosen at startup, or named with --roms DIR (README.md lists each one).";
    }

    /**
     * @return the base ROM images that can't be found where the ROM classes
     *         will look, so the emulator can ask for them before starting
     * @throws IOException if one is there but can't be opened
     */
    public static List<String> missingBaseRoms() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String name : BASE_ROMS) {
            try (InputStream in = open(RomFiles.class, name)) { // the base images share this package
                if (in == null) {
                    missing.add(name);
                }
            }
        }
        return missing;
    }

    /**
     * @param folder a candidate ROM folder
     * @return the base ROM images it lacks
     */
    public static List<String> missingFrom(Path folder) {
        List<String> missing = new ArrayList<>();
        for (String name : BASE_ROMS) {
            if (!Files.isRegularFile(folder.resolve(name))) {
                missing.add(name);
            }
        }
        return missing;
    }

    /** @return the ROM folder the user chose before, if it still exists; else null */
    public static Path rememberedDirectory() {
        String remembered = preferences().get(REMEMBERED_DIRECTORY, null);
        if (remembered == null) {
            return null;
        }
        Path folder = Path.of(remembered);
        return Files.isDirectory(folder) ? folder : null;
    }

    /**
     * Remembers a ROM folder the user chose, for the next start.
     *
     * @param folder the folder
     */
    public static void remember(Path folder) {
        preferences().put(REMEMBERED_DIRECTORY, folder.toAbsolutePath().toString());
    }

    private static Preferences preferences() {
        return Preferences.userNodeForPackage(RomFiles.class);
    }
}
