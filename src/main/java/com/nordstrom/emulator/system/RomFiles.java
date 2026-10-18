package com.nordstrom.emulator.system;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the ROM images come from. They're copyrighted firmware, so they
 * aren't part of this project's source or its published artifacts: a
 * released build finds them in a ROM folder -- the one named by
 * {@code --roms}, or else the emulator folder's {@code roms/}
 * (EmulatorFolder) -- and a build from source can also keep them on the
 * classpath, under {@code src/main/resources} (README.md lists
 * each file and its place).
 * <p>
 * The folder, if there is one, is searched first, by file name alone;
 * then the classpath. The ROM classes load their images once, when first
 * used, so {@link #setDirectory} must be called before any of them is.
 */
public final class RomFiles {

    /** The ROM images every machine needs; others belong to particular cards. */
    public static final List<String> BASE_ROMS = List.of("system-rom.rom", "character-rom.rom");

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
            + "The ROM images aren't distributed with the emulator: put the file in the ROM folder -- roms in "
            + "the emulator's folder in your home folder, or the one named with --roms DIR (README.md lists each "
            + "one).";
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




}
