package com.nordstrom.emulator;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs a test class or method only if the ROM images it needs are
 * present. The ROMs aren't part of the repository (README.md lists where
 * each goes), so without them these tests are skipped, with the missing
 * file named, rather than failing -- as the tests that need disk images
 * skip without theirs. A ROM that is present but wrong still fails: the
 * ROM classes verify their checksums when loaded.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RomsCondition.class)
public @interface RequiresRoms {

    /** @return the ROMs the test needs */
    Rom[] value();

    /** The ROM images, by their resource paths (as in README.md). */
    enum Rom {
        /** Applesoft BASIC and the Autostart Monitor: anything built on {@code MotherboardBus}. */
        SYSTEM("/com/nordstrom/emulator/system/system-rom.rom"),
        /** The Apple II+ character generator: the 40-column text screen. */
        CHARACTER("/com/nordstrom/emulator/system/character-rom.rom"),
        /** The Videx VideoTerm's firmware and character generator: a {@code VideoTerm} loads both. */
        VIDEOTERM("/com/nordstrom/emulator/expansion/videx-videoterm-firmware-2_4.rom",
            "/com/nordstrom/emulator/expansion/videx-videoterm-charset-normal.rom");

        private final String[] resources;

        Rom(String... resources) {
            this.resources = resources;
        }

        /** @return the first of this ROM's files that isn't on the classpath, or null if none is missing */
        String missing() {
            for (String resource : resources) {
                if (RequiresRoms.class.getResource(resource) == null) {
                    return resource;
                }
            }
            return null;
        }
    }
}
